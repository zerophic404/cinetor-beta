package com.mythbrix.cinetor.core.player

import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Season
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.Sub
import com.mythbrix.cinetor.core.data.SubFormat
import com.mythbrix.cinetor.core.perf.PerformanceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceResolverTest {
    @Test
    fun resolver_m3u8_isHls() {
        // Kind says MP4 (mislabelled) but explicit stream kinds win; extension decides for progressive kinds.
        assertEquals(StreamType.HLS, SourceResolver.streamTypeOf(Source("https://x.test/a/master.m3u8?token=1", SourceKind.MP4)))
        assertEquals(StreamType.HLS, SourceResolver.streamTypeOf(Source("https://x.test/a/manifest", SourceKind.HLS)))
        assertEquals("application/x-mpegURL", SourceResolver.plan(Source("https://x.test/v.m3u8", SourceKind.HLS), emptyList()).type.mimeType)
    }

    @Test
    fun resolver_mpd_isDash() {
        assertEquals(StreamType.DASH, SourceResolver.streamTypeOf(Source("https://x.test/dash.mpd", SourceKind.MP4)))
        assertEquals(StreamType.DASH, SourceResolver.streamTypeOf(Source("https://x.test/whatever", SourceKind.DASH)))
        assertEquals("application/dash+xml", StreamType.DASH.mimeType)
    }

    @Test
    fun resolver_mkv_isProgressive() {
        assertEquals(StreamType.PROGRESSIVE, SourceResolver.streamTypeOf(Source("https://x.test/movie.mkv", SourceKind.MKV)))
        assertEquals(StreamType.PROGRESSIVE, SourceResolver.streamTypeOf(Source("https://x.test/movie.webm", SourceKind.WEBM)))
        assertEquals(StreamType.PROGRESSIVE, SourceResolver.streamTypeOf(Source("https://x.test/song.mp3", SourceKind.MP3)))
        assertNull(StreamType.PROGRESSIVE.mimeType)
        assertEquals(StreamType.SS, SourceResolver.streamTypeOfUrl("https://x.test/a.ism/Manifest"))
        assertEquals(StreamType.RTSP, SourceResolver.streamTypeOfUrl("rtsp://cam.test/live"))
    }

    @Test
    fun resolver_headers_applied() {
        val src = Source(
            "https://x.test/v.m3u8", SourceKind.HLS,
            headers = mapOf("Referer" to "https://ref.test/", "user-agent" to "CustomUA/2"),
        )
        val plan = SourceResolver.plan(src, listOf(Sub("en", "English", "subs/sample-en.vtt", SubFormat.VTT)))
        assertEquals("https://ref.test/", plan.headers["Referer"])
        // Custom UA replaces the default one (case-insensitive), no duplicate header.
        assertEquals("CustomUA/2", plan.headers["user-agent"])
        assertEquals(2, plan.headers.size)
        // Default UA when the source sets none.
        assertEquals(SourceResolver.USER_AGENT, SourceResolver.plan(Source("https://x.test/a.mp4", SourceKind.MP4), emptyList()).headers["User-Agent"])
        // Side-loaded subs: bundled path → asset URI + MIME.
        assertEquals("asset:///mock/subs/sample-en.vtt", plan.subtitles.single().url)
        assertEquals("text/vtt", plan.subtitles.single().mimeType)
    }

    @Test
    fun request_seriesPicksEpisode_andNextEpisode() {
        val ep = { id: String, n: Int -> Episode(id, n, "E$n", durationSec = 60, still = "s", sources = listOf(Source("https://x.test/$id.mp4", SourceKind.MP4))) }
        val item = Item(
            id = "s1", space = Space.HOME, categoryId = "c", type = ItemType.SERIES, title = "Show", year = 2020,
            rating = 7.0, maturity = "PG", genres = emptyList(), synopsis = "", poster = "p", backdrop = "b",
            dominantColor = "#000000", accentColor = "#000000",
            seasons = listOf(Season(1, episodes = listOf(ep("e1", 1), ep("e2", 2))), Season(2, episodes = listOf(ep("e3", 1)))),
        )
        assertEquals("s1#e1", SourceResolver.request(item, null)?.key)
        assertEquals("s1#e3", SourceResolver.request(item, "e3")?.key)
        assertEquals("S2:E1 · E1", SourceResolver.request(item, "e3")?.subtitle)
        assertEquals("e3", SourceResolver.nextEpisode(item, "e2")?.id)
        assertNull(SourceResolver.nextEpisode(item, "e3"))
    }

    @Test
    fun loadControl_tierAware() {
        val low = LoadControlConfig.forTier(PerformanceTier.LOW)
        val mid = LoadControlConfig.forTier(PerformanceTier.MID)
        assertEquals(15_000, low.minBufferMs); assertEquals(30_000, low.maxBufferMs)
        assertEquals(30_000, mid.minBufferMs); assertEquals(50_000, mid.maxBufferMs)
        assertEquals(500, mid.bufferForPlaybackMs); assertEquals(1_500, mid.bufferForPlaybackAfterRebufferMs)
        assertEquals(80L shl 20, cacheBytesFor(PerformanceTier.LOW))
        assertEquals(200L shl 20, cacheBytesFor(PerformanceTier.HIGH))
    }

    @Test
    fun tracks_qualitiesDedupedAndSorted() {
        val raw = listOf(
            RawTrack(TrackKind.VIDEO, TrackRef(0, 0), 640, 360, 800_000),
            RawTrack(TrackKind.VIDEO, TrackRef(0, 1), 1280, 720, 2_000_000),
            RawTrack(TrackKind.VIDEO, TrackRef(0, 2), 1280, 720, 2_500_000),
            RawTrack(TrackKind.VIDEO, TrackRef(0, 3), 1920, 1080, 5_000_000, supported = false),
            RawTrack(TrackKind.AUDIO, TrackRef(1, 0), language = "en", label = "English", selected = true),
            RawTrack(TrackKind.AUDIO, TrackRef(2, 0), language = "fr"),
        )
        val t = buildTracks(raw, forcedHeight = null, playingHeight = 720)
        assertEquals(listOf(720, 360), t.video.map { it.height })
        assertEquals(TrackRef(0, 2), t.video.first().ref)
        assertEquals(listOf("English", "FR"), t.audio.map { it.label })
        assertEquals(12, PLAYBACK_SPEEDS.size)
        assertEquals(0.25f, PLAYBACK_SPEEDS.first(), 0.001f); assertEquals(3.0f, PLAYBACK_SPEEDS.last(), 0.001f)
    }
}
