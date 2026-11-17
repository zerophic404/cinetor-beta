package com.mythbrix.cinetor.core.data.gateway

import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.player.GatewaySourceResolver
import com.mythbrix.cinetor.core.player.LinkHealth
import com.mythbrix.cinetor.core.player.NetworkState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GatewayBetaParityTest {

    @Before
    fun setUp() {
        LinkHealth.reset()
        NetworkState.mobile = false
    }

    @After
    fun tearDown() {
        LinkHealth.reset()
        NetworkState.mobile = false
    }

    private fun env(
        extraRoutes: Map<String, String> = emptyMap(),
        adult: Boolean = false,
    ): Triple<FakeGatewayHttp, GatewayApi, GatewayCatalogSource> {
        val http = FakeGatewayHttp(GatewayFixtures.fullHomeRoutes() + extraRoutes)
        val api = GatewayApi(http, GatewayCache(clock = { 0L }))
        val gw = GatewayCatalogSource(api, adultAllowed = { adult })
        return Triple(http, api, gw)
    }

    @Test
    fun home_top10FirstRow_filtersAnimeAndAdult_includesAllBetaShelves_andPagesEndlessly() = runTest {
        val customHome = """{"status":"success","sections":[
          {"section":"Banner","items":[
            {"name":"Hero Action","slug":"hero-1","subject_id":"10","poster_url":"https://img/10.jpg","genre":"Action,Thriller","type":"movie","has_resource":true},
            {"name":"Anime Leak","slug":"anime-leak","subject_id":"11","poster_url":"https://img/11.jpg","genre":"Anime,Action","type":"tv","has_resource":true},
            {"name":"Adult Leak","slug":"adult-leak","subject_id":"12","poster_url":"https://img/12.jpg","genre":"Adult,Erotic","type":"movie","has_resource":true}
          ]},
          {"section":"Trending Short Drama","items":[
            {"name":"Rank One","slug":"rank-1","subject_id":"20","poster_url":"https://img/20.jpg","genre":"Drama","type":"tv","has_resource":true},
            {"name":"Missing Stream","slug":"gone-1","subject_id":"21","poster_url":"https://img/21.jpg","genre":"Drama","type":"tv","has_resource":false},
            {"name":"Cyberpunk Anime","slug":"cyber-1","subject_id":"22","poster_url":"https://img/22.jpg","genre":"Anime,Sci-Fi","type":"tv","has_resource":true},
            {"name":"Badge 18+","slug":"b18","subject_id":"23","poster_url":"https://img/23.jpg","badge":"18+","genre":"Drama","type":"movie","has_resource":true}
          ]},
          {"section":"Anime[English Dubbed]","items":[
            {"name":"Dubbed Anime","slug":"da-1","subject_id":"30","poster_url":"https://img/30.jpg","genre":"Anime","type":"tv","has_resource":true}
          ]},
          {"section":"Popular Movie","items":[
            {"name":"Street Fighter","slug":"sf-1","subject_id":"40","poster_url":"https://img/40.jpg","genre":"Action","type":"movie","has_resource":true}
          ]}
        ]}"""
        val (http, _, gw) = env(extraRoutes = mapOf("home" to customHome))
        val cats = gw.categories(Space.HOME)

        assertTrue(cats.map { it.title }.containsAll(listOf("For You", "Movies", "Series", "Short TV", "Zee Bangla")))
        val featured = cats.first { it.id == "gw:home-featured" }
        assertEquals(listOf("gw:10"), featured.heroIds)

        assertFalse(featured.rows.any { it.title.equals("Banner", ignoreCase = true) })
        assertFalse(featured.rows.any { it.title.contains("Anime", ignoreCase = true) })

        // Index 1 of /home (first curated row after Banner) is styled TOP10; poster shelves use POSTER.
        assertEquals("Trending Short Drama", featured.rows[0].title)
        assertEquals(RowStyle.TOP10, featured.rows[0].style)
        val topRowItems = gw.row(featured.rows[0].id)
        assertEquals(listOf("Rank One"), topRowItems.map { it.title })

        val titles = featured.rows.map { it.title }
        val expectedExtra = listOf(
            "Hot Short TV",
            "Zee Bangla Serials",
            "Zee Bangla Shows",
            "Star Jalsha Shows",
            "Sony AATH Series",
            "Indian Dramas",
            "Latest Movies",
            "Latest Series",
            "Hollywood",
            "Action",
            "Comedy",
            "Horror",
            "Thriller",
            "Crime Series",
        )
        expectedExtra.forEach { shelf ->
            assertTrue("Missing Home shelf '$shelf' in $titles", titles.any { it.contains(shelf, ignoreCase = true) })
        }

        // All row IDs in For You are strictly unique (prevents Compose LazyColumn duplicate key crashes).
        assertEquals(featured.rows.size, featured.rows.map { it.id }.toSet().size)

        // Curated /home row supports endless pagination on page >= 2 via sectionFallback.
        val page1 = gw.rowPage(featured.rows[0].id, 1)
        assertTrue(page1.hasMore)
        val page2 = gw.rowPage(featured.rows[0].id, 2)
        assertEquals(2, page2.page)
        assertTrue(http.urls.last().contains("short-tv?"))

        // Zee Bangla 59-show integration hits /zee-bangla endpoint.
        val zeeRow = featured.rows.first { it.id.contains(":zee-bangla?") }
        val zeeItems = gw.rowPage(zeeRow.id, 1)
        assertTrue(zeeItems.items.isNotEmpty())
        assertTrue(http.urls.last().contains("zee-bangla?"))
    }

    @Test
    fun drama_exposesAllSevenChips_andAllTwentyBetaShelves() = runTest {
        val (_, _, gw) = env()
        val cats = gw.categories(Space.DRAMA)
        assertEquals(
            listOf("All", "K-Drama", "C-Drama", "J-Drama", "Pakistani Drama", "Indian Drama", "Others"),
            cats.map { it.title },
        )

        val allDramaRows = cats.first().rows.map { it.title }
        val expectedShelves = listOf(
            "Trending K-Drama",
            "Latest K-Drama",
            "Top Rated K-Drama",
            "Korean Romance",
            "Korean Movies",
            "Trending C-Drama",
            "Latest C-Drama",
            "Top Rated C-Drama",
            "Costume & Historical",
            "Chinese Romance",
            "Chinese Movies",
            "Trending J-Drama",
            "Latest J-Drama",
            "Japanese Movies",
            "Trending Pakistani Drama",
            "All-Time Best Pakistani Drama",
            "Indian Dramas",
            "Thai Dramas",
            "Taiwanese Dramas",
            "Other Asian Dramas",
        )
        assertEquals(expectedShelves, allDramaRows)
        assertTrue(cats.first().rows.all { it.style == RowStyle.POSTER })
        assertEquals(cats.first().rows.size, cats.first().rows.map { it.id }.toSet().size)
    }

    @Test
    fun anime_exposesFiveBetaShelves_includingSeparateCartoons_andGenreChips() = runTest {
        val (http, _, gw) = env()
        val cats = gw.categories(Space.ANIME)
        val animeRows = cats.first().rows
        assertEquals(
            listOf("Trending Anime", "Latest Anime", "Top Rated Anime", "Animated Movies", "Cartoons"),
            animeRows.take(5).map { it.title },
        )
        val cartoons = animeRows.first { it.title == "Cartoons" }
        gw.row(cartoons.id)
        assertTrue(http.urls.last().contains("animation?"))
        val chips = gw.genres(Space.ANIME)
        assertTrue(chips.containsAll(listOf("Action", "Adventure", "Fantasy", "Comedy", "Romance")))
    }

    @Test
    fun adult_enforcesSafeNameBlocklist_adGwPrefix_sortChips_andGate() = runTest {
        val unsafeAndSafeAdult = """{"page":1,"per_page":48,"has_more":true,"items":[
          {"name":"Midnight Desire","slug":"midnight-desire-1","subject_id":"901","poster_url":"https://img/901.jpg","genre":"Adult,Erotic","type":"movie","has_resource":true},
          {"name":"School Girl Diary","slug":"bad-1","subject_id":"902","poster_url":"https://img/902.jpg","genre":"Adult,Erotic","type":"movie","has_resource":true},
          {"name":"Step Sister Secret","slug":"bad-2","subject_id":"903","poster_url":"https://img/903.jpg","genre":"Adult,Erotic","type":"movie","has_resource":true},
          {"name":"Teen Party","slug":"bad-3","subject_id":"904","poster_url":"https://img/904.jpg","genre":"Adult,Erotic","type":"movie","has_resource":true},
          {"name":"Hidden Cam Leak","slug":"bad-4","subject_id":"905","poster_url":"https://img/905.jpg","genre":"Adult,Erotic","type":"movie","has_resource":true},
          {"name":"Hentai Fantasy","slug":"bad-5","subject_id":"906","poster_url":"https://img/906.jpg","genre":"Adult,Erotic","type":"movie","has_resource":true}
        ]}"""
        var allowed = false
        val http = FakeGatewayHttp(GatewayFixtures.routes() + mapOf("adult" to unsafeAndSafeAdult))
        val api = GatewayApi(http, GatewayCache(clock = { 0L }))
        val gw = GatewayCatalogSource(api, adultAllowed = { allowed })

        try {
            gw.categories(Space.ADULT)
            fail("Must reject Space.ADULT when adultAllowed() is false")
        } catch (_: IllegalArgumentException) {
        }

        allowed = true
        val cats = gw.categories(Space.ADULT)
        assertTrue(cats.map { it.title }.containsAll(listOf("For You", "Hottest", "Latest", "Rating")))
        val items = gw.row(cats.first().rows.first().id)
        assertEquals(listOf("Midnight Desire"), items.map { it.title })
        assertEquals("ad-gw:901", items.single().id)
        assertEquals("18+", items.single().maturity)

        assertFalse(GatewayMapper.safeName("College girl confession"))
        assertFalse(GatewayMapper.safeName("Forced encounter"))
        assertFalse(GatewayMapper.safeName("Underage story"))
        assertTrue(GatewayMapper.safeName("Labas pasok"))
    }

    @Test
    fun dubMetadata_preservedFromTitleBadgeAndDubsJson_withoutMutatingCleanTitles() {
        val fromBracket = GatewayMapper.toItem(
            GwItem(name = "Where is Home [Bengali]", subjectId = "1", slug = "wih", posterUrl = "https://img/1.jpg", genre = "Drama", type = "tv"),
            Space.HOME,
            "cat",
        )!!
        assertEquals("Where is Home [Bengali]", fromBracket.title)
        assertTrue(fromBracket.genres.any { it.equals("Bengali", ignoreCase = true) })

        val fromZeeBadge = GatewayMapper.toItem(
            GwItem(name = "Neem Phuler Modhu", subjectId = "2", slug = "npm-bengali", badge = "Zee Bangla", posterUrl = "https://img/2.jpg", genre = "Drama,Family", type = "tv"),
            Space.HOME,
            "cat",
        )!!
        assertEquals("Neem Phuler Modhu", fromZeeBadge.title)
        assertTrue(fromZeeBadge.genres.contains("Bengali"))

        val dubObjs = JsonArray(
            listOf(
                JsonObject(mapOf("subject_id" to JsonPrimitive("31"), "slug" to JsonPrimitive("s-hi"), "language_name" to JsonPrimitive("Hindi"))),
                JsonObject(mapOf("subject_id" to JsonPrimitive("32"), "slug" to JsonPrimitive("s-ta"), "language_name" to JsonPrimitive("Tamil Dub"))),
                JsonObject(mapOf("subject_id" to JsonPrimitive("33"), "slug" to JsonPrimitive("s-te"), "language_name" to JsonPrimitive("Telugu"))),
                JsonObject(mapOf("subject_id" to JsonPrimitive("34"), "slug" to JsonPrimitive("s-en"), "language_name" to JsonPrimitive("English"))),
            ),
        )
        val fromDubs = GatewayMapper.toItem(
            GwItem(name = "Pushpa", subjectId = "3", slug = "pushpa", posterUrl = "https://img/3.jpg", genre = "Action", type = "movie", dubs = dubObjs),
            Space.HOME,
            "cat",
        )!!
        assertEquals("Pushpa", fromDubs.title)
        assertTrue(fromDubs.genres.containsAll(listOf("Action", "Hindi", "Tamil", "Telugu", "English")))
        assertEquals("Hindi", GatewayMapper.extractDubBadge(fromDubs))
    }

    @Test
    fun streamRanking_wifiPrefersBdixAndDirectHighRes_mobilePrefersProxyAndWebOverBdix() {
        val raw = listOf(
            GwSource(
                source = "BDIX",
                isBdix = true,
                resolution = "1080p",
                format = "MP4",
                url = "http://bdix.ftp.local/movies/inception-1080p.mp4",
                size = 2_200_000_000L,
                codec = "h264",
            ),
            GwSource(
                source = "MovieBox",
                isBdix = false,
                resolution = "1080p",
                format = "MP4",
                url = "https://cdn.example.com/1080.mp4",
                size = 1_500_000_000L,
                codec = "h264",
                proxyUrl = "/api/public/v1/stream/99/play?url=https%3A%2F%2Fcdn.example.com%2F1080.mp4&res=1080p",
            ),
            GwSource(
                source = "MovieBox",
                isBdix = false,
                resolution = "720p",
                format = "MP4",
                url = "https://cdn.example.com/720.mp4",
                size = 700_000_000L,
                codec = "h264",
                proxyUrl = "/api/public/v1/stream/99/play?url=https%3A%2F%2Fcdn.example.com%2F720.mp4&res=720p",
            ),
            GwSource(
                source = "MovieBox",
                isBdix = false,
                resolution = "720p",
                format = "HLS",
                url = "https://cdn.example.com/720/master.m3u8",
                size = 650_000_000L,
                codec = "h264",
            ),
        )

        val wifi = GatewaySourceResolver.rankGwSources(
            raw = raw,
            baseUrl = "https://gw.example",
            directPlayUrl = "/api/public/v1/stream/99/play?detail_path=inc&se=0&ep=0",
            mobile = false,
        )
        // Unmetered Wi-Fi: BDIX 1080p leads, followed by direct 1080p CDN, with proxy variants after direct.
        assertEquals("http://bdix.ftp.local/movies/inception-1080p.mp4", wifi.first().url)
        assertEquals("https://cdn.example.com/1080.mp4", wifi[1].url)
        // Labels never leak provider names ("MovieBox", "BDIX").
        wifi.forEach { src ->
            val lbl = src.label.orEmpty()
            assertFalse("Provider leaked in label: $lbl", lbl.contains("MovieBox", ignoreCase = true))
        }

        val mobile = GatewaySourceResolver.rankGwSources(
            raw = raw,
            baseUrl = "https://gw.example",
            directPlayUrl = "/api/public/v1/stream/99/play?detail_path=inc&se=0&ep=0",
            mobile = true,
        )
        // Metered mobile data: reliable gateway proxy / adaptive web stream leads; local-only BDIX is last.
        val firstMobileUrl = mobile.first().url
        assertTrue(
            "Expected gateway proxy or adaptive HLS first on mobile, got $firstMobileUrl",
            firstMobileUrl.startsWith("https://gw.example/api/public/v1/stream/99/play") || firstMobileUrl.endsWith(".m3u8"),
        )
        assertEquals("http://bdix.ftp.local/movies/inception-1080p.mp4", mobile.last().url)
    }

    @Test
    fun linkHealth_filtersImagesDeadLinksAndDemotesBrokenHevc_andHonorsDualAudio() {
        val raw = listOf(
            GwSource(source = "MovieBox", resolution = "1080p", format = "MP4", url = "https://image.tmdb.org/t/p/w500/poster.jpg"),
            GwSource(source = "MovieBox", resolution = "1080p", format = "MP4", url = "https://dead.example.com/movie-1080.mp4", codec = "h264"),
            GwSource(source = "MovieBox", resolution = "1080p", format = "MKV", url = "https://ok.example.com/Movie.1080p.x265.10bit.mkv", codec = "hevc"),
            GwSource(source = "MovieBox", resolution = "1080p", format = "MKV", url = "https://ok.example.com/Movie.1080p.Dual-Audio.Hindi-Eng.mkv", codec = "h264"),
            GwSource(source = "MovieBox", resolution = "1080p", format = "MP4", url = "https://ok.example.com/Movie.1080p.mp4", codec = "h264"),
        )

        LinkHealth.failed("https://dead.example.com/other.mp4", 403, nowMs = 1_000L)
        assertTrue(LinkHealth.refused("https://dead.example.com/movie-1080.mp4", nowMs = 2_000L))
        LinkHealth.hevcFailed = true
        LinkHealth.preferDualAudio = true

        val ranked = GatewaySourceResolver.rankGwSources(raw, mobile = false, nowMs = 2_000L)
        assertFalse(ranked.any { it.url.contains("poster.jpg") })
        assertFalse(ranked.any { it.url.contains("dead.example.com") })
        assertEquals("https://ok.example.com/Movie.1080p.Dual-Audio.Hindi-Eng.mkv", ranked.first().url)
        assertTrue(ranked.first().label.orEmpty().contains("Dual Audio"))
        assertEquals("https://ok.example.com/Movie.1080p.x265.10bit.mkv", ranked.last().url)
    }

    @Test
    fun youtubeNormalization_convertsAllUrlVariantsToNocookieEmbedMp4Source() {
        val inputs = listOf(
            GwSource(source = "YouTube", resolution = "720p", format = "YOUTUBE", url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&ab_channel=Test"),
            GwSource(source = "Zee Bangla", resolution = "1080p", format = "MP4", url = "https://youtu.be/aqz-KE-bpKQ?si=xyz123"),
            GwSource(source = "YouTube", resolution = "720p", format = "YT", url = "yt://M7lc1UVf-VE"),
            GwSource(source = "YouTube", resolution = "720p", format = "YOUTUBE", url = "9bZkp7q19f0"),
            GwSource(source = "YouTube", resolution = "720p", format = "MP4", url = "https://www.youtube.com/shorts/jNQXAC9IVRw"),
            GwSource(source = "YouTube", resolution = "720p", format = "MP4", url = "https://www.youtube-nocookie.com/embed/ScMzIvxBSi4?autoplay=1"),
        )
        val expectedIds = listOf(
            "dQw4w9WgXcQ",
            "aqz-KE-bpKQ",
            "M7lc1UVf-VE",
            "9bZkp7q19f0",
            "jNQXAC9IVRw",
            "ScMzIvxBSi4",
        )

        inputs.zip(expectedIds).forEach { (gwSrc, id) ->
            assertEquals(id, GatewaySourceResolver.extractYouTubeVideoId(gwSrc.url, gwSrc.format, gwSrc.source))
            val mapped: Source? = GatewaySourceResolver.toSource(gwSrc)
            assertNotNull("Expected YouTube source for ${gwSrc.url}", mapped)
            assertEquals("https://www.youtube-nocookie.com/embed/$id", mapped!!.url)
            assertEquals(SourceKind.MP4, mapped.kind)
            assertTrue(mapped.headers.isEmpty())
            assertFalse(mapped.label.orEmpty().contains("YouTube", ignoreCase = true))
            assertFalse(mapped.label.orEmpty().contains("Zee Bangla", ignoreCase = true))
        }
        assertNull(GatewaySourceResolver.extractYouTubeVideoId("https://cdn.example.com/1080.mp4"))
    }

    @Test
    fun adultHttp451_returnsEmptyPageInsteadOfCrashing_andManifestUnlockPlusRescueWork() = runTest {
        val http451 = GatewayHttp { url, _ ->
            when {
                url.contains("/adult?") -> throw GatewayHttpException(451, "Unavailable For Legal Reasons")
                url.contains("/stream/777?") && url.contains("se=0&ep=0") ->
                    """{"subject_id":"777","se":0,"ep":0,"has_resource":false,"sources":[],"dash":[],"hls":[]}"""
                url.contains("/stream/777?") && url.contains("se=1&ep=1") ->
                    """{"subject_id":"777","se":1,"ep":1,"has_resource":true,"sources":[],"hls":[],"dash":[
                      {"format":"DASH","url":"https://cdn.example.com/dash/777_1080/index.mpd","resolutions":"1080,720,480","size":"1200000000","codecName":"h264","signCookie":"Edge-Cache-Cookie=xyz","signHeaderKey":"X-MB-Token","vipLocked":false}
                    ]}"""
                url.contains("/captions?") -> """{"count":0,"captions":[]}"""
                else -> throw GatewayHttpException(404, "Not found")
            }
        }
        val api = GatewayApi(http451, GatewayCache(clock = { 0L }))
        val gw = GatewayCatalogSource(api, adultAllowed = { true })

        val cats = gw.categories(Space.ADULT)
        assertTrue(cats.isNotEmpty())
        val page = gw.rowPage(cats.first().rows.first().id, 1)
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)

        val resolver = GatewaySourceResolver(api, isMobile = { false })
        val movieStub = GatewayMapper.toItem(
            GwItem(name = "Rescued Movie", subjectId = "777", slug = "rescued-777", posterUrl = "https://img/777.jpg", genre = "Action", type = "movie"),
            Space.HOME,
            "cat",
        )!!
        val resolved = resolver.attach(movieStub, null)
        assertEquals(1, resolved.sources.size)
        val unlockedDash = resolved.sources.single()
        assertEquals(SourceKind.DASH, unlockedDash.kind)
        assertEquals(1080, unlockedDash.height)
        assertEquals("Edge-Cache-Cookie=xyz", unlockedDash.headers["Cookie"])
        assertEquals("Edge-Cache-Cookie=xyz", unlockedDash.headers["X-MB-Token"])
    }
}
