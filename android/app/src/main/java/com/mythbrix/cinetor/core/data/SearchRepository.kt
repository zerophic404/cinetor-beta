package com.mythbrix.cinetor.core.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer

/**
 * Local title search over per-space [LocalSearchIndex]es. [search] covers ONLY video spaces
 * (Home/Anime/Drama) — isolated spaces (TV, Music, Comics, Adult) are reachable solely through
 * [searchInSpace]. Matching: lowercase + diacritics stripped; prefix matches (title or any word)
 * rank before substring matches; title and altTitle are both indexed.
 */
class SearchRepository(private val catalog: CatalogRepository) {

    private val mutex = Mutex()
    private val indexes = HashMap<Space, LocalSearchIndex>()

    suspend fun search(q: String): List<Item> = query(Space.VIDEO, q)

    suspend fun searchInSpace(space: Space, q: String): List<Item> = query(listOf(space), q)

    /** Feeds remote (TMDB) results into the local index so the next keystroke finds them instantly. */
    suspend fun addSeen(items: List<Item>) {
        items.filter { it.space.isVideo }.groupBy { it.space }.forEach { (space, list) -> index(space).addAll(list) }
    }

    private suspend fun query(spaces: List<Space>, q: String): List<Item> {
        val needle = normalize(q)
        if (needle.isEmpty()) return emptyList()
        val prefix = ArrayList<Item>()
        val substring = ArrayList<Item>()
        for (space in spaces) index(space).match(needle, prefix, substring)
        return prefix + substring
    }

    private suspend fun index(space: Space): LocalSearchIndex = mutex.withLock {
        indexes[space] ?: LocalSearchIndex(catalog.allItems(space)).also { indexes[space] = it }
    }

    companion object {
        private val MARKS = Regex("\\p{Mn}+")
        private val SPACES = Regex("\\s+")
        internal val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")

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

/**
 * In-memory index: every key and word is normalized ONCE at insert time, so a query is a tight
 * scan of precomputed strings (well under 1 ms per 1,000 items). Append-only, thread-safe
 * (copy-on-write entry list), deduped by item id.
 */
class LocalSearchIndex(items: Collection<Item> = emptyList()) {

    private class Entry(val item: Item, val keys: Array<String>, val words: Array<String>)

    private val ids = HashSet<String>()

    @Volatile
    private var entries: List<Entry> = emptyList()

    init {
        addAll(items)
    }

    val size: Int get() = entries.size

    fun addAll(items: Collection<Item>) {
        synchronized(this) {
            val fresh = items.filter { ids.add(it.id) }.map(::entry)
            if (fresh.isNotEmpty()) {
                entries = entries + fresh
            }
        }
    }

    /** Appends prefix matches to [prefix] and substring-only matches to [substring]. [needle] must be normalized. */
    fun match(needle: String, prefix: MutableList<Item>, substring: MutableList<Item>) {
        for (e in entries) {
            when (rank(e, needle)) {
                0 -> prefix += e.item
                1 -> substring += e.item
            }
        }
    }

    fun search(needle: String): List<Item> {
        val p = ArrayList<Item>()
        val s = ArrayList<Item>()
        match(needle, p, s)
        return p + s
    }

    private fun rank(e: Entry, needle: String): Int {
        for (k in e.keys) if (k.startsWith(needle)) return 0
        for (w in e.words) if (w.startsWith(needle)) return 0
        for (k in e.keys) if (k.contains(needle)) return 1
        return -1
    }

    private fun entry(item: Item): Entry {
        val keys = listOfNotNull(item.title, item.altTitle).map(SearchRepository::normalize)
        val words = keys.flatMap { it.split(SearchRepository.WORD_SPLIT) }.filter { it.isNotEmpty() }.distinct()
        return Entry(item, keys.toTypedArray(), words.toTypedArray())
    }
}
