package com.mythbrix.cinetor.feature.music

import com.mythbrix.cinetor.TestFiles
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.gateway.GatewayCache
import com.mythbrix.cinetor.core.data.gateway.GatewayDiskCache
import com.mythbrix.cinetor.core.data.gateway.GatewayHttp
import com.mythbrix.cinetor.core.data.gateway.GatewayHttpException
import com.mythbrix.cinetor.core.legal.UserErrors
import com.mythbrix.cinetor.core.player.PlaybackPhase
import com.mythbrix.cinetor.feature.player.PlayerStateHolderTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URLEncoder

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayMusicTest {

    private class MemoryDiskCache : GatewayDiskCache {
        val store = HashMap<String, Pair<Long, String>>()
        override fun read(key: String): Pair<Long, String>? = store[key]
        override fun write(key: String, savedAt: Long, body: String) {
            store[key] = savedAt to body
        }
    }

    private fun testUrlFor(path: String, params: Map<String, String?>): String {
        val q = params.entries.filter { !it.value.isNullOrBlank() }
            .joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v!!, "UTF-8") }
        return "https://gw.test/api/public/v1/" + path.trimStart('/') + (if (q.isEmpty()) "" else "?$q")
    }

    @Test
    fun lrcParser_parsesCentisecondsMillisecondsMultiTagsAndSorts() {
        val raw = """
            [ti:Midnight City]
            [ar:M83]
            [01:05.089] Waiting in a car
            [00:12.34] Waiting for a ride in the dark
            [00:20.00][01:20.50] The night city grows
            [00:30.00]    
            [02:00:25] Look at the horizon
            [02:30] Fade out
        """.trimIndent()

        val lines = LrcParser.parse(raw)
        assertEquals(
            listOf(
                LyricLine(12_340L, "Waiting for a ride in the dark"),
                LyricLine(20_000L, "The night city grows"),
                LyricLine(65_089L, "Waiting in a car"),
                LyricLine(80_500L, "The night city grows"),
                LyricLine(120_250L, "Look at the horizon"),
                LyricLine(150_000L, "Fade out"),
            ),
            lines,
        )
    }

    @Test
    fun lrcParser_handlesBlankNullAndActiveLineLookup() {
        assertTrue(LrcParser.parse(null).isEmpty())
        assertTrue(LrcParser.parse("   \n  ").isEmpty())
        assertEquals(-1, LrcParser.activeLineIndex(emptyList(), 5_000L))

        val lines = listOf(
            LyricLine(5_000L, "Line one"),
            LyricLine(12_500L, "Line two"),
            LyricLine(25_000L, "Line three"),
        )
        assertEquals(-1, LrcParser.activeLineIndex(lines, 0L))
        assertEquals(-1, LrcParser.activeLineIndex(lines, 4_999L))
        assertEquals(0, LrcParser.activeLineIndex(lines, 5_000L))
        assertEquals(0, LrcParser.activeLineIndex(lines, 12_499L))
        assertEquals(1, LrcParser.activeLineIndex(lines, 12_500L))
        assertEquals(2, LrcParser.activeLineIndex(lines, 25_000L))
        assertEquals(2, LrcParser.activeLineIndex(lines, 99_000L))
    }

    @Test
    fun lyricsPayload_parsesPlainAndSyncedOrReturnsNullWhenEmpty() {
        assertNull(GatewayMusicMapper.parseLyrics("""{"plainLyrics": "", "syncedLyrics": null}"""))
        val plainOnly = GatewayMusicMapper.parseLyrics("""{"plainLyrics": "Hello darkness my old friend"}""")!!
        assertEquals("Hello darkness my old friend", plainOnly.plainLyrics)
        assertTrue(plainOnly.lines.isEmpty())

        val synced = GatewayMusicMapper.parseLyrics(
            """{"plainLyrics": "Verse 1", "syncedLyrics": "[00:04.20] Verse 1\n[00:08.05] Verse 2"}""",
        )!!
        assertEquals(2, synced.lines.size)
        assertEquals(4_200L, synced.lines[0].timeMs)
        assertEquals("Verse 2", synced.lines[1].text)
    }

    @Test
    fun trendingJson_parsesChartsTracksDynamicCategoriesAndSkipsMalformedRows() {
        val json = """
            {
              "categories": ["Trending", "Bengali", "Lo-Fi & Chill"],
              "charts": [
                {"id": "ch-top50", "title": "Global Top 50", "artwork": "https://img.test/c1.jpg", "count": 50},
                {"title": "Missing ID"}
              ],
              "trendingTracks": [
                {
                  "id": "tr-99",
                  "title": "Starboy",
                  "artist": "The Weeknd",
                  "album": "Starboy",
                  "albumId": "al-99",
                  "year": "2016",
                  "duration": 230,
                  "durationLabel": "3:50",
                  "artwork": "https://img.test/s1.jpg",
                  "quality": "FLAC Lossless",
                  "format": "flac",
                  "hasFlac": true
                },
                {"id": "tr-incomplete"}
              ]
            }
        """.trimIndent()

        val feed = GatewayMusicMapper.parseTrending(json)
        assertEquals(listOf("Trending", "Bengali", "Lo-Fi & Chill"), feed.categories)
        assertEquals(1, feed.charts.size)
        assertEquals("ch-top50", feed.charts[0].id)
        assertEquals(50, feed.charts[0].count)

        assertEquals(1, feed.tracks.size)
        val item = GatewayMusicMapper.trackToItem(feed.tracks[0])
        assertEquals("mu-tr:tr-99", item.id)
        assertEquals(Space.MUSIC, item.space)
        assertEquals(ItemType.TRACK, item.type)
        assertEquals("Starboy", item.title)
        assertEquals("The Weeknd", item.artist)
        assertEquals("mu-al:al-99", item.albumId)
        assertEquals(2016, item.year)
        assertEquals(230, item.durationSec)
        assertEquals("FLAC Lossless", item.studio)
        assertEquals("FLAC", item.maturity)
    }

    @Test
    fun searchAlbumAndPlaylistJson_parsesAllThreeScopesAndDetails() {
        val songJson = """{"results": [{"id": "s1", "title": "Kesariya", "artist": "Arijit Singh", "duration": 268, "hasFlac": false, "quality": "320kbps Studio Master"}]}"""
        val (songs, songMore) = GatewayMusicMapper.parseSearchSongs(songJson)
        assertEquals(1, songs.size)
        assertFalse(songMore)
        assertFalse(songs[0].hasFlac)

        val albumSearchJson = """{"has_more": true, "results": [{"id": "a1", "title": "After Hours", "artist": "The Weeknd", "year": "2020", "artwork": "https://img.test/ah.jpg", "count": 14}]}"""
        val (albums, albumMore) = GatewayMusicMapper.parseSearchAlbums(albumSearchJson)
        assertEquals(1, albums.size)
        assertTrue(albumMore)
        val albumItem = GatewayMusicMapper.albumToItem(albums[0])
        assertEquals("mu-al:a1", albumItem.id)
        assertEquals(ItemType.ALBUM, albumItem.type)
        assertEquals(2020, albumItem.year)

        val playlistSearchJson = """{"results": [{"id": "p1", "title": "Late Night Lo-Fi", "artwork": "https://img.test/p1.jpg", "count": 32}]}"""
        val (playlists, _) = GatewayMusicMapper.parseSearchPlaylists(playlistSearchJson)
        assertEquals(1, playlists.size)
        val plItem = GatewayMusicMapper.chartToItem(playlists[0])
        assertEquals("mu-al-pl:p1", plItem.id)
        assertTrue(plItem.id.startsWith("mu-al"))

        val albumDetailJson = """
            {
              "id": "a1",
              "title": "After Hours",
              "artist": "The Weeknd",
              "year": "2020",
              "artwork": "https://img.test/ah.jpg",
              "tracks": [
                {"id": "t1", "title": "Alone Again", "duration": 250},
                {"id": "t2", "title": "Blinding Lights", "duration": 200}
              ]
            }
        """.trimIndent()
        val albumDetail = GatewayMusicMapper.parseAlbum(albumDetailJson, "a1")!!
        assertEquals(2, albumDetail.tracks.size)
        assertEquals("The Weeknd", albumDetail.tracks[0].artist)

        val playlistDetailJson = """
            {
              "id": "p1",
              "title": "Late Night Lo-Fi",
              "artwork": "https://img.test/p1.jpg",
              "count": 2,
              "tracks": [
                {"id": "pt1", "title": "Rainy Window", "artist": "Kupla", "duration": 142}
              ]
            }
        """.trimIndent()
        val plDetail = GatewayMusicMapper.parsePlaylist(playlistDetailJson, "p1")!!
        assertEquals("Late Night Lo-Fi", plDetail.title)
        assertEquals(1, plDetail.tracks.size)
    }

    @Test
    fun streamEndpoint_supportsFlacAnd320WithFallbackAndRelativeUrlNormalization() = runTest {
        val requestedUrls = ArrayList<String>()
        val api = GatewayMusicApi(
            http = GatewayHttp { url, _ ->
                requestedUrls += url
                if (url.contains("quality=flac")) {
                    throw GatewayHttpException(404, "No FLAC")
                }
                """
                {
                  "streamUrl": "/api/public/v1/music/proxy?id=tr-1",
                  "quality": "320kbps Studio Master",
                  "format": "mp3",
                  "headers": {"Referer": "https://music.example.org/"}
                }
                """.trimIndent()
            },
            cache = GatewayCache(disk = MemoryDiskCache()),
            urlFor = ::testUrlFor,
            absoluteUrl = { p -> if (p.startsWith("http")) p else "https://gw.test/" + p.trimStart('/') },
        )

        val stream = api.stream("tr-1", MusicAudioQuality.FLAC)
        assertNotNull(stream)
        assertEquals(2, requestedUrls.size)
        assertTrue(requestedUrls[0].contains("quality=flac"))
        assertTrue(requestedUrls[1].contains("quality=320"))
        assertEquals("https://gw.test/api/public/v1/music/proxy?id=tr-1", stream!!.streamUrl)
        assertEquals(stream.streamUrl, stream.downloadUrl)
        assertEquals("320kbps Studio Master", stream.quality)
        assertEquals("https://music.example.org/", stream.headers["Referer"])

        val src = GatewayMusicMapper.sourceOfStream(stream, MusicAudioQuality.KBPS_320)
        assertEquals(SourceKind.MP3, src.kind)
        assertEquals("320kbps Studio Master", src.label)
    }

    @Test
    fun gatewayCache_usesStaleCacheOfflineAndThrowsOfflineNoCacheWhenUncached() = runTest {
        var clock = 1_000L
        var online = true
        val disk = MemoryDiskCache()
        val api = GatewayMusicApi(
            http = GatewayHttp { _, _ ->
                if (!online) throw IOException("Network unreachable")
                """{"charts":[{"id":"c1","title":"Top 10","count":10}],"trendingTracks":[{"id":"t1","title":"Song A","artist":"Artist A"}],"categories":["Trending"]}"""
            },
            cache = GatewayCache(ttlMs = 60_000L, clock = { clock }, disk = disk),
            urlFor = ::testUrlFor,
        )

        val first = api.trending()
        assertEquals("c1", first.charts.single().id)

        // Expire TTL and go offline -> stale cache serves the real gateway response.
        clock += 120_000L
        online = false
        val stale = api.trending()
        assertEquals("c1", stale.charts.single().id)

        // Fresh uncached instance while completely offline -> OfflineNoCacheException.
        val uncachedOffline = GatewayMusicApi(
            http = GatewayHttp { _, _ -> throw IOException("Airplane mode") },
            cache = GatewayCache(disk = MemoryDiskCache()),
            urlFor = ::testUrlFor,
        )
        var threwOfflineNoCache = false
        try {
            uncachedOffline.trending()
        } catch (_: OfflineNoCacheException) {
            threwOfflineNoCache = true
        }
        assertTrue(threwOfflineNoCache)
    }

    @Test
    fun repository_liveGatewayOnlyWhenOnline_cachedWhenOffline_mockFallbackOnlyWhenUncachedOffline() = runTest {
        val mockCatalog = TestFiles.catalog()
        val disk = MemoryDiskCache()
        var clock = 1_000L
        var mode = "online"

        fun buildApi() = GatewayMusicApi(
            http = GatewayHttp { _, _ ->
                when (mode) {
                    "online" -> """
                        {
                          "categories": ["Trending", "Bollywood"],
                          "charts": [{"id": "c-live", "title": "Live Top 50", "count": 50, "artwork": "https://img.test/c.jpg"}],
                          "trendingTracks": [{"id": "t-live", "title": "Live Song", "artist": "Live Artist", "duration": 210}]
                        }
                    """.trimIndent()
                    "http500" -> throw GatewayHttpException(502, "Bad Gateway https://secret.vercel.app/api/public/v1/music/trending")
                    else -> throw IOException("Unable to resolve host secret.vercel.app")
                }
            },
            cache = GatewayCache(ttlMs = 10_000L, clock = { clock }, disk = disk),
            urlFor = ::testUrlFor,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        // 1. Online -> live gateway items ONLY (zero mock items mixed in).
        val onlineRepo = MusicRepository(mockCatalog, api = buildApi())
        val liveData = onlineRepo.load()
        assertFalse(liveData.isOfflineFallback)
        assertEquals(listOf("mu-al-pl:c-live"), liveData.charts.map { it.id })
        assertEquals(listOf("mu-tr:t-live"), liveData.tracks.map { it.id })
        assertEquals(listOf("Trending", "Bollywood"), liveData.categories)

        // 2. Offline WITH cached gateway response -> uses stale gateway cache, NOT mock data.
        clock += 60_000L
        mode = "offline"
        val cachedOfflineRepo = MusicRepository(mockCatalog, api = buildApi())
        val cachedData = cachedOfflineRepo.load()
        assertFalse(cachedData.isOfflineFallback)
        assertEquals(listOf("mu-tr:t-live"), cachedData.tracks.map { it.id })

        // 3. Completely offline with NO cached response -> graceful fallback to bundled mock catalog.
        disk.store.clear()
        val pureOfflineRepo = MusicRepository(mockCatalog, api = buildApi())
        val fallbackData = pureOfflineRepo.load()
        assertTrue(fallbackData.isOfflineFallback)
        assertEquals(10, fallbackData.albums.size)
        assertEquals(50, fallbackData.tracks.size)

        // 4. Online HTTP 5xx with NO cache -> locator-free error, never fake mock fallback.
        mode = "http500"
        val errorRepo = MusicRepository(mockCatalog, api = buildApi())
        val res = runCatching { errorRepo.load() }
        assertTrue(res.isFailure)
        val shownError = errorRepo.error.value
        assertNotNull(shownError)
        assertFalse(UserErrors.containsLocator(shownError))
    }

    @Test
    fun repository_searchAndCollectionLoad_handlesSongsAlbumsAndPlaylists() = runTest {
        val api = GatewayMusicApi(
            http = GatewayHttp { url, _ ->
                when {
                    url.contains("type=song") -> """{"has_more": true, "results": [{"id": "s10", "title": "Tum Hi Ho", "artist": "Arijit Singh", "duration": 262}]}"""
                    url.contains("type=album") -> """{"results": [{"id": "a10", "title": "Aashiqui 2", "artist": "Mithoon", "year": "2013", "count": 11}]}"""
                    url.contains("type=playlist") -> """{"results": [{"id": "p10", "title": "Bollywood Acoustics", "count": 25}]}"""
                    url.contains("music/playlist") -> """{"id": "p10", "title": "Bollywood Acoustics", "count": 1, "tracks": [{"id": "s10", "title": "Tum Hi Ho", "artist": "Arijit Singh", "duration": 262}]}"""
                    url.contains("music/album") -> """{"id": "a10", "title": "Aashiqui 2", "artist": "Mithoon", "year": "2013", "tracks": [{"id": "s10", "title": "Tum Hi Ho", "duration": 262}]}"""
                    else -> """{"charts":[],"trendingTracks":[],"categories":[]}"""
                }
            },
            cache = GatewayCache(disk = MemoryDiskCache()),
            urlFor = ::testUrlFor,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val repo = MusicRepository(TestFiles.catalog(), api = api)

        val songsPage = repo.search("tum", MusicSearchType.SONG, page = 1)
        assertTrue(songsPage.hasMore)
        assertEquals("mu-tr:s10", songsPage.items.single().id)

        val albumsPage = repo.search("aashiqui", MusicSearchType.ALBUM, page = 1)
        assertEquals("mu-al:a10", albumsPage.items.single().id)

        val playlistsPage = repo.search("acoustics", MusicSearchType.PLAYLIST, page = 1)
        assertEquals("mu-al-pl:p10", playlistsPage.items.single().id)

        val plCollection = repo.loadCollection("mu-al-pl:p10")!!
        assertTrue(plCollection.isPlaylist)
        assertEquals("Bollywood Acoustics", plCollection.header.title)
        assertEquals("mu-tr:s10", plCollection.tracks.single().id)

        val alCollection = repo.loadCollection("mu-al:a10")!!
        assertFalse(alCollection.isPlaylist)
        assertEquals("Aashiqui 2", alCollection.header.title)
        assertEquals("Mithoon", alCollection.tracks.single().artist)
    }

    @Test
    fun controller_resolvesGatewayStreamLoadsLyricsTogglesQualityAndScrubsErrors() = runTest {
        val base = PlayerStateHolderTest.Env(this)
        val gwTrack = GatewayMusicMapper.trackToItem(
            GwMusicTrack(id = "tr-77", title = "Levitating", artist = "Dua Lipa", duration = 203, hasFlac = true),
        )
        var failResolve = false
        val requestedQualities = ArrayList<MusicAudioQuality>()

        val controller = MusicController(
            scope = backgroundScope,
            preloader = base.pre,
            prepareTrack = { item, quality ->
                requestedQualities += quality
                if (failResolve) throw IOException("GET https://gateway.example.org/api/public/v1/music/stream?id=tr-77 failed")
                val label = if (quality == MusicAudioQuality.FLAC) "FLAC Lossless" else "320kbps Studio Master"
                item.copy(
                    studio = label,
                    sources = listOf(Source(url = "https://audio.example/stream-${quality.queryValue}.mp4", kind = SourceKind.MP4, label = label)),
                )
            },
            fetchLyrics = { _ ->
                MusicLyrics(
                    plainLyrics = "If you wanna run away with me",
                    syncedLyrics = "[00:05.00] If you wanna run away with me\n[00:10.50] I know a galaxy",
                    lines = listOf(
                        LyricLine(5_000L, "If you wanna run away with me"),
                        LyricLine(10_500L, "I know a galaxy"),
                    ),
                )
            },
        )

        controller.playQueue(listOf(gwTrack), 0, expand = true)
        runCurrent()
        base.fake.setPhase(PlaybackPhase.READY)
        base.fake.seekTo(42_000L)
        runCurrent()

        assertEquals("mu-tr:tr-77", base.fake.currentKey)
        assertEquals("FLAC Lossless", controller.state.value.activeQualityLabel)
        assertEquals(2, controller.state.value.lyrics?.lines?.size)

        // Toggle quality to 320kbps -> reloads with 320kbps stream and restores playback position!
        controller.setQuality(MusicAudioQuality.KBPS_320)
        runCurrent()
        assertEquals(MusicAudioQuality.KBPS_320, controller.state.value.preferredQuality)
        assertEquals("320kbps Studio Master", controller.state.value.activeQualityLabel)
        assertEquals(listOf(MusicAudioQuality.FLAC, MusicAudioQuality.KBPS_320), requestedQualities)
        assertEquals(2, base.fake.loads.size)
        assertEquals("https://audio.example/stream-320.mp4", controller.state.value.current?.sources?.single()?.url)
        assertEquals(42_000L, base.fake.state.value.positionMs)

        // Failure with URL in exception message -> scrubbed user-safe error in MusicState.
        failResolve = true
        val brokenTrack = GatewayMusicMapper.trackToItem(GwMusicTrack(id = "tr-dead", title = "Broken", artist = "Artist"))
        controller.playQueue(listOf(brokenTrack), 0)
        runCurrent()
        val err = controller.state.value.error
        assertNotNull(err)
        assertFalse("error leaked URL: $err", UserErrors.containsLocator(err))
    }

    @Test
    fun artTint_extractsVividDominantHueAndRejectsMuddyGray() {
        // Grayscale pixels -> null so theme red is kept instead of muddy gray.
        val grayPixels = IntArray(64) { 0xFF444444.toInt() }
        assertNull(MusicArtTint.extractFromArgb(grayPixels))

        // Vivid ruby artwork -> produces dark ground + bright high-contrast glow.
        val rubyPixels = IntArray(64) { 0xFFE11D2E.toInt() }
        val tint = MusicArtTint.extractFromArgb(rubyPixels)
        assertNotNull(tint)
        assertTrue(tint!!.groundHex.startsWith("#") && tint.groundHex.length == 7)
        assertTrue(tint.glowHex.startsWith("#") && tint.glowHex.length == 7)
    }
}

