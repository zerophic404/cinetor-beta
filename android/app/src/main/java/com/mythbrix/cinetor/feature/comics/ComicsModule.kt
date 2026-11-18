package com.mythbrix.cinetor.feature.comics

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mythbrix.cinetor.core.data.AssetUris
import com.mythbrix.cinetor.core.data.Chapter
import com.mythbrix.cinetor.core.data.HistoryStorage
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.comics.MangaDexCatalogSource
import com.mythbrix.cinetor.core.data.gateway.GatewaySigner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

private val Context.comicsDataStore: DataStore<Preferences> by preferencesDataStore(name = "comics_reading")

/** DataStore blob behind [ComicsStore] (progress per chapter, bookmarks, reader modes). */
class ComicsDataStore(context: Context) : HistoryStorage {
    private val store = context.applicationContext.comicsDataStore
    override suspend fun load(): String? = store.data.first()[KEY]
    override suspend fun save(json: String) {
        store.edit { it[KEY] = json }
    }

    private companion object {
        val KEY = stringPreferencesKey("comics_v1")
    }
}

/** Download state of one chapter. */
sealed interface ChapterDownload {
    data class Running(val done: Int, val total: Int) : ChapterDownload
    data object Done : ChapterDownload
    data class Failed(val message: String) : ChapterDownload
}

/**
 * Chapter downloads: pages copied into app-private storage (`files/comics/<comic>/<chapter>/NNN.ext` + `.done`
 * marker). The reader prefers local pages, so downloaded chapters read offline. Remote gateway chapters resolve
 * their page URLs lazily via [resolvePages] and fall back to backup URLs via [fallbackUrl].
 */
class ComicDownloads(
    private val root: File,
    private val scope: CoroutineScope,
    private val openPath: (String) -> InputStream,
    private val resolvePages: (suspend (Chapter) -> List<String>)? = null,
    private val fallbackUrl: ((String) -> String?)? = null,
) {
    private val _states = MutableStateFlow<Map<String, ChapterDownload>>(emptyMap())
    val states: StateFlow<Map<String, ChapterDownload>> = _states.asStateFlow()
    private val jobs = HashMap<String, Job>()

    init {
        scope.launch(Dispatchers.IO) {
            val done = root.listFiles().orEmpty().flatMap { comic ->
                comic.listFiles().orEmpty().filter { File(it, DONE).isFile }.map { it.name }
            }
            _states.update { cur -> done.associateWith { ChapterDownload.Done as ChapterDownload } + cur }
        }
    }

    private fun dir(comicId: String, chapterId: String) = File(File(root, safe(comicId)), safe(chapterId))

    private fun pageFile(comicId: String, chapterId: String, index: Int, path: String): File {
        val segment = path.substringBefore('?').substringAfterLast('/')
        val rawExt = if ('.' in segment) segment.substringAfterLast('.') else "img"
        val ext = rawExt.filter { it.isLetterOrDigit() }.take(5).ifBlank { "img" }
        return File(dir(comicId, chapterId), "%03d.%s".format(index, ext))
    }

    /** Local page file (only for fully downloaded chapters). */
    fun localPage(comicId: String, chapter: Chapter, index: Int): File? {
        if (_states.value[chapter.id] != ChapterDownload.Done) return null
        val path = chapter.pages.getOrNull(index)
        if (path != null) {
            pageFile(comicId, chapter.id, index, path).takeIf { it.isFile && it.length() > 0L }?.let { return it }
        }
        val prefix = "%03d.".format(index)
        return dir(comicId, chapter.id).listFiles()
            ?.firstOrNull { it.isFile && it.name.startsWith(prefix) && !it.name.endsWith(".part") && it.length() > 0L }
    }

    /** Coil model for a page: local file when downloaded, else the asset/remote URI. */
    fun pageModel(comicId: String, chapter: Chapter, index: Int): Any =
        localPage(comicId, chapter, index) ?: chapter.pages.getOrNull(index)?.let(AssetUris::of).orEmpty()

    /** Compressed data-saver or raw CDN backup URL for a primary page URL when known. */
    fun backupPageUrl(url: String): String? = fallbackUrl?.invoke(url)

    fun download(comic: Item, chapter: Chapter) {
        if (chapter.pages.isEmpty() && resolvePages == null) return
        val st = _states.value[chapter.id]
        if (st is ChapterDownload.Running || st == ChapterDownload.Done) return
        _states.update { it + (chapter.id to ChapterDownload.Running(0, chapter.pages.size)) }
        jobs[chapter.id] = scope.launch(Dispatchers.IO) {
            val d = dir(comic.id, chapter.id)
            val result = runCatching {
                val pages = if (chapter.pages.isNotEmpty()) {
                    chapter.pages
                } else {
                    resolvePages?.invoke(chapter).orEmpty()
                }
                if (pages.isEmpty()) error("Chapter unavailable")
                _states.update { it + (chapter.id to ChapterDownload.Running(0, pages.size)) }
                d.mkdirs()
                pages.forEachIndexed { i, primaryPath ->
                    val existingPrefix = "%03d.".format(i)
                    val existing = d.listFiles()?.firstOrNull {
                        it.isFile && it.name.startsWith(existingPrefix) && !it.name.endsWith(".part") && it.length() > 0L
                    }
                    if (existing == null) {
                        val saved = trySavePage(comic.id, chapter.id, i, primaryPath) ||
                            fallbackUrl?.invoke(primaryPath)?.let { alt -> trySavePage(comic.id, chapter.id, i, alt) } == true
                        if (!saved) error("Page download failed")
                    }
                    _states.update { it + (chapter.id to ChapterDownload.Running(i + 1, pages.size)) }
                }
                File(d, DONE).writeText(pages.size.toString())
            }
            if (result.exceptionOrNull() is CancellationException) return@launch
            _states.update {
                it + (chapter.id to (result.exceptionOrNull()?.let { ChapterDownload.Failed("Download failed") } ?: ChapterDownload.Done))
            }
        }
    }

    private fun trySavePage(comicId: String, chapterId: String, index: Int, path: String): Boolean = runCatching {
        val out = pageFile(comicId, chapterId, index, path)
        val tmp = File(out.path + ".part")
        openPath(path).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(out)) {
            tmp.delete()
            return false
        }
        true
    }.getOrDefault(false)

    fun delete(comic: Item, chapter: Chapter) {
        jobs.remove(chapter.id)?.cancel()
        scope.launch(Dispatchers.IO) {
            dir(comic.id, chapter.id).deleteRecursively()
            _states.update { it - chapter.id }
        }
    }

    private companion object {
        const val DONE = ".done"
        fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}

/** Reader overlay request (reader is drawn above the NavHost, like the player). */
data class ReaderRequest(val comic: Item, val chapterId: String?)

/** Comics space wiring (store + downloads + reader overlay). One per process (AppContainer). */
class ComicsModule(
    context: Context,
    scope: CoroutineScope,
    /** Resolves a chapter's page URLs on demand (gateway / MangaDex); mock chapters already carry theirs. */
    val pageResolver: (suspend (Chapter) -> List<String>)? = null,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var catalogRef: MangaDexCatalogSource? = null

    @Volatile
    private var dataSaverPref: () -> Boolean = { false }

    val store: ComicsStore = ComicsStore(ComicsDataStore(appContext), scope)

    /** Attaches [MangaDexCatalogSource] for data-saver page mirrors, Referer headers, and alternate chapter fallback. */
    fun attachCatalog(catalog: MangaDexCatalogSource, dataSaver: () -> Boolean = { false }) {
        catalogRef = catalog
        dataSaverPref = dataSaver
    }

    fun isDefaultDataSaver(): Boolean = dataSaverPref()

    fun fallbackUrlFor(url: String): String? = catalogRef?.fallbackUrlFor(url)

    fun refererFor(url: String): String? = catalogRef?.refererFor(url)

    val fallbackPageResolver: (suspend (Chapter, Boolean, Boolean) -> List<String>)?
        get() = { chapter, dataSaver, preferBackup ->
            val cat = catalogRef
            when {
                cat != null && cat.isComicChapter(chapter.id) ->
                    cat.pagesWithFallback(chapter.id, dataSaver = dataSaver, fresh = true, preferBackup = preferBackup)
                pageResolver != null -> pageResolver.invoke(chapter)
                else -> chapter.pages
            }
        }

    val downloads: ComicDownloads = ComicDownloads(
        root = File(appContext.filesDir, "comics"),
        scope = scope,
        openPath = { path ->
            if (AssetUris.isRemote(path)) {
                val conn = (java.net.URL(path).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", GatewaySigner.BROWSER_USER_AGENT)
                    refererFor(path)?.let { ref -> setRequestProperty("Referer", ref) }
                }
                conn.inputStream
            } else {
                appContext.assets.open("mock/$path")
            }
        },
        resolvePages = { chapter ->
            val cat = catalogRef
            when {
                chapter.pages.isNotEmpty() -> chapter.pages
                cat != null && cat.isComicChapter(chapter.id) -> cat.pages(chapter.id, dataSaver = dataSaverPref())
                pageResolver != null -> pageResolver.invoke(chapter)
                else -> emptyList()
            }
        },
        fallbackUrl = { url -> fallbackUrlFor(url) },
    )

    private val _reader = MutableStateFlow<ReaderRequest?>(null)
    val reader: StateFlow<ReaderRequest?> = _reader.asStateFlow()

    init {
        scope.launch { store.ensureLoaded() }
    }

    fun openReader(comic: Item, chapterId: String? = null) {
        _reader.value = ReaderRequest(comic, chapterId)
    }

    fun closeReader() {
        _reader.value = null
    }
}
