package com.mythbrix.cinetor.feature.player

import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.InMemoryHistoryStorage
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Season
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.player.DEFAULT_PROFILE
import com.mythbrix.cinetor.core.player.FakeCinetorPlayer
import com.mythbrix.cinetor.core.player.MediaPlaybackPreloader
import com.mythbrix.cinetor.core.player.PlaybackPhase
import com.mythbrix.cinetor.core.player.PlayerTracks
import com.mythbrix.cinetor.core.player.ResumeEntry
import com.mythbrix.cinetor.core.player.ResumeRepository
import com.mythbrix.cinetor.core.player.ResumeStore
import com.mythbrix.cinetor.core.player.TrackOption
import com.mythbrix.cinetor.core.player.TrackRef
import com.mythbrix.cinetor.core.player.VideoQuality
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun testMovie(id: String, durationSec: Int = 600) = Item(
    id = id, space = Space.HOME, categoryId = "c", type = ItemType.MOVIE, title = "T-$id", year = 2020, rating = 7.0,
    maturity = "PG", genres = emptyList(), synopsis = "", poster = "p", backdrop = "b",
    dominantColor = "#112233", accentColor = "#445566", durationSec = durationSec,
    sources = listOf(Source("https://x.test/$id.m3u8", SourceKind.HLS)),
)

internal fun testEpisode(id: String, n: Int) = Episode(
    id = id, n = n, title = "Ep $n", durationSec = 600, still = "s",
    sources = listOf(Source("https://x.test/$id.mp4", SourceKind.MP4)),
    introStartSec = 5, introEndSec = 60,
)

internal fun testSeries(id: String) = Item(
    id = id, space = Space.ANIME, categoryId = "c", type = ItemType.SERIES, title = "S-$id", year = 2021, rating = 8.0,
    maturity = "PG", genres = emptyList(), synopsis = "", poster = "p", backdrop = "b",
    dominantColor = "#112233", accentColor = "#445566",
    seasons = listOf(Season(1, null, listOf(testEpisode("e1", 1), testEpisode("e2", 2)))),
)

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerStateHolderTest {
    internal class Env(scope: TestScope, val store: ResumeStore? = null) {
        val fake = FakeCinetorPlayer()
        var providerCalls = 0
        val pre = MediaPlaybackPreloader(
            scope = scope.backgroundScope,
            playerProvider = { providerCalls++; fake },
            resume = store,
            clock = { scope.testScheduler.currentTime },
        )
        val holder = PlayerStateHolder(
            scope = scope.backgroundScope,
            preloader = pre,
            resume = store,
            clock = { scope.testScheduler.currentTime },
        )
    }

    @Test
    fun quality_selectOverridesTrack() = runTest {
        val e = Env(this)
        e.holder.play(testMovie("a"))
        runCurrent()
        val q720 = VideoQuality(720, 1280, 2_000_000, TrackRef(0, 1))
        val q480 = VideoQuality(480, 854, 900_000, TrackRef(0, 0))
        e.fake.mutableState.update { it.copy(tracks = PlayerTracks(video = listOf(q720, q480))) }
        e.holder.selectQuality(720)
        assertEquals(TrackRef(0, 1), e.fake.overriddenRef)
        assertEquals(720, e.fake.state.value.tracks.forcedHeight)
        e.holder.selectQuality(null) // Auto clears the override
        assertNull(e.fake.overriddenRef)
        assertTrue(e.fake.state.value.tracks.autoQuality)
    }

    @Test
    fun speed_appliedToPlayer() = runTest {
        val e = Env(this)
        e.holder.play(testMovie("a"))
        runCurrent()
        e.holder.setSpeed(1.5f)
        assertEquals(1.5f, e.fake.state.value.speed)
        e.holder.setSpeed(5f)
        assertEquals(3.0f, e.fake.state.value.speed)
    }

    @Test
    fun audioTrack_select() = runTest {
        val e = Env(this)
        e.holder.play(testMovie("a"))
        runCurrent()
        val audio = listOf(TrackOption("1:0", "English", "en", true), TrackOption("1:1", "Japanese", "ja", false))
        e.fake.mutableState.update { it.copy(tracks = PlayerTracks(audio = audio)) }
        e.holder.selectAudio("1:1")
        assertEquals("1:1", e.fake.audioId)
        assertEquals(listOf(false, true), e.fake.state.value.tracks.audio.map { it.selected })
    }

    @Test
    fun resume_restoresPosition() = runTest {
        val store = ResumeRepository(InMemoryHistoryStorage())
        store.save(ResumeEntry(DEFAULT_PROFILE, "a", null, 42_000, 600_000, 1, Space.HOME))
        val e = Env(this, store)
        e.holder.play(testMovie("a"))
        runCurrent()
        assertEquals("a" to 42_000L, e.fake.loads.single())
        // Saved every 5 s while playing.
        e.fake.mutableState.update { it.copy(positionMs = 90_000, durationMs = 600_000, isPlaying = true, phase = PlaybackPhase.READY) }
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(90_000L, store.get(DEFAULT_PROFILE, "a", null)?.positionMs)
        // And on pause.
        e.fake.mutableState.update { it.copy(positionMs = 95_000, playWhenReady = true) }
        e.holder.togglePlay()
        runCurrent()
        assertEquals(95_000L, store.get(DEFAULT_PROFILE, "a", null)?.positionMs)
    }

    @Test
    fun rotation_doesNotRecreatePlayer() = runTest {
        val e = Env(this)
        val item = testMovie("a")
        e.holder.play(item)
        runCurrent()
        val gen = e.holder.state.value.generation
        val p = e.holder.player.value
        // Rotation / recomposition re-enters with the same title: same instance, no reload.
        e.holder.play(item)
        e.pre.open(item)
        runCurrent()
        assertEquals(1, e.fake.loads.size)
        assertEquals(1, e.providerCalls)
        assertEquals(1, e.holder.opens)
        assertSame(p, e.holder.player.value)
        assertEquals(gen, e.holder.state.value.generation)
        assertEquals(HostMode.FULL, e.holder.state.value.mode)
    }

    @Test
    fun autoplayNext_afterCountdown() = runTest {
        val e = Env(this)
        e.holder.play(testSeries("s"))
        runCurrent()
        assertEquals("e1", e.holder.state.value.episodeId)
        assertEquals("e2", e.holder.state.value.nextEpisode?.id)
        e.fake.setPhase(PlaybackPhase.ENDED)
        runCurrent()
        assertEquals(10, e.holder.state.value.nextCountdown)
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals("s#e2", e.fake.loads.last().first)
        assertEquals("e2", e.holder.state.value.episodeId)
        assertNull(e.holder.state.value.nextCountdown)
    }

    @Test
    fun autoplay_cancelled_staysOnEpisode() = runTest {
        val e = Env(this)
        e.holder.play(testSeries("s"))
        runCurrent()
        e.fake.setPhase(PlaybackPhase.ENDED)
        runCurrent()
        e.holder.cancelAutoplay()
        advanceTimeBy(11_000)
        runCurrent()
        assertEquals(1, e.fake.loads.size)
    }

    @Test
    fun skipIntro_seeksToIntroEnd() = runTest {
        val e = Env(this)
        e.holder.play(testSeries("s"))
        runCurrent()
        assertTrue(e.holder.state.value.showSkipIntro(10_000))
        e.holder.skipIntro()
        assertEquals(60_000L, e.fake.seeks.last())
    }

    @Test
    fun close_releasesPlayer_andHides() = runTest {
        val e = Env(this)
        e.holder.play(testMovie("a"))
        runCurrent()
        e.holder.close()
        assertEquals(1, e.fake.stops)
        assertEquals(HostMode.HIDDEN, e.holder.state.value.mode)
        assertNull(e.holder.player.value)
    }

    @Test
    fun inlineMode_openExpandCollapseAndMinimizeLifecycle() = runTest {
        val e = Env(this)
        val series = testSeries("s")
        e.holder.open(series, season = 1, episode = 2, inline = true)
        runCurrent()
        assertEquals(HostMode.INLINE, e.holder.state.value.mode)
        assertTrue(e.holder.state.value.active)
        assertEquals("e2", e.holder.state.value.episodeId)
        assertEquals("s#e2", e.fake.currentKey)

        // Fullscreen inside Details -> HostMode.FULL, then Back/Collapse returns to INLINE!
        e.holder.expandFullscreen()
        assertEquals(HostMode.FULL, e.holder.state.value.mode)
        e.holder.collapseFullscreen()
        assertEquals(HostMode.INLINE, e.holder.state.value.mode)

        // Leaving DetailsScreen -> minimize() transitions INLINE -> MINI and clears openedInline.
        e.holder.minimize()
        assertEquals(HostMode.MINI, e.holder.state.value.mode)

        // Tapping MiniPlayer on Home -> FULL; Back now returns to MINI (Details is gone).
        e.holder.expand()
        assertEquals(HostMode.FULL, e.holder.state.value.mode)
        e.holder.collapseFullscreen()
        assertEquals(HostMode.MINI, e.holder.state.value.mode)

        // Re-entering Details of same title -> enterInline() docks without reloading!
        val loadsBefore = e.fake.loads.size
        e.holder.enterInline()
        assertEquals(HostMode.INLINE, e.holder.state.value.mode)
        assertEquals(loadsBefore, e.fake.loads.size)
    }
}
