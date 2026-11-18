package com.mythbrix.cinetor.feature.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Season
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.legal.UserErrors
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

/** Extracts clean dub / language pills from Item.dubs, title, altTitle, and genre dub tags. */
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
    val detected = DUB_TOKENS.mapNotNull { (label, rx) -> label.takeIf { rx.containsMatchIn(haystack) } }
    val explicitDubs = item.dubs
        .map { it.languageName.trim() }
        .filter { it.isNotEmpty() && !it.equals("Original", ignoreCase = true) }
    val merged = LinkedHashSet<String>()
    detected.forEach { merged += it }
    explicitDubs.forEach { dub ->
        val alreadyCovered = (dub.equals("Bangla", ignoreCase = true) && merged.any { it.equals("Bengali", ignoreCase = true) }) ||
            (dub.equals("Bengali", ignoreCase = true) && merged.any { it.equals("Bangla", ignoreCase = true) }) ||
            merged.any { it.equals(dub, ignoreCase = true) }
        if (!alreadyCovered) merged += dub
    }
    return merged.take(4)
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

/** Synthesizes Season 1 Episode 1 when a SERIES card/fallback item has no season metadata yet. */
fun ensurePlayableItem(item: Item): Item {
    if (item.type != ItemType.SERIES || item.seasons.isNotEmpty()) return item
    val fallbackEpisode = Episode(
        id = "s1e1",
        n = 1,
        title = "Episode 1",
        durationSec = item.durationSec ?: 0,
        still = item.backdrop.ifBlank { item.poster },
        sources = emptyList(),
    )
    return item.copy(
        seasons = listOf(
            Season(
                n = 1,
                title = "Season 1",
                episodes = listOf(fallbackEpisode),
            ),
        ),
    )
}

/** Resolves `(seasonNumber, episodeNumber)` for inline playback (`(null, null)` for movies). */
fun resolvePlayTarget(item: Item, resumeEpisodeId: String? = null): Pair<Int?, Int?> {
    if (item.type != ItemType.SERIES && item.seasons.isEmpty()) return null to null
    if (!resumeEpisodeId.isNullOrBlank()) {
        for (s in item.seasons) {
            val ep = s.episodes.firstOrNull { it.id == resumeEpisodeId }
            if (ep != null) return s.n to ep.n
        }
        val parsed = Regex("""^s0*(\d+)e0*(\d+)$""", RegexOption.IGNORE_CASE).find(resumeEpisodeId)
        if (parsed != null) {
            val se = parsed.groupValues[1].toIntOrNull()
            val ep = parsed.groupValues[2].toIntOrNull()
            if (se != null && ep != null) return se to ep
        }
    }
    val firstSeason = item.seasons.firstOrNull()
    val firstEp = firstSeason?.episodes?.firstOrNull()
    return (firstSeason?.n ?: 1) to (firstEp?.n ?: 1)
}

/** Extracts human search keywords from a raw item ID/slug when peek metadata is absent. */
internal fun cleanSearchQueryFromId(rawId: String): String {
    val stripped = rawId
        .removePrefix("ad-gw:")
        .removePrefix("gw:tmdb-tv-")
        .removePrefix("gw:tmdb-movie-")
        .removePrefix("gw:")
        .removePrefix("tmdb:tv:")
        .removePrefix("tmdb:movie:")
        .removePrefix("tmdb-tv-")
        .removePrefix("tmdb-movie-")
    val words = stripped.replace(Regex("""[-_:./]+"""), " ").trim()
    return if (words.any { it.isLetter() }) words else ""
}

@Immutable
data class Person(
    val name: String,
    val role: String? = null,
)

@Immutable
data class CastSheetState(
    val selectedCast: Person? = null,
    val castWorksLoading: Boolean = false,
    val castWorks: List<Item> = emptyList(),
)

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

/**
 * How long Details keeps its shimmer skeleton up after a tap while the stream source resolves,
 * so the page lands with Play already green. After this cap the page shows anyway and the dot
 * turns green on its own when the source arrives; buffering continues behind the page.
 */
const val STREAM_GATE_MS = 1_200L

/** A source is in hand (buffering may still be running): Play can show its green dot. */
fun Readiness?.sourceReady(): Boolean = this == Readiness.READY || this == Readiness.BUFFERING

private suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/**
 * Details logic (Android-free, TestScope-drivable).
 * - Immediately kicks off [preloader.warm] concurrently with `catalog.item(id)` and keeps the shimmer
 *   skeleton up until the stream is READY (bounded by `streamGateMs`), so Play is green on arrival (synthesizing S1:E1 when `peek` is a `SERIES` summary so gateway stream
 *   resolution runs in parallel with detail fetching!).
 * - Eliminates `"Title not found"` whenever `peek(id)` exists or a fallback search resolves the title.
 * - Loads actor filmography (`castWorks`) via `catalog.castWorks(person.name)`.
 */
class DetailsStateHolder(
    private val catalog: CatalogRepository,
    private val preloader: PlaybackPreloader,
    private val scope: CoroutineScope,
    private val peek: (String) -> Item? = { null },
    private val resumeEpisodeOf: (String) -> String? = { null },
    private val searchFallback: (suspend (String) -> List<Item>)? = null,
    private val onItemsDiscovered: (List<Item>) -> Unit = {},
    /** Keep the skeleton until the stream is READY/ERROR, at most this long (0 = don't wait). */
    private val streamGateMs: Long = 0L,
) {
    private val _state = MutableStateFlow<DetailsUiState>(DetailsUiState.Loading(null))
    val state: StateFlow<DetailsUiState> = _state.asStateFlow()
    val readiness: StateFlow<Map<String, Readiness>> get() = preloader.readiness

    private val _castState = MutableStateFlow(CastSheetState())
    val castState: StateFlow<CastSheetState> = _castState.asStateFlow()

    var currentId: String? = null
        private set
    private var warmedId: String? = null
    private var warmedEpisodeId: String? = null
    private var job: Job? = null
    private var castJob: Job? = null

    fun open(id: String) {
        if (id == currentId) return
        leave()
        currentId = id
        val summary = peek(id)
        if (summary != null) {
            val warmCandidate = ensurePlayableItem(summary)
            val initialEpId = resumeEpisodeOf(id)
                ?: if (warmCandidate.type == ItemType.SERIES) warmCandidate.seasons.firstOrNull()?.episodes?.firstOrNull()?.id else null
            warm(warmCandidate, initialEpId)
        }
        _state.value = DetailsUiState.Loading(summary)
        job = scope.launch {
            val fetched = orNull { catalog.item(id) }
            val resolved: Item? = when {
                fetched != null -> ensurePlayableItem(fetched)
                summary != null -> ensurePlayableItem(summary)
                else -> {
                    val words = cleanSearchQueryFromId(id)
                    val hit = if (words.isNotBlank()) {
                        orNull { searchFallback?.invoke(words).orEmpty().ifEmpty { catalog.castWorks(words) } }?.firstOrNull()
                    } else {
                        null
                    }
                    hit?.let { m -> ensurePlayableItem(orNull { catalog.item(m.id) } ?: m) }
                }
            }
            if (resolved == null) {
                _state.value = DetailsUiState.Error(id, UserErrors.NOT_FOUND)
                return@launch
            }
            onItemsDiscovered(listOf(resolved))

            val targetEpId = resumeEpisodeOf(resolved.id)
                ?: if (resolved.type == ItemType.SERIES) resolved.seasons.firstOrNull()?.episodes?.firstOrNull()?.id else null
            val summaryUnplayable = summary != null && SourceResolver.request(summary, null) == null
            val preloaderFailed = readiness.value[resolved.id] == Readiness.ERROR
            if (warmedId != resolved.id ||
                targetEpId != warmedEpisodeId ||
                preloaderFailed ||
                (summaryUnplayable && SourceResolver.request(resolved, targetEpId) != null)
            ) {
                warm(resolved, targetEpId)
            }
            awaitStream(resolved.id)

            _state.value = DetailsUiState.Ready(DetailsUi(resolved))
            val more = moreLikeThis(resolved, candidates(resolved))
            if (more.isNotEmpty()) onItemsDiscovered(more)
            _state.value = DetailsUiState.Ready(DetailsUi(resolved, more, moreLoading = false))
        }
    }

    fun openCast(person: Person) {
        val cleanName = person.name.trim()
        if (cleanName.isEmpty()) return
        castJob?.cancel()
        val normalizedPerson = person.copy(name = cleanName)
        _castState.value = CastSheetState(selectedCast = normalizedPerson, castWorksLoading = true, castWorks = emptyList())
        castJob = scope.launch {
            val primary = orNull { catalog.castWorks(cleanName) }.orEmpty()
            val works = (if (primary.isNotEmpty()) primary else orNull { searchFallback?.invoke(cleanName) }.orEmpty())
                .distinctBy { it.id }
            if (works.isNotEmpty()) onItemsDiscovered(works)
            _castState.value = CastSheetState(
                selectedCast = normalizedPerson,
                castWorksLoading = false,
                castWorks = works,
            )
        }
    }

    fun openCast(personName: String) = openCast(Person(name = personName))

    fun closeCast() {
        castJob?.cancel()
        castJob = null
        _castState.value = CastSheetState()
    }

    /** Screen left (back / destroyed): stop loading, cancel the warm-up. */
    fun leave() {
        job?.cancel()
        job = null
        closeCast()
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
        if (item.id == currentId) {
            val warmCandidate = ensurePlayableItem(item)
            val targetEpId = resumeEpisodeOf(warmCandidate.id)
                ?: if (warmCandidate.type == ItemType.SERIES) warmCandidate.seasons.firstOrNull()?.episodes?.firstOrNull()?.id else null
            warm(warmCandidate, targetEpId)
        }
    }

    /** Source first, metadata second: hold the skeleton until the warm-up settles (bounded). */
    private suspend fun awaitStream(id: String) {
        if (streamGateMs <= 0L) return
        withTimeoutOrNull(streamGateMs) {
            readiness.first { it[id].sourceReady() || it[id] == Readiness.ERROR }
        }
    }

    /**
     * Details is showing but the preloader holds no state for its title (e.g. a search result whose
     * first warm was replaced): warm again so Play gets its green dot.
     */
    fun ensureWarm() {
        val item = (state.value as? DetailsUiState.Ready)?.ui?.item ?: return
        if (item.id != currentId && warmedId == null) return
        if (readiness.value[item.id] != null) return
        stopWarm()
        val targetEpId = resumeEpisodeOf(item.id)
            ?: if (item.type == ItemType.SERIES) item.seasons.firstOrNull()?.episodes?.firstOrNull()?.id else null
        warm(item, targetEpId)
    }

    private fun warm(item: Item, episodeId: String? = null) {
        warmedId = item.id
        warmedEpisodeId = episodeId
        preloader.warm(item, episodeId)
    }

    private fun stopWarm() {
        warmedId?.let { preloader.cancel(it) }
        warmedId = null
        warmedEpisodeId = null
    }

    private suspend fun candidates(item: Item): List<Item> {
        val cleanGenres = displayGenres(item)
        val byGenre = (cleanGenres.firstOrNull() ?: item.genres.firstOrNull())
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
    resumeEpisodeOf: (String) -> String? = { null },
    searchFallback: (suspend (String) -> List<Item>)? = null,
    onItemsDiscovered: (List<Item>) -> Unit = {},
) : ViewModel() {
    val holder = DetailsStateHolder(
        catalog = catalog,
        preloader = preloader,
        scope = viewModelScope,
        peek = peek,
        resumeEpisodeOf = resumeEpisodeOf,
        searchFallback = searchFallback,
        onItemsDiscovered = onItemsDiscovered,
        streamGateMs = STREAM_GATE_MS,
    )

    val castState: StateFlow<CastSheetState> get() = holder.castState
    val selectedCast: Person? get() = holder.castState.value.selectedCast
    val castWorksLoading: Boolean get() = holder.castState.value.castWorksLoading
    val castWorks: List<Item> get() = holder.castState.value.castWorks

    init {
        holder.open(id)
    }

    fun openCast(person: Person) = holder.openCast(person)

    fun openCast(personName: String) = holder.openCast(personName)

    fun closeCast() = holder.closeCast()

    override fun onCleared() {
        holder.leave()
    }
}
