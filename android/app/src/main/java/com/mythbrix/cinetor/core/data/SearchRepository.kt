package com.mythbrix.cinetor.core.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer

/**
 * Local title search. [search] covers ONLY video spaces (Home/Anime/Drama) — isolated spaces
 * (TV, Music, Comics, Adult) are reachable solely through [searchInSpace].
 * Matching: lowercase + diacritics stripped; prefix matches (title or any word) rank before
 * substring matches; title and altTitle are both indexed.
 */
class SearchRepository(private val catalog: CatalogRepository) {

    private class Entry(val item: Item, val keys: List<String>)

    private val mutex = Mutex()
    private val indexes = HashMap<Space, List<Entry>>()

    suspend fun search(q: String): List<Item> = query(Space.VIDEO, q)

    suspend fun searchInSpace(space: Space, q: String): List<Item> = query(listOf(space), q)

    private suspend fun query(spaces: List<Space>, q: String): List<Item> {
        val needle = normalize(q)
        if (needle.isEmpty()) return emptyList()
        val prefix = ArrayList<Item>()
        val substring = ArrayList<Item>()
        for (space in spaces) {
            for (entry in index(space)) {
                when (matchRank(entry.keys, needle)) {
                    0 -> prefix += entry.item
                    1 -> substring += entry.item
                }
            }
        }
        return prefix + substring
    }

    private suspend fun index(space: Space): List<Entry> = mutex.withLock {
        indexes[space] ?: catalog.allItems(space)
            .map { item -> Entry(item, listOfNotNull(item.title, item.altTitle).map(::normalize)) }
            .also { indexes[space] = it }
    }

    companion object {
        private val MARKS = Regex("\\p{Mn}+")
        private val SPACES = Regex("\\s+")
        private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")

        /** Lowercase, strip diacritics, collapse whitespace. */
        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(MARKS, "")
                .lowercase()
                .trim()
                .replace(SPACES, " ")

        /** 0 = prefix (whole key or a word), 1 = substring, -1 = no match. */
        internal fun matchRank(keys: List<String>, needle: String): Int {
            var best = -1
            for (key in keys) {
                if (key.startsWith(needle) || key.split(WORD_SPLIT).any { it.startsWith(needle) }) return 0
                if (key.contains(needle)) best = 1
            }
            return best
        }
    }
}
