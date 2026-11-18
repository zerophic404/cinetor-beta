package com.mythbrix.cinetor.feature.comics

import com.mythbrix.cinetor.core.data.Chapter
import com.mythbrix.cinetor.core.data.ComicFormat
import com.mythbrix.cinetor.core.data.HistoryStorage
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ReadingDirection
import com.mythbrix.cinetor.core.perf.PerformanceTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How pages are laid out. Manga → RTL paged, Manhwa/Manhua → vertical webtoon; user may override per title. */
@Serializable
enum class ReaderMode(val label: String) {
    RTL_PAGED("Paged · right to left"),
    LTR_PAGED("Paged · left to right"),
    VERTICAL("Vertical · webtoon");

    val paged: Boolean get() = this != VERTICAL
}

/** Chapter list sort order on ComicDetails. */
@Serializable
enum class ChapterSort(val label: String) {
    ASCENDING("Oldest first"),
    DESCENDING("Newest first"),
}

/** Chapter list quick filter on ComicDetails. */
@Serializable
enum class ChapterFilter(val label: String) {
    ALL("All"),
    UNREAD("Unread"),
    DOWNLOADED("Downloaded"),
}

/** Aggregated reading progress across a comic's chapters. */
data class ComicReadSummary(
    val readCount: Int,
    val inProgressCount: Int,
    val totalCount: Int,
) {
    val unreadCount: Int get() = (totalCount - readCount).coerceAtLeast(0)
    val fraction: Float get() = if (totalCount > 0) (readCount.toFloat() / totalCount).coerceIn(0f, 1f) else 0f
    val badgeLabel: String?
        get() = when {
            totalCount <= 0 || (readCount == 0 && inProgressCount == 0) -> null
            readCount >= totalCount -> "Completed · $totalCount/$totalCount"
            else -> "$readCount / $totalCount read"
        }
}

object ComicIds {
    /** Mock comic ids (`co-ma-001`, `co-mw-…`, `co-mh-…`), AniList gateway ids (`cm:al:<id>`) and MangaDex ids (`cm:<uuid>`). */
    fun isComic(id: String): Boolean = id.startsWith("co-") || id.startsWith("cm:")
}

/** Default mode from the catalog: RTL direction / manga category → RTL paged; long strip / TTB → vertical. */
fun defaultReaderMode(item: Item): ReaderMode = when {
    item.readingDirection == ReadingDirection.RTL -> ReaderMode.RTL_PAGED
    item.format == ComicFormat.LONGSTRIP || item.readingDirection == ReadingDirection.TTB -> ReaderMode.VERTICAL
    item.readingDirection == ReadingDirection.LTR -> ReaderMode.LTR_PAGED
    item.categoryId.endsWith("manga") -> ReaderMode.RTL_PAGED
    else -> ReaderMode.VERTICAL
}

/** Summary of read / in-progress chapters for a title. */
fun comicReadSummary(chapters: List<Chapter>, data: ComicsData, comicId: String): ComicReadSummary {
    if (chapters.isEmpty()) return ComicReadSummary(0, 0, 0)
    val byChapter = data.progress.asSequence()
        .filter { it.comicId == comicId }
        .associateBy { it.chapterId }
    var read = 0
    var inProgress = 0
    for (ch in chapters) {
        val p = byChapter[ch.id] ?: continue
        if (p.read) read += 1 else inProgress += 1
    }
    return ComicReadSummary(read, inProgress, chapters.size)
}

/** Human-readable status badge for one chapter row. */
fun chapterStatusBadge(chapter: Chapter, progress: ChapterProgress?): String = when {
    progress == null -> if (chapter.pages.isNotEmpty()) "${chapter.pages.size} pages" else "Unread"
    progress.read -> "Read"
    else -> {
        val pct = (progress.fraction * 100f).toInt().coerceIn(1, 99)
        "Page ${progress.page + 1}/${progress.pageCount} · $pct%"
    }
}

/** Pure chapter list filter + search + sort for ComicDetails. */
fun filterAndSortChapters(
    chapters: List<Chapter>,
    data: ComicsData,
    comicId: String,
    downloadedIds: Set<String> = emptySet(),
    query: String = "",
    sort: ChapterSort = ChapterSort.ASCENDING,
    filter: ChapterFilter = ChapterFilter.ALL,
): List<Chapter> {
    val byChapter = if (filter == ChapterFilter.UNREAD) {
        data.progress.asSequence().filter { it.comicId == comicId }.associateBy { it.chapterId }
    } else {
        emptyMap()
    }
    val q = query.trim()
    val filtered = chapters.filter { ch ->
        val matchesFilter = when (filter) {
            ChapterFilter.ALL -> true
            ChapterFilter.UNREAD -> byChapter[ch.id]?.read != true
            ChapterFilter.DOWNLOADED -> ch.id in downloadedIds
        }
        val matchesQuery = q.isEmpty() ||
            ch.n.toString().contains(q, ignoreCase = true) ||
            ch.title.contains(q, ignoreCase = true)
        matchesFilter && matchesQuery
    }
    return if (sort == ChapterSort.DESCENDING) filtered.asReversed() else filtered
}

/**
 * Generic message for a chapter whose pages cannot be resolved. Deliberately carries no host, path or
 * provider name (docs/requirements-hardening.md §2).
 */
const val CHAPTER_UNAVAILABLE: String = "Chapter unavailable. Try another chapter."

/** Pages decoded ahead of the reader: 3 normally, 1 on LOW tier. */
fun preloadCount(tier: PerformanceTier): Int = if (tier == PerformanceTier.LOW) 1 else 3

/**
 * Next [count] page paths after [page] of chapter [chapterIndex], continuing into the next chapter when the
 * current one ends (so the auto-loaded chapter opens instantly).
 */
fun preloadPages(chapters: List<Chapter>, chapterIndex: Int, page: Int, count: Int): List<String> {
    val out = ArrayList<String>(count)
    var c = chapterIndex
    var p = page + 1
    while (out.size < count && c < chapters.size) {
        val pages = chapters[c].pages
        if (p < pages.size) {
            out += pages[p]
            p += 1
        } else {
            c += 1
            p = 0
        }
    }
    return out
}

@Serializable
data class ChapterProgress(
    val comicId: String,
    val chapterId: String,
    val page: Int,
    val pageCount: Int,
    val updatedAt: Long,
    val comicTitle: String = "",
    val chapterN: Int = 0,
) {
    val read: Boolean get() = pageCount > 0 && page >= pageCount - 1
    val fraction: Float get() = if (pageCount > 1) (page.toFloat() / (pageCount - 1)).coerceIn(0f, 1f) else if (read) 1f else 0f
}

@Serializable
data class ComicsData(
    val progress: List<ChapterProgress> = emptyList(),
    val bookmarks: Set<String> = emptySet(),
    val modes: Map<String, ReaderMode> = emptyMap(),
)

/**
 * Reading progress (per chapter), bookmarks and per-title reader mode. State updates are synchronous (UI + tests
 * see them at once); persistence to [storage] (DataStore JSON blob) runs on [scope].
 */
class ComicsStore(
    private val storage: HistoryStorage,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val max: Int = 2_000,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val writeMutex = Mutex()
    private val _data = MutableStateFlow(ComicsData())
    val data: StateFlow<ComicsData> = _data.asStateFlow()

    @Volatile
    private var loaded = false

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val blob = runCatching { storage.load() }.getOrNull()
            val parsed = blob?.let { runCatching { json.decodeFromString(ComicsData.serializer(), it) }.getOrNull() }
            // Merge anything written before the load finished.
            _data.update { cur ->
                val base = parsed ?: ComicsData()
                ComicsData(
                    progress = (cur.progress + base.progress).distinctBy { it.comicId to it.chapterId },
                    bookmarks = base.bookmarks + cur.bookmarks,
                    modes = base.modes + cur.modes,
                )
            }
            loaded = true
        }
    }

    fun progress(comicId: String, chapterId: String): ChapterProgress? =
        _data.value.progress.firstOrNull { it.comicId == comicId && it.chapterId == chapterId }

    /** Newest progress entry of [comicId] (continue reading). */
    fun latest(comicId: String): ChapterProgress? =
        _data.value.progress.filter { it.comicId == comicId }.maxByOrNull { it.updatedAt }

    /** Newest entry over all titles (Comics home "Continue reading"). */
    fun lastRead(): ChapterProgress? = _data.value.progress.maxByOrNull { it.updatedAt }

    fun saveProgress(comic: Item, chapter: Chapter, page: Int) {
        val entry = ChapterProgress(
            comicId = comic.id,
            chapterId = chapter.id,
            page = page.coerceIn(0, (chapter.pages.size - 1).coerceAtLeast(0)),
            pageCount = chapter.pages.size,
            updatedAt = clock(),
            comicTitle = comic.title,
            chapterN = chapter.n,
        )
        mutate { d ->
            d.copy(progress = (listOf(entry) + d.progress.filterNot { it.comicId == comic.id && it.chapterId == chapter.id }).take(max))
        }
    }

    /** Clears saved progress for one comic (e.g. dismissing "Continue reading"). */
    fun forgetComic(comicId: String) = mutate { d ->
        d.copy(progress = d.progress.filterNot { it.comicId == comicId })
    }

    fun isBookmarked(comicId: String): Boolean = comicId in _data.value.bookmarks

    fun toggleBookmark(comicId: String) = mutate { d ->
        d.copy(bookmarks = if (comicId in d.bookmarks) d.bookmarks - comicId else d.bookmarks + comicId)
    }

    fun modeOverride(comicId: String): ReaderMode? = _data.value.modes[comicId]

    fun setMode(comicId: String, mode: ReaderMode?) = mutate { d ->
        d.copy(modes = if (mode == null) d.modes - comicId else d.modes + (comicId to mode))
    }

    private fun mutate(block: (ComicsData) -> ComicsData) {
        _data.update(block)
        scope.launch {
            ensureLoaded()
            // Always writes the newest state, so out-of-order writers still end on the latest data.
            writeMutex.withLock { runCatching { storage.save(json.encodeToString(ComicsData.serializer(), _data.value)) } }
        }
    }
}

data class ReaderState(
    val comic: Item,
    val chapterIndex: Int,
    val page: Int,
    val mode: ReaderMode,
    val defaultMode: ReaderMode,
    val uiVisible: Boolean = true,
    /** 0..1 window brightness; null = follow system. */
    val brightness: Float? = null,
    /** Reached the end of the last chapter. */
    val endOfComic: Boolean = false,
    /** Bumped whenever the chapter changes (UI scrolls/re-keys the pager). */
    val chapterGeneration: Int = 0,
    /** Remote pages (MangaDex via gateway) are being resolved for the current chapter. */
    val loadingPages: Boolean = false,
    /** Set when a chapter has no pages anywhere — reader stays usable, user picks another chapter. */
    val unavailable: String? = null,
    /** Compressed data-saver page stream enabled for slower connections / broken primary nodes. */
    val dataSaverPages: Boolean = false,
    /** True when the current chapter switched onto a backup host or alternate scanlation upload. */
    val usingBackupServer: Boolean = false,
    /** Bumped when re-resolving page links on the current chapter so image loaders bypass stale URLs. */
    val reloadGeneration: Int = 0,
) {
    val chapters: List<Chapter> get() = comic.pages
    val chapter: Chapter get() = chapters[chapterIndex]
    val pageCount: Int get() = chapter.pages.size
    val hasNextChapter: Boolean get() = chapterIndex < chapters.size - 1
    val hasPrevChapter: Boolean get() = chapterIndex > 0
}

/**
 * Reader logic (Android-free, TestScope-drivable): chapter/page position, mode, progress saving per chapter,
 * next-chapter auto-load, preload list, and resilient data-saver / backup server retry.
 */
class ReaderStateHolder(
    comic: Item,
    startChapterId: String?,
    private val store: ComicsStore,
    private val tier: PerformanceTier = PerformanceTier.MID,
    /** Scope for lazy page resolution (null = pages are already in the model, e.g. mock comics). */
    private val scope: CoroutineScope? = null,
    /** Resolves a chapter's page URLs on demand; throws when the chapter has none. */
    private val resolvePages: (suspend (Chapter) -> List<String>)? = null,
    /** Optional fallback resolver `(chapter, dataSaver, preferBackup)` for host-switch & data-saver retry. */
    private val resolveFallbackPages: (suspend (Chapter, Boolean, Boolean) -> List<String>)? = null,
) {
    private val _state: MutableStateFlow<ReaderState>
    val state: StateFlow<ReaderState>

    init {
        require(comic.pages.isNotEmpty()) { "Comic ${comic.id} has no chapters" }
        val chapterId = startChapterId ?: store.latest(comic.id)?.chapterId
        val idx = comic.pages.indexOfFirst { it.id == chapterId }.coerceAtLeast(0)
        val saved = store.progress(comic.id, comic.pages[idx].id)
        // Finished chapter reopened → start from the top again.
        val page = if (saved == null || saved.read) 0 else saved.page
        val def = defaultReaderMode(comic)
        _state = MutableStateFlow(
            ReaderState(comic, idx, page, store.modeOverride(comic.id) ?: def, def),
        )
        state = _state.asStateFlow()
        if (comic.pages[idx].pages.isEmpty()) ensurePages(idx) else save()
    }

    val current: ReaderState get() = _state.value

    /**
     * Fills a chapter's pages when the catalog only knows its id (MangaDex). Never throws: a chapter
     * with no pages anywhere sets [ReaderState.unavailable] and leaves the rest of the reader working.
     */
    private fun ensurePages(index: Int, forceReload: Boolean = false, preferDataSaver: Boolean = current.dataSaverPages, preferBackup: Boolean = false) {
        val s = current
        val chapter = s.chapters.getOrNull(index) ?: return
        if (chapter.pages.isNotEmpty() && !forceReload) return
        val fallback = resolveFallbackPages
        val primary = resolvePages
        val sc = scope
        if ((fallback == null && primary == null) || sc == null) {
            if (chapter.pages.isEmpty()) {
                _state.update { it.copy(unavailable = CHAPTER_UNAVAILABLE, loadingPages = false) }
            }
            return
        }
        val keepExistingOnFailure = chapter.pages.isNotEmpty()
        _state.update { it.copy(loadingPages = chapter.pages.isEmpty(), unavailable = null) }
        sc.launch {
            val urls = runCatching {
                when {
                    (forceReload || preferDataSaver || preferBackup) && fallback != null ->
                        fallback(chapter, preferDataSaver, preferBackup)
                    primary != null -> primary(chapter)
                    fallback != null -> fallback(chapter, preferDataSaver, preferBackup)
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
            _state.update { st ->
                if (urls.isEmpty()) {
                    if (keepExistingOnFailure) {
                        st.copy(loadingPages = false, reloadGeneration = st.reloadGeneration + 1)
                    } else {
                        st.copy(loadingPages = false, unavailable = CHAPTER_UNAVAILABLE)
                    }
                } else {
                    val chapters = st.comic.pages.map { c -> if (c.id == chapter.id) c.copy(pages = urls) else c }
                    st.copy(
                        comic = st.comic.copy(pages = chapters),
                        loadingPages = false,
                        unavailable = null,
                        page = st.page.coerceIn(0, (urls.size - 1).coerceAtLeast(0)),
                        dataSaverPages = preferDataSaver,
                        usingBackupServer = preferBackup,
                        chapterGeneration = if (keepExistingOnFailure) st.chapterGeneration else st.chapterGeneration + 1,
                        reloadGeneration = st.reloadGeneration + 1,
                    )
                }
            }
            if (urls.isNotEmpty()) save()
        }
    }

    /** Paths to decode ahead (next 3 pages, LOW: 1), crossing into the next chapter. */
    fun preload(): List<String> = current.let { preloadPages(it.chapters, it.chapterIndex, it.page, preloadCount(tier)) }

    fun onPageChanged(page: Int) {
        val s = current
        if (s.pageCount == 0) return
        val p = page.coerceIn(0, s.pageCount - 1)
        if (p == s.page) return
        _state.value = s.copy(page = p, endOfComic = false)
        save()
    }

    fun nextPage() {
        val s = current
        if (s.pageCount == 0) return
        if (s.page < s.pageCount - 1) onPageChanged(s.page + 1) else onEndReached()
    }

    fun prevPage() {
        val s = current
        if (s.page > 0) {
            onPageChanged(s.page - 1)
        } else if (s.hasPrevChapter) {
            openChapter(s.chapterIndex - 1, last = true)
        }
    }

    /** Scrolled/paged past the last page: auto-load the next chapter (or flag the end of the comic). */
    fun onEndReached() {
        val s = current
        if (s.pageCount == 0) return
        // The chapter counts as read when its end is reached.
        if (s.page != s.pageCount - 1) {
            _state.value = s.copy(page = s.pageCount - 1)
            save()
        }
        if (s.hasNextChapter) openChapter(s.chapterIndex + 1) else _state.update { it.copy(endOfComic = true) }
    }

    fun nextChapter() {
        if (current.hasNextChapter) openChapter(current.chapterIndex + 1)
    }

    fun prevChapter() {
        if (current.hasPrevChapter) openChapter(current.chapterIndex - 1)
    }

    fun openChapter(index: Int, last: Boolean = false) {
        val s = current
        val i = index.coerceIn(0, s.chapters.size - 1)
        val saved = store.progress(s.comic.id, s.chapters[i].id)
        val pages = s.chapters[i].pages
        val page = when {
            pages.isEmpty() -> 0
            last -> pages.size - 1
            saved != null && !saved.read -> saved.page
            else -> 0
        }
        _state.value = s.copy(
            chapterIndex = i,
            page = page,
            endOfComic = false,
            unavailable = null,
            usingBackupServer = false,
            chapterGeneration = s.chapterGeneration + 1,
        )
        if (pages.isEmpty()) ensurePages(i) else save()
    }

    /** User retried an unavailable chapter. */
    fun retryChapter() = ensurePages(
        index = current.chapterIndex,
        forceReload = true,
        preferDataSaver = current.dataSaverPages,
        preferBackup = resolveFallbackPages != null && current.unavailable != null,
    )

    /** Switches current chapter onto compressed data-saver / alternate host / backup scanlation upload. */
    fun reloadChapterWithFallback(preferDataSaver: Boolean = true, preferBackup: Boolean = true) {
        ensurePages(
            index = current.chapterIndex,
            forceReload = true,
            preferDataSaver = preferDataSaver,
            preferBackup = preferBackup,
        )
    }

    /** Toggles data-saver compressed pages from ReaderSettings. */
    fun setDataSaverPages(enabled: Boolean) {
        if (current.dataSaverPages == enabled) return
        _state.update { it.copy(dataSaverPages = enabled) }
        if (resolveFallbackPages != null) {
            ensurePages(
                index = current.chapterIndex,
                forceReload = true,
                preferDataSaver = enabled,
                preferBackup = current.usingBackupServer,
            )
        }
    }

    fun setMode(mode: ReaderMode) {
        val s = current
        store.setMode(s.comic.id, mode.takeIf { it != s.defaultMode })
        _state.value = s.copy(mode = mode)
    }

    fun toggleUi() = _state.update { it.copy(uiVisible = !it.uiVisible) }

    fun setUiVisible(visible: Boolean) = _state.update { it.copy(uiVisible = visible) }

    fun setBrightness(value: Float?) = _state.update { it.copy(brightness = value?.coerceIn(0.02f, 1f)) }

    private fun save() {
        val s = current
        store.saveProgress(s.comic, s.chapter, s.page)
    }
}

/** Tap zones: left/right thirds turn pages (mirrored for RTL), middle toggles the UI. */
enum class TapZone { PREV, NEXT, TOGGLE }

fun tapZone(x: Float, width: Float, mode: ReaderMode): TapZone {
    if (width <= 0f) return TapZone.TOGGLE
    val f = x / width
    val left = f < 1f / 3f
    val right = f > 2f / 3f
    return when {
        !left && !right -> TapZone.TOGGLE
        mode == ReaderMode.RTL_PAGED -> if (left) TapZone.NEXT else TapZone.PREV
        else -> if (left) TapZone.PREV else TapZone.NEXT
    }
}
