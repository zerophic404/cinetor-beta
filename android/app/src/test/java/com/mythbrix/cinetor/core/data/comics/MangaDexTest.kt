package com.mythbrix.cinetor.core.data.comics

import com.mythbrix.cinetor.core.data.ComicFormat
import com.mythbrix.cinetor.core.data.ReadingDirection
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.gateway.GatewayCache
import com.mythbrix.cinetor.core.data.gateway.GatewayHttp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Routes by URL substring; records every request. */
private class FakeHttp(private val routes: List<Pair<String, String>>) : GatewayHttp {
    val urls = mutableListOf<String>()
    var down = false

    override suspend fun get(url: String, timeoutMs: Long): String {
        urls += url
        if (down) throw IOException("offline")
        return routes.firstOrNull { url.contains(it.first) }?.second ?: throw IOException("no route for $url")
    }
}

private const val MANGA_LIST = """
{"result":"ok","total":42,"limit":2,"offset":0,"data":[
  {"id":"uuid-1","type":"manga","attributes":{
     "title":{"en":"One Piece"},"altTitles":[{"ja":"ワンピース"},{"en":"OP"}],
     "description":{"en":"Pirates."},"year":1999,"status":"ongoing","contentRating":"safe",
     "originalLanguage":"ja","lastChapter":"1100",
     "tags":[{"id":"t1","type":"tag","attributes":{"name":{"en":"Adventure"}}}]},
   "relationships":[{"id":"c1","type":"cover_art","attributes":{"fileName":"cover-1.jpg"}}]},
  {"id":"uuid-2","type":"manga","attributes":{
     "title":{"en":"Solo Leveling"},"description":{"en":"Hunters."},"year":2018,
     "status":"completed","contentRating":"suggestive","originalLanguage":"ko","tags":[]},
   "relationships":[{"id":"c2","type":"cover_art","attributes":{"fileName":"cover-2.jpg"}}]}
]}
"""

private const val FEED = """
{"result":"ok","total":4,"data":[
  {"id":"ch-1","type":"chapter","attributes":{"chapter":"1","title":"Romance Dawn","pages":17,"translatedLanguage":"en"}},
  {"id":"ch-1-dup","type":"chapter","attributes":{"chapter":"1","title":"Dup group","pages":17,"translatedLanguage":"en"}},
  {"id":"ch-2","type":"chapter","attributes":{"chapter":"2","pages":19,"translatedLanguage":"en"}},
  {"id":"ch-empty","type":"chapter","attributes":{"chapter":"3","pages":0,"translatedLanguage":"en"}}
]}
"""

private const val AT_HOME = """
{"result":"ok","baseUrl":"https://md.example/abc","chapter":{"hash":"HASH","data":["p1.png","p2.png"],"dataSaver":["s1.jpg"]}}
"""

private const val AT_HOME_EMPTY = """{"result":"ok","baseUrl":"","chapter":{"hash":"","data":[]}}"""

class MangaDexMapperTest {

    @Test
    fun kinds_splitByOriginalLanguage() {
        assertEquals(ComicKind.MANGA, ComicKind.ofLanguage("ja"))
        assertEquals(ComicKind.MANHWA, ComicKind.ofLanguage("ko"))
        assertEquals(ComicKind.MANHUA, ComicKind.ofLanguage("zh"))
        assertEquals(ComicKind.MANHUA, ComicKind.ofLanguage("zh-hk"))
        // Unknown / missing language never crashes — defaults to Manga.
        assertEquals(ComicKind.MANGA, ComicKind.ofLanguage(null))
        assertEquals(listOf("cm:manga", "cm:manhwa", "cm:manhua"), ComicKind.entries.map { it.id })
        assertEquals(ComicKind.MANHWA, ComicKind.ofId("cm:manhwa"))
        assertNull(ComicKind.ofId("co-manga"))
    }

    @Test
    fun manga_toItem_realCoverAndReaderDefaults() {
        val list = MangaDexApi.json.decodeFromString(MdList.serializer(), MANGA_LIST)
        val manga = MangaDexMapper.toItem(list.data[0], ComicKind.MANGA, ComicKind.MANGA.id)!!
        assertEquals("cm:uuid-1", manga.id)
        assertEquals(Space.COMICS, manga.space)
        assertEquals("One Piece", manga.title)
        assertEquals("OP", manga.altTitle)
        assertEquals(1999, manga.year)
        assertEquals(listOf("Adventure"), manga.genres)
        assertEquals("https://uploads.mangadex.org/covers/uuid-1/cover-1.jpg.512.jpg", manga.poster)
        // Manga = right-to-left paged; manhwa/manhua = vertical long strip.
        assertEquals(ComicFormat.PAGED, manga.format)
        assertEquals(ReadingDirection.RTL, manga.readingDirection)

        val manhwa = MangaDexMapper.toItem(list.data[1], ComicKind.MANHWA, ComicKind.MANHWA.id)!!
        assertEquals(ComicFormat.LONGSTRIP, manhwa.format)
        assertEquals(ReadingDirection.TTB, manhwa.readingDirection)
        assertEquals("16+", manhwa.maturity)
        assertEquals(listOf("Completed"), manhwa.genres) // no tags → status label
        assertTrue(MangaDexMapper.isComicId(manhwa.id))
        assertEquals("uuid-2", MangaDexMapper.uuidOf(manhwa.id))
        assertNull(MangaDexMapper.uuidOf("co-ma-001"))
    }

    @Test
    fun chapters_dedupedAndPagelessDropped() {
        val feed = MangaDexApi.json.decodeFromString(MdList.serializer(), FEED)
        val chapters = MangaDexMapper.toChapters(feed)
        assertEquals(listOf("cmch:ch-1", "cmch:ch-2"), chapters.map { it.id })
        assertEquals("Chapter 1 · Romance Dawn", chapters[0].title)
        assertEquals("Chapter 2", chapters[1].title)
        assertEquals(listOf(1, 2), chapters.map { it.n })
        // Pages resolve later, so the chapter list ships empty page lists.
        assertTrue(chapters.all { it.pages.isEmpty() })
        assertEquals("ch-1", MangaDexMapper.chapterUuidOf(chapters[0].id))
    }

    @Test
    fun pageUrls_builtFromAtHomeServer() {
        val at = MangaDexApi.json.decodeFromString(MdAtHome.serializer(), AT_HOME)
        assertEquals(
            listOf("https://md.example/abc/data/HASH/p1.png", "https://md.example/abc/data/HASH/p2.png"),
            MangaDexMapper.pageUrls(at),
        )
        assertEquals(listOf("https://md.example/abc/data-saver/HASH/s1.jpg"), MangaDexMapper.pageUrls(at, dataSaver = true))
        // Bot-challenged response → empty, never a crash.
        assertTrue(MangaDexMapper.pageUrls(MangaDexApi.json.decodeFromString(MdAtHome.serializer(), AT_HOME_EMPTY)).isEmpty())
    }
}

class MangaDexApiTest {

    private fun api(routes: List<Pair<String, String>>, http: FakeHttp = FakeHttp(routes)) =
        http to MangaDexApi(http, GatewayCache(ttlMs = 60_000, clock = { 0L }), gatewayBase = { "https://gw.test" })

    @Test
    fun browse_splitsByLanguage_andCaches() = runTest {
        val (http, api) = api(listOf("api.mangadex.org/manga?" to MANGA_LIST))
        api.browse(ComicKind.MANHUA)
        val u = http.urls.single()
        assertTrue(u, u.contains("originalLanguage%5B%5D=zh") && u.contains("originalLanguage%5B%5D=zh-hk"))
        assertTrue(u, u.contains("includes%5B%5D=cover_art"))
        assertTrue(u, u.contains("order%5BfollowedCount%5D=desc"))
        assertFalse(u, u.contains("contentRating%5B%5D=pornographic"))
        // Same query twice = one request (catalog cache).
        api.browse(ComicKind.MANHUA)
        assertEquals(1, http.urls.size)
    }

    @Test
    fun pages_gatewayFirstThenDirectMangaDex() = runTest {
        // Gateway answers with the known-empty bot-challenge body → fall back to MangaDex at-home.
        val (http, api) = api(
            listOf(
                "gw.test" to AT_HOME_EMPTY,
                "api.mangadex.org/at-home/server/" to AT_HOME,
            ),
        )
        val pages = api.pages("ch-1")
        assertEquals(2, pages.size)
        assertTrue(http.urls[0], http.urls[0].startsWith("https://gw.test/api/public/v1/manga?action=c2&id=ch-1"))
        assertTrue(http.urls[1], http.urls[1].endsWith("/at-home/server/ch-1"))
    }

    @Test
    fun pages_bothEmpty_throwsChapterUnavailable() = runTest {
        val (_, api) = api(listOf("gw.test" to AT_HOME_EMPTY, "at-home/server/" to AT_HOME_EMPTY))
        val e = runCatching { api.pages("ch-x") }.exceptionOrNull()
        assertTrue("$e", e is ChapterUnavailableException)
        // Message carries no host / provider (hardening §2).
        assertFalse("${e?.message}", e?.message.orEmpty().contains("mangadex"))
        assertFalse("${e?.message}", e?.message.orEmpty().contains("http"))
    }

    @Test
    fun pages_gatewayWins_whenItAnswers() = runTest {
        val (http, api) = api(listOf("gw.test" to AT_HOME))
        assertEquals(2, api.pages("ch-1").size)
        assertEquals(1, http.urls.size) // no direct MangaDex call needed
    }
}

class MangaDexCatalogSourceTest {

    private fun source(): Pair<FakeHttp, MangaDexCatalogSource> {
        val http = FakeHttp(
            listOf(
                "api.mangadex.org/manga/uuid-1/feed" to FEED,
                "api.mangadex.org/manga?" to MANGA_LIST,
            ),
        )
        return http to MangaDexCatalogSource(MangaDexApi(http, GatewayCache(ttlMs = 60_000, clock = { 0L }), gatewayBase = { "https://gw.test" }))
    }

    @Test
    fun categories_threeTabsInOrder() = runTest {
        val (_, src) = source()
        val cats = src.categories(Space.COMICS)
        assertEquals(listOf("Manga", "Manhwa", "Manhua"), cats.map { it.title })
        assertEquals(listOf("cm:manga", "cm:manhwa", "cm:manhua"), cats.map { it.id })
        cats.forEach { assertEquals(Space.COMICS, it.space) }
        assertEquals(4, cats[0].rows.size)
        assertTrue(cats[0].rows.all { src.ownsRow(it.id) })
        // Only the first tab is probed for heroes.
        assertEquals(2, cats[0].heroIds.size)
        assertTrue(cats[1].heroIds.isEmpty())
    }

    @Test
    fun rowPage_mapsItems_andPagesOn() = runTest {
        val (_, src) = source()
        val page = src.rowPage("cmrow:MANGA:popular", 1)
        assertEquals(listOf("cm:uuid-1", "cm:uuid-2"), page.items.map { it.id })
        assertTrue(page.hasMore) // total 42 > 2 returned
        assertTrue(src.ownsItem("cm:uuid-1"))
        assertFalse(src.ownsItem("gw:123"))
        assertTrue(runCatching { src.rowPage("cmrow:NOPE:popular", 1) }.isFailure)
    }

    @Test
    fun item_attachesChapterList() = runTest {
        val (_, src) = source()
        src.row("cmrow:MANGA:popular") // remembers summaries
        val item = src.item("cm:uuid-1")
        assertEquals("One Piece", item.title)
        assertEquals(listOf("cmch:ch-1", "cmch:ch-2"), item.pages.map { it.id })
        assertTrue(src.isComicChapter(item.pages[0].id))
        assertFalse(src.isComicChapter("co-ma-001-ch1"))
    }

    @Test
    fun search_coversEveryKind_andSurvivesFailures() = runTest {
        val (http, src) = source()
        val hits = src.search("piece")
        assertEquals(listOf("cm:uuid-1", "cm:uuid-2"), hits.map { it.id })
        assertTrue(http.urls.any { it.contains("title=piece") })
        assertTrue(src.search("  ").isEmpty())
        http.down = true
        // Offline: no crash, just nothing (the mock catalog takes over at the HybridCatalog level).
        assertTrue(src.search("nothing").isEmpty())
    }
}
