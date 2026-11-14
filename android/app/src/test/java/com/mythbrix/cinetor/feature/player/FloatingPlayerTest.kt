package com.mythbrix.cinetor.feature.player

import com.mythbrix.cinetor.core.player.PlaybackPhase
import com.mythbrix.cinetor.feature.details.DetailsStateHolder
import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.Category
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Space
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class MapCatalog(items: List<Item>) : CatalogRepository {
    private val byId = items.associateBy { it.id }
    override suspend fun categories(space: Space): List<Category> = emptyList()
    override suspend fun item(id: String): Item = byId.getValue(id)
    override suspend fun row(rowId: String): List<Item> = emptyList()
    override suspend fun byGenre(space: Space, genre: String): List<Item> = emptyList()
    override suspend fun genres(space: Space): List<String> = emptyList()
    override suspend fun allItems(space: Space): List<Item> = byId.values.toList()
}

@OptIn(ExperimentalCoroutinesApi::class)
class FloatingPlayerTest {
    @Test
    fun miniPlayer_replacedByNewPlay_singleAudio() = runTest {
        val e = PlayerStateHolderTest.Env(this)
        e.holder.play(testMovie("a"))
        runCurrent()
        e.fake.setPhase(PlaybackPhase.READY)
        e.holder.minimize()
        assertEquals(HostMode.MINI, e.holder.state.value.mode)
        // New title Play while mini plays a: same single player, a replaced by b, full screen again.
        e.holder.play(testMovie("b"))
        runCurrent()
        assertEquals(listOf("a", "b"), e.fake.loads.map { it.first })
        assertEquals("b", e.fake.currentKey)
        assertEquals("b", e.pre.ownedItemId)
        assertEquals(1, e.providerCalls)
        assertEquals(HostMode.FULL, e.holder.state.value.mode)
        assertEquals("b", e.holder.state.value.item?.id)
        assertTrue(e.fake.lastPlayWhenReady)
    }

    @Test
    fun navigation_keepsPlayback() = runTest {
        val e = PlayerStateHolderTest.Env(this)
        val a = testMovie("a")
        val b = testMovie("b")
        e.holder.play(a)
        runCurrent()
        e.fake.setPhase(PlaybackPhase.READY)
        e.holder.minimize()
        // Browse: open Details of b (warms) then go back (cancels) while the mini plays a.
        val details = DetailsStateHolder(MapCatalog(listOf(a, b)), e.pre, backgroundScope) { id -> if (id == "b") b else null }
        details.open("b")
        advanceTimeBy(100)
        runCurrent()
        details.pauseWarm()
        details.resumeWarm()
        details.leave()
        runCurrent()
        assertEquals(listOf("a"), e.fake.loads.map { it.first })
        assertEquals(0, e.fake.stops)
        assertEquals("a", e.fake.currentKey)
        assertTrue(e.fake.state.value.isPlaying)
        assertEquals(HostMode.MINI, e.holder.state.value.mode)
        // Details of the playing title (e.g. back on it) keeps it playing too.
        details.open("a")
        runCurrent()
        details.leave()
        assertEquals(1, e.fake.loads.size)
        assertEquals(0, e.fake.stops)
        // Expand → same player, no reload.
        e.holder.expand()
        assertEquals(HostMode.FULL, e.holder.state.value.mode)
        assertEquals(1, e.holder.opens)
    }

    @Test
    fun pip_actions_seek() = runTest {
        val e = PlayerStateHolderTest.Env(this)
        e.holder.play(testMovie("a"))
        runCurrent()
        e.fake.mutableState.update { it.copy(positionMs = 30_000, durationMs = 600_000, playWhenReady = true) }
        e.holder.applyPip(PipAction.FORWARD)
        assertEquals(40_000L, e.fake.seeks.last())
        e.fake.mutableState.update { it.copy(positionMs = 40_000) }
        e.holder.applyPip(PipAction.REWIND)
        assertEquals(30_000L, e.fake.seeks.last())
        e.holder.applyPip(PipAction.TOGGLE)
        assertFalse(e.fake.lastPlayWhenReady)
        assertEquals(PipAction.TOGGLE, PipAction.fromCode(2))
        assertEquals(null, PipAction.fromCode(9))
        assertEquals(239 to 100, pipAspect(4000, 1000))
        assertEquals(16 to 9, pipAspect(0, 0))
        assertEquals(1920 to 1080, pipAspect(1920, 1080))
    }

    @Test
    fun miniWindow_snapsToNearestCorner_andSwipeOffDismisses() {
        val w = 1080f
        val h = 2200f
        assertEquals(Corner.TOP_START, MiniPlayerMath.nearestCorner(10f, 10f, 400f, 225f, w, h))
        assertEquals(Corner.BOTTOM_END, MiniPlayerMath.nearestCorner(700f, 1800f, 400f, 225f, w, h))
        assertEquals(Corner.TOP_END, MiniPlayerMath.nearestCorner(600f, 300f, 400f, 225f, w, h))
        val o = MiniPlayerMath.offsetFor(Corner.BOTTOM_END, 400f, 225f, w, h, 12f, 100f, 200f)
        assertEquals(1080f - 400f - 12f, o.x)
        assertEquals(2200f - 225f - 200f, o.y)
        assertTrue(MiniPlayerMath.shouldDismiss(-250f, 400f, w))
        assertTrue(MiniPlayerMath.shouldDismiss(900f, 400f, w))
        assertFalse(MiniPlayerMath.shouldDismiss(500f, 400f, w))
    }
}
