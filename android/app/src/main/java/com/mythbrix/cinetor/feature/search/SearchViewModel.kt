package com.mythbrix.cinetor.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.QueryCache
import com.mythbrix.cinetor.core.data.RemoteSearch
import com.mythbrix.cinetor.core.data.SearchEngine
import com.mythbrix.cinetor.core.data.SearchHistory
import com.mythbrix.cinetor.core.data.SearchRepository
import com.mythbrix.cinetor.core.data.SearchResults
import com.mythbrix.cinetor.core.data.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val PUNCT_STRIP_REGEX = Regex("""[^\p{L}\p{N}\s]+""")
private val MULTI_SPACE_REGEX = Regex("""\s+""")

/**
 * Merges local index hits and remote gateway/TMDB results without dropping any `gw:*` or `gw:tmdb-*`
 * items, keeping exact/prefix matches first and deduplicating strictly by `Item.id`.
 */
fun mergeResilientSearchResults(
    query: String,
    local: List<Item>,
    remote: List<Item>,
    space: Space?,
): List<Item> {
    val needle = SearchRepository.normalize(query)
    val allowed: (Item) -> Boolean = { item ->
        if (space == null) item.space.isVideo else item.space == space
    }
    val seen = HashSet<String>()
    val combined = (local + remote).filter { allowed(it) && seen.add(it.id) }
    if (needle.isEmpty()) return combined
    return combined.sortedBy { item ->
        val normTitle = SearchRepository.normalize(item.title)
        val normAlt = item.altTitle?.let(SearchRepository::normalize).orEmpty()
        when {
            normTitle == needle || normAlt == needle -> 0
            normTitle.startsWith(needle) || normAlt.startsWith(needle) -> 1
            normTitle.contains(needle) || normAlt.contains(needle) -> 2
            else -> 3
        }
    }
}

/**
 * Search state holder:
 * - Minimum 2 characters (`MIN_REMOTE_CHARS = 2`) before firing remote catalog queries.
 * - Debounced `280ms` (`REMOTE_DEBOUNCE_MS = 280L`) via `flatMapLatest` so stale keystrokes cancel cleanly.
 * - Emits `remoteLoading = true` immediately for `>= 2`-char queries so `"No titles found"` never flashes prematurely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val engine: SearchEngine,
    private val history: SearchHistory,
    private val space: Space?,
    private val profileId: String = DEFAULT_PROFILE,
    private val localRepo: SearchRepository? = null,
    private val remoteSearch: RemoteSearch? = null,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val retryTick = MutableStateFlow(0)
    private val remoteCache = QueryCache<List<Item>>(100)

    val results: StateFlow<SearchResults> = if (localRepo != null) {
        combine(_query, retryTick) { q, tick -> q.trim() to tick }
            .distinctUntilChanged()
            .flatMapLatest { (trimmed, _) -> resilientResultsFor(trimmed) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults.EMPTY)
    } else {
        engine.results(_query, space)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults.EMPTY)
    }

    val recent: StateFlow<List<String>> = history.observe(profileId, space)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** History prefix matches while typing. */
    val suggestions: StateFlow<List<String>> = combine(_query, recent) { q, r ->
        val n = q.trim()
        if (n.isEmpty()) emptyList() else r.filter { it.startsWith(n, ignoreCase = true) && !it.equals(n, true) }.take(5)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(q: String) {
        _query.value = q
    }

    /** Retries the current query when a previous network request failed. */
    fun retry() {
        retryTick.value += 1
    }

    /** Records the current (or given) query in this profile's history bucket. */
    fun submit(q: String = _query.value) {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) return
        if (results.value.remoteError) retryTick.value += 1
        viewModelScope.launch { history.record(profileId, trimmed, space) }
    }

    fun removeRecent(q: String) {
        viewModelScope.launch { history.delete(profileId, q, space) }
    }

    fun clearRecent() {
        viewModelScope.launch { history.clear(profileId, space) }
    }

    private fun resilientResultsFor(trimmed: String): Flow<SearchResults> = flow {
        if (trimmed.isEmpty()) {
            emit(SearchResults("", emptyList(), remoteLoading = false))
            return@flow
        }
        val repo = localRepo ?: return@flow
        val remote = remoteSearch
        val canQueryRemote = remote != null && trimmed.length >= MIN_REMOTE_CHARS

        // Immediately signal loading before any suspend work so UI never flashes premature empty state.
        if (canQueryRemote) {
            emit(SearchResults(trimmed, emptyList(), remoteLoading = true))
        }

        val localItems = try {
            if (space == null) repo.search(trimmed) else repo.searchInSpace(space, trimmed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        if (!canQueryRemote || remote == null) {
            emit(SearchResults(trimmed, localItems, remoteLoading = false))
            return@flow
        }

        val cacheKey = "${space?.name ?: "VIDEO"}:${SearchRepository.normalize(trimmed)}"
        val cached = remoteCache.get(cacheKey)
        if (!cached.isNullOrEmpty()) {
            emit(
                SearchResults(
                    query = trimmed,
                    items = mergeResilientSearchResults(trimmed, localItems, cached, space),
                    remoteLoading = false,
                ),
            )
            return@flow
        }

        emit(SearchResults(trimmed, localItems, remoteLoading = true))
        delay(REMOTE_DEBOUNCE_MS)

        val remoteItems = try {
            val primary = remote.search(trimmed)
            if (primary.isNotEmpty()) {
                primary
            } else {
                val cleaned = trimmed.replace(PUNCT_STRIP_REGEX, " ").replace(MULTI_SPACE_REGEX, " ").trim()
                if (cleaned.length >= MIN_REMOTE_CHARS && !cleaned.equals(trimmed, ignoreCase = true)) {
                    remote.search(cleaned)
                } else {
                    emptyList()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emit(SearchResults(trimmed, localItems, remoteLoading = false, remoteError = true))
            return@flow
        }

        val scopedRemote = remoteItems.filter { if (space == null) it.space.isVideo else it.space == space }
        if (scopedRemote.isNotEmpty()) {
            remoteCache.put(cacheKey, scopedRemote)
            runCatching { repo.addSeen(scopedRemote.filter { it.space.isVideo }) }
        }
        val merged = mergeResilientSearchResults(trimmed, localItems, scopedRemote, space)
        emit(SearchResults(trimmed, merged, remoteLoading = false))
    }

    companion object {
        const val DEFAULT_PROFILE = "default"
        const val MIN_REMOTE_CHARS = 2
        const val REMOTE_DEBOUNCE_MS = 280L
    }
}
