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
}
