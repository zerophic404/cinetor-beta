package com.mythbrix.cinetor.core.data.gateway

import com.mythbrix.cinetor.TestFiles
import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.Category
import com.mythbrix.cinetor.core.data.DubOption
import com.mythbrix.cinetor.core.data.HybridCatalog
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.core.data.SearchEngine
import com.mythbrix.cinetor.core.data.SearchRepository
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.fullLanguageName
import com.mythbrix.cinetor.core.player.GatewaySourceResolver
import com.mythbrix.cinetor.core.player.KnownSubtitle
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
            listOf("gw:home-featured", "gw:home-movies", "gw:home-series", "gw:short-tv"),
            cats.map { it.id },
        )
        val featured = cats.first()
        assertEquals(3, featured.heroIds.size) // Banner section (sample trimmed to 3) → hero carousel
        assertTrue("heroes ${featured.heroIds}", featured.heroIds.all { it.startsWith("gw:") })
        assertFalse("rows ${featured.rows.map { it.title }}", featured.rows.any { it.title == "Banner" })
        assertEquals(RowStyle.POSTER, featured.rows[0].style)
        assertTrue(featured.rows.none { it.style == RowStyle.TOP10 })
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

    /** Curated `/home` sections (minus Banner, anime/sport & regional Indian/Bengali/Pakistani dramas) render in order, followed by pageable Home shelves. */
    @Test
    fun home_rendersCuratedSectionsInOrder_excludesRegionalDramasToDramaSpace_andUsesPosterStyle() = runTest {
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
                    !sec.section.contains("Zee Bangla", true) &&
                    !sec.section.contains("Indian Drama", true) &&
                    !sec.section.contains("Star Jalsha", true) &&
                    !sec.section.contains("Sony AATH", true) &&
                    !sec.section.contains("Pakistani", true) &&
                    sec.items.any { it.hasResource && !GatewayMapper.isAnime(GatewayMapper.genresOf(it.genre)) }
            }
            .map { it.section }
        val featured = cats.first { it.id == "gw:home-featured" }
        assertEquals(expectedCurated, featured.rows.take(expectedCurated.size).map { it.title })
        assertEquals(expectedCurated.size + GatewayLayout.homeExtraShelves().size, featured.rows.size)
        assertEquals(RowStyle.POSTER, featured.rows.first().style)
        assertTrue(featured.rows.none { it.style == RowStyle.TOP10 })
        assertEquals(2, featured.heroIds.size)

        // Coming Soon's first fixture item is has_resource=false → never listed.
        val comingSoon = featured.rows.first { it.title.contains("Coming Soon") }
        assertEquals(1, gw.rowPage(comingSoon.id, 1).items.size)

        val short = cats.first { it.id == "gw:short-tv" }
        assertEquals(3, gw.rowPage(short.rows.first().id, 1).items.size)
        assertTrue(http.urls.last(), http.urls.last().contains("short-tv?page=1"))
        assertTrue(short.rows.any { it.title.contains("Short Drama") || it.title.contains("Short TV") })

        // Zee Bangla & Indian Dramas stay rich inside Space.DRAMA rather than Space.HOME.
        assertFalse(cats.any { it.id == "gw:zee-bangla" })
        val dramaIn = gw.categories(Space.DRAMA).first { it.id == "gw:drama-in" }
        val zeeRow = dramaIn.rows.first { it.id.contains(":zee-bangla?") }
        assertTrue(gw.rowPage(zeeRow.id, 1).items.isNotEmpty())
        assertTrue(http.urls.last(), http.urls.last().contains("zee-bangla?page=1"))
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

class GatewayV21FeaturesTest {

    @Test
    fun fullLanguageName_mapsAllIsoCodesVariants_andNeverOutputsShortCodes() {
        val cases = mapOf(
            "bn" to "Bangla",
            "ben" to "Bangla",
            "bengali" to "Bangla",
            "bangla" to "Bangla",
            "বাংলা" to "Bangla",
            "bn-BD" to "Bangla",
            "en" to "English",
            "eng" to "English",
            "en-US" to "English",
            "hi" to "Hindi",
            "hin" to "Hindi",
            "Hindi Dub" to "Hindi",
            "ur" to "Urdu",
            "urd" to "Urdu",
            "zh" to "Chinese",
            "chi" to "Chinese",
            "zho" to "Chinese",
            "zh-cn" to "Chinese",
            "zh-tw" to "Chinese",
            "ar" to "Arabic",
            "ara" to "Arabic",
            "ko" to "Korean",
            "kor" to "Korean",
            "ja" to "Japanese",
            "jpn" to "Japanese",
            "es" to "Spanish",
            "spa" to "Spanish",
            "fr" to "French",
            "fre" to "French",
            "fra" to "French",
            "French dub" to "French",
            "id" to "Indonesian",
            "ind" to "Indonesian",
            "in" to "Indonesian",
            "tr" to "Turkish",
            "tur" to "Turkish",
            "ta" to "Tamil",
            "tam" to "Tamil",
            "te" to "Telugu",
            "tel" to "Telugu",
            "ml" to "Malayalam",
            "pa" to "Punjabi",
            "ru" to "Russian",
            "pt" to "Portuguese",
            "ms" to "Malay",
            "th" to "Thai",
            "vi" to "Vietnamese",
            "tl" to "Filipino",
            "fil" to "Filipino",
            "de" to "German",
            "it" to "Italian",
            "und" to "Original",
            "" to "Original",
            "   " to "Original",
            "Original Audio" to "Original",
        )
        for ((input, expected) in cases) {
            val actual = fullLanguageName(input)
            assertEquals("Failed for input='$input'", expected, actual)
            assertTrue("Short code leaked for '$input': '$actual'", actual.length > 3)
        }
        // Unknown 2/3-letter codes must never pass through as raw 2/3-letter codes.
        assertTrue(fullLanguageName("qzx").length > 3)
        assertTrue(fullLanguageName("zz").length > 3)
    }

    @Test
    fun homeCleanup_removesIndianBengaliPakistaniFromHome_keepsThemInDrama_andUsesPosterForTrending() = runTest {
        val customHome = """{"status":"success","sections":[
          {"section":"Banner","items":[
            {"name":"Dune Part Two","slug":"dune-2","subject_id":"101","poster_url":"https://img.test/101.jpg","genre":"Sci-Fi,Action","type":"movie","has_resource":true}
          ]},
          {"section":"Trending Now","items":[
            {"name":"Gladiator II","slug":"glad-2","subject_id":"102","poster_url":"https://img.test/102.jpg","genre":"Action,Drama","type":"movie","has_resource":true}
          ]},
          {"section":"Indian Dramas","items":[
            {"name":"Anupamaa","slug":"anu-1","subject_id":"103","poster_url":"https://img.test/103.jpg","genre":"Drama","type":"tv","country":"India","has_resource":true}
          ]},
          {"section":"Zee Bangla Shows","items":[
            {"name":"Jagaddhatri","slug":"jag-1","subject_id":"104","poster_url":"https://img.test/104.jpg","genre":"Drama","type":"tv","country":"India","has_resource":true}
          ]},
          {"section":"Star Jalsha Hits","items":[
            {"name":"Geeta LLB","slug":"geeta-1","subject_id":"105","poster_url":"https://img.test/105.jpg","genre":"Drama","type":"tv","country":"India","has_resource":true}
          ]},
          {"section":"Sony AATH Classics","items":[
            {"name":"Gopal Bhar","slug":"gopal-1","subject_id":"106","poster_url":"https://img.test/106.jpg","genre":"Comedy","type":"tv","country":"India","has_resource":true}
          ]},
          {"section":"Pakistani Blockbusters","items":[
            {"name":"Tere Bin","slug":"terebin-1","subject_id":"107","poster_url":"https://img.test/107.jpg","genre":"Drama,Romance","type":"tv","country":"Pakistan","has_resource":true}
          ]}
        ]}"""
        val http = FakeGatewayHttp(GatewayFixtures.routes() + mapOf("home" to customHome))
        val api = GatewayApi(http, GatewayCache(clock = { 0L }))
        val gw = GatewayCatalogSource(api)

        val homeCats = gw.categories(Space.HOME)
        val allHomeRows = homeCats.flatMap { it.rows }
        val bannedTokens = listOf("indian drama", "zee bangla", "star jalsha", "sony aath", "pakistani")
        for (cat in homeCats) {
            for (token in bannedTokens) {
                assertFalse("Banned category '$token' on HOME: ${cat.title}", cat.title.contains(token, ignoreCase = true))
            }
        }
        for (row in allHomeRows) {
            for (token in bannedTokens) {
                assertFalse("Banned row '$token' on HOME: ${row.title}", row.title.contains(token, ignoreCase = true))
            }
            assertFalse("RowStyle.TOP10 must not be used on HOME: ${row.title}", row.style == RowStyle.TOP10)
        }
        val trending = homeCats.first().rows.first { it.title == "Trending Now" }
        assertEquals(RowStyle.POSTER, trending.style)

        // All regional drama categories & shelves remain rich inside Space.DRAMA.
        val dramaCats = gw.categories(Space.DRAMA)
        assertTrue(dramaCats.any { it.title == "Indian Drama" })
        assertTrue(dramaCats.any { it.title == "Pakistani Drama" })
        val dramaRowTitles = dramaCats.flatMap { it.rows }.map { it.title }
        assertTrue(dramaRowTitles.any { it.contains("Indian Dramas", ignoreCase = true) })
        assertTrue(dramaRowTitles.any { it.contains("Zee Bangla", ignoreCase = true) })
        assertTrue(dramaRowTitles.any { it.contains("Star Jalsha", ignoreCase = true) })
        assertTrue(dramaRowTitles.any { it.contains("Sony AATH", ignoreCase = true) })
        assertTrue(dramaRowTitles.any { it.contains("Pakistani", ignoreCase = true) })
    }

    @Test
    fun item_neverThrowsTitleNotFoundWhenSummaryOrSearchOrTmdbResolves_andSynthesizesSeason1Ep1() = runTest {
        val sparseHome = """{"status":"success","sections":[
          {"section":"Popular Series","items":[
            {"name":"Broken Detail Show","slug":"broken-detail-show","subject_id":"88801","poster_url":"https://img.test/88801.jpg","genre":"Drama","type":"tv","has_resource":true}
          ]}
        ]}"""
        val emptySeasonDetail = """{"code":0,"data":{"subject":{"subjectId":"88802","subjectType":2,"title":"Empty Seasons Series","releaseDate":"2025-05-01","genre":"Thriller","detailPath":"empty-seasons-88802"},"stars":[],"resource":{"seasons":[]}}}"""
        val http = GatewayHttp { url, _ ->
            when {
                url.endsWith("/home") -> sparseHome
                url.contains("/detail/broken-detail-show") -> throw GatewayHttpException(404, "missing detail")
                url.contains("/detail/empty-seasons-88802") -> emptySeasonDetail
                url.contains("/search?") -> """{"page":1,"has_more":false,"items":[]}"""
                else -> throw GatewayHttpException(404, "not found")
            }
        }
        val fakeTmdb = object : GatewayTmdbBridge {
            override val isConfigured: Boolean = true
            override suspend fun searchMulti(query: String): List<TmdbCandidate> = emptyList()
            override suspend fun personCredits(personName: String): List<TmdbCandidate> = emptyList()
            override suspend fun detail(mediaType: String, tmdbId: Int): TmdbCandidate? =
                if (mediaType == "tv" && tmdbId == 293610) {
                    TmdbCandidate(
                        tmdbId = 293610,
                        mediaType = "tv",
                        title = "Doctor X: Mafia in White",
                        year = 2025,
                        rating = 8.1,
                        posterUrl = "https://image.tmdb.org/t/p/w500/doc-x.jpg",
                        backdropUrl = "https://image.tmdb.org/t/p/w780/doc-x-bg.jpg",
                        synopsis = "Medical crime drama.",
                    )
                } else {
                    null
                }
        }
        val api = GatewayApi(http, GatewayCache(clock = { 0L }))
        val gw = GatewayCatalogSource(api, tmdbBridge = fakeTmdb)

        // 1. Card loaded from row whose /detail throws 404 still opens from summary with synthesized S1E1!
        val cats = gw.categories(Space.HOME)
        val cards = gw.row(cats.first().rows.first().id)
        val brokenCard = cards.single { it.id == "gw:88801" }
        val synthesized = gw.item(brokenCard.id)
        assertEquals("Broken Detail Show", synthesized.title)
        assertEquals(ItemType.SERIES, synthesized.type)
        assertEquals(1, synthesized.seasons.size)
        assertEquals("s1e1", synthesized.seasons.first().episodes.single().id)

        // 2. Detail returning subjectType=2 with empty seasons also synthesizes S1E1 so playback works!
        val slugs = InMemorySlugStore().apply { put("gw:88802", "empty-seasons-88802") }
        val gw2 = GatewayCatalogSource(api, slugs = slugs, tmdbBridge = fakeTmdb)
        val fromEmptyDetail = gw2.item("gw:88802")
        assertEquals("Empty Seasons Series", fromEmptyDetail.title)
        assertEquals(1, fromEmptyDetail.seasons.size)
        assertEquals("s1e1", fromEmptyDetail.seasons.first().episodes.single().id)

        // 3. Cold deep-link to gw:tmdb-tv-293610 resolves via GatewayTmdbBridge with S1E1!
        val tmdbItem = gw2.item("gw:tmdb-tv-293610")
        assertEquals("Doctor X: Mafia in White", tmdbItem.title)
        assertEquals("s1e1", tmdbItem.seasons.first().episodes.single().id)

        // 4. Lenient DTO deserialization handles missing/null fields cleanly.
        val sparseDetail = GatewayApi.json.decodeFromString(GwDetail.serializer(), """{"code":null,"data":{"subject":{"subjectId":null,"title":null}}}""")
        assertNotNull(sparseDetail)
    }

    @Test
    fun hybridSearch_resolvesBiggBossAndDoctorXWhiteMafia_andHttpBridgeSupportsBearerAndApiKey() = runTest {
        val http = GatewayHttp { url, _ ->
            when {
                url.contains("/search?") && url.contains("Doctor%20X%20Mafia%20in%20White", ignoreCase = true) ->
                    """{"page":1,"has_more":false,"items":[{"name":"Doctor X: Mafia in White","slug":"doctor-x-mafia-in-white","subject_id":"99100","poster_url":"https://img.test/dx.jpg","genre":"Drama,Crime","type":"tv","country":"Korea","year":"2025","has_resource":true}]}"""
                url.contains("/search?") -> """{"page":1,"has_more":false,"items":[]}"""
                url.contains("/search/suggest?") -> """{"suggestions":[]}"""
                url.contains("/stream/tmdb-tv-19668") ->
                    """{"subject_id":"tmdb-tv-19668","se":1,"ep":1,"has_resource":true,"sources":[{"source":"YouTube","resolution":"720p","format":"YOUTUBE","url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"}]}"""
                url.contains("/captions") -> """{"count":0,"captions":[]}"""
                else -> throw GatewayHttpException(404, "no route")
            }
        }
        val mockTmdbClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val q = req.url.queryParameter("query").orEmpty()
                val body = when {
                    q.contains("Doctor X", ignoreCase = true) ->
                        """{"results":[{"id":293610,"media_type":"tv","name":"Doctor X: Mafia in White","first_air_date":"2025-01-10","vote_average":8.2,"poster_path":"/dx.jpg","backdrop_path":"/dx_bg.jpg","overview":"Surgeon noir."}]}"""
                    q.contains("Bigg Boss", ignoreCase = true) ->
                        """{"results":[{"id":19668,"media_type":"tv","name":"Bigg Boss","first_air_date":"2006-11-03","vote_average":6.8,"poster_path":"/bb.jpg","backdrop_path":"/bb_bg.jpg","overview":"Reality TV show."}]}"""
                    else -> """{"results":[]}"""
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val bridge = HttpGatewayTmdbBridge(
            client = mockTmdbClient,
            bearerToken = { "" },
            apiKey = { "test-v3-api-key" },
        )
        assertTrue(bridge.isConfigured)
        val api = GatewayApi(http, GatewayCache(clock = { 0L }))
        val gw = GatewayCatalogSource(api, tmdbBridge = bridge)

        // "Doctor X: White Mafia" resolves via TMDB -> canonical title "Doctor X: Mafia in White" -> native gateway subject!
        val docResults = gw.search("Doctor X: White Mafia")
        assertTrue(docResults.isNotEmpty())
        assertEquals("Doctor X: Mafia in White", docResults.first().title)
        assertEquals("gw:99100", docResults.first().id)

        // "Bigg Boss" resolves via TMDB fallback -> gw:tmdb-tv-19668, opens via item(id), and plays via GatewaySourceResolver!
        val bbResults = gw.search("Bigg Boss")
        assertTrue(bbResults.isNotEmpty())
        val bb = bbResults.first()
        assertEquals("gw:tmdb-tv-19668", bb.id)
        assertEquals("Bigg Boss", bb.title)
        val fullBb = gw.item(bb.id)
        assertEquals(1, fullBb.seasons.size)
        val resolver = GatewaySourceResolver(api, slugOf = { gw.slugOf(it) })
        val playableBb = resolver.attach(fullBb, "s1e1")
        val req = SourceResolver.request(playableBb, "s1e1")
        assertNotNull(req)
        assertEquals("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", req!!.plan.url)
    }

    @Test
    fun castWorks_combinesGatewayAndTmdbPersonCredits_deduplicatesAndPopulatesSummaries() = runTest {
        val http = GatewayHttp { url, _ ->
            when {
                url.contains("/search?") && url.contains("Shah", ignoreCase = true) ->
                    """{"page":1,"has_more":false,"items":[
                      {"name":"Jawan [Hindi]","slug":"jawan-hi","subject_id":"501","poster_url":"https://img.test/501.jpg","genre":"Action,Thriller","type":"movie","year":"2023","has_resource":true},
                      {"name":"Adult Cast Noise","slug":"bad-cast","subject_id":"502","poster_url":"https://img.test/502.jpg","genre":"Adult,Erotic","type":"movie","year":"2023","has_resource":true}
                    ]}"""
                else -> """{"page":1,"has_more":false,"items":[]}"""
            }
        }
        val fakeTmdb = object : GatewayTmdbBridge {
            override val isConfigured: Boolean = true
            override suspend fun searchMulti(query: String): List<TmdbCandidate> = emptyList()
            override suspend fun detail(mediaType: String, tmdbId: Int): TmdbCandidate? = null
            override suspend fun personCredits(personName: String): List<TmdbCandidate> = listOf(
                // Duplicate of Jawan -> must be deduplicated in favor of native gateway gw:501
                TmdbCandidate(tmdbId = 872906, mediaType = "movie", title = "Jawan", year = 2023, rating = 7.2, posterUrl = "https://image.tmdb.org/t/p/w500/jawan.jpg"),
                TmdbCandidate(tmdbId = 783461, mediaType = "movie", title = "Pathaan", year = 2023, rating = 6.7, posterUrl = "https://image.tmdb.org/t/p/w500/pathaan.jpg"),
            )
        }
        val api = GatewayApi(http, GatewayCache(clock = { 0L }))
        val gw = GatewayCatalogSource(api, tmdbBridge = fakeTmdb)
        val hybrid = HybridCatalog(listOf(gw), TestFiles.catalog())

        val works = hybrid.castWorks("Shah Rukh Khan")
        assertEquals(2, works.size)
        assertEquals("gw:501", works[0].id)
        assertEquals("gw:tmdb-movie-783461", works[1].id)
        assertTrue(works.none { it.space == Space.ADULT })
        // Every returned work is remembered in summaries so item(id) opens without "Title not found"!
        for (work in works) {
            assertEquals(work.title, hybrid.item(work.id).title)
        }
    }

    @Test
    fun warmStartupSpaces_fetchesHomeAnimeDramaConcurrently_andReturnsCappedImageUrls() = runTest {
        val http = FakeGatewayHttp(GatewayFixtures.fullHomeRoutes())
        val api = GatewayApi(http, GatewayCache(clock = { 0L }))
        val gw = GatewayCatalogSource(api)
        val hybrid = HybridCatalog(listOf(gw), TestFiles.catalog())

        val urls = hybrid.warmStartupSpaces()
        assertTrue("Expected non-empty image URLs", urls.isNotEmpty())
        assertTrue("Expected <= 24 URLs, got ${urls.size}", urls.size <= 24)
        assertEquals(urls.size, urls.distinct().size)
        assertTrue(urls.all { it.startsWith("https://") || it.startsWith("http://") })
        // Confirmed HOME, ANIME, and DRAMA endpoints were all warmed.
        assertTrue(http.urls.any { it.endsWith("/home") })
        assertTrue(http.urls.any { it.contains("genre=Anime") || it.contains("/animation") })
        assertTrue(http.urls.any { it.contains("country=Korea") })
    }

    @Test
    fun audioDubs_discoversSiblingDubs_populatesItemDubs_andAttachesPlayableDubSources() = runTest {
        val mainDetail = """{"code":0,"data":{"subject":{"subjectId":"7001","subjectType":1,"title":"Pushpa: The Rise","releaseDate":"2021-12-17","duration":10500,"genre":"Action,Drama","cover":{"url":"https://img.test/p.jpg"},"detailPath":"pushpa-orig","dubs":[
          {"subjectId":"7001","lanName":"Original Audio","lanCode":"te","original":true,"type":0,"detailPath":"pushpa-orig"},
          {"subjectId":"7002","lanName":"hi","lanCode":"hi","original":false,"type":0,"detailPath":"pushpa-hindi"},
          {"subjectId":"7099","lanName":"ar","lanCode":"ar","original":false,"type":1,"detailPath":"pushpa-ar-sub"}
        ]},"stars":[],"resource":{"seasons":[]}}}"""
        val siblingSearch = """{"page":1,"has_more":false,"items":[
          {"name":"Pushpa: The Rise","slug":"pushpa-orig","subject_id":"7001","type":"movie","year":"2021","has_resource":true},
          {"name":"Pushpa: The Rise [Bangla]","slug":"pushpa-bangla","subject_id":"7003","type":"movie","year":"2021","has_resource":true},
          {"name":"Pushpa: The Rise [English]","slug":"pushpa-eng","subject_id":"7004","type":"movie","year":"2021","has_resource":true}
        ]}"""
        val http = GatewayHttp { url, _ ->
            when {
                url.contains("/detail/pushpa-orig") -> mainDetail
                url.contains("/search?") -> siblingSearch
                url.contains("/stream/7001/captions") -> """{"count":0,"captions":[]}"""
                url.contains("/stream/7001?") -> """{"subject_id":"7001","se":0,"ep":0,"has_resource":true,"sources":[{"source":"MovieBox","resolution":"1080p","format":"MP4","url":"https://cdn.test/7001-orig.mp4","codec":"h264"}]}"""
                url.contains("/stream/7002?") -> """{"subject_id":"7002","se":0,"ep":0,"has_resource":true,"sources":[{"source":"MovieBox","resolution":"1080p","format":"MP4","url":"https://cdn.test/7002-hi.mp4","codec":"h264"}]}"""
                url.contains("/stream/7003?") -> """{"subject_id":"7003","se":0,"ep":0,"has_resource":true,"sources":[{"source":"MovieBox","resolution":"720p","format":"MP4","url":"https://cdn.test/7003-bn.mp4","codec":"h264"}]}"""
                url.contains("/stream/7004?") -> """{"subject_id":"7004","se":0,"ep":0,"has_resource":true,"sources":[{"source":"MovieBox","resolution":"1080p","format":"MP4","url":"https://cdn.test/7004-en.mp4","codec":"h264"}]}"""
                else -> throw GatewayHttpException(404, "no route for $url")
            }
        }
        val slugs = InMemorySlugStore().apply { put("gw:7001", "pushpa-orig") }
        val api = GatewayApi(http, GatewayCache(clock = { 0L }))
        val gw = GatewayCatalogSource(api, slugs = slugs)

        val item = gw.item("gw:7001")
        val dubLangs = item.dubs.map { it.languageName }
        assertTrue("Expected Hindi, Bangla, English in $dubLangs", dubLangs.containsAll(listOf("Hindi", "Bangla", "English")))
        assertFalse("Burnt-in sub (type=1) must not appear in dubs: $dubLangs", dubLangs.contains("Arabic"))
        assertTrue(item.dubs.all { it.languageName.length > 3 })

        val resolver = GatewaySourceResolver(api, slugOf = { gw.slugOf(it) }, isMobile = { false })
        val attached = resolver.attach(item, null)
        val audioLangs = attached.sources.mapNotNull { it.audioLang }.toSet()
        assertTrue("Expected Original, Hindi, Bangla, English in $audioLangs", audioLangs.containsAll(listOf("Original", "Hindi", "Bangla", "English")))
        val hindiSrc = attached.sources.first { it.audioLang == "Hindi" }
        assertEquals("https://cdn.test/7002-hi.mp4", hindiSrc.url)
        assertTrue(hindiSrc.label.orEmpty().contains("Hindi"))
        val banglaSrc = attached.sources.first { it.audioLang == "Bangla" }
        assertEquals("https://cdn.test/7003-bn.mp4", banglaSrc.url)
        assertTrue(banglaSrc.label.orEmpty().contains("Bangla"))
    }

    @Test
    fun onlineSubtitles_queriesEndpointAndFallbackCaptions_withFullLanguageNames() = runTest {
        var failPrimaryEndpoint = false
        val http = GatewayHttp { url, _ ->
            when {
                url.contains("/subtitles/search?") -> {
                    if (failPrimaryEndpoint) throw GatewayHttpException(404, "endpoint unavailable")
                    """{"count":3,"subtitles":[
                      {"id":"sub-en","language":"en","label":"en","vtt_url":"/api/public/v1/subtitle?url=https%3A%2F%2Fsubs.test%2Fen.srt"},
                      {"id":"sub-bn","language":"bn","label":"বাংলা","vtt_url":"/api/public/v1/subtitle?url=https%3A%2F%2Fsubs.test%2Fbn.srt"},
                      {"id":"sub-zh","language":"zh-cn","label":"zh-cn","vtt_url":"/api/public/v1/subtitle?url=https%3A%2F%2Fsubs.test%2Fzh.srt"}
                    ]}"""
                }
                url.contains("/search?") ->
                    """{"page":1,"has_more":false,"items":[
                      {"name":"Avengers","slug":"avengers-1","subject_id":"9101","type":"movie","has_resource":true},
                      {"name":"Avengers Age","slug":"avengers-2","subject_id":"9102","type":"movie","has_resource":true}
                    ]}"""
                url.contains("/stream/9101/captions") ->
                    """{"count":2,"captions":[
                      {"lan":"hi","lanName":"hi","url":"https://subs.test/9101-hi.srt"},
                      {"lan":"bn","lanName":"ben","url":"https://subs.test/9101-bn.srt"}
                    ]}"""
                url.contains("/stream/9102/captions") ->
                    """{"count":1,"captions":[
                      {"lan":"ur","lanName":"urd","url":"https://subs.test/9102-ur.vtt"}
                    ]}"""
                else -> throw GatewayHttpException(404, "no route for $url")
            }
        }
        val api = GatewayApi(http, GatewayCache(clock = { 0L }), baseUrl = { "https://gw.example" })
        val resolver = GatewaySourceResolver(api)
        val previousDelegate = SourceResolver.onlineSubtitleDelegate
        try {
            SourceResolver.onlineSubtitleDelegate = { q, id, se, ep, l ->
                resolver.searchOnlineSubtitles(q, id, se, ep, l)
            }

            // 1. Primary /subtitles/search endpoint with lang="bn" prioritizes Bangla first & resolves absolute URLs.
            val primary: List<KnownSubtitle> = SourceResolver.searchOnlineSubtitles("Avengers", itemId = "gw:9101", lang = "bn")
            assertEquals(3, primary.size)
            assertEquals("Bangla", primary.first().language)
            assertEquals("Bangla", primary.first().label)
            assertTrue(primary.first().url.startsWith("https://gw.example/api/public/v1/subtitle?url="))
            assertEquals(setOf("Bangla", "English", "Chinese"), primary.map { it.language }.toSet())
            assertTrue(primary.all { it.label.length > 3 && it.language.length > 3 })

            // 2. Fallback path when /subtitles/search throws -> top search subjects -> /captions wrapped via /subtitle?url=...
            failPrimaryEndpoint = true
            val fallback = resolver.searchOnlineSubtitles("Avengers", lang = "ur")
            assertEquals(3, fallback.size)
            assertEquals("Urdu", fallback.first().language)
            assertEquals(setOf("Hindi", "Bangla", "Urdu"), fallback.map { it.language }.toSet())
            assertTrue(fallback.all { it.url.startsWith("https://gw.example/api/public/v1/subtitle?url=") })
        } finally {
            SourceResolver.onlineSubtitleDelegate = previousDelegate
        }
    }
}
