package com.mythbrix.cinetor.feature.comics

import com.mythbrix.cinetor.TestFiles
import com.mythbrix.cinetor.core.data.Chapter
import com.mythbrix.cinetor.core.data.ComicFormat
import com.mythbrix.cinetor.core.data.InMemoryHistoryStorage
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.ReadingDirection
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.perf.PerformanceTier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun comic(
    id: String = "co-ma-x",
    category: String = "co-manga",
    format: ComicFormat? = ComicFormat.PAGED,
    dir: ReadingDirection? = ReadingDirection.RTL,
    chapters: Int = 3,
    pages: Int = 5,
) = Item(
    id = id, space = Space.COMICS, categoryId = category, type = ItemType.COMIC, title = id, year = 2023, rating = 7.0,
    maturity = "All", genres = listOf("Action"), synopsis = "", poster = "p", backdrop = "b",
    dominantColor = "#112233", accentColor = "#445566", format = format, readingDirection = dir,
    pages = (1..chapters).map { c -> Chapter("$id-ch$c", c, "Chapter $c", (1..pages).map { "img/$c-$it.webp" }) },
)

@OptIn(ExperimentalCoroutinesApi::class)
class ComicsReaderTest {

    private fun TestScope.store(storage: InMemoryHistoryStorage = InMemoryHistoryStorage()) =
        ComicsStore(storage, backgroundScope, clock = { testScheduler.currentTime })

    @Test
    fun manga_defaultsRtl() = runTest {
        assertEquals(ReaderMode.RTL_PAGED, defaultReaderMode(comic()))
        // Real catalog: every Manga title opens right-to-left paged.
        val manga = TestFiles.catalog().allItems(Space.COMICS).filter { it.categoryId == "co-manga" }
        assertEquals(10, manga.size)
        manga.forEach { assertEquals(it.id, ReaderMode.RTL_PAGED, defaultReaderMode(it)) }
        val s = store()
        s.ensureLoaded()
        val holder = ReaderStateHolder(manga.first(), null, s)
        assertEquals(ReaderMode.RTL_PAGED, holder.current.mode)
        assertTrue(holder.current.mode.paged)
        // RTL tap zones: left third = next page.
        assertEquals(TapZone.NEXT, tapZone(10f, 300f, ReaderMode.RTL_PAGED))
        assertEquals(TapZone.PREV, tapZone(10f, 300f, ReaderMode.LTR_PAGED))
        assertEquals(TapZone.TOGGLE, tapZone(150f, 300f, ReaderMode.RTL_PAGED))
    }

    @Test
    fun manhwa_defaultsVertical() = runTest {
        assertEquals(ReaderMode.VERTICAL, defaultReaderMode(comic(category = "co-manhwa", format = ComicFormat.LONGSTRIP, dir = ReadingDirection.TTB)))
        val strips = TestFiles.catalog().allItems(Space.COMICS).filter { it.categoryId == "co-manhwa" || it.categoryId == "co-manhua" }
        assertEquals(20, strips.size)
        strips.forEach { assertEquals(it.id, ReaderMode.VERTICAL, defaultReaderMode(it)) }
        // User override is remembered per title, default stays vertical for others.
        val s = store()
        s.ensureLoaded()
        val holder = ReaderStateHolder(strips[0], null, s)
        assertEquals(ReaderMode.VERTICAL, holder.current.mode)
        holder.setMode(ReaderMode.LTR_PAGED)
        assertEquals(ReaderMode.LTR_PAGED, ReaderStateHolder(strips[0], null, s).current.mode)
        assertEquals(ReaderMode.VERTICAL, ReaderStateHolder(strips[1], null, s).current.mode)
        holder.setMode(ReaderMode.VERTICAL)
        assertNull(s.modeOverride(strips[0].id))
    }

    @Test
    fun progress_savedPerChapter() = runTest {
        val storage = InMemoryHistoryStorage()
        val s = store(storage)
        s.ensureLoaded()
        val c = comic()
        val holder = ReaderStateHolder(c, c.pages[0].id, s)
        holder.onPageChanged(3)
        holder.openChapter(1)
        holder.onPageChanged(2)
        assertEquals(3, s.progress(c.id, c.pages[0].id)?.page)
        assertEquals(2, s.progress(c.id, c.pages[1].id)?.page)
        assertNull(s.progress(c.id, c.pages[2].id))
        runCurrent()
        // Persisted: a fresh store over the same storage restores both chapters + resumes where we stopped.
        val again = store(storage)
        again.ensureLoaded()
        assertEquals(3, again.progress(c.id, c.pages[0].id)?.page)
        assertEquals(2, again.progress(c.id, c.pages[1].id)?.page)
        val resumed = ReaderStateHolder(c, null, again)
        assertEquals(1, resumed.current.chapterIndex)
        assertEquals(2, resumed.current.page)
        // Reopening chapter 1 resumes its own page.
        resumed.openChapter(0)
        assertEquals(3, resumed.current.page)
    }

    @Test
    fun nextChapter_autoLoad() = runTest {
        val s = store()
        s.ensureLoaded()
        val c = comic(chapters = 2, pages = 4)
        val holder = ReaderStateHolder(c, null, s)
        val gen = holder.current.chapterGeneration
        repeat(3) { holder.nextPage() }
        assertEquals(3, holder.current.page)
        holder.nextPage() // past the last page → next chapter auto-loads
        assertEquals(1, holder.current.chapterIndex)
        assertEquals(0, holder.current.page)
        assertTrue(holder.current.chapterGeneration > gen)
        assertTrue(s.progress(c.id, c.pages[0].id)!!.read)
        // Webtoon end-of-strip on the last chapter → end flag, no crash.
        holder.onPageChanged(3)
        holder.onEndReached()
        assertEquals(1, holder.current.chapterIndex)
        assertTrue(holder.current.endOfComic)
        // Back from page 0 goes to the previous chapter's last page.
        holder.openChapter(1)
        holder.onPageChanged(0)
        holder.prevPage()
        assertEquals(0, holder.current.chapterIndex)
        assertEquals(3, holder.current.page)
    }

    @Test
    fun preload_next3_lowTier1_crossesChapters() {
        val c = comic(chapters = 2, pages = 4)
        assertEquals(3, preloadCount(PerformanceTier.MID))
        assertEquals(1, preloadCount(PerformanceTier.LOW))
        assertEquals(listOf("img/1-2.webp", "img/1-3.webp", "img/1-4.webp"), preloadPages(c.pages, 0, 0, 3))
        assertEquals(listOf("img/1-4.webp", "img/2-1.webp", "img/2-2.webp"), preloadPages(c.pages, 0, 2, 3))
        assertEquals(listOf("img/2-1.webp"), preloadPages(c.pages, 0, 3, 1))
        assertTrue(preloadPages(c.pages, 1, 3, 3).isEmpty())
    }

    @Test
    fun bookmark_and_continueTarget() = runTest {
        val s = store()
        s.ensureLoaded()
        val c = comic(chapters = 2, pages = 4)
        assertEquals("Start reading", continueTarget(c, s.data.value)?.label)
        s.toggleBookmark(c.id)
        assertTrue(s.isBookmarked(c.id))
        val h = ReaderStateHolder(c, null, s)
        h.onPageChanged(2)
        val t = continueTarget(c, s.data.value)
        assertNotNull(t)
        assertEquals(c.pages[0].id, t!!.chapterId)
        h.onPageChanged(3) // chapter read → continue with the next one
        assertEquals(c.pages[1].id, continueTarget(c, s.data.value)!!.chapterId)
        s.toggleBookmark(c.id)
        assertFalse(s.isBookmarked(c.id))
    }

    // ------------------------------------------------- lazy remote pages (MangaDex via the gateway)

    /** MangaDex-shaped comic: chapters known, page URLs still unresolved. */
    private fun remoteComic(chapters: Int = 2) = comic(id = "cm:uuid-1", category = "cm:manga", chapters = chapters, pages = 0)

    @Test
    fun remoteChapter_resolvesPagesLazily() = runTest {
        val s = store()
        s.ensureLoaded()
        val c = remoteComic()
        val asked = mutableListOf<String>()
        val h = ReaderStateHolder(
            comic = c,
            startChapterId = null,
            store = s,
            tier = PerformanceTier.MID,
            scope = backgroundScope,
            resolvePages = { ch ->
                asked += ch.id
                listOf("https://md/p1.png", "https://md/p2.png", "https://md/p3.png")
            },
        )
        runCurrent()
        assertEquals(listOf(c.pages[0].id), asked)
        assertEquals(3, h.current.pageCount)
        assertFalse(h.current.loadingPages)
        assertNull(h.current.unavailable)
        // Reader works normally once the pages arrive.
        h.nextPage()
        assertEquals(1, h.current.page)
        // Switching chapter resolves that chapter too.
        h.nextChapter()
        runCurrent()
        assertEquals(listOf(c.pages[0].id, c.pages[1].id), asked)
        assertEquals(3, h.current.pageCount)
    }

    @Test
    fun remoteChapter_withoutPages_showsEmptyStateAndNeverCrashes() = runTest {
        val s = store()
        s.ensureLoaded()
        val c = remoteComic()
        var fail = true
        val h = ReaderStateHolder(
            comic = c,
            startChapterId = null,
            store = s,
            tier = PerformanceTier.MID,
            scope = backgroundScope,
            resolvePages = {
                if (fail) emptyList() else listOf("https://md/p1.png")
            },
        )
        runCurrent()
        assertEquals(CHAPTER_UNAVAILABLE, h.current.unavailable)
        assertEquals(0, h.current.pageCount)
        assertFalse(h.current.loadingPages)
        // Generic message: no host, path or provider name (hardening §2).
        listOf("http", "mangadex", "gateway", "vercel").forEach {
            assertFalse(it, CHAPTER_UNAVAILABLE.lowercase().contains(it))
        }
        // Page navigation on an empty chapter is a no-op, not a crash.
        h.nextPage(); h.prevPage(); h.onEndReached(); h.onPageChanged(4)
        runCurrent()
        assertEquals(0, h.current.page)
        assertTrue(h.preload().isEmpty())
        // Retry succeeds once the resolver has pages.
        fail = false
        h.retryChapter()
        runCurrent()
        assertEquals(1, h.current.pageCount)
        assertNull(h.current.unavailable)
    }

    @Test
    fun remoteChapter_withoutResolver_flagsUnavailable() = runTest {
        val s = store()
        s.ensureLoaded()
        val h = ReaderStateHolder(remoteComic(), null, s)
        assertEquals(CHAPTER_UNAVAILABLE, h.current.unavailable)
        assertEquals(0, h.current.pageCount)
    }

    @Test
    fun readerFallbackReload_switchesToDataSaverOrBackupHost_andKeepsCurrentPage() = runTest {
        val s = store()
        s.ensureLoaded()
        val c = remoteComic()
        var fallbackCalls = 0
        val h = ReaderStateHolder(
            comic = c,
            startChapterId = null,
            store = s,
            tier = PerformanceTier.MID,
            scope = backgroundScope,
            resolvePages = { listOf("https://primary/1.png", "https://primary/2.png", "https://primary/3.png") },
            resolveFallbackPages = { _, dataSaver, preferBackup ->
                fallbackCalls++
                assertTrue(dataSaver)
                assertTrue(preferBackup)
                listOf("https://backup/data-saver/1.jpg", "https://backup/data-saver/2.jpg", "https://backup/data-saver/3.jpg")
            },
        )
        runCurrent()
        h.onPageChanged(2)
        assertEquals(2, h.current.page)

        val prevReloadGen = h.current.reloadGeneration
        h.reloadChapterWithFallback(preferDataSaver = true, preferBackup = true)
        runCurrent()

        assertEquals(1, fallbackCalls)
        assertEquals(2, h.current.page)
        assertTrue(h.current.dataSaverPages)
        assertTrue(h.current.usingBackupServer)
        assertTrue(h.current.reloadGeneration > prevReloadGen)
        assertEquals("https://backup/data-saver/3.jpg", h.current.chapter.pages[2])
    }
}
