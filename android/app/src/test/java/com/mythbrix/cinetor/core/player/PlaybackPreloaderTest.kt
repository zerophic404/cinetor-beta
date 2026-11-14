package com.mythbrix.cinetor.core.player

import com.mythbrix.cinetor.core.data.InMemoryHistoryStorage
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun movie(id: String, space: Space = Space.HOME, durationSec: Int = 600) = Item(
    id = id, space = space, categoryId = "c", type = ItemType.MOVIE, title = "T-$id", year = 2020, rating = 7.0,
    maturity = "PG", genres = emptyList(), synopsis = "", poster = "p", backdrop = "b",
    dominantColor = "#112233", accentColor = "#445566", durationSec = durationSec,
    sources = listOf(Source("https://x.test/$id.m3u8", SourceKind.HLS)),
)

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPreloaderTest {
    private class Env(scope: TestScope, dataSaver: Boolean = false, val resume: ResumeStore? = null) {
        val fake = FakeCinetorPlayer()
        var providerCalls = 0
        val pre = MediaPlaybackPreloader(
            scope = scope.backgroundScope,
            playerProvider = { providerCalls++; fake },
            resume = resume,
            dataSaver = { dataSaver },
            clock = { scope.testScheduler.currentTime },
        )
    }

    @Test
    fun rapidSwitch_keepsSinglePlayer_andLatestItem() = runTest {
        val e = Env(this)
        e.pre.warm(movie("a"))
        e.pre.warm(movie("b"))
        e.pre.warm(movie("c"))
        runCurrent()
        // a and b were cancelled before resolving: only c ever reaches the player.
        assertEquals(listOf("c"), e.fake.loads.map { it.first })
        assertEquals(setOf("c"), e.pre.readiness.value.keys)

        e.pre.warm(movie("a"))
        runCurrent()
        assertEquals(listOf("c", "a"), e.fake.loads.map { it.first })
        assertEquals("a", e.fake.currentKey)
        assertEquals(setOf("a"), e.pre.readiness.value.keys)
        assertEquals(1, e.providerCalls) // one shared player, never a second
        // Taking a stale id returns nothing → no wrong video can play.
        assertNull(e.pre.take("c"))
        assertSame(e.fake, e.pre.take("a"))
    }

    @Test
    fun cancel_releasesBuffer() = runTest {
        val e = Env(this)
        e.pre.warm(movie("a"))
        runCurrent()
        assertEquals("a", e.fake.currentKey)
        e.pre.cancel("a")
        assertEquals(1, e.fake.stops)
        assertNull(e.fake.currentKey)
        assertTrue(e.pre.readiness.value.isEmpty())
        assertNull(e.pre.take("a"))
        // Cancelling an id that is not current never touches the player.
        e.pre.warm(movie("b"))
        runCurrent()
        e.pre.cancel("zzz")
        assertEquals(1, e.fake.stops)
        assertEquals("b", e.fake.currentKey)
    }

    @Test
    fun readiness_transitions() = runTest {
        val e = Env(this)
        e.pre.warm(movie("a"))
        assertEquals(Readiness.RESOLVING, e.pre.readiness.value["a"])
        runCurrent()
        assertEquals(Readiness.BUFFERING, e.pre.readiness.value["a"])
        assertFalse(e.fake.lastPlayWhenReady) // warm never starts playback
        e.fake.setPhase(PlaybackPhase.READY)
        runCurrent()
        assertEquals(Readiness.READY, e.pre.readiness.value["a"])
        e.fake.setPhase(PlaybackPhase.BUFFERING, error = "source error")
        runCurrent()
        assertEquals(Readiness.ERROR, e.pre.readiness.value["a"])
    }

    @Test
    fun unplayableItem_isError() = runTest {
        val e = Env(this)
        e.pre.warm(movie("a").copy(sources = emptyList()))
        runCurrent()
        assertEquals(Readiness.ERROR, e.pre.readiness.value["a"])
        assertTrue(e.fake.loads.isEmpty())
    }

    @Test
    fun take_handsSamePlayer_cancelIgnoredUntilRelease() = runTest {
        val e = Env(this)
        e.pre.warm(movie("a"))
        runCurrent()
        val p = e.pre.take("a")
        assertSame(e.fake, p)
        // Details goes to background (ON_STOP → cancel) while the player screen owns it.
        e.pre.cancel("a")
        assertEquals(0, e.fake.stops)
        // Back on Details (e.g. after Home/PiP): same item warm → keeps playing, no reload.
        e.pre.warm(movie("a"))
        assertEquals(1, e.fake.loads.size)
        assertTrue(e.fake.lastPlayWhenReady)
        // Player closes → release frees it.
        e.pre.release("a")
        assertEquals(1, e.fake.stops)
    }

    @Test
    fun warmWhileOwned_onlyResolves_playReplacesOwned() = runTest {
        val e = Env(this)
        e.pre.open(movie("a"))
        runCurrent()
        // Mini player plays a; user opens Details of b → b resolves but never steals the player.
        e.pre.warm(movie("b"))
        runCurrent()
        assertEquals(listOf("a"), e.fake.loads.map { it.first })
        assertEquals("a", e.fake.currentKey)
        assertEquals(Readiness.RESOLVING, e.pre.readiness.value["b"])
        // Play on b replaces a in the same player (single audio).
        assertSame(e.fake, e.pre.take("b"))
        assertEquals(listOf("a", "b"), e.fake.loads.map { it.first })
        assertEquals("b", e.pre.ownedItemId)
        assertFalse("a" in e.pre.readiness.value)
        // Stale release of a never stops b.
        e.pre.release("a")
        assertEquals(0, e.fake.stops)
        assertEquals(1, e.providerCalls)
    }

    @Test
    fun releaseOwned_thenPendingCandidateBuffers() = runTest {
        val e = Env(this)
        e.pre.open(movie("a"))
        runCurrent()
        e.pre.warm(movie("b"))
        runCurrent()
        e.pre.release("a")
        assertEquals(listOf("a", "b"), e.fake.loads.map { it.first })
        assertFalse(e.fake.lastPlayWhenReady)
    }

    @Test
    fun dataSaver_onlyResolves_loadsOnTake() = runTest {
        val e = Env(this, dataSaver = true)
        e.pre.warm(movie("a"))
        runCurrent()
        assertTrue(e.fake.loads.isEmpty())
        assertEquals(Readiness.RESOLVING, e.pre.readiness.value["a"])
        e.pre.take("a")
        assertEquals(listOf("a"), e.fake.loads.map { it.first })
        assertTrue(e.fake.lastPlayWhenReady)
    }

    @Test
    fun warm_seeksToResumePosition() = runTest {
        val store = ResumeRepository(InMemoryHistoryStorage())
        store.save(ResumeEntry(DEFAULT_PROFILE, "a", null, positionMs = 42_000, durationMs = 600_000, updatedAt = 1, space = Space.HOME))
        val e = Env(this, resume = store)
        e.pre.warm(movie("a"))
        runCurrent()
        assertEquals("a" to 42_000L, e.fake.loads.single())
    }

    @Test
    fun open_withoutWarm_loadsAndPlays_logsFirstFrame() = runTest {
        val e = Env(this)
        val p = e.pre.open(movie("a"))
        runCurrent()
        assertSame(e.fake, p)
        assertTrue(e.fake.lastPlayWhenReady)
        // Re-open (rotation / recomposition) never reloads.
        e.pre.open(movie("a"))
        runCurrent()
        assertEquals(1, e.fake.loads.size)
        advanceTimeBy(180)
        e.fake.frames.emit("a")
        runCurrent()
        assertEquals(180L, e.pre.tapToFirstFrameMs.value)
    }
}
