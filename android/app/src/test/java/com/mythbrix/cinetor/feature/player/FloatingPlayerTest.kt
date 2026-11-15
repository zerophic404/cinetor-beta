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

    // ---------------------------------------------- resizable mini player (hardening §3)

    @Test
    fun miniResize_clampedTo25To80PercentAndKeeps16By9() {
        assertEquals(0.25f, MiniPlayerMath.MIN_FRACTION, 0f)
        assertEquals(0.80f, MiniPlayerMath.MAX_FRACTION, 0f)
        // Clamp
        assertEquals(0.25f, MiniPlayerMath.clampFraction(0.05f), 1e-4f)
        assertEquals(0.80f, MiniPlayerMath.clampFraction(1.5f), 1e-4f)
        assertEquals(0.42f, MiniPlayerMath.clampFraction(Float.NaN), 1e-4f)
        // Corner-handle drag: outward grows, inward shrinks, never past the clamps.
        val areaW = 1000f
        assertEquals(0.5f, MiniPlayerMath.resize(0.4f, 100f, 100f * 9f / 16f, areaW), 1e-3f)
        assertTrue(MiniPlayerMath.resize(0.4f, -300f, -300f, areaW) == MiniPlayerMath.MIN_FRACTION)
        assertTrue(MiniPlayerMath.resize(0.4f, 3000f, 3000f, areaW) == MiniPlayerMath.MAX_FRACTION)
        assertEquals(0.4f, MiniPlayerMath.resize(0.4f, 10f, 10f, 0f), 1e-4f) // not measured yet
        // Pinch
        assertEquals(0.6f, MiniPlayerMath.pinch(0.3f, 2f), 1e-4f)
        assertEquals(MiniPlayerMath.MIN_FRACTION, MiniPlayerMath.pinch(0.3f, 0.1f), 1e-4f)
        assertEquals(0.3f, MiniPlayerMath.pinch(0.3f, 0f), 1e-4f)
        // 16:9 is a property of the size function, so resizing can never change the aspect.
        listOf(0.25f, 0.42f, 0.8f).forEach { f ->
            val w = 1080f * f
            assertEquals(16f / 9f, w / MiniPlayerMath.heightFor(w), 1e-4f)
        }
    }

    @Test
    fun miniSize_rememberedPerProfile() = runTest {
        val sizes = InMemoryMiniSizeStore()
        var profile = "default"
        val pre = com.mythbrix.cinetor.core.player.MediaPlaybackPreloader(
            scope = backgroundScope,
            playerProvider = { com.mythbrix.cinetor.core.player.FakeCinetorPlayer() },
            clock = { testScheduler.currentTime },
        )
        fun holder() = PlayerStateHolder(
            scope = backgroundScope, preloader = pre, profileId = { profile },
            clock = { testScheduler.currentTime }, miniSizes = sizes,
        )
        val h = holder()
        assertEquals(MiniPlayerMath.DEFAULT_FRACTION, h.miniFraction.value, 1e-4f)
        h.setMiniFraction(0.7f)
        assertEquals(0.7f, h.miniFraction.value, 1e-4f)
        // Out-of-range input is clamped before it is stored.
        h.setMiniFraction(9f)
        assertEquals(MiniPlayerMath.MAX_FRACTION, h.miniFraction.value, 1e-4f)
        // Another profile keeps its own size; switching back restores 0.80.
        profile = "kids"
        h.reloadMiniFraction()
        assertEquals(MiniPlayerMath.DEFAULT_FRACTION, h.miniFraction.value, 1e-4f)
        h.setMiniFraction(0.3f)
        profile = "default"
        h.reloadMiniFraction()
        assertEquals(MiniPlayerMath.MAX_FRACTION, h.miniFraction.value, 1e-4f)
        // A fresh holder (process restart) reads the stored value.
        assertEquals(MiniPlayerMath.MAX_FRACTION, holder().miniFraction.value, 1e-4f)
    }
}
