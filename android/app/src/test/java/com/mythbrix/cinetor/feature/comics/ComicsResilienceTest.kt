package com.mythbrix.cinetor.feature.comics

import com.mythbrix.cinetor.core.data.Chapter
import com.mythbrix.cinetor.core.data.ComicFormat
import com.mythbrix.cinetor.core.data.InMemoryHistoryStorage
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.ReadingDirection
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.comics.ComicKind
import com.mythbrix.cinetor.core.data.comics.MangaDexApi
import com.mythbrix.cinetor.core.data.comics.MangaDexCatalogSource
import com.mythbrix.cinetor.core.data.comics.MangaDexMapper
import com.mythbrix.cinetor.core.data.comics.MdAtHome
import com.mythbrix.cinetor.core.data.comics.MdList
import com.mythbrix.cinetor.core.data.gateway.GatewayCache
import com.mythbrix.cinetor.core.data.gateway.GatewayHttp
import com.mythbrix.cinetor.core.data.gateway.GatewayLayout
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.feature.adult.AdultGate
import com.mythbrix.cinetor.feature.adult.AdultKindFilter
import com.mythbrix.cinetor.feature.adult.AdultSortChip
import com.mythbrix.cinetor.feature.adult.adultGatewayRowId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files

private class ScriptedHttp(private val handler: (String) -> String) : GatewayHttp {
    val calls = mutableListOf<String>()
    override suspend fun get(url: String, timeoutMs: Long): String {
        calls += url
        return handler(url)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ComicsResilienceTest {

    @Test
    fun pageUrls_fallsBackToDataSaverWhenFullDataEmpty_andSupportsGatewayPagesArray() {
        val saverOnly = MangaDexApi.json.decodeFromString(
            MdAtHome.serializer(),
            """{"result":"ok","baseUrl":"https://node1.example","chapter":{"hash":"H1","data":[],"dataSaver":["s1.jpg","s2.jpg"]}}""",
        )
        assertEquals(
            listOf("https://node1.example/data-saver/H1/s1.jpg", "https://node1.example/data-saver/H1/s2.jpg"),
            MangaDexMapper.pageUrls(saverOnly, dataSaver = false),
        )

        val gatewayDirectPages = MangaDexApi.json.decodeFromString(
            MdAtHome.serializer(),
            """{"pages":["https://cdn.example/p1.webp","https://cdn.example/p2.webp"]}""",
        )
        assertEquals(
            listOf("https://cdn.example/p1.webp", "https://cdn.example/p2.webp"),
            MangaDexMapper.pageUrls(gatewayDirectPages, dataSaver = false),
        )
    }

    @Test
    fun toChaptersWithBackups_sortsDecimals_dropsExternalLinks_andRecordsDuplicateScanlations() {
        val feedJson = """
            {"result":"ok","total":6,"data":[
              {"id":"ch-10","type":"chapter","attributes":{"chapter":"10","pages":20,"translatedLanguage":"en"}},
              {"id":"ch-2","type":"chapter","attributes":{"chapter":"2","pages":18,"translatedLanguage":"en"}},
              {"id":"ch-1-main","type":"chapter","attributes":{"chapter":"1","title":"Awakening","pages":22,"translatedLanguage":"en"}},
              {"id":"ch-1-alt","type":"chapter","attributes":{"chapter":"1","title":"Alt Scan","pages":21,"translatedLanguage":"en"}},
              {"id":"ch-1-half","type":"chapter","attributes":{"chapter":"1.5","title":"Bonus","pages":8,"translatedLanguage":"en"}},
              {"id":"ch-licensed","type":"chapter","attributes":{"chapter":"3","pages":25,"externalUrl":"https://official.example/3","translatedLanguage":"en"}}
            ]}
        """.trimIndent()
        val list = MangaDexApi.json.decodeFromString(MdList.serializer(), feedJson)
        val parsed = MangaDexMapper.toChaptersWithBackups(list.data)

        assertEquals(
            listOf("cmch:ch-1-main", "cmch:ch-1-half", "cmch:ch-2", "cmch:ch-10"),
            parsed.chapters.map { it.id },
        )
        assertEquals(listOf("ch-1-alt"), parsed.backups["cmch:ch-1-main"])
    }

    @Test
    fun catalogSource_resolvesUncachedManhwaByUuid_andProvidesDataSaverAndBackupChapterFallback() = runTest {
        val singleManhwa = """
            {"result":"ok","data":{
              "id":"uuid-solo","type":"manga",
              "attributes":{"title":{"en":"Solo Leveling"},"description":{"en":"Arise."},"year":2018,"status":"completed","originalLanguage":"ko","tags":[]},
              "relationships":[{"id":"cov1","type":"cover_art","attributes":{"fileName":"solo.jpg"}}]
            }}
        """.trimIndent()
        val feed = """
            {"result":"ok","total":2,"data":[
              {"id":"ch-primary","type":"chapter","attributes":{"chapter":"1","pages":2,"translatedLanguage":"en"}},
              {"id":"ch-backup","type":"chapter","attributes":{"chapter":"1","pages":2,"translatedLanguage":"en"}}
            ]}
        """.trimIndent()
        val primaryAtHome = """
            {"result":"ok","baseUrl":"https://nodeA.example","chapter":{"hash":"HA","data":["1.png","2.png"],"dataSaver":["1s.jpg","2s.jpg"]}}
        """.trimIndent()
        val backupAtHome = """
            {"result":"ok","baseUrl":"https://nodeB.example","chapter":{"hash":"HB","data":["b1.jpg","b2.jpg"],"dataSaver":["b1s.jpg","b2s.jpg"]}}
        """.trimIndent()

        val http = ScriptedHttp { url ->
            when {
                url.contains("/manga/uuid-solo?") -> singleManhwa
                url.contains("/manga/uuid-solo/feed") -> feed
                url.contains("id=ch-primary") || url.contains("/at-home/server/ch-primary") -> primaryAtHome
                url.contains("id=ch-backup") || url.contains("/at-home/server/ch-backup") -> backupAtHome
                else -> throw IOException("unexpected $url")
            }
        }
        val api = MangaDexApi(http, GatewayCache(ttlMs = 60_000, clock = { 0L }), gatewayBase = { "https://gw.test" })
        val src = MangaDexCatalogSource(api)

        val item = src.item("cm:uuid-solo")
        assertEquals("Solo Leveling", item.title)
        assertEquals(Space.COMICS, item.space)
        assertEquals("cm:manhwa", item.categoryId)
        assertEquals(ComicFormat.LONGSTRIP, item.format)
        assertEquals(ReadingDirection.TTB, item.readingDirection)
        assertEquals(listOf("cmch:ch-primary"), item.pages.map { it.id })
        assertTrue(src.hasBackupChapter("cmch:ch-primary"))

        val primaryUrls = src.pages("cmch:ch-primary")
        assertEquals(listOf("https://nodeA.example/data/HA/1.png", "https://nodeA.example/data/HA/2.png"), primaryUrls)
        assertEquals("https://nodeA.example/data-saver/HA/1s.jpg", src.fallbackUrlFor(primaryUrls[0]))
        assertNull(src.fallbackUrlFor("https://other.example/unknown.png"))

        val backupUrls = src.pagesWithFallback("cmch:ch-primary", dataSaver = true, fresh = true, preferBackup = true)
        assertEquals(listOf("https://nodeB.example/data-saver/HB/b1s.jpg", "https://nodeB.example/data-saver/HB/b2s.jpg"), backupUrls)
    }

    @Test
    fun chapterSortingFiltering_andReadSummaryBadges() {
        val chapters = listOf(
            Chapter("ch-1", 1, "Chapter 1 · Prologue", listOf("a", "b", "c", "d")),
            Chapter("ch-2", 2, "Chapter 2 · Dungeon", listOf("a", "b", "c", "d")),
            Chapter("ch-3", 3, "Chapter 3 · Boss Raid", listOf("a", "b", "c", "d")),
        )
        val data = ComicsData(
            progress = listOf(
                ChapterProgress("cm:1", "ch-1", page = 3, pageCount = 4, updatedAt = 100L),
                ChapterProgress("cm:1", "ch-2", page = 1, pageCount = 4, updatedAt = 200L),
            ),
        )
        val summary = comicReadSummary(chapters, data, "cm:1")
        assertEquals(1, summary.readCount)
        assertEquals(1, summary.inProgressCount)
        assertEquals(2, summary.unreadCount)
        assertEquals("1 / 3 read", summary.badgeLabel)

        assertEquals("Read", chapterStatusBadge(chapters[0], data.progress[0]))
        assertEquals("Page 2/4 · 33%", chapterStatusBadge(chapters[1], data.progress[1]))
        assertEquals("4 pages", chapterStatusBadge(chapters[2], null))
        assertEquals("Unread", chapterStatusBadge(chapters[2].copy(pages = emptyList()), null))

        // Descending sort (newest first)
        assertEquals(
            listOf("ch-3", "ch-2", "ch-1"),
            filterAndSortChapters(chapters, data, "cm:1", sort = ChapterSort.DESCENDING).map { it.id },
        )
        // Unread filter includes in-progress + untouched, excludes completed ch-1
        assertEquals(
            listOf("ch-2", "ch-3"),
            filterAndSortChapters(chapters, data, "cm:1", filter = ChapterFilter.UNREAD).map { it.id },
        )
        // Downloaded filter + live query search
        assertEquals(
            listOf("ch-3"),
            filterAndSortChapters(
                chapters,
                data,
                "cm:1",
                downloadedIds = setOf("ch-1", "ch-3"),
                query = "raid",
                filter = ChapterFilter.DOWNLOADED,
            ).map { it.id },
        )
    }

    @Test
    fun comicDownloads_resolvesRemoteChapterLazily_andRetriesDataSaverUrlOnPageFailure() = runTest {
        val tempRoot = Files.createTempDirectory("comics-dl-test").toFile()
        try {
            val comic = Item(
                id = "cm:solo",
                space = Space.COMICS,
                categoryId = "cm:manhwa",
                type = ItemType.COMIC,
                title = "Solo",
                year = 2020,
                rating = 8.5,
                maturity = "All",
                genres = emptyList(),
                synopsis = "",
                poster = "",
                backdrop = "",
                dominantColor = "#000000",
                accentColor = "#E50914",
                pages = listOf(Chapter("cmch:c1", 1, "Chapter 1", emptyList())),
            )
            val requestedUrls = mutableListOf<String>()
            val downloads = ComicDownloads(
                root = tempRoot,
                scope = backgroundScope,
                openPath = { url ->
                    requestedUrls += url
                    if (url.endsWith("broken.png")) throw IOException("node timeout")
                    ByteArrayInputStream("img-bytes".toByteArray())
                },
                resolvePages = { listOf("https://node1.example/data/H/broken.png", "https://node1.example/data/H/ok.png") },
                fallbackUrl = { url -> if (url.endsWith("broken.png")) "https://node1.example/data-saver/H/fallback.jpg" else null },
            )
            downloads.download(comic, comic.pages.first())
            // Let IO dispatcher complete cleanly inside temp folder.
            val deadline = System.currentTimeMillis() + 3_000L
            while (downloads.states.value["cmch:c1"] != ChapterDownload.Done && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(ChapterDownload.Done, downloads.states.value["cmch:c1"])
            assertTrue(requestedUrls.any { it.endsWith("fallback.jpg") })
            assertNotNull(downloads.localPage(comic.id, comic.pages.first(), 0))
            assertNotNull(downloads.localPage(comic.id, comic.pages.first(), 1))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun adultSortChips_gatewayRowIds_andSafetyFilter() {
        assertEquals(
            listOf("For You", "Hottest", "Latest", "Top Rated"),
            AdultSortChip.entries.map { it.label },
        )
        val rowId = adultGatewayRowId(AdultKindFilter.MOVIES, AdultSortChip.HOTTEST)
        assertEquals("gwrow:ADULT:adult?kind=movies&sort=hottest", rowId)
        val parsed = GatewayLayout.parseRow(rowId)
        assertNotNull(parsed)
        assertEquals(Space.ADULT, parsed!!.space)
        assertEquals("adult", parsed.path)
        assertEquals("movies", parsed.params["kind"])
        assertEquals("hottest", parsed.params["sort"])

        assertTrue(AdultGate.isSafeAdultTitle("Midnight Temptation"))
        assertFalse(AdultGate.isSafeAdultTitle("High school girl"))
        assertFalse(AdultGate.isSafeAdultTitle("Hidden cam leak"))
        assertFalse(AdultGate.isSafeAdultTitle("   "))
    }
}
