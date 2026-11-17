package com.mythbrix.cinetor.feature.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.core.player.PlaybackPreloader
import com.mythbrix.cinetor.core.player.Readiness
import com.mythbrix.cinetor.core.player.SourceResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val DUB_TOKENS = listOf(
    "Dual Audio" to Regex("""\b(dual\s*audio|multi\s*audio)\b""", RegexOption.IGNORE_CASE),
    "Hindi" to Regex("""(\[hindi]|\bhindi\b)""", RegexOption.IGNORE_CASE),
    "Bengali" to Regex("""(\[bengali]|\bbengali\b|\bbangla\b)""", RegexOption.IGNORE_CASE),
    "Tamil" to Regex("""(\[tamil]|\btamil\b)""", RegexOption.IGNORE_CASE),
    "Telugu" to Regex("""(\[telugu]|\btelugu\b)""", RegexOption.IGNORE_CASE),
    "Malayalam" to Regex("""(\[malayalam]|\bmalayalam\b)""", RegexOption.IGNORE_CASE),
    "English" to Regex("""(\[english]|\benglish\s*dub\b)""", RegexOption.IGNORE_CASE),
    "Japanese" to Regex("""(\[japanese]|\bjapanese\b)""", RegexOption.IGNORE_CASE),
    "Korean" to Regex("""(\[korean]|\bkorean\b)""", RegexOption.IGNORE_CASE),
)

private val GENRE_DUB_NAMES = setOf(
    "hindi", "bengali", "bangla", "tamil", "telugu", "malayalam", "english", "dual audio", "multi audio",
)

/** Extracts clean dub / language pills from title, altTitle, and genre dub tags. */
fun extractDubBadges(item: Item): List<String> {
    val haystack = buildString {
        append(item.altTitle.orEmpty())
        append(' ')
        append(item.title)
        append(' ')
        item.genres.forEach { g ->
            val trimmed = g.trim()
            if (trimmed.startsWith("[") ||
                trimmed.lowercase() in GENRE_DUB_NAMES ||
                trimmed.contains("dub", ignoreCase = true) ||
                trimmed.contains("audio", ignoreCase = true)
            ) {
                append('[').append(trimmed.trim('[', ']')).append("] ")
            }
        }
    }
    return DUB_TOKENS.mapNotNull { (label, rx) -> label.takeIf { rx.containsMatchIn(haystack) } }.take(4)
}

/** Genres stripped of dub/language tags (`Hindi`, `[Hindi]`, etc.) so they don't duplicate dub pills. */
fun displayGenres(item: Item): List<String> =
    item.genres.filterNot { g ->
        val trimmed = g.trim()
        (trimmed.startsWith("[") && trimmed.endsWith("]")) || trimmed.lowercase() in GENRE_DUB_NAMES
    }

/** True when [item] or [extraSources] contains a real >=2000p / 4K UHD stream. */
fun hasUltraHdSource(item: Item, extraSources: List<Source> = emptyList()): Boolean {
    val allSources = item.sources + item.seasons.flatMap { s -> s.episodes.flatMap { it.sources } } + extraSources
    if (allSources.any { (it.height ?: 0) >= 2000 || it.label?.contains("4K", ignoreCase = true) == true || it.label?.contains("2160", ignoreCase = true) == true }) {
        return true
    }
    return Regex("""\b(4k|2160p|uhd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(item.title)
}

/** Inline muted backdrop preview eligibility: strictly off on LOW tier, Data Saver, Reduce Motion, TV, or active playback. */
fun canAutoplayInlinePreview(
    tier: PerformanceTier,
    dataSaver: Boolean,
    bannerAutoplay: Boolean,
    reduceMotion: Boolean,
    isTv: Boolean,
    playerActive: Boolean,
): Boolean = tier != PerformanceTier.LOW && !dataSaver && bannerAutoplay && !reduceMotion && !isTv && !playerActive

/** Extracts a safe 11-char YouTube video ID from embed/watch/short/yt:// URLs, or null for regular streams. */
fun extractPreviewYouTubeId(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.startsWith("yt://", ignoreCase = true)) {
        return trimmed.substring(5).substringBefore('?').substringBefore('&').filter { it.isLetterOrDigit() || it == '-' || it == '_' }.takeIf { it.length in 6..20 }
    }
    val patterns = listOf(
        Regex("""youtube(?:-nocookie)?\.com/embed/([A-Za-z0-9_-]{6,20})""", RegexOption.IGNORE_CASE),
        Regex("""youtube\.com/watch\?[^#]*v=([A-Za-z0-9_-]{6,20})""", RegexOption.IGNORE_CASE),
        Regex("""youtu\.be/([A-Za-z0-9_-]{6,20})""", RegexOption.IGNORE_CASE),
    )
    return patterns.firstNotNullOfOrNull { rx -> rx.find(trimmed)?.groupValues?.get(1) }
}

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
                _state.value = DetailsUiState.Error(id, com.mythbrix.cinetor.core.legal.UserErrors.safe(e, com.mythbrix.cinetor.core.legal.UserErrors.NOT_FOUND))
                return@launch
            }
            // Re-warm with the full item when the card summary wasn't playable (no sources yet).
            val summaryUnplayable = summary != null && SourceResolver.request(summary, null) == null
            // Gateway series: the card summary has no seasons (warm skipped) → warm the full item (stream resolved in the preloader).
            val gainedSeasons = item.seasons.isNotEmpty() && summary?.seasons.isNullOrEmpty()
            if (warmedId != id || (summaryUnplayable && (SourceResolver.request(item, null) != null || gainedSeasons))) warm(item)
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
