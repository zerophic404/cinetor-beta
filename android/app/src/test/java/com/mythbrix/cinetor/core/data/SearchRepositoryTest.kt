package com.mythbrix.cinetor.core.data

import com.mythbrix.cinetor.TestFiles
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRepositoryTest {
    private val catalog = TestFiles.catalog()
    private val search = SearchRepository(catalog)

    @Test
    fun globalSearch_excludesIsolatedSections() = runTest {
        for (space in Space.ISOLATED) {
            for (item in catalog.allItems(space).take(5)) {
                // The query really matches inside its own space…
                assertTrue(search.searchInSpace(space, item.title).any { it.id == item.id })
                // …but global search never returns isolated items.
                val global = search.search(item.title)
                assertTrue("${item.title} leaked: $global", global.none { it.space.isIsolated })
            }
        }
        for (q in listOf("a", "e", "late night", "news", "live", "manga", "velvet")) {
            assertTrue(search.search(q).all { it.space.isVideo })
        }
    }

    @Test
    fun searchInSpace_onlyThatSpace() = runTest {
        for (space in Space.entries) {
            val results = search.searchInSpace(space, "e")
            assertTrue("$space has results", results.isNotEmpty())
            assertTrue(results.all { it.space == space })
        }
    }

    @Test
    fun search_isDiacriticAndCaseInsensitive() = runTest {
        val accented = search.search("Café")
        assertTrue(accented.isNotEmpty())
        assertEquals(accented.map { it.id }, search.search("CAFE").map { it.id })
        assertEquals(accented.map { it.id }, search.search("  cafe ").map { it.id })
        assertTrue(accented.all { SearchRepository.normalize(it.title).contains("cafe") })
        assertEquals("cafe nocturne", SearchRepository.normalize("  CAFÉ   Nocturne "))
    }

    @Test
    fun search_matchesAltTitle_andRanksPrefixFirst() = runTest {
        val anime = catalog.allItems(Space.ANIME).first { it.altTitle != null }
        assertTrue(search.search(anime.altTitle!!.uppercase()).any { it.id == anime.id })
        val results = search.search("s")
        val firstSubstring = results.indexOfFirst { SearchRepository.matchRank(keys(it), "s") == 1 }
        val lastPrefix = results.indexOfLast { SearchRepository.matchRank(keys(it), "s") == 0 }
        if (firstSubstring >= 0) assertTrue(lastPrefix < firstSubstring)
    }

    @Test
    fun search_blankQuery_isEmpty() = runTest {
        assertTrue(search.search("   ").isEmpty())
    }

    private fun keys(item: Item) = listOfNotNull(item.title, item.altTitle).map(SearchRepository::normalize)
}
