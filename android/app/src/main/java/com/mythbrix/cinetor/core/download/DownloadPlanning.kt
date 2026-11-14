package com.mythbrix.cinetor.core.download

import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space

/** Engine for a catalog source; null = not downloadable (RTSP live, SmoothStreaming). */
fun downloadKindOf(kind: SourceKind): DownloadKind? = when (kind) {
    SourceKind.MP4, SourceKind.MKV, SourceKind.WEBM, SourceKind.MP3 -> DownloadKind.PROGRESSIVE
    SourceKind.HLS -> DownloadKind.HLS
    SourceKind.DASH -> DownloadKind.DASH
    SourceKind.RTSP, SourceKind.SS -> null
}

fun containerOf(kind: SourceKind): String = kind.name.lowercase()

/** Live TV / channels / live events never show a Download button; comics use the reader's own chapter cache. */
fun isDownloadable(item: Item): Boolean =
    item.space != Space.TV && !item.live && item.type != ItemType.CHANNEL && item.type != ItemType.EVENT &&
        item.type != ItemType.COMIC && item.space != Space.COMICS

/** One row in the options sheet. [estimatedBytes] −1 = unknown. */
data class QualityOption(
    val source: Source,
    val kind: DownloadKind,
    val label: String,
    val height: Int? = null,
    val width: Int? = null,
    val bitrate: Long? = null,
    val codecs: String? = null,
    val estimatedBytes: Long = -1,
)

/** User choices from the options sheet. */
data class DownloadChoice(
    val quality: QualityOption,
    val audio: String? = null,
    val subtitles: List<String> = emptyList(),
    val connections: Int = 4,
)

/** Builds quality options for one source: HLS/DASH → one per video height (size = bitrate × duration). */
fun qualityOptions(source: Source, tracks: StreamTrackInfo?, durationSec: Long, contentLength: Long = -1): List<QualityOption> {
    val kind = downloadKindOf(source.kind) ?: return emptyList()
    if (kind == DownloadKind.PROGRESSIVE) {
        val est = if (contentLength > 0) contentLength else -1
        val base = source.label?.takeIf { it.isNotBlank() } ?: source.kind.name
        return listOf(QualityOption(source, kind, "Original · $base", estimatedBytes = est))
    }
    val dur = if (tracks != null && tracks.durationSec > 0) tracks.durationSec else durationSec
    val videos = tracks?.videos.orEmpty().filter { it.height > 0 }.distinctBy { it.height }
    if (videos.isEmpty()) return listOf(QualityOption(source, kind, "Auto · ${source.kind.name}"))
    return videos.map { v ->
        QualityOption(
            source = source, kind = kind,
            label = "${v.height}p · ${source.kind.name}",
            height = v.height, width = v.width, bitrate = v.bitrate.takeIf { it > 0 }, codecs = v.codecs,
            estimatedBytes = estimateSizeBytes(v.bitrate, dur),
        )
    }
}

/** Target file extension for a progressive source. */
fun extensionOf(kind: SourceKind): String = when (kind) {
    SourceKind.MKV -> "mkv"
    SourceKind.WEBM -> "webm"
    SourceKind.MP3 -> "mp3"
    else -> "mp4"
}

/**
 * Creates the persisted record for [item] (or [episode]). [fileFor] maps (id, adult, ext) to the target path
 * (adult downloads land in their own private folder).
 */
fun buildRecord(
    item: Item,
    episode: Episode?,
    seasonNumber: Int?,
    choice: DownloadChoice,
    fileFor: (String, Boolean, String) -> String,
    mediaCacheDir: String,
): DownloadRecord {
    val id = downloadIdFor(item.id, episode?.id)
    val adult = item.space == Space.ADULT
    val q = choice.quality
    val subs = (episode?.subtitles ?: item.subtitles)
    val chosenSubs = subs.filter { it.label in choice.subtitles || it.lang in choice.subtitles }
    val path = if (q.kind == DownloadKind.PROGRESSIVE) fileFor(id, adult, extensionOf(q.source.kind)) else mediaCacheDir
    return DownloadRecord(
        id = id,
        itemId = item.id,
        episodeId = episode?.id,
        title = item.title,
        subtitle = episode?.let { "S${seasonNumber ?: 1}:E${it.n} · ${it.title}" },
        poster = episode?.still ?: item.poster,
        space = item.space.name,
        adult = adult,
        kind = q.kind,
        url = q.source.url,
        headers = encodeHeaders(q.source.headers),
        filePath = path,
        container = containerOf(q.source.kind),
        qualityLabel = q.label,
        width = q.width,
        height = q.height,
        bitrate = q.bitrate,
        codecs = q.codecs,
        audioTrack = choice.audio,
        subtitleTracks = choice.subtitles.joinToString("|"),
        sidecarSubs = if (q.kind == DownloadKind.PROGRESSIVE) chosenSubs.joinToString("\n") { "${it.lang}|${it.url}" } else "",
        connections = choice.connections.coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS),
        totalBytes = q.estimatedBytes,
        durationSec = episode?.durationSec ?: item.durationSec,
    )
}

/** Episodes for "next 3 unwatched" (no watch history yet → the first 3 not already downloaded). */
fun nextUnwatched(episodes: List<Episode>, downloadedIds: Set<String>, itemId: String, count: Int = 3): List<Episode> =
    episodes.filter { downloadIdFor(itemId, it.id) !in downloadedIds }.take(count)
