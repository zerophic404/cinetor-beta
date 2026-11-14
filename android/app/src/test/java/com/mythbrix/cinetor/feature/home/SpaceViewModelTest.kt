package com.mythbrix.cinetor.feature.home

import androidx.compose.ui.graphics.Color
import com.mythbrix.cinetor.TestFiles
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.core.data.Space
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
    fun hero_pausesWhileDragging() = runTest {
        var advances = 0
        val auto = HeroAutoAdvance(backgroundScope) { advances++ }
        auto.start()
        advanceTimeBy(3_000)
        auto.pause(HeroPause.DRAG)
        assertTrue(auto.isPaused)
        advanceTimeBy(30_000)
        assertEquals(0, advances)
        // releasing restarts a full interval (no jump right after the finger lifts)
        auto.resume(HeroPause.DRAG)
        advanceTimeBy(4_999)
        assertEquals(0, advances)
        advanceTimeBy(2)
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
}
