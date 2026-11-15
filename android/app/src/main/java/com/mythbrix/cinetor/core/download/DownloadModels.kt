package com.mythbrix.cinetor.core.download

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Fragment 5/5 of the ownership notice (see core/legal/Ownership.kt). Never shown in the UI.
internal const val OWNERSHIP_F5 = "32382833227d297a3f222a363339332e7a2a3f283733292933353474"

/** Lifecycle of one download. */
enum class DownloadStatus {
    /** Waiting for a free slot. */
    QUEUED,
    DOWNLOADING,
    /** Paused by the user. */
    PAUSED,
    /** Blocked by a constraint (Wi-Fi only, charging only, low storage, offline). */
    WAITING,
    COMPLETED,
    FAILED,
}

/** How the bytes are fetched. */
enum class DownloadKind {
    /** MP4/MKV/WebM/MP3 → [ChunkedHttpDownloader] (multi-connection HTTP Range). */
    PROGRESSIVE,
    /** HLS → Media3 DownloadManager (segments in the shared cache). */
    HLS,
    /** DASH → Media3 DownloadManager. */
    DASH,
}

/**
 * Persisted download (Room table `downloads`). Plain columns only; list-ish values are joined strings
 * (see [subtitleList], [streamKeyList]) so no converters are needed.
 */
@Immutable
@Entity(tableName = "downloads")
data class DownloadRecord(
    /** `itemId` or `itemId:episodeId`. */
    @PrimaryKey val id: String,
    val itemId: String,
    val episodeId: String? = null,
    val title: String,
    /** e.g. "S1:E3 · Pilot". */
    val subtitle: String? = null,
    val poster: String = "",
    /** `Space.name`. */
    val space: String = "HOME",
    val adult: Boolean = false,
    val kind: DownloadKind,
    val url: String,
    /** Request headers as `k=v` lines. */
    val headers: String = "",
    val mimeType: String? = null,
    /** Final file (progressive) or the Media3 cache dir (HLS/DASH). */
    val filePath: String = "",
    /** SAF document URI of the copy exported to the user-chosen folder ("" = app storage only). */
    val exportUri: String = "",
    /** mp4 / mkv / webm / mp3 / hls / dash. */
    val container: String = "",
    val qualityLabel: String = "Original",
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val codecs: String? = null,
    val audioTrack: String? = null,
    /** Chosen subtitle labels joined by `|`. */
    val subtitleTracks: String = "",
    /** Sidecar subtitle files `lang|url` joined by `\n` (downloaded next to the media file). */
    val sidecarSubs: String = "",
    /** Media3 stream keys `period.group.stream` joined by `,` (HLS/DASH). */
    val streamKeys: String = "",
    /** Requested connections (progressive). */
    val connections: Int = 4,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    /** Lower runs first. */
    val priority: Int = 0,
    val createdAt: Long = 0,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    /** -1 = unknown. */
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val segmentsDone: Int = 0,
    val segmentsTotal: Int = 0,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val acceptRanges: Boolean? = null,
    val durationSec: Int? = null,
    /** Constraint that is blocking this download (WAITING). */
    val waitingReason: String? = null,
)

/** Per-connection byte range with its persisted progress (Room table `download_chunks`). */
@Immutable
@Entity(tableName = "download_chunks", primaryKeys = ["downloadId", "idx"], indices = [Index("downloadId")])
data class ChunkState(
    val downloadId: String,
    val idx: Int,
    val start: Long,
    /** Inclusive; -1 = open ended (unknown length, single connection). */
    val endInclusive: Long,
    /** Bytes already written from [start]. */
    val downloaded: Long,
)

/** Event log (Room table `download_events`): started, retry, error, paused, completed. */
@Immutable
@Entity(tableName = "download_events", indices = [Index("downloadId")])
data class DownloadEvent(
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0,
    val downloadId: String,
    val time: Long,
    val type: String,
    val message: String = "",
)

val ChunkState.position: Long get() = start + downloaded
val ChunkState.isDone: Boolean get() = endInclusive >= 0 && position > endInclusive

val DownloadRecord.progress: Float
    get() = when {
        totalBytes > 0 -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        segmentsTotal > 0 -> (segmentsDone.toFloat() / segmentsTotal).coerceIn(0f, 1f)
        else -> 0f
    }

val DownloadRecord.isActive: Boolean
    get() = status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING || status == DownloadStatus.WAITING

val DownloadRecord.subtitleList: List<String>
    get() = subtitleTracks.split('|').filter { it.isNotBlank() }

val DownloadRecord.streamKeyList: List<Triple<Int, Int, Int>>
    get() = streamKeys.split(',').mapNotNull { k ->
        val p = k.trim().split('.')
        if (p.size == 3) Triple(p[0].toIntOrNull() ?: return@mapNotNull null, p[1].toIntOrNull() ?: return@mapNotNull null, p[2].toIntOrNull() ?: return@mapNotNull null) else null
    }

val DownloadRecord.headerMap: Map<String, String>
    get() = headers.lines().mapNotNull { l ->
        val i = l.indexOf('=')
        if (i > 0) l.substring(0, i) to l.substring(i + 1) else null
    }.toMap()

val DownloadRecord.sourceHost: String
    get() = runCatching { java.net.URI(url).host }.getOrNull() ?: ""

fun encodeHeaders(h: Map<String, String>): String = h.entries.joinToString("\n") { "${it.key}=${it.value}" }

fun downloadIdFor(itemId: String, episodeId: String?): String = if (episodeId == null) itemId else "$itemId:$episodeId"

/** Live (not persisted) per-chunk progress for the details sheet. */
@Immutable
data class ChunkProgress(val index: Int, val start: Long, val endInclusive: Long, val downloaded: Long, val active: Boolean) {
    val length: Long get() = if (endInclusive < 0) -1 else endInclusive - start + 1
    val fraction: Float get() = if (length <= 0) 0f else (downloaded.toFloat() / length).coerceIn(0f, 1f)
}

/** Live stats sampled once per second while downloading. */
@Immutable
data class LiveStats(
    val currentBps: Long = 0,
    val averageBps: Long = 0,
    /** Last 60 one-second speed samples (oldest first). */
    val speedHistory: List<Long> = emptyList(),
    val etaSec: Long? = null,
    val elapsedMs: Long = 0,
    val activeConnections: Int = 0,
    val chunks: List<ChunkProgress> = emptyList(),
)

/** Record + live stats = what the UI renders. */
@Immutable
data class DownloadInfo(val record: DownloadRecord, val live: LiveStats? = null)
