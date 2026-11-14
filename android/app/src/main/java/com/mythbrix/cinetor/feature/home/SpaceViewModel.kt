package com.mythbrix.cinetor.feature.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class SectionUi(
    val categoryId: String,
    val title: String,
    val rowId: String,
    val rowTitle: String,
    val items: List<Item>,
)

sealed interface SpaceUiState {
    data object Loading : SpaceUiState
    @Immutable
    data class Ready(val sections: List<SectionUi>, val heroColor: String?) : SpaceUiState
    data class Error(val message: String) : SpaceUiState
}

/** Phase-1 placeholder: category titles + first row of each category. */
class SpaceViewModel(private val space: Space, private val catalog: CatalogRepository) : ViewModel() {
    private val _state = MutableStateFlow<SpaceUiState>(SpaceUiState.Loading)
    val state: StateFlow<SpaceUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = SpaceUiState.Loading
            _state.value = try {
                loadSections(space, catalog)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SpaceUiState.Error(e.message ?: "Failed to load")
            }
        }
    }

    companion object {
        suspend fun loadSections(space: Space, catalog: CatalogRepository): SpaceUiState.Ready = coroutineScope {
            val categories = catalog.categories(space)
            val hero = async { categories.firstOrNull()?.heroIds?.firstOrNull()?.let { catalog.item(it) } }
            val sections = categories.map { c ->
                async {
                    val row = c.rows.first()
                    SectionUi(c.id, c.title, row.id, row.title, catalog.row(row.id))
                }
            }.awaitAll()
            SpaceUiState.Ready(sections, hero.await()?.dominantColor)
        }
    }
}
