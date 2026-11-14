package com.mythbrix.cinetor.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchEngineTest {

    private fun item(id: String, title: String, space: Space = Space.HOME) = Item(
        id = id, space = space, categoryId = "c", type = ItemType.MOVIE, title = title, year = 2020,
        rating = 7.0, maturity = "NR", genres = listOf("Drama"), synopsis = "", poster = "", backdrop = "",
        dominantColor = "#000000", accentColor = "#E50914",
    )

    private class FakeCatalog(private val bySpace: Map<Space, List<Item>>) : CatalogRepository {
        override suspend fun categories(space: Space): List<Category> = emptyList()
        override suspend fun item(id: String): Item = bySpace.values.flatten().first { it.id == id }
        override suspend fun row(rowId: String): List<Item> = emptyList()
        override suspend fun byGenre(space: Space, genre: String): List<Item> = emptyList()
        override suspend fun genres(space: Space): List<String> = emptyList()
        override suspend fun allItems(space: Space): List<Item> = bySpace[space].orEmpty()
    }

    private val words = listOf("dark", "night", "star", "river", "crimson", "echo", "silent", "golden", "lost", "city")

    private fun bigCatalog(n: Int): FakeCatalog {
        val items = (0 until n).map { i ->
            val space = Space.VIDEO[i % 3]
            item("i$i", "${words[i % 10]} ${words[(i / 10) % 10]} Chapter $i", space)
        }
        return FakeCatalog(items.groupBy { it.space })
    }

    @Test
    fun localSearch_returnsUnder50ms() = runTest {
        val repo = SearchRepository(bigCatalog(1_000))
        repo.search("warm") // builds the indexes once
        repo.search("dark")
        val queries = listOf("d", "da", "dark", "night st", "chapter 99", "crim", "zzz", "golden lost", "e", "cité")
        for (q in queries) {
            val start = System.nanoTime()
            val res = repo.search(q)
            val ms = (System.nanoTime() - start) / 1_000_000.0
            assertTrue("'$q' took ${ms}ms", ms < 50.0)
            if (q == "dark") assertEquals(190, res.size)
        }
        // Index stays fast after remote items are merged in.
        repo.addSeen((0 until 500).map { item("r$it", "remote title $it") })
        val start = System.nanoTime()
        assertEquals(500, repo.search("remote").size)
        assertTrue((System.nanoTime() - start) / 1_000_000.0 < 50.0)
    }

    @Test
    fun staleRemoteResult_isDiscarded() = runTest {
        val calls = mutableListOf<String>()
        val remote = RemoteSearch { q ->
            calls += q
            delay(if (q == "ab") 1_000 else 100)
            listOf(item("r-$q", "Remote $q"))
        }
        val engine = SearchEngine(SearchRepository(FakeCatalog(emptyMap())), remote)
        val queries = MutableStateFlow("")
        val out = mutableListOf<SearchResults>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.results(queries, null).toList(out) }

        queries.value = "ab"
        advanceTimeBy(500) // past 200 ms debounce; "ab" remote call in flight
        assertEquals(listOf("ab"), calls)
        queries.value = "abc"
        advanceUntilIdle()

        assertEquals(listOf("ab", "abc"), calls)
        assertTrue("stale result leaked: $out", out.none { r -> r.items.any { it.id == "r-ab" } })
        val last = out.last()
        assertEquals("abc", last.query)
        assertEquals(listOf("r-abc"), last.items.map { it.id })
        assertFalse(last.remoteLoading)
    }

    @Test
    fun remoteDebounce_skipsIntermediateKeystrokes() = runTest {
        val calls = mutableListOf<String>()
        val remote = RemoteSearch { q -> calls += q; emptyList() }
        val engine = SearchEngine(SearchRepository(FakeCatalog(emptyMap())), remote)
        val queries = MutableStateFlow("")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.results(queries, null).collect {} }
        for (q in listOf("d", "du", "dun")) {
            queries.value = q
            advanceTimeBy(50)
        }
        queries.value = "dune"
        advanceUntilIdle()
        assertEquals(listOf("dune"), calls)
    }

    @Test
    fun queryCache_hitSkipsNetwork() = runTest {
        var calls = 0
        val remote = RemoteSearch { q -> calls++; listOf(item("r-$q", "Remote $q")) }
        val local = SearchRepository(FakeCatalog(mapOf(Space.HOME to listOf(item("l1", "Dune")))))
        val engine = SearchEngine(local, remote)

        val first = engine.resultsFor("Dune", null).toList()
        assertEquals(1, calls)
        assertTrue(first.first().remoteLoading)
        assertEquals(listOf("l1", "r-Dune"), first.last().items.map { it.id })

        val second = engine.resultsFor("  dune ", null).toList()
        assertEquals("cache hit must not call remote", 1, calls)
        assertEquals(1, second.size)
        assertFalse(second.single().remoteLoading)
        assertTrue(second.single().items.any { it.id == "r-Dune" })
    }

    @Test
    fun queryCache_isLru100() {
        val cache = QueryCache<Int>(100)
        repeat(100) { cache.put("q$it", it) }
        assertEquals(0, cache.get("q0")) // touch → most recent
        cache.put("q100", 100)
        assertEquals(100, cache.size)
        assertNull(cache.get("q1"))
        assertEquals(0, cache.get("q0"))
    }

    @Test
    fun remoteResults_neverIsolated_andMergedIntoLocalIndex() = runTest {
        val remote = RemoteSearch { listOf(item("r1", "Neon Tokyo"), item("bad", "Neon Adult", Space.ADULT)) }
        val local = SearchRepository(FakeCatalog(emptyMap()))
        val engine = SearchEngine(local, remote)
        val res = engine.resultsFor("neon", null).toList().last()
        assertEquals(listOf("r1"), res.items.map { it.id })
        assertEquals(listOf("r1"), local.search("neo").map { it.id })
    }

    @Test
    fun inSpaceSearch_neverCallsRemote() = runTest {
        var calls = 0
        val remote = RemoteSearch { calls++; emptyList() }
        val tv = item("tv1", "Late Night News", Space.TV)
        val engine = SearchEngine(SearchRepository(FakeCatalog(mapOf(Space.TV to listOf(tv)))), remote)
        val res = engine.resultsFor("late", Space.TV).toList()
        assertEquals(0, calls)
        assertEquals(listOf("tv1"), res.single().items.map { it.id })
        assertTrue(engine.resultsFor("late", null).toList().last().items.isEmpty())
    }

    @Test
    fun remoteError_keepsLocalResults() = runTest {
        val remote = RemoteSearch { throw java.io.IOException("offline") }
        val local = SearchRepository(FakeCatalog(mapOf(Space.HOME to listOf(item("l1", "Dune")))))
        val last = SearchEngine(local, remote).resultsFor("dune", null).toList().last()
        assertTrue(last.remoteError)
        assertEquals(listOf("l1"), last.items.map { it.id })
    }
}
