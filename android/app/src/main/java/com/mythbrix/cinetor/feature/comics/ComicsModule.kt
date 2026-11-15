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
 * marker). The reader prefers local pages, so downloaded chapters read offline. Pages come from bundled mock
 * assets ([openPath]); http pages would go through the same function.
 */
class ComicDownloads(
    private val root: File,
    private val scope: CoroutineScope,
    private val openPath: (String) -> InputStream,
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
        val ext = path.substringAfterLast('.', "img").take(5)
        return File(dir(comicId, chapterId), "%03d.%s".format(index, ext))
    }

    /** Local page file (only for fully downloaded chapters). */
    fun localPage(comicId: String, chapter: Chapter, index: Int): File? {
        if (_states.value[chapter.id] != ChapterDownload.Done) return null
        val path = chapter.pages.getOrNull(index) ?: return null
        return pageFile(comicId, chapter.id, index, path).takeIf { it.isFile }
    }

    /** Coil model for a page: local file when downloaded, else the asset/remote URI. */
    fun pageModel(comicId: String, chapter: Chapter, index: Int): Any =
        localPage(comicId, chapter, index) ?: AssetUris.of(chapter.pages[index])

    fun download(comic: Item, chapter: Chapter) {
        val st = _states.value[chapter.id]
        if (st is ChapterDownload.Running || st == ChapterDownload.Done) return
        _states.update { it + (chapter.id to ChapterDownload.Running(0, chapter.pages.size)) }
        jobs[chapter.id] = scope.launch(Dispatchers.IO) {
            val d = dir(comic.id, chapter.id)
            val result = runCatching {
                d.mkdirs()
                chapter.pages.forEachIndexed { i, path ->
                    val out = pageFile(comic.id, chapter.id, i, path)
                    if (!out.isFile || out.length() == 0L) {
                        val tmp = File(out.path + ".part")
                        openPath(path).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        if (!tmp.renameTo(out)) error("rename failed")
                    }
                    _states.update { it + (chapter.id to ChapterDownload.Running(i + 1, chapter.pages.size)) }
                }
                File(d, DONE).writeText(chapter.pages.size.toString())
            }
            _states.update {
                it + (chapter.id to (result.exceptionOrNull()?.let { e -> ChapterDownload.Failed(e.message ?: "error") } ?: ChapterDownload.Done))
            }
        }
    }

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
class ComicsModule(context: Context, scope: CoroutineScope) {
    private val appContext = context.applicationContext

    val store: ComicsStore = ComicsStore(ComicsDataStore(appContext), scope)

    val downloads: ComicDownloads = ComicDownloads(File(appContext.filesDir, "comics"), scope) { path ->
        if (AssetUris.isRemote(path)) java.net.URL(path).openStream() else appContext.assets.open("mock/$path")
    }

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
