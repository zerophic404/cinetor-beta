package com.mythbrix.cinetor.feature.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.player.PlaybackPreloader
import com.mythbrix.cinetor.core.player.Readiness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class DetailsUi(
    val item: Item,
    val moreLikeThis: List<Item> = emptyList(),
    val moreLoading: Boolean = true,
)

sealed interface DetailsUiState {
    /** [peek] = card summary (title/poster) shown in the header while the full item resolves. */
    data class Loading(val peek: Item?) : DetailsUiState

    @Immutable
    data class Ready(val ui: DetailsUi) : DetailsUiState

    data class Error(val id: String, val message: String) : DetailsUiState
}

/** Same space only (isolation rule), best genre overlap first, never the item itself. */
fun moreLikeThis(item: Item, candidates: List<Item>, limit: Int = 12): List<Item> =
    candidates
        .asSequence()
        .filter { it.space == item.space && it.id != item.id }
        .distinctBy { it.id }
        .sortedByDescending { c -> c.genres.count { it in item.genres } }
        .take(limit)
        .toList()

private suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/**
 * Details logic (Android-free, TestScope-drivable). [open] starts the playback warm-up right away
 * (from the card summary when known) in parallel with loading the full item; [leave] cancels both.
 * Opening another id first leaves the previous one (rapid switch → previous warm cancelled).
 */
class DetailsStateHolder(
    private val catalog: CatalogRepository,
    private val preloader: PlaybackPreloader,
    private val scope: CoroutineScope,
    private val peek: (String) -> Item? = { null },
) {
    private val _state = MutableStateFlow<DetailsUiState>(DetailsUiState.Loading(null))
    val state: StateFlow<DetailsUiState> = _state.asStateFlow()
    val readiness: StateFlow<Map<String, Readiness>> get() = preloader.readiness

    var currentId: String? = null
        private set
    private var warmedId: String? = null
    private var job: Job? = null

    fun open(id: String) {
        if (id == currentId) return
        leave()
        currentId = id
        val summary = peek(id)
        summary?.let { warm(it) }
        _state.value = DetailsUiState.Loading(summary)
        job = scope.launch {
            val item = try {
                catalog.item(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = DetailsUiState.Error(id, e.message ?: "Title not found")
                return@launch
            }
            if (warmedId != id) warm(item)
            _state.value = DetailsUiState.Ready(DetailsUi(item))
            val more = moreLikeThis(item, candidates(item))
            _state.value = DetailsUiState.Ready(DetailsUi(item, more, moreLoading = false))
        }
    }

    /** Screen left (back / destroyed): stop loading, cancel the warm-up. */
    fun leave() {
        job?.cancel()
        job = null
        stopWarm()
        currentId = null
    }

    /** Screen hidden but kept on the back stack (e.g. opened another title): free the player. */
    fun pauseWarm() = stopWarm()

    /** Back on screen: warm again (cheap if the preloader still has it). */
    fun resumeWarm() {
        if (warmedId != null) return
        val item = (state.value as? DetailsUiState.Ready)?.ui?.item
            ?: (state.value as? DetailsUiState.Loading)?.peek
            ?: return
        if (item.id == currentId) warm(item)
    }

    private fun warm(item: Item) {
        warmedId = item.id
        preloader.warm(item)
    }

    private fun stopWarm() {
        warmedId?.let { preloader.cancel(it) }
        warmedId = null
    }

    private suspend fun candidates(item: Item): List<Item> {
        val byGenre = item.genres.firstOrNull()
            ?.let { g -> orNull { catalog.byGenre(item.space, g) } }
            .orEmpty()
        if (byGenre.size >= 8) return byGenre
        return byGenre + orNull { catalog.allItems(item.space) }.orEmpty()
    }
}

class DetailsViewModel(
    id: String,
    catalog: CatalogRepository,
    preloader: PlaybackPreloader,
    peek: (String) -> Item?,
) : ViewModel() {
    val holder = DetailsStateHolder(catalog, preloader, viewModelScope, peek)

    init {
        holder.open(id)
    }

    override fun onCleared() {
        holder.leave()
    }
}
