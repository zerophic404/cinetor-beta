package com.mythbrix.cinetor.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHistoryTest {

    @Test
    fun searchHistory_localPerProfile_neverIncludesIsolated() = runTest {
        val history = SearchHistory(InMemoryHistoryStorage())
        history.record("p1", "naruto", null)
        history.record("p2", "dune", null)
        history.record("p1", "late night", Space.TV)
        history.record("p1", "secret", Space.ADULT)
        history.record("p1", "manga x", Space.COMICS)
        history.record("p1", "lofi", Space.MUSIC)
        history.record("p1", "squid", Space.DRAMA) // video space → global bucket

        assertEquals(listOf("squid", "naruto"), history.entries("p1", null))
        assertEquals(listOf("dune"), history.entries("p2", null))
        assertEquals(listOf("late night"), history.entries("p1", Space.TV))
        assertEquals(listOf("secret"), history.entries("p1", Space.ADULT))
        assertTrue(history.entries("p2", Space.TV).isEmpty())
        val isolatedQueries = setOf("late night", "secret", "manga x", "lofi")
        for (p in listOf("p1", "p2")) {
            for (s in listOf<Space?>(null) + Space.VIDEO) {
                assertTrue(history.entries(p, s).none { it in isolatedQueries })
            }
        }
        assertEquals(listOf("squid", "naruto"), history.observe("p1", Space.HOME).first())
    }

    @Test
    fun recentSearches_perProfile_dedupeMax50AndPersist() = runTest {
        val storage = InMemoryHistoryStorage()
        var t = 0L
        val history = SearchHistory(storage, clock = { t++ })
        repeat(60) { history.record("p1", "q$it", null) }
        history.record("p1", "Q10", null)
        val list = history.entries("p1", null)
        assertEquals(SearchHistory.MAX, list.size)
        assertEquals("Q10", list.first())
        assertEquals(1, list.count { it.equals("q10", ignoreCase = true) })

        history.delete("p1", "q59", null)
        assertTrue("q59" !in history.entries("p1", null))

        val reloaded = SearchHistory(storage)
        assertEquals(history.entries("p1", null), reloaded.entries("p1", null))
        reloaded.clear("p1", null)
        assertTrue(reloaded.entries("p1", null).isEmpty())
    }

    @Test
    fun pausedHistory_recordsNothing() = runTest {
        val history = SearchHistory(InMemoryHistoryStorage())
        history.paused = true
        history.record("p1", "hidden", null)
        assertTrue(history.entries("p1", null).isEmpty())
    }
}
