package com.mythbrix.cinetor.feature.home

import androidx.compose.ui.graphics.Color
import com.mythbrix.cinetor.TestFiles
import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.Category
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Row
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.gateway.PagedCatalog
import com.mythbrix.cinetor.core.data.gateway.RowPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceViewModelTest {

    private suspend fun SpaceStateHolder.ready(predicate: (SpaceUi) -> Boolean = { true }): SpaceUi =
        (state.first { it is SpaceUiState.Ready && predicate(it.ui) } as SpaceUiState.Ready).ui

    private fun TestScope.holder(space: Space) =
        SpaceStateHolder(space, TestFiles.catalog(), backgroundScope).also { it.load() }

    @Test
    fun load_homeHasMoviesSeriesTabsHeroesAndStyledRows() = runTest {
        val ui = holder(Space.HOME).ready()
        assertEquals(listOf("Movies", "Series"), ui.tabs.map { it.title })
        assertTrue(ui.showTabs)
        assertTrue(ui.heroes.isNotEmpty())
        assertTrue(ui.genres.isNotEmpty())
        assertTrue(ui.rows.map { it.style }.containsAll(listOf(RowStyle.POSTER, RowStyle.TOP10, RowStyle.CONTINUE)))
        assertTrue(ui.rows.all { r -> r.items.map { it.id }.toSet().size == r.items.size })
    }

    @Test
    fun categoryTab_swapsHero() = runTest {
        val h = holder(Space.HOME)
        val movies = h.ready()
        assertTrue(movies.heroes.all { it.categoryId == "home-movies" })
        h.selectTab(1)
        val series = h.ready { it.selectedTab == 1 && !it.tabLoading }
        assertTrue(series.heroes.isNotEmpty())
        assertTrue(series.heroes.all { it.categoryId == "home-series" })
        assertNotEquals(movies.heroes.map { it.id }, series.heroes.map { it.id })
        // back to the cached tab: same heroes again
        h.selectTab(0)
        assertEquals(movies.heroes.map { it.id }, h.ready { it.selectedTab == 0 }.heroes.map { it.id })
    }

    @Test
    fun genreChip_filtersRows() = runTest {
        val h = holder(Space.HOME)
        val all = h.ready()
        val genre = all.genres.first { g -> all.rows.any { r -> r.items.any { g in it.genres } } }
        h.selectGenre(genre)
        val filtered = h.ready { it.selectedGenre == genre }
        assertTrue(filtered.rows.isNotEmpty())
        assertTrue(filtered.rows.all { r -> r.items.isNotEmpty() && r.items.all { genre in it.genres } })
        assertTrue(filtered.rows.sumOf { it.items.size } < all.rows.sumOf { it.items.size })
        assertTrue(filtered.heroes.all { genre in it.genres })
        // tapping the selected chip again clears the filter
        h.selectGenre(genre)
        val cleared = h.ready { it.selectedGenre == null }
        assertEquals(all.rows.map { it.id }, cleared.rows.map { it.id })
    }

    @Test
    fun applyGenre_null_isIdentity() {
        val content = TabContent(emptyList(), emptyList())
        assertTrue(applyGenre(content, null) === content)
    }

    @Test
    fun musicTabs_areAlbumsAndTracks_filteredByType() = runTest {
        val h = holder(Space.MUSIC)
        val albums = h.ready()
        assertEquals(listOf("Albums", "Tracks"), albums.tabs.map { it.title })
        assertTrue(albums.rows.flatMap { it.items }.all { it.type == ItemType.ALBUM })
        h.selectTab(1)
        val tracks = h.ready { it.selectedTab == 1 && !it.tabLoading }
        assertTrue(tracks.rows.isNotEmpty())
        assertTrue(tracks.rows.flatMap { it.items }.all { it.type == ItemType.TRACK })
        assertTrue(tracks.heroes.all { it.type == ItemType.TRACK })
    }

    @Test
    fun adult_singleCategory_hidesTabs_genreLed() = runTest {
        val ui = holder(Space.ADULT).ready()
        assertFalse(ui.showTabs)
        assertTrue(ui.genres.isNotEmpty())
    }

    @Test
    fun hero_autoAdvancesEvery5s() = runTest {
        var advances = 0
        val auto = HeroAutoAdvance(backgroundScope) { advances++ }
        auto.start()
        advanceTimeBy(4_999)
        assertEquals(0, advances)
        advanceTimeBy(2)
        assertEquals(1, advances)
        advanceTimeBy(5_000)
        assertEquals(2, advances)
        advanceTimeBy(15_000)
        assertEquals(5, advances)
        auto.stop()
        advanceTimeBy(20_000)
        assertEquals(5, advances)
    }

    @Test
    fun hero_pausesWhileDragging_andResumesAfter6sPostTouchDelay() = runTest {
        var advances = 0
        val auto = HeroAutoAdvance(backgroundScope) { advances++ }
        auto.start()
        advanceTimeBy(3_000)
        auto.pause(HeroPause.DRAG)
        assertTrue(auto.isPaused)
        advanceTimeBy(30_000)
        assertEquals(0, advances)
        // Releasing a drag waits 6s post-touch cooldown, then resumes normal 5s cadence
        auto.resume(HeroPause.DRAG)
        advanceTimeBy(5_999)
        assertEquals(0, advances)
        advanceTimeBy(2)
        assertEquals(1, advances)
        advanceTimeBy(5_000)
        assertEquals(2, advances)
    }

    @Test
    fun hero_lowTierOrSettingPause_stopsTicking() = runTest {
        var advances = 0
        val auto = HeroAutoAdvance(backgroundScope) { advances++ }
        auto.start()
        auto.setPaused(HeroPause.LOW_TIER, true)
        assertTrue(auto.isPaused)
        assertFalse(auto.isTicking)
        advanceTimeBy(20_000)
        assertEquals(0, advances)
        auto.setPaused(HeroPause.LOW_TIER, false)
        advanceTimeBy(5_001)
        assertEquals(1, advances)
    }

    @Test
    fun hero_anyPauseReasonBlocks_untilAllCleared() = runTest {
        var advances = 0
        val auto = HeroAutoAdvance(backgroundScope) { advances++ }
        auto.start()
        auto.pause(HeroPause.OFFSCREEN)
        auto.pause(HeroPause.LIFECYCLE)
        auto.resume(HeroPause.OFFSCREEN)
        advanceTimeBy(12_000)
        assertEquals(0, advances)
        auto.resume(HeroPause.LIFECYCLE)
        advanceTimeBy(5_001)
        assertEquals(1, advances)
    }

    @Test
    fun heroStartPage_alignsToFirstHero() {
        assertEquals(0, heroStartPage(1))
        for (n in 2..8) assertEquals(0, heroStartPage(n) % n)
    }

    @Test
    fun tintFeeder_debounces150ms() = runTest {
        val applied = mutableListOf<Color>()
        val feeder = TintFeeder { applied += it }
        backgroundScope.launch { feeder.run() }
        runCurrent()
        feeder.offer(Color.Red)
        advanceTimeBy(60)
        feeder.offer(Color.Blue)
        advanceTimeBy(149)
        assertTrue(applied.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf(Color.Blue), applied)
        assertNull(applied.firstOrNull { it == Color.Red })
    }

    @Test
    fun pagedCatalog_loadMore_appendsDeduplicatedPages_andUpdatesSeeAllSheet() = runTest {
        val fake = FakePagedCatalog()
        val h = SpaceStateHolder(Space.HOME, fake, backgroundScope)
        h.load()
        val initial = h.ready()
        assertEquals(listOf("i1", "i2", "i3", "i4"), initial.rows.single().items.map { it.id })
        assertNull(initial.seeAllRow)

        h.openSeeAll("r1")
        val opened = h.ready { it.seeAllRowId == "r1" }
        assertEquals("Trending Now", opened.seeAllRow?.title)
        assertEquals(4, opened.seeAllRow?.items?.size)

        h.loadMore("r1")
        val page2 = h.ready { it.rows.single().items.size == 6 }
        assertEquals(listOf("i1", "i2", "i3", "i4", "i5", "i6"), page2.rows.single().items.map { it.id })
        assertEquals(6, page2.seeAllRow?.items?.size)

        // Page 3 has hasMore = false -> subsequent loadMore calls are ignored
        h.loadMore("r1")
        val page3 = h.ready { it.rows.single().items.size == 7 }
        assertEquals(listOf("i1", "i2", "i3", "i4", "i5", "i6", "i7"), page3.rows.single().items.map { it.id })
        val callsAfterExhausted = fake.pageRequests.size
        h.loadMore("r1")
        runCurrent()
        assertEquals(callsAfterExhausted, fake.pageRequests.size)

        h.closeSeeAll()
        val closed = h.ready { it.seeAllRowId == null }
        assertNull(closed.seeAllRow)
    }

    private class FakePagedCatalog : CatalogRepository, PagedCatalog {
        val pageRequests = mutableListOf<Pair<String, Int>>()
        private val items = (1..7).map { n ->
            Item(
                id = "i$n",
                space = Space.HOME,
                categoryId = "cat-1",
                type = ItemType.MOVIE,
                title = "Movie $n",
                year = 2026,
                rating = 7.5,
                maturity = "PG-13",
                genres = listOf("Action"),
                synopsis = "Synopsis $n",
                poster = "images/posters/$n.webp",
                backdrop = "images/posters/$n.webp",
                dominantColor = "#111111",
                accentColor = "#E11D2E",
            )
        }

        override suspend fun categories(space: Space): List<Category> = listOf(
            Category(
                id = "cat-1",
                space = Space.HOME,
                title = "Movies",
                heroIds = listOf("i1"),
                rows = listOf(Row("r1", "Trending Now", RowStyle.POSTER, listOf("i1", "i2", "i3", "i4"))),
            ),
        )

        override suspend fun genres(space: Space): List<String> = listOf("Action")
        override suspend fun row(rowId: String): List<Item> = items.take(4)
        override suspend fun item(id: String): Item = items.first { it.id == id }
        override suspend fun byGenre(space: Space, genre: String): List<Item> = items
        override suspend fun allItems(space: Space): List<Item> = items

        override suspend fun rowPage(rowId: String, page: Int): RowPage {
            pageRequests += rowId to page
            return when (page) {
                2 -> RowPage(listOf(items[3], items[4], items[5]), page = 2, hasMore = true) // i4 duplicate + i5, i6
                3 -> RowPage(listOf(items[6]), page = 3, hasMore = false) // i7, exhausted
                else -> RowPage(emptyList(), page = page, hasMore = false)
            }
        }
    }
}
