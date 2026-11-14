package com.mythbrix.cinetor.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythbrix.cinetor.core.data.SearchEngine
import com.mythbrix.cinetor.core.data.SearchHistory
import com.mythbrix.cinetor.core.data.SearchResults
import com.mythbrix.cinetor.core.data.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [space] = null → global search (video spaces); otherwise in-space search of that space. */
class SearchViewModel(
    private val engine: SearchEngine,
    private val history: SearchHistory,
    private val space: Space?,
    private val profileId: String = DEFAULT_PROFILE,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val results: StateFlow<SearchResults> = engine.results(_query, space)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults.EMPTY)

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

    /** Records the current (or given) query in this profile's history bucket. */
    fun submit(q: String = _query.value) {
        if (q.isBlank()) return
        viewModelScope.launch { history.record(profileId, q, space) }
    }

    fun removeRecent(q: String) {
        viewModelScope.launch { history.delete(profileId, q, space) }
    }

    fun clearRecent() {
        viewModelScope.launch { history.clear(profileId, space) }
    }

    companion object {
        /** Until profiles land (Task 5.1). */
        const val DEFAULT_PROFILE = "default"
    }
}
