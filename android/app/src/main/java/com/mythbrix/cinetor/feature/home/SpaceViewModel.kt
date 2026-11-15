package com.mythbrix.cinetor.feature.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.Category
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.core.data.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.mythbrix.cinetor.core.legal.UserErrors

/** One category tab. [type] narrows items (Music: Albums | Tracks over every music category). */
@Immutable
data class TabSpec(
    val id: String,
    val title: String,
    val categoryIds: List<String>,
    val type: ItemType? = null,
)

@Immutable
data class RowUi(
    val id: String,
    val title: String,
    val style: RowStyle,
    val items: List<Item>,
)

/** Unfiltered content of one tab (cached per tab). */
@Immutable
data class TabContent(val heroes: List<Item>, val rows: List<RowUi>)

@Immutable
data class SpaceUi(
    val tabs: List<TabSpec>,
    val selectedTab: Int,
    val heroes: List<Item>,
    val genres: List<String>,
    val selectedGenre: String?,
    val rows: List<RowUi>,
    /** A tab switch is fetching; previous content stays visible. */
    val tabLoading: Boolean = false,
) {
    /** Category tabs only make sense with 2+ entries (Anime/Adult are genre-led). */
    val showTabs: Boolean get() = tabs.size > 1
}

sealed interface SpaceUiState {
    data object Loading : SpaceUiState

    @Immutable
    data class Ready(val ui: SpaceUi) : SpaceUiState

    data class Error(val message: String) : SpaceUiState
}

/** Tab rules per space (Home Movies|Series, Drama K|C|J, TV Live|Sports, Comics x3, Music Albums|Tracks). */
object SpaceTabs {
    fun of(space: Space, categories: List<Category>): List<TabSpec> = when (space) {
        Space.MUSIC -> {
            val all = categories.map { it.id }
            listOf(
                TabSpec("music-albums", "Albums", all, ItemType.ALBUM),
                TabSpec("music-tracks", "Tracks", all, ItemType.TRACK),
            )
        }
        else -> categories.map { TabSpec(it.id, it.title, listOf(it.id)) }
    }
}

/** Pure genre filter: rows keep matching items (empty rows dropped); heroes fall back to top matches. */
fun applyGenre(content: TabContent, genre: String?): TabContent {
    if (genre == null) return content
    fun Item.matches() = genres.any { it.equals(genre, ignoreCase = true) }
    val rows = content.rows
        .map { r -> r.copy(items = r.items.filter { it.matches() }) }
        .filter { it.items.isNotEmpty() }
    val heroes = content.heroes.filter { it.matches() }
        .ifEmpty { rows.flatMap { it.items }.distinctBy { it.id }.take(5) }
    return TabContent(heroes, rows)
}

private suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/**
 * State logic of a space screen, independent of Android so tests can drive it with a TestScope.
 * Owned by [SpaceViewModel] (scope = viewModelScope).
 */
class SpaceStateHolder(
    private val space: Space,
    private val catalog: CatalogRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SpaceUiState>(SpaceUiState.Loading)
    val state: StateFlow<SpaceUiState> = _state.asStateFlow()

    private var categories: List<Category> = emptyList()
    private var tabs: List<TabSpec> = emptyList()
    private var genres: List<String> = emptyList()
    private val cache = HashMap<String, TabContent>()
    private var selected = 0
    private var genre: String? = null
    private var job: Job? = null

    /** Next page to fetch per row id; absent = page 2; -1 = no more. */
    private val nextPage = HashMap<String, Int>()
    private val pageJobs = HashMap<String, Job>()

    /** Row scrolled near its end: fetch the next gateway page and append (no-op for non-paged rows). */
    fun loadMore(rowId: String) {
        val paged = catalog as? com.mythbrix.cinetor.core.data.gateway.PagedCatalog ?: return
        val page = nextPage[rowId] ?: 2
        if (page < 0 || pageJobs[rowId]?.isActive == true) return
        val tabId = tabs.getOrNull(selected)?.id ?: return
        pageJobs[rowId] = scope.launch {
            val result = orNull { paged.rowPage(rowId, page) } ?: return@launch
            nextPage[rowId] = if (result.hasMore && result.items.isNotEmpty()) page + 1 else -1
            val content = cache[tabId] ?: return@launch
            val rows = content.rows.map { r ->
                if (r.id != rowId) r else r.copy(items = (r.items + result.items).distinctBy { it.id })
            }
            val updated = TabContent(content.heroes, rows)
            cache[tabId] = updated
            if (tabs.getOrNull(selected)?.id == tabId && _state.value is SpaceUiState.Ready) {
                _state.value = SpaceUiState.Ready(ui(updated))
            }
        }
    }

    /** Never surface raw exception text: OkHttp / Media3 messages embed the request URL (hardening §2). */
    private fun friendly(e: Exception): String = UserErrors.safe(e, UserErrors.CATALOG)

    fun load() {
        job?.cancel()
        job = scope.launch {
            _state.value = SpaceUiState.Loading
            _state.value = try {
                categories = catalog.categories(space)
                tabs = SpaceTabs.of(space, categories)
                if (tabs.isEmpty()) throw IllegalStateException("No content in ${space.displayName}")
                genres = orNull { catalog.genres(space) }.orEmpty()
                selected = selected.coerceIn(0, tabs.lastIndex)
                val content = loadTab(tabs[selected])
                SpaceUiState.Ready(ui(content))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SpaceUiState.Error(friendly(e))
            }
        }
    }

    fun selectTab(index: Int) {
        if (index == selected || index !in tabs.indices) return
        selected = index
        genre = null
        val tab = tabs[index]
        val cached = cache[tab.id]
        job?.cancel()
        if (cached != null) {
            _state.value = SpaceUiState.Ready(ui(cached))
            return
        }
        val current = (_state.value as? SpaceUiState.Ready)?.ui
        if (current != null) {
            _state.value = SpaceUiState.Ready(current.copy(selectedTab = index, selectedGenre = null, tabLoading = true))
        }
        job = scope.launch {
            val content = try {
                loadTab(tab)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SpaceUiState.Error(friendly(e))
                return@launch
            }
            if (selected == index) _state.value = SpaceUiState.Ready(ui(content))
        }
    }

    /** Tap a chip: select it; tap the selected chip again (or null) to clear. */
    fun selectGenre(g: String?) {
        genre = if (g == null || g == genre) null else g
        val content = tabs.getOrNull(selected)?.let { cache[it.id] } ?: return
        _state.value = SpaceUiState.Ready(ui(content))
    }

    private fun ui(content: TabContent): SpaceUi {
        val filtered = applyGenre(content, genre)
        return SpaceUi(
            tabs = tabs,
            selectedTab = selected,
            heroes = filtered.heroes,
            genres = genres,
            selectedGenre = genre,
            rows = filtered.rows,
        )
    }

    private suspend fun loadTab(tab: TabSpec): TabContent = coroutineScope {
        cache[tab.id]?.let { return@coroutineScope it }
        fun Item.typeOk() = tab.type == null || type == tab.type
        val cats = categories.filter { it.id in tab.categoryIds }
        val rows = cats.flatMap { it.rows }.distinctBy { it.id }.map { r ->
            async {
                val items = orNull { catalog.row(r.id) }.orEmpty().filter { it.typeOk() }.distinctBy { it.id }
                RowUi(r.id, r.title, r.style, items)
            }
        }.awaitAll().filter { it.items.isNotEmpty() }
        if (rows.isEmpty()) throw IllegalStateException("Nothing to show yet")
        val known = HashMap<String, Item>()
        rows.forEach { r -> r.items.forEach { known[it.id] = it } }
        val heroes = cats.flatMap { it.heroIds }.distinct()
            .map { id -> async { known[id] ?: orNull { catalog.item(id) } } }
            .awaitAll()
            .filterNotNull()
            .filter { it.typeOk() }
            .ifEmpty { rows.first().items.take(5) }
            .take(MAX_HEROES)
        TabContent(heroes, rows).also { cache[tab.id] = it }
    }

    companion object {
        const val MAX_HEROES = 8
    }
}

class SpaceViewModel(space: Space, catalog: CatalogRepository) : ViewModel() {
    private val holder = SpaceStateHolder(space, catalog, viewModelScope)
    val state: StateFlow<SpaceUiState> = holder.state

    init {
        holder.load()
    }

    fun load() = holder.load()
    fun selectTab(index: Int) = holder.selectTab(index)
    fun selectGenre(genre: String?) = holder.selectGenre(genre)
    fun loadMore(rowId: String) = holder.loadMore(rowId)
}
