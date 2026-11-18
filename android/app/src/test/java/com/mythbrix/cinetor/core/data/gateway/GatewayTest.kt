package com.mythbrix.cinetor.core.data.gateway

import com.mythbrix.cinetor.TestFiles
import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.Category
import com.mythbrix.cinetor.core.data.HybridCatalog
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.core.data.SearchEngine
import com.mythbrix.cinetor.core.data.SearchRepository
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.player.GatewaySourceResolver
import com.mythbrix.cinetor.core.player.SourceResolver
import com.mythbrix.cinetor.core.player.StreamType
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/** Routes gateway URLs to canned bodies (live-captured samples + small inline fixtures). Records every URL. */
class FakeGatewayHttp(private val routes: Map<String, String> = emptyMap()) : GatewayHttp {
    val urls = mutableListOf<String>()
    var down = false
    var failStreamTimes = 0

    override suspend fun get(url: String, timeoutMs: Long): String {
        urls += url
        if (down) throw IOException("gateway down")
        val path = url.substringAfter("/api/public/v1/").substringAfter("/api/v1/")
        val key = routes.keys.filter { path.startsWith(it) }.maxByOrNull { it.length }
            ?: throw GatewayHttpException(404, "no route for $path")
        return routes.getValue(key)
    }
}

object GatewayFixtures {
    private val dir: File by lazy { File(TestFiles.sharedDir.parentFile, "docs/gateway-samples") }
    fun sample(name: String): String = File(dir, name).readText()

    const val TV_DETAIL = """{"code":0,"data":{"subject":{"subjectId":"4661791271722305928","subjectType":2,"title":"12 12 12",
      "description":"A series.","releaseDate":"2026-11-13","duration":0,"genre":"Crime,Drama,Thriller","cover":{"url":"https://img.test/p.jpg","avgHueDark":"#223344"},
      "countryName":"United States","imdbRatingValue":"6.9","detailPath":"12-12-12-gQ2HnAj3ny5"},
      "stars":[{"name":"Actor A","staffType":1},{"name":"Director D","staffType":2}],
      "resource":{"seasons":[{"se":2,"maxEp":3,"allEp":""},{"se":1,"maxEp":2,"allEp":""},{"se":3,"maxEp":0}]}}}"""

    const val TV_STREAM = """{"subject_id":"4661791271722305928","se":1,"ep":2,"has_resource":true,
      "direct_play_url":"/api/public/v1/stream/4661791271722305928/play?detail_path=12-12-12-gQ2HnAj3ny5&se=1&ep=2",
      "sources":[
        {"source":"MovieBox","resolution":"480p","format":"MP4","url":"https://cdn.test/480.mp4?t=1","size":284886263,"codec":"h264","headers":{"Referer":"https://movieboxhd.net/","Origin":"https://movieboxhd.net"}},
        {"source":"MovieBox","resolution":"1080p","format":"DASH","url":"https://cdn.test/1080/index.mpd","size":1457322738,"codec":"hevc/h264","headers":{"Referer":"https://movieboxhd.net/","Cookie":"Edge-Cache-Cookie=abc","X-MB-Token":"Edge-Cache-Cookie=abc"}},
        {"source":"MovieBox","resolution":"1080p","format":"MP4","url":"https://cdn.test/1080.mp4?t=1","size":1021862569,"codec":"h264","headers":{"Referer":"https://movieboxhd.net/","Origin":"https://movieboxhd.net"}},
        {"source":"MovieBox","resolution":"720p","format":"MP4","url":"https://cdn.test/vip.mp4","vip_locked":true}
      ]}"""

    const val CAPTIONS = """{"count":2,"captions":[{"lan":"en","lanName":"English","url":"https://subs.test/a.srt?Policy=x"},{"lan":"bn","lanName":"বাংলা","url":"https://subs.test/b.vtt"}]}"""

    const val SEARCH_WITH_ADULT = """{"query":"x","page":1,"items":[
      {"name":"Inception","slug":"inception-e1BOR6f19C7","subject_id":"6391474290696802080","rating":"8.8","year":"2010","genre":"Action,Adventure,Sci-Fi","type":"movie","has_resource":true,"subtitles":["English"],"dubs":[]},
      {"name":"Night Thing","slug":"night-x1","subject_id":"111","rating":"6.0","year":"2026","genre":"Adult,Erotic\t","type":"movie","has_resource":true},
      {"name":"Gone Title","slug":"gone-x2","subject_id":"222","rating":"6.0","year":"2020","genre":"Drama","type":"movie","has_resource":false},
      {"name":"Squid Game","slug":"squid-1","subject_id":"333","rating":"8","year":"2021","genre":"Drama,Thriller","type":"tv","country":"Korea","has_resource":true}
    ]}"""

    const val ADULT_LIST = """{"page":1,"per_page":48,"has_more":true,"items":[
      {"name":"Labas pasok","slug":"labas-pasok-6HqGYcQkbc4","subject_id":"3522117640984618440","rating":"6.9","year":"2026","genre":"Adult,Erotic\t","type":"movie","has_resource":true}]}"""

    /** Live-captured `/home` trimmed to 2 items per section but with ALL 26 curated sections. */
    fun fullHomeRoutes(): Map<String, String> = routes() + mapOf(
        "home" to sample("home_full.json"),
        "short-tv" to sample("short-tv.json"),
        "zee-bangla" to sample("zee-bangla.json"),
    )

    fun routes(): Map<String, String> = mapOf(
        "home" to sample("home.json"),
        "short-tv" to sample("short-tv.json"),
        "zee-bangla" to sample("zee-bangla.json"),
        "movies" to sample("movies.json"),
        "tv-series" to sample("tv-series.json"),
        "animation" to sample("animation.json"),
        "adult" to ADULT_LIST,
        "search/suggest" to sample("search_suggest_q_incep.json"),
        "search" to SEARCH_WITH_ADULT,
        "detail/inception-e1BOR6f19C7" to sample("detail_inception-e1BOR6f19C7.json"),
        "detail/12-12-12-gQ2HnAj3ny5" to TV_DETAIL,
        "stream/6391474290696802080/captions" to sample("stream_6391474290696802080_captions.json"),
        "stream/6391474290696802080" to sample("stream_6391474290696802080.json"),
        "stream/4661791271722305928/captions" to CAPTIONS,
        "stream/4661791271722305928" to TV_STREAM,
    )
}

class GatewayTest {
    private fun env(adult: Boolean = false): Triple<FakeGatewayHttp, GatewayApi, GatewayCatalogSource> {
        val http = FakeGatewayHttp(GatewayFixtures.routes())
        val api = GatewayApi(http, GatewayCache(clock = { 0L }))
        return Triple(http, api, GatewayCatalogSource(api, adultAllowed = { adult }))
    }

    @Test
    fun home_bannerIsHero_sectionsAreRows_moviesRowPaged() = runTest {
        val (http, _, gw) = env()
        val cats = gw.categories(Space.HOME)
        assertEquals(
            listOf("gw:home-featured", "gw:home-movies", "gw:home-series", "gw:short-tv", "gw:zee-bangla"),
            cats.map { it.id },
        )
        val featured = cats.first()
        assertEquals(3, featured.heroIds.size) // Banner section (sample trimmed to 3) → hero carousel
        assertTrue("heroes ${featured.heroIds}", featured.heroIds.all { it.startsWith("gw:") })
        assertFalse("rows ${featured.rows.map { it.title }}", featured.rows.any { it.title == "Banner" })
        assertEquals(RowStyle.TOP10, featured.rows[0].style)
        val popular = gw.row(featured.rows.first().id)
        assertEquals(3, popular.size)
        assertEquals(1, http.urls.count { it.endsWith("/home") }) // cached for the section row
        val movies = cats[1]
        assertEquals("New Movies", movies.rows.first().title)
        val newMovies = gw.rowPage(movies.rows.first().id, 1)
        assertTrue("page1 $newMovies urls=${http.urls}", newMovies.hasMore)
        val p2 = gw.rowPage(movies.rows.first().id, 2)
        assertTrue(http.urls.last(), http.urls.last().contains("movies?page=2"))
        assertEquals(2, p2.page)
        val first = newMovies.items.first()
        assertEquals(Space.HOME, first.space)
        assertEquals(ItemType.MOVIE, first.type)
        assertNotNull(first.slug)
        assertEquals(first.poster, first.backdrop)
    }

    /** Curated `/home` sections (minus Banner & anime/sport) render in order, followed by Beta pageable shelves. */
    @Test
    fun home_rendersCuratedSectionsInOrder_plusBetaShelvesShortTvAndZeeBangla() = runTest {
        val http = FakeGatewayHttp(GatewayFixtures.fullHomeRoutes())
        val api = GatewayApi(http, GatewayCache(clock = { 0L }))
        val gw = GatewayCatalogSource(api)
        val cats = gw.categories(Space.HOME)
        val sections = GatewayApi.json.decodeFromString(GwHome.serializer(), GatewayFixtures.sample("home_full.json")).sections
        assertEquals(26, sections.size)
        val expectedCurated = sections
            .filter { sec ->
                !sec.section.equals("Banner", true) &&
                    !sec.section.contains("Anime", true) &&
                    sec.items.any { it.hasResource && !GatewayMapper.isAnime(GatewayMapper.genresOf(it.genre)) }
            }
            .map { it.section }
        val featured = cats.first { it.id == "gw:home-featured" }
        assertEquals(expectedCurated, featured.rows.take(expectedCurated.size).map { it.title })
        assertEquals(expectedCurated.size + GatewayLayout.homeExtraShelves().size, featured.rows.size)
        assertEquals(RowStyle.TOP10, featured.rows.first().style)
        assertEquals(2, featured.heroIds.size)

        // Coming Soon's first fixture item is has_resource=false → never listed.
        val comingSoon = featured.rows.first { it.title.contains("Coming Soon") }
        assertEquals(1, gw.rowPage(comingSoon.id, 1).items.size)

        val short = cats.first { it.id == "gw:short-tv" }
        assertEquals(3, gw.rowPage(short.rows.first().id, 1).items.size)
        assertTrue(http.urls.last(), http.urls.last().contains("short-tv?page=1"))
        val zee = cats.first { it.id == "gw:zee-bangla" }
        assertTrue(gw.rowPage(zee.rows.first().id, 1).items.isNotEmpty())
        assertTrue(http.urls.last(), http.urls.last().contains("zee-bangla?page=1"))
        // Curated short / Zee sections also appear inside their own tabs.
        assertTrue(short.rows.any { it.title.contains("Short Drama") || it.title.contains("Short TV") })
        assertTrue(zee.rows.any { it.title.contains("Zee Bangla") })
    }

    @Test
    fun dramaAndAnime_useCountryAndAnimationLists() = runTest {
        val (http, _, gw) = env()
        val drama = gw.categories(Space.DRAMA)
        assertEquals(
            listOf("All", "K-Drama", "C-Drama", "J-Drama", "Pakistani Drama", "Indian Drama", "Others"),
            drama.map { it.title },
        )
        assertTrue(http.urls.last(), http.urls.last().contains("tv-series?country=Korea&sort=hottest&page=1"))
        assertTrue(gw.row(drama.first().rows.first().id).all { it.space == Space.DRAMA && it.type == ItemType.SERIES })
        val anime = gw.categories(Space.ANIME)
        val cartoons = anime.first().rows.first { it.title == "Cartoons" }
        gw.row(cartoons.id)
        assertTrue(http.urls.any { it.contains("/animation?sort=hottest&page=1") })
        gw.byGenre(Space.HOME, "Action")
        assertTrue(http.urls.last(), http.urls.last().contains("movies?genre=Action"))
    }

    @Test
    fun detail_movie_and_tvSeasons() = runTest {
        val (_, _, gw) = env()
        val movie = gw.search("inception").first { it.title == "Inception" }
        val full = gw.item(movie.id)
        assertEquals(ItemType.MOVIE, full.type)
        assertEquals(8880, full.durationSec)
        assertEquals("#324a56", full.dominantColor) // cover.avgHueDark, no palette needed
        assertTrue(full.cast.contains("Leonardo DiCaprio"))
        assertTrue(full.seasons.isEmpty())

        val tvSummary = GatewayMapper.toItem(
            GwItem(name = "12 12 12", subjectId = "4661791271722305928", slug = "12-12-12-gQ2HnAj3ny5", type = "tv", genre = "Crime"),
            Space.HOME, "c",
        )!!
        val d = GatewayApi.json.decodeFromString(GwDetail.serializer(), GatewayFixtures.TV_DETAIL).data!!
        val tv = GatewayMapper.detailToItem(d, "12-12-12-gQ2HnAj3ny5", tvSummary)!!
        assertEquals(ItemType.SERIES, tv.type)
        assertEquals(listOf(1, 2), tv.seasons.map { it.n }) // sorted, empty season dropped
        assertEquals(listOf("s1e1", "s1e2"), tv.seasons[0].episodes.map { it.id })
        assertEquals(3, tv.seasons[1].episodes.size)
        assertEquals(2 to 3, GatewayMapper.parseEpisodeId("s2e3"))
        assertEquals(listOf("Actor A"), tv.cast)
    }

    @Test
    fun adult_neverInGlobalSearch() = runTest {
        val (_, _, gw) = env(adult = true)
        val results = gw.search("x")
        assertEquals(listOf("Inception", "Squid Game"), results.map { it.title })
        assertTrue(results.none { it.space == Space.ADULT || it.id.startsWith("ad-") })
        assertEquals(Space.DRAMA, results.last().space) // tv + Korea → Drama
        // merged results of the search engine are video-only as well
        assertTrue(SearchEngine.merge(emptyList(), results).all { it.space.isVideo })
        // adult rows never leak into video rows either
        assertTrue(GatewayMapper.isAdult(GatewayMapper.genresOf("Adult,Erotic\t")))
    }

    @Test
    fun hasResourceFalse_isNeverShown_andUnplayableGetsHidden() = runTest {
        val (_, _, gw) = env()
        val all = gw.search("x")
        assertFalse(all.any { it.title == "Gone Title" })
        gw.markUnplayable(all.first().id)
        assertFalse(gw.search("x").any { it.id == all.first().id })
    }

    @Test
    fun suggest_returnsTitles() = runTest {
        val (_, _, gw) = env()
        assertEquals("Inception", gw.suggest("incep").first())
    }

    @Test
    fun sources_sortedByResolution_vipDropped_headersPropagate() = runTest {
        val (_, api, _) = env()
        val stream = api.stream("4661791271722305928", "slug", 1, 2)
        val sources = GatewaySourceResolver.sortSources(stream.sources.mapNotNull { GatewaySourceResolver.toSource(it) })
        assertEquals(listOf(1080, 1080, 480), sources.map { it.height })
        assertEquals(SourceKind.MP4, sources[0].kind) // H.264 MP4 before HEVC DASH at equal height
        assertEquals(SourceKind.DASH, sources[1].kind)
        assertEquals(1021862569L, sources[0].sizeBytes)
        // quality cap 720 → 480p first, higher ones after
        assertEquals(480, GatewaySourceResolver.sortSources(sources, 720).first().height)

        val resolver = GatewaySourceResolver(api)
        val d = GatewayApi.json.decodeFromString(GwDetail.serializer(), GatewayFixtures.TV_DETAIL).data!!
        val tv = GatewayMapper.detailToItem(d, "12-12-12-gQ2HnAj3ny5", null)!!
        val attached = resolver.attach(tv, "s1e2")
        val req = SourceResolver.request(attached, "s1e2")!!
        assertEquals("gw:4661791271722305928#s1e2", req.key)
        assertEquals("https://cdn.test/1080.mp4?t=1", req.plan.url)
        assertEquals("https://movieboxhd.net/", req.plan.headers["Referer"])
        assertEquals(StreamType.PROGRESSIVE, req.plan.type)
        assertEquals(listOf("en", "bn"), req.plan.subtitles.map { it.language }) // captions side-loaded
        assertEquals("application/x-subrip", req.plan.subtitles.first().mimeType)
        // DASH source keeps its signed cookie headers for Media3
        val dash = SourceResolver.plan(SourceResolver.episodes(attached).first { it.id == "s1e2" }.sources[1], emptyList())
        assertEquals("Edge-Cache-Cookie=abc", dash.headers["Cookie"])
        assertEquals("Edge-Cache-Cookie=abc", dash.headers["X-MB-Token"])
        assertEquals(StreamType.DASH, dash.type)
        // proxy fallback = absolute direct_play_url
        val fb = resolver.fallback(attached, "s1e2")!!
        val fbUrl = SourceResolver.request(fb, "s1e2")!!.plan.url
        assertTrue(fbUrl, fbUrl.startsWith(GatewayConfig.BASE_URL + "/api/public/v1/stream/4661791271722305928/play"))
    }

    @Test
    fun movieStream_dashWithCookie() = runTest {
        val (http, api, gw) = env()
        val movie = gw.item(gw.search("inception").first { it.title == "Inception" }.id)
        val resolver = GatewaySourceResolver(api, slugOf = { gw.slugOf(it) })
        val req = SourceResolver.request(resolver.attach(movie, null), null)!!
        assertEquals(StreamType.DASH, req.plan.type)
        assertTrue(req.plan.headers.getValue("Cookie").startsWith("Edge-Cache-Cookie="))
        assertTrue(http.urls.any { it.contains("stream/6391474290696802080?detail_path=inception-e1BOR6f19C7&se=0&ep=0") })
        // cached: a second warm/play does not hit the network
        val n = http.urls.size
        resolver.attach(movie, null)
        assertEquals(n, http.urls.size)
        resolver.attach(movie, null, fresh = true) // re-resolve after 403 bypasses the cache
        assertTrue(http.urls.size > n)
    }

    @Test
    fun cache_servesStaleCopyWhenOffline() = runTest {
        var now = 0L
        val http = FakeGatewayHttp(GatewayFixtures.routes())
        val gw = GatewayCatalogSource(GatewayApi(http, GatewayCache(ttlMs = 1_000, clock = { now })))
        val online = gw.categories(Space.HOME).size
        assertTrue("home tabs", online >= 2)
        now = 10_000
        http.down = true
        assertEquals(online, gw.categories(Space.HOME).size) // stale gateway data instead of an error
        val fresh = GatewayCatalogSource(GatewayApi(http, GatewayCache(clock = { now })))
        try {
            fresh.categories(Space.HOME); fail("expected IOException without any cached copy")
        } catch (e: IOException) {
        }
    }
}

/** Catalog routing: gateway primary, fallbacks, isolated spaces, adult gate. */
class GatewayRoutingTest {
    private class FakeRemote(private val tag: String, var fail: Boolean = false) : CatalogRepository {
        val calls = mutableListOf<String>()
        override suspend fun categories(space: Space): List<Category> {
            calls += "categories"
            if (fail) throw IOException("$tag down")
            return listOf(Category("$tag-cat", space, tag, emptyList(), emptyList()))
        }
        override suspend fun item(id: String): Item = throw IOException()
        override suspend fun row(rowId: String): List<Item> = emptyList()
        override suspend fun byGenre(space: Space, genre: String): List<Item> = emptyList()
        override suspend fun genres(space: Space): List<String> = emptyList()
        override suspend fun allItems(space: Space): List<Item> = emptyList()
    }

    @Test
    fun fallbackChain_gatewayDown_thenTmdb_thenMock() = runTest {
        val mock = TestFiles.catalog()
        val gateway = FakeRemote("gateway", fail = true)
        val tmdb = FakeRemote("tmdb")
        val hybrid = HybridCatalog(listOf(gateway, tmdb), mock)
        assertEquals("tmdb", hybrid.categories(Space.HOME).single().title)
        tmdb.fail = true
        assertEquals(Space.HOME, hybrid.categories(Space.HOME).first().space) // mock
        assertTrue(hybrid.categories(Space.HOME).first().id != "tmdb-cat")
        gateway.fail = false
        assertEquals("gateway", hybrid.categories(Space.HOME).single().title)
    }

    @Test
    fun gatewayOnlySpaces_neverShowMock() = runTest {
        val gateway = FakeRemote("gateway", fail = true)
        val hybrid = HybridCatalog(listOf(gateway), TestFiles.catalog(), mockFallback = { it == Space.TV || it == Space.MUSIC || it == Space.COMICS })
        try {
            hybrid.categories(Space.HOME); fail("Home must surface the gateway error (retry UI), not mock data")
        } catch (e: IOException) {
        }
        assertTrue(hybrid.categories(Space.TV).isNotEmpty())
    }

    @Test
    fun isolatedSpaces_neverCallGateway() = runTest {
        val http = FakeGatewayHttp(GatewayFixtures.routes())
        val gw = GatewayCatalogSource(GatewayApi(http, GatewayCache()), adultAllowed = { true })
        val hybrid = HybridCatalog(listOf(gw), TestFiles.catalog(), adultChain = { emptyList() })
        for (space in listOf(Space.TV, Space.MUSIC, Space.COMICS)) {
            assertTrue(hybrid.categories(space).isNotEmpty())
            hybrid.allItems(space)
            hybrid.genres(space)
        }
        assertTrue(http.urls.toString(), http.urls.isEmpty())
    }

    @Test
    fun adult_usesGateway_onlyWhenUnlocked() = runTest {
        var unlocked = false
        val http = FakeGatewayHttp(GatewayFixtures.routes())
        val gw = GatewayCatalogSource(GatewayApi(http, GatewayCache()), adultAllowed = { unlocked })
        val hybrid = HybridCatalog(listOf(gw), TestFiles.catalog(), adultChain = { if (unlocked) listOf(gw) else emptyList() })
        hybrid.categories(Space.ADULT) // locked → never the gateway
        assertTrue(http.urls.none { it.contains("/adult") })
        try {
            gw.categories(Space.ADULT); fail("gateway refuses adult while locked")
        } catch (e: IllegalArgumentException) {
        }
        unlocked = true
        val cats = hybrid.categories(Space.ADULT)
        assertEquals(
            listOf("gw:adult-foryou", "gw:adult-hottest", "gw:adult-latest", "gw:adult-rating", "gw:adult-movies", "gw:adult-series"),
            cats.map { it.id },
        )
        assertTrue(http.urls.any { it.contains("/adult?kind=movies&sort=foryou&page=1") })
        val items = hybrid.row(cats.first().rows.first().id)
        assertTrue(items.isNotEmpty() && items.all { it.space == Space.ADULT && it.id.startsWith("ad-gw:") })
        // adult items are routed by the adult gate prefix (Details wraps them in the PIN guard)
        assertTrue(com.mythbrix.cinetor.feature.adult.AdultGate.isAdultId(items.first().id))
        // in-space adult search index works, global search stays video-only
        val repo = SearchRepository(hybrid)
        assertTrue(repo.searchInSpace(Space.ADULT, "labas").isNotEmpty())
        assertTrue(repo.search("labas").isEmpty())
    }
}

class GatewaySigningTest {

    @Test
    fun signHeaders_deterministicHmacSha256_preservesEncodedPathname_andSkipsBlankSecret() {
        assertTrue(GatewaySigner.signHeaders("", "GET", "https://gw.example/api/public/v1/home", 1763452800L).isEmpty())
        assertTrue(GatewaySigner.signHeaders("   ", "GET", "https://gw.example/api/public/v1/home", 1763452800L).isEmpty())

        val headers = GatewaySigner.signHeaders(
            secret = "test-gateway-secret-key",
            method = "get",
            url = "https://gw.example/api/public/v1/home?page=1&sort=hottest",
            nowEpochSec = 1763452800L,
        )
        assertEquals("1763452800", headers["X-App-Timestamp"])
        assertEquals("1857199d56694186b630fb765cd71acf6516e250fd306492470fec352a773b87", headers["X-App-Signature"])
        assertEquals(64, headers["X-App-Signature"]?.length)

        assertEquals(
            "/api/public/v1/detail/inception%202010",
            GatewaySigner.encodedPathOf("https://gw.example/api/public/v1/detail/inception%202010?foo=bar#frag"),
        )
    }

    @Test
    fun shouldSign_andUserAgent_isolateGatewayFromMangaDexAndThirdParties() {
        val base = "https://gw.example/"
        assertTrue(GatewaySigner.shouldSign("https://gw.example/api/public/v1/home", base))
        assertTrue(GatewaySigner.shouldSign("https://gw.example:443/api/public/v1/manga?action=c2&id=abc", base))
        assertTrue(GatewaySigner.shouldSign("https://gw.example/api/v1/resolve?tmdb=1", base))

        // Health endpoints and non-/api/ paths are never signed.
        assertFalse(GatewaySigner.shouldSign("https://gw.example/api/public/v1/health", base))
        assertFalse(GatewaySigner.shouldSign("https://gw.example/api/v1/health", base))
        assertFalse(GatewaySigner.shouldSign("https://gw.example/static/logo.png", base))

        // Different scheme, port, MangaDex, or CDN hosts are never signed.
        assertFalse(GatewaySigner.shouldSign("http://gw.example/api/public/v1/home", base))
        assertFalse(GatewaySigner.shouldSign("https://gw.example:8443/api/public/v1/home", base))
        assertFalse(GatewaySigner.shouldSign("https://api.mangadex.org/manga?limit=20", base))
        assertFalse(GatewaySigner.shouldSign("https://api.mangadex.org/api/public/v1/home", base))
        assertFalse(GatewaySigner.shouldSign("https://pbcdnw.aoneroom.com/image/poster.jpg", base))

        // User-Agent selection: browser UA for gateway, CinetorApp/1.0 strictly for mangadex.org.
        assertTrue(GatewaySigner.userAgentFor("https://gw.example/api/public/v1/home", base).contains("Cinetor/2.0"))
        assertEquals("CinetorApp/1.0", GatewaySigner.userAgentFor("https://api.mangadex.org/manga?limit=20", base))
        assertEquals("CinetorApp/1.0", GatewaySigner.userAgentFor("https://uploads.mangadex.org/covers/a/b.jpg", base))
    }

    @Test
    fun okHttpGatewayHttp_recoversFrom401ClockSkewViaServerTimeAndDateHeader_withoutSigningMangaDex() = runTest {
        val seenRequests = mutableListOf<Request>()
        val secret = "test-gateway-secret-key"
        val serverSec = 1763452800L
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                seenRequests += req
                val jsonMedia = "application/json".toMediaType()
                if (req.url.host.contains("mangadex.org")) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"result":"ok"}""".toResponseBody(jsonMedia))
                        .build()
                } else {
                    val ts = req.header("X-App-Timestamp")?.toLongOrNull() ?: 0L
                    val sig = req.header("X-App-Signature").orEmpty()
                    val expected = GatewaySigner.signHeaders(secret, req.method, req.url.toString(), serverSec)["X-App-Signature"]
                    if (kotlin.math.abs(ts - serverSec) > 60L || sig != expected) {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(401)
                            .message("Unauthorized")
                            .body("""{"error":"clock_skew","serverTime":$serverSec}""".toResponseBody(jsonMedia))
                            .build()
                    } else {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"status":"success","sections":[]}""".toResponseBody(jsonMedia))
                            .build()
                    }
                }
            }
            .build()

        val http = OkHttpGatewayHttp(
            client = mockClient,
            signingSecret = { secret },
            gatewayBase = { "https://gw.example" },
            nowMs = { 1_000_000L }, // skewed local clock (1000s vs 1763452800s)
        )

        val body = http.get("https://gw.example/api/public/v1/home", 5_000L)
        assertTrue(body.contains("\"success\""))
        assertEquals(2, seenRequests.size) // 1st skewed 401 -> 2nd corrected 200
        assertEquals("1000", seenRequests[0].header("X-App-Timestamp"))
        assertEquals("1763452800", seenRequests[1].header("X-App-Timestamp"))
        assertEquals(
            "1857199d56694186b630fb765cd71acf6516e250fd306492470fec352a773b87",
            seenRequests[1].header("X-App-Signature"),
        )
        assertTrue(seenRequests[1].header("User-Agent").orEmpty().contains("Cinetor/2.0"))

        // Subsequent call reuses learned clockOffsetSec immediately with zero extra 401 round-trip.
        http.get("https://gw.example/api/public/v1/home", 5_000L)
        assertEquals(3, seenRequests.size)
        assertEquals("1763452800", seenRequests[2].header("X-App-Timestamp"))

        // MangaDex requests through the same transport never carry signature headers and keep CinetorApp/1.0.
        http.get("https://api.mangadex.org/manga?limit=1", 5_000L)
        val mdReq = seenRequests.last()
        assertNull(mdReq.header("X-App-Timestamp"))
        assertNull(mdReq.header("X-App-Signature"))
        assertEquals("CinetorApp/1.0", mdReq.header("User-Agent"))

        // RFC 1123 HTTP Date header fallback when error body has no serverTime field.
        val parsedDate = GatewaySigner.parseServerEpochSec("""{"error":"unauthorized"}""", "Wed, 18 Nov 2026 08:00:00 GMT")
        assertNotNull(parsedDate)
        assertEquals(1794988800L, parsedDate)
    }
}
