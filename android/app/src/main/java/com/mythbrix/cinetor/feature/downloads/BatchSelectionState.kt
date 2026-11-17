package com.mythbrix.cinetor.feature.downloads

import androidx.compose.runtime.Immutable
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Season
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.download.DownloadChoice
import com.mythbrix.cinetor.core.download.DownloadKind
import com.mythbrix.cinetor.core.download.QualityOption
import com.mythbrix.cinetor.core.download.dedupeQualityOptions
import com.mythbrix.cinetor.core.download.downloadKindOf
import com.mythbrix.cinetor.core.download.formatBatchEpisodeLabel
import com.mythbrix.cinetor.core.download.qualityOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

@Immutable
data class BatchEpisodeKey(
    val seasonNumber: Int,
    val episodeId: String,
)

enum class BatchQuality(val height: Int, val label: String) {
    P1080(1080, "1080p"),
    P720(720, "720p"),
    P480(480, "480p"),
    P360(360, "360p"),
}

@Immutable
data class SelectedEpisodeRef(
    val seasonNumber: Int,
    val episode: Episode,
)

@Immutable
data class BatchSelectionState(
    val seasons: List<Season>,
    val activeSeasonNumber: Int,
    val selectedKeys: Set<BatchEpisodeKey> = emptySet(),
    val preferredQuality: BatchQuality = BatchQuality.P1080,
) {
    val activeSeason: Season?
        get() = seasons.firstOrNull { it.n == activeSeasonNumber } ?: seasons.firstOrNull()

    val totalEpisodeCount: Int
        get() = seasons.sumOf { it.episodes.size }

    val totalSelectedCount: Int
        get() = seasons.sumOf { s -> s.episodes.count { BatchEpisodeKey(s.n, it.id) in selectedKeys } }

    val isAllSeasonsSelected: Boolean
        get() = totalEpisodeCount > 0 && totalSelectedCount == totalEpisodeCount

    fun isSelected(seasonNumber: Int, episodeId: String): Boolean =
        BatchEpisodeKey(seasonNumber, episodeId) in selectedKeys

    fun totalInSeason(seasonNumber: Int): Int =
        seasons.firstOrNull { it.n == seasonNumber }?.episodes?.size ?: 0

    fun selectedCountInSeason(seasonNumber: Int): Int {
        val season = seasons.firstOrNull { it.n == seasonNumber } ?: return 0
        return season.episodes.count { BatchEpisodeKey(seasonNumber, it.id) in selectedKeys }
    }

    fun isSeasonFullySelected(seasonNumber: Int): Boolean {
        val total = totalInSeason(seasonNumber)
        return total > 0 && selectedCountInSeason(seasonNumber) == total
    }

    fun seasonBadgeLabel(seasonNumber: Int): String =
        "S$seasonNumber (${selectedCountInSeason(seasonNumber)}/${totalInSeason(seasonNumber)})"

    fun selectedSeasonBadges(): List<String> =
        seasons.filter { selectedCountInSeason(it.n) > 0 }.map { seasonBadgeLabel(it.n) }

    fun selectSeasonTab(seasonNumber: Int): BatchSelectionState =
        if (seasons.any { it.n == seasonNumber }) copy(activeSeasonNumber = seasonNumber) else this

    fun toggleEpisode(seasonNumber: Int, episodeId: String): BatchSelectionState {
        val season = seasons.firstOrNull { it.n == seasonNumber } ?: return this
        if (season.episodes.none { it.id == episodeId }) return this
        val key = BatchEpisodeKey(seasonNumber, episodeId)
        val next = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
        return copy(selectedKeys = next)
    }

    fun selectSeason(seasonNumber: Int): BatchSelectionState {
        val season = seasons.firstOrNull { it.n == seasonNumber } ?: return this
        val keys = season.episodes.map { BatchEpisodeKey(seasonNumber, it.id) }
        return copy(selectedKeys = selectedKeys + keys)
    }

    fun unselectSeason(seasonNumber: Int): BatchSelectionState {
        val season = seasons.firstOrNull { it.n == seasonNumber } ?: return this
        val keys = season.episodes.map { BatchEpisodeKey(seasonNumber, it.id) }.toSet()
        return copy(selectedKeys = selectedKeys - keys)
    }

    fun toggleSeasonAll(seasonNumber: Int = activeSeasonNumber): BatchSelectionState =
        if (isSeasonFullySelected(seasonNumber)) unselectSeason(seasonNumber) else selectSeason(seasonNumber)

    fun selectAllSeasons(): BatchSelectionState {
        val allKeys = seasons.flatMap { s -> s.episodes.map { BatchEpisodeKey(s.n, it.id) } }.toSet()
        return copy(selectedKeys = allKeys)
    }

    fun clearAll(): BatchSelectionState = copy(selectedKeys = emptySet())

    fun toggleAllSeasons(): BatchSelectionState =
        if (isAllSeasonsSelected) clearAll() else selectAllSeasons()

    fun setQuality(quality: BatchQuality): BatchSelectionState = copy(preferredQuality = quality)

    fun selectedEpisodes(): List<SelectedEpisodeRef> =
        seasons.sortedBy { it.n }.flatMap { season ->
            season.episodes
                .sortedBy { it.n }
                .filter { BatchEpisodeKey(season.n, it.id) in selectedKeys }
                .map { SelectedEpisodeRef(season.n, it) }
        }
}

class BatchSelection(
    seasons: List<Season>,
    initialSeasonNumber: Int = seasons.firstOrNull()?.n ?: 1,
    startEmpty: Boolean = false,
    completedEpisodeKeys: Set<BatchEpisodeKey> = emptySet(),
    initialQuality: BatchQuality = BatchQuality.P1080,
) {
    private val _state: MutableStateFlow<BatchSelectionState>

    init {
        val resolvedSeason = seasons.firstOrNull { it.n == initialSeasonNumber } ?: seasons.firstOrNull()
        val activeNum = resolvedSeason?.n ?: initialSeasonNumber
        val defaultKeys = if (startEmpty || resolvedSeason == null) {
            emptySet()
        } else {
            val allInSeason = resolvedSeason.episodes.map { BatchEpisodeKey(resolvedSeason.n, it.id) }.toSet()
            val remaining = allInSeason - completedEpisodeKeys
            remaining.ifEmpty { allInSeason }
        }
        _state = MutableStateFlow(
            BatchSelectionState(
                seasons = seasons,
                activeSeasonNumber = activeNum,
                selectedKeys = defaultKeys,
                preferredQuality = initialQuality,
            ),
        )
    }

    val state: StateFlow<BatchSelectionState> = _state.asStateFlow()

    fun selectSeasonTab(seasonNumber: Int) = _state.update { it.selectSeasonTab(seasonNumber) }

    fun toggleEpisode(seasonNumber: Int, episodeId: String) = _state.update { it.toggleEpisode(seasonNumber, episodeId) }

    fun selectSeason(seasonNumber: Int = _state.value.activeSeasonNumber) = _state.update { it.selectSeason(seasonNumber) }

    fun unselectSeason(seasonNumber: Int = _state.value.activeSeasonNumber) = _state.update { it.unselectSeason(seasonNumber) }

    fun toggleSeasonAll(seasonNumber: Int = _state.value.activeSeasonNumber) = _state.update { it.toggleSeasonAll(seasonNumber) }

    fun selectAllSeasons() = _state.update { it.selectAllSeasons() }

    fun clearAll() = _state.update { it.clearAll() }

    fun toggleAllSeasons() = _state.update { it.toggleAllSeasons() }

    fun setQuality(quality: BatchQuality) = _state.update { it.setQuality(quality) }
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "svg")

/** Filters out YouTube embed/watch pages and static images that cannot be saved as media files. */
internal fun isNonDownloadableUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return true
    val lower = trimmed.lowercase()
    if (lower.startsWith("yt://") ||
        lower.contains("youtube.com/") ||
        lower.contains("youtu.be/") ||
        lower.contains("youtube-nocookie.com/")
    ) {
        return true
    }
    val path = lower.substringBefore('?').substringBefore('#')
    val ext = path.substringAfterLast('.', "")
    return ext in IMAGE_EXTENSIONS
}

/**
 * Filters candidate sources down to usable downloadable streams.
 * Gateway resolution-tagged lists prefer progressive files (MP4/MKV/WEBM) when present so Media3 DASH
 * probing never chokes on signed-cookie manifests.
 */
fun usableDownloadSources(sources: List<Source>, deadUrls: Set<String> = emptySet()): List<Source> {
    val ok = sources.filter { s ->
        downloadKindOf(s.kind) != null && s.url !in deadUrls && !isNonDownloadableUrl(s.url)
    }
    val tagged = ok.any { it.height != null }
    val progressive = ok.filter { it.kind == SourceKind.MP4 || it.kind == SourceKind.MKV || it.kind == SourceKind.WEBM }
    return if (tagged && progressive.isNotEmpty()) progressive else ok
}

/**
 * Deduplicates usable options (§4 rule: identical resolution + format keeps primary / size-known entry)
 * and picks the option closest to [targetHeight] (preferring progressive files and higher resolution on ties).
 */
fun pickBestQualityForBatch(
    sources: List<Source>,
    targetHeight: Int,
    durationSec: Long = 0,
    deadUrls: Set<String> = emptySet(),
): QualityOption? {
    val candidates = usableDownloadSources(sources, deadUrls)
    if (candidates.isEmpty()) return null
    val deduped = dedupeQualityOptions(candidates.flatMap { qualityOptions(it, null, durationSec) })
    if (deduped.isEmpty()) return null
    return deduped.minWithOrNull(
        compareBy<QualityOption> { opt -> opt.height?.let { abs(it - targetHeight) } ?: Int.MAX_VALUE }
            .thenBy { if (it.kind == DownloadKind.PROGRESSIVE) 0 else 1 }
            .thenByDescending { it.height ?: 0 },
    )
}

@Immutable
data class BatchResolveProgress(
    val currentIndex: Int,
    val totalCount: Int,
    val currentEpisodeLabel: String,
    val queuedCount: Int,
    val skippedCount: Int,
)

@Immutable
data class BatchQueueResult(
    val queuedCount: Int,
    val skippedCount: Int,
)

fun batchQueuedSnackbarMessage(queuedCount: Int): String =
    "Queued $queuedCount episodes for download"

/**
 * Sequentially resolves and enqueues each selected `(season, episode)` in [episodes].
 * Cancellation stops immediately via [ensureActive] / re-thrown [CancellationException];
 * individual episode failures increment [BatchQueueResult.skippedCount] without aborting the batch.
 */
suspend fun enqueueBatchEpisodes(
    item: Item,
    episodes: List<SelectedEpisodeRef>,
    targetHeight: Int,
    connections: Int,
    deadUrls: Set<String> = emptySet(),
    resolveSources: suspend (Item, Int, Episode) -> List<Source>,
    enqueue: suspend (Item, Episode, Int, DownloadChoice) -> Unit,
    onProgress: (BatchResolveProgress) -> Unit = {},
): BatchQueueResult {
    var queued = 0
    var skipped = 0
    val total = episodes.size
    episodes.forEachIndexed { idx, ref ->
        currentCoroutineContext().ensureActive()
        val label = formatBatchEpisodeLabel(item.title, ref.seasonNumber, ref.episode.n)
        onProgress(
            BatchResolveProgress(
                currentIndex = idx + 1,
                totalCount = total,
                currentEpisodeLabel = label,
                queuedCount = queued,
                skippedCount = skipped,
            ),
        )
        val rawSources = try {
            resolveSources(item, ref.seasonNumber, ref.episode)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        currentCoroutineContext().ensureActive()
        val best = pickBestQualityForBatch(
            sources = rawSources,
            targetHeight = targetHeight,
            durationSec = ref.episode.durationSec.toLong(),
            deadUrls = deadUrls,
        )
        if (best != null) {
            val defaultSubs = (ref.episode.subtitles.ifEmpty { item.subtitles }).map { it.label }.distinct()
            val choice = DownloadChoice(
                quality = best,
                subtitles = defaultSubs,
                connections = connections,
            )
            currentCoroutineContext().ensureActive()
            enqueue(item, ref.episode, ref.seasonNumber, choice)
            queued++
        } else {
            skipped++
        }
    }
    return BatchQueueResult(queuedCount = queued, skippedCount = skipped)
}
