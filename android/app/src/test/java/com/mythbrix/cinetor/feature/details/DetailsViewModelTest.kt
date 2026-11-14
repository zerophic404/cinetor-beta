package com.mythbrix.cinetor.feature.details

import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.Category
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.NotFoundException
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.player.PlaybackPreloader
import com.mythbrix.cinetor.core.player.Readiness
import com.mythbrix.cinetor.core.player.SimulatedPlaybackPreloader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun item(id: String, space: Space = Space.HOME, genres: List<String> = listOf("Action")) = Item(
    id = id, space = space, categoryId = "c", type = ItemType.MOVIE, title = id, year = 2020, rating = 7.0,
    maturity = "PG", genres = genres, synopsis = "", poster = "p", backdrop = "b",
    dominantColor = "#112233", accentColor = "#445566",
)

private class FakeCatalog(private val items: List<Item>, private val delayMs: Long = 1_000) : CatalogRepository {
    override suspend fun categories(space: Space): List<Category> = emptyList()
    override suspend fun item(id: String): Item {
        delay(delayMs)
        return items.firstOrNull { it.id == id } ?: throw NotFoundException(id)
    }
    override suspend fun row(rowId: String): List<Item> = throw NotFoundException(rowId)
    override suspend fun byGenre(space: Space, genre: String): List<Item> = items.filter { genre in it.genres }
    override suspend fun genres(space: Space): List<String> = emptyList()
    // Deliberately leaky: returns every space so the details layer must filter.
    override suspend fun allItems(space: Space): List<Item> = items
}

private class RecordingPreloader : PlaybackPreloader {
    val warmed = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    override val readiness: StateFlow<Map<String, Readiness>> = MutableStateFlow(emptyMap())
    override fun warm(item: Item, episodeId: String?) {
        warmed += item.id
    }
    override fun cancel(itemId: String) {
        cancelled += itemId
    }
}

/** advanceUntilIdle ignores backgroundScope work (coroutines-test ≥1.7) → advance virtual time instead. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() {
    advanceTimeBy(5_000)
    runCurrent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelTest {
    private val catalogItems = listOf(
        item("a"), item("b"), item("c", genres = listOf("Action", "Drama")),
        item("tv1", Space.TV), item("adult1", Space.ADULT), item("music1", Space.MUSIC),
        item("anime1", Space.ANIME),
    )

    @Test
    fun details_showsSkeletonUntilLoaded() = runTest {
        val h = DetailsStateHolder(FakeCatalog(catalogItems), RecordingPreloader(), backgroundScope)
        h.open("a")
        runCurrent()
        assertTrue(h.state.value is DetailsUiState.Loading)
        assertNull((h.state.value as DetailsUiState.Loading).peek)
        advanceTimeBy(999)
        assertTrue(h.state.value is DetailsUiState.Loading)
        advanceTimeBy(2)
        runCurrent()
        val ready = h.state.value as DetailsUiState.Ready
        assertEquals("a", ready.ui.item.id)
    }

    @Test
    fun details_callsWarmOnOpen_andCancelOnLeave() = runTest {
        val pre = RecordingPreloader()
        val peek = catalogItems.associateBy { it.id }
        val h = DetailsStateHolder(FakeCatalog(catalogItems), pre, backgroundScope, peek::get)
        h.open("a")
        // warm starts immediately (in parallel with the item load), not after it
        assertEquals(listOf("a"), pre.warmed)
        assertTrue(h.state.value is DetailsUiState.Loading)
        settle()
        assertEquals(listOf("a"), pre.warmed) // not warmed twice
        h.leave()
        assertEquals(listOf("a"), pre.cancelled)
    }

    @Test
    fun details_withoutPeek_warmsAsSoonAsItemResolves() = runTest {
        val pre = RecordingPreloader()
        val h = DetailsStateHolder(FakeCatalog(catalogItems), pre, backgroundScope)
        h.open("b")
        runCurrent()
        assertTrue(pre.warmed.isEmpty())
        settle()
        assertEquals(listOf("b"), pre.warmed)
    }

    @Test
    fun moreLikeThis_sameSpaceOnly() = runTest {
        val h = DetailsStateHolder(FakeCatalog(catalogItems), RecordingPreloader(), backgroundScope)
        h.open("a")
        settle()
        val ui = (h.state.value as DetailsUiState.Ready).ui
        assertFalse(ui.moreLoading)
        assertTrue(ui.moreLikeThis.isNotEmpty())
        assertTrue(ui.moreLikeThis.all { it.space == Space.HOME })
        assertFalse(ui.moreLikeThis.any { it.id == "a" })
        // pure rule too
        val pure = moreLikeThis(item("x"), catalogItems)
        assertEquals(listOf("a", "b", "c"), pure.map { it.id }.sorted())
    }

    @Test
    fun rapidSwitch_cancelsPreviousWarm() = runTest {
        val pre = RecordingPreloader()
        val peek = catalogItems.associateBy { it.id }
        val h = DetailsStateHolder(FakeCatalog(catalogItems), pre, backgroundScope, peek::get)
        h.open("a")
        runCurrent()
        advanceTimeBy(300)
        h.open("b")
        assertEquals(listOf("a"), pre.cancelled)
        assertEquals(listOf("a", "b"), pre.warmed)
        settle()
        assertEquals("b", (h.state.value as DetailsUiState.Ready).ui.item.id)
        assertEquals("b", h.currentId)
    }

    @Test
    fun pauseAndResumeWarm_followScreenVisibility() = runTest {
        val pre = RecordingPreloader()
        val h = DetailsStateHolder(FakeCatalog(catalogItems), pre, backgroundScope)
        h.open("a")
        settle()
        h.pauseWarm()
        assertEquals(listOf("a"), pre.cancelled)
        h.resumeWarm()
        assertEquals(listOf("a", "a"), pre.warmed)
    }

    @Test
    fun simulatedPreloader_latestWarmWins_andReachesReady() = runTest {
        val pre = SimulatedPlaybackPreloader(backgroundScope, resolveMs = 100, bufferMs = 100)
        pre.warm(item("a"))
        runCurrent()
        assertEquals(Readiness.RESOLVING, pre.readiness.value["a"])
        pre.warm(item("b"))
        assertNull(pre.readiness.value["a"])
        advanceTimeBy(250)
        assertEquals(Readiness.READY, pre.readiness.value["b"])
        pre.cancel("b")
        assertTrue(pre.readiness.value.isEmpty())
    }
}
