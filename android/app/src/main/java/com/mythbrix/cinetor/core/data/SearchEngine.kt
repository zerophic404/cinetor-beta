package com.mythbrix.cinetor.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

data class SearchResults(
    val query: String,
    val items: List<Item>,
    val remoteLoading: Boolean = false,
    val remoteError: Boolean = false,
) {
    companion object {
        val EMPTY = SearchResults("", emptyList())
    }
}

/**
 * Keystroke search: local index answers instantly (no debounce); global search additionally asks
 * [remote] (TMDB) after [debounceMs], cancelling stale calls via `flatMapLatest`. Remote results
 * are cached (LRU, [cache]), fed into the local index and appended after local hits (stable order,
 * deduped by id). Global results are always video-space only.
 */
class SearchEngine(
    private val local: SearchRepository,
    private val remote: RemoteSearch?,
    private val cache: QueryCache<List<Item>> = QueryCache(100),
    private val debounceMs: Long = 200,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun results(queries: Flow<String>, space: Space?): Flow<SearchResults> =
        queries.map { it.trim() }.distinctUntilChanged().flatMapLatest { q -> resultsFor(q, space) }

    fun resultsFor(query: String, space: Space?): Flow<SearchResults> = flow {
        val q = query.trim()
        if (q.isEmpty()) {
            emit(SearchResults(q, emptyList()))
            return@flow
        }
        val localItems = if (space == null) local.search(q) else local.searchInSpace(space, q)
        val r = remote
        if (r == null || space != null) {
            emit(SearchResults(q, localItems))
            return@flow
        }
        val key = SearchRepository.normalize(q)
        val cached = cache.get(key)
        if (cached != null) {
            emit(SearchResults(q, merge(localItems, cached)))
            return@flow
        }
        emit(SearchResults(q, localItems, remoteLoading = true))
        delay(debounceMs)
        val remoteItems = try {
            r.search(q).filter { it.space.isVideo }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(SearchResults(q, localItems, remoteError = true))
            return@flow
        }
        cache.put(key, remoteItems)
        local.addSeen(remoteItems)
        emit(SearchResults(q, merge(localItems, remoteItems)))
    }

    companion object {
        /** Local first (unchanged positions), then unseen remote items; video spaces only. */
        fun merge(local: List<Item>, remote: List<Item>): List<Item> {
            val seen = HashSet<String>()
            return (local + remote).filter { it.space.isVideo && seen.add(it.id) }
        }
    }
}
