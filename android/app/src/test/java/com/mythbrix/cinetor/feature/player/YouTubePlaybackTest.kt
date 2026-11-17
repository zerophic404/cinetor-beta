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
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.core.player.ResumeRepository
import com.mythbrix.cinetor.core.player.applyYouTubeProgress
import com.mythbrix.cinetor.core.player.applyYouTubeReady
import com.mythbrix.cinetor.core.player.applyYouTubeStateChange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubePlaybackTest {

    @Test
    fun extractYouTubeVideoId_parsesAllSupportedUrlFormats() {
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=42s"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ?si=abc_123"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ?autoplay=1"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://youtube-nocookie.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://www.youtube.com/live/dQw4w9WgXcQ?feature=share"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("yt://dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("yt://dQw4w9WgXcQ?t=15"))
        assertEquals("vid_123-XYZ", extractYouTubeVideoId("yt:vid_123-XYZ"))
    }

    @Test
    fun extractYouTubeVideoId_rejectsNonYouTubeAndUnsafeUrls() {
        assertNull(extractYouTubeVideoId(null))
        assertNull(extractYouTubeVideoId(""))
        assertNull(extractYouTubeVideoId("   "))
        assertNull(extractYouTubeVideoId("https://cdn.example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(extractYouTubeVideoId("https://evil-youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(extractYouTubeVideoId("https://www.youtube.com/watch?list=PL1234567890"))
        assertNull(extractYouTubeVideoId("https://www.youtube.com/channel/UC1234567890"))
        assertNull(extractYouTubeVideoId("https://www.youtube-nocookie.com/embed/ab\"onload=\"alert(1)"))
        assertNull(extractYouTubeVideoId("gw-stream:999?detail_path=movie"))
    }

    @Test
    fun buildChromelessYouTubeHtml_setsNoCookieControlsOffAndOverscanCss() {
        val html = buildChromelessYouTubeHtml(videoId = "dQw4w9WgXcQ", startSec = 37, autoplay = true)
        assertTrue(html.contains("'controls': 0"))
        assertTrue(html.contains("'modestbranding': 1"))
        assertTrue(html.contains("'rel': 0"))
        assertTrue(html.contains("'playsinline': 1"))
        assertTrue(html.contains("'iv_load_policy': 3"))
        assertTrue(html.contains("'start': 37"))
        assertTrue(html.contains("youtube-nocookie.com"))
        assertTrue(html.contains("pointer-events: none"))
        assertTrue(html.contains("window.AndroidBridge"))
    }

    @Test
    fun youtubeStateTransitions_driveReadyPlayingPausedBufferingAndEnded() {
        val initial = PlayerUiState(
            key = "m1",
            youtubeVideoId = "dQw4w9WgXcQ",
            playWhenReady = true,
            phase = PlaybackPhase.BUFFERING,
        )
        val ready = applyYouTubeReady(initial, durationSec = 125.4)
        assertEquals(125_400L, ready.durationMs)
        assertEquals(PlaybackPhase.READY, ready.phase)

        val playing = applyYouTubeStateChange(ready, ytState = 1)
        assertTrue(playing.isPlaying)
        assertTrue(playing.playWhenReady)
        assertEquals(PlaybackPhase.READY, playing.phase)

        val progressed = applyYouTubeProgress(playing, currentSec = 42.5, durationSec = 125.4, ytState = 1)
        assertEquals(42_500L, progressed.positionMs)
        assertEquals(42_500L, progressed.bufferedMs)
        assertTrue(progressed.isPlaying)

        val buffering = applyYouTubeStateChange(progressed, ytState = 3)
        assertEquals(PlaybackPhase.BUFFERING, buffering.phase)
        assertFalse(buffering.isPlaying)

        val paused = applyYouTubeStateChange(buffering, ytState = 2)
        assertEquals(PlaybackPhase.READY, paused.phase)
        assertFalse(paused.isPlaying)
        assertFalse(paused.playWhenReady)

        val ended = applyYouTubeStateChange(progressed, ytState = 0)
        assertEquals(PlaybackPhase.ENDED, ended.phase)
        assertFalse(ended.isPlaying)
    }

    @Test
    fun youtubeEnded_savesResumeAndTriggersAutoNextEpisode() = runTest {
        val store = ResumeRepository(InMemoryHistoryStorage())
        val fake = FakeCinetorPlayer()
        val pre = MediaPlaybackPreloader(
            scope = backgroundScope,
            playerProvider = { fake },
            resume = store,
            clock = { testScheduler.currentTime },
        )
        val holder = PlayerStateHolder(
            scope = backgroundScope,
            preloader = pre,
            resume = store,
            clock = { testScheduler.currentTime },
        )
        val ep1 = Episode(
            id = "ep1", n = 1, title = "Pilot", durationSec = 120, still = "s1",
            sources = listOf(Source("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", SourceKind.MP4, "YouTube · HD")),
        )
        val ep2 = Episode(
            id = "ep2", n = 2, title = "Finale", durationSec = 150, still = "s2",
            sources = listOf(Source("https://www.youtube-nocookie.com/embed/9bZkp7q19f0", SourceKind.MP4, "YouTube · HD")),
        )
        val series = testSeries("yt-series").copy(seasons = listOf(Season(1, null, listOf(ep1, ep2))))

        holder.play(series)
        runCurrent()
        assertEquals("ep1", holder.state.value.episodeId)

        fake.mutableState.update {
            it.copy(
                youtubeVideoId = "dQw4w9WgXcQ",
                positionMs = 118_000L,
                durationMs = 120_000L,
                isPlaying = true,
                playWhenReady = true,
                phase = PlaybackPhase.READY,
            )
        }
        // State 0 (ENDED) emitted by YouTube bridge transitions phase to ENDED.
        fake.mutableState.update { applyYouTubeStateChange(it, ytState = 0) }
        runCurrent()

        assertEquals(118_000L, store.get(DEFAULT_PROFILE, "yt-series", "ep1")?.positionMs)
        assertEquals(10, holder.state.value.nextCountdown)
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals("ep2", holder.state.value.episodeId)
        assertEquals("yt-series#ep2", fake.loads.last().first)
    }

    @Test
    fun youtubeError_fallsBackToAlternateGatewayStreamKeepingPosition() = runTest {
        val fake = FakeCinetorPlayer()
        val loadedUrls = mutableListOf<String>()
        val trackingPlayer = object : com.mythbrix.cinetor.core.player.CinetorPlayer by fake {
            override fun load(request: com.mythbrix.cinetor.core.player.PlaybackRequest, startPositionMs: Long, playWhenReady: Boolean) {
                loadedUrls += request.plan.url
                fake.load(request, startPositionMs, playWhenReady)
            }
        }
        val pre = MediaPlaybackPreloader(
            scope = backgroundScope,
            playerProvider = { trackingPlayer },
            clock = { testScheduler.currentTime },
        )
        val holder = PlayerStateHolder(
            scope = backgroundScope,
            preloader = pre,
            needsPrepare = { it.id.startsWith("gw:") },
            prepare = { item, _, _ -> item },
            clock = { testScheduler.currentTime },
        )
        val ytUrl = "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"
        val mp4Url = "https://cdn.test/backup-720.mp4"
        val movie = Item(
            id = "gw:yt-fallback", space = Space.HOME, categoryId = "c", type = ItemType.MOVIE,
            title = "Fallback Movie", year = 2025, rating = 8.1, maturity = "PG-13",
            genres = emptyList(), synopsis = "", poster = "p", backdrop = "b",
            dominantColor = "#112233", accentColor = "#E11D2E", durationSec = 300,
            sources = listOf(
                Source(ytUrl, SourceKind.MP4, "YouTube · HD"),
                Source(mp4Url, SourceKind.MP4, "720p · MP4", height = 720),
            ),
        )

        holder.play(movie)
        runCurrent()
        assertEquals(ytUrl, loadedUrls.last())

        // Simulate playback progressing to 25s, then YouTube onError (150: embed restricted).
        fake.mutableState.update { it.copy(youtubeVideoId = "dQw4w9WgXcQ", positionMs = 25_000L, phase = PlaybackPhase.READY) }
        fake.setPhase(PlaybackPhase.IDLE, error = "stream unavailable (150)")
        runCurrent()

        assertEquals(mp4Url, loadedUrls.last())
        assertEquals(1, holder.state.value.sourceIndex)
        assertEquals(25_000L, fake.loads.last().second)
    }

    @Test
    fun dubAndQualityChoice_persistsAcrossEpisodes() = runTest {
        val prefs = InMemoryPlaybackPreferenceStore()
        val fake = FakeCinetorPlayer()
        val loadedUrls = mutableListOf<String>()
        val trackingPlayer = object : com.mythbrix.cinetor.core.player.CinetorPlayer by fake {
            override fun load(request: com.mythbrix.cinetor.core.player.PlaybackRequest, startPositionMs: Long, playWhenReady: Boolean) {
                loadedUrls += request.plan.url
                fake.load(request, startPositionMs, playWhenReady)
            }
        }
        val pre = MediaPlaybackPreloader(
            scope = backgroundScope,
            playerProvider = { trackingPlayer },
            clock = { testScheduler.currentTime },
        )
        val holder = PlayerStateHolder(
            scope = backgroundScope,
            preloader = pre,
            playbackPrefs = prefs,
            clock = { testScheduler.currentTime },
        )
        val ep1 = Episode(
            id = "e1", n = 1, title = "Ep 1", durationSec = 600, still = "s",
            sources = listOf(
                Source("https://cdn.test/e1-1080-en.mp4", SourceKind.MP4, "1080p · English", height = 1080),
                Source("https://cdn.test/e1-1080-hi.mp4", SourceKind.MP4, "1080p · Hindi", height = 1080),
                Source("https://cdn.test/e1-720-hi.mp4", SourceKind.MP4, "720p · Hindi", height = 720),
            ),
        )
        val ep2 = Episode(
            id = "e2", n = 2, title = "Ep 2", durationSec = 600, still = "s",
            sources = listOf(
                Source("https://cdn.test/e2-1080-en.mp4", SourceKind.MP4, "1080p · English", height = 1080),
                Source("https://cdn.test/e2-1080-hi.mp4", SourceKind.MP4, "1080p · Hindi", height = 1080),
                Source("https://cdn.test/e2-720-hi.mp4", SourceKind.MP4, "720p · Hindi", height = 720),
            ),
        )
        val series = testSeries("dub-series").copy(seasons = listOf(Season(1, null, listOf(ep1, ep2))))

        holder.play(series, "e1")
        runCurrent()
        assertEquals(0, holder.state.value.sourceIndex)

        // Viewer chooses 720p · Hindi on Episode 1.
        holder.selectSource(2)
        runCurrent()
        assertEquals(2, holder.state.value.sourceIndex)
        assertEquals("https://cdn.test/e1-720-hi.mp4", loadedUrls.last())

        // Episode 2 automatically selects 720p · Hindi!
        holder.playEpisode("e2")
        runCurrent()
        assertEquals(2, holder.state.value.sourceIndex)
        assertEquals("https://cdn.test/e2-720-hi.mp4", loadedUrls.last())
    }

    @Test
    fun dubExtractionAndMatching_fallsBackToClosestHeightForSameDub() {
        assertEquals("Hindi", dubOfSource(Source("https://x/1.mp4", SourceKind.MP4, "1080p · Hindi", height = 1080)))
        assertEquals("Bengali", dubOfSource(Source("https://x/2.mp4", SourceKind.MP4, "720p · MP4 · Bengali · 1.2 GB", height = 720)))
        assertEquals("Tamil", dubOfSource(Source("https://x/3.mp4", SourceKind.MP4, "YouTube · [Tamil]")))
        assertNull(dubOfSource(Source("https://x/4.mp4", SourceKind.MP4, "1080p · MP4 · 2.1 GB", height = 1080)))

        val epSources = listOf(
            Source("https://x/en-1080.mp4", SourceKind.MP4, "1080p · English", height = 1080),
            Source("https://x/hi-1080.mp4", SourceKind.MP4, "1080p · Hindi", height = 1080),
            Source("https://x/hi-480.mp4", SourceKind.MP4, "480p · Hindi", height = 480),
        )
        // Target is Hindi 720p; ep only has Hindi 1080p and Hindi 480p -> picks closest height (480p is 240 away vs 1080p 360 away).
        val idx = preferredSourceIndex(
            sources = epSources,
            titlePref = PlaybackPref(dub = "Hindi", height = 720),
            globalPref = null,
        )
        assertEquals(2, idx)
    }

    @Test
    fun youtubeAndroidBridge_dispatchesCallbacksThroughMainExecutor() {
        var readyDur = 0.0
        var lastPos = 0.0
        var lastState = -99
        var lastErr = 0
        val bridge = YouTubeAndroidBridge(
            postToMain = { it() },
            onReadyCallback = { readyDur = it },
            onProgressCallback = { cur, _, st ->
                lastPos = cur
                lastState = st
            },
            onStateChangeCallback = { lastState = it },
            onErrorCallback = { lastErr = it },
        )
        bridge.onReady(95.0)
        assertEquals(95.0, readyDur, 1e-4)
        bridge.onProgress(14.5, 95.0, 1)
        assertEquals(14.5, lastPos, 1e-4)
        assertEquals(1, lastState)
        bridge.onStateChange(0)
        assertEquals(0, lastState)
        bridge.onError(150)
        assertEquals(150, lastErr)
    }
}
