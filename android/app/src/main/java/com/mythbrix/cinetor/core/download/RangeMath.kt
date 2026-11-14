package com.mythbrix.cinetor.core.download

import kotlin.math.max
import kotlin.math.min

/** Inclusive byte range `[start, end]`. */
data class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1
}

const val MIN_CONNECTIONS = 1
const val MAX_CONNECTIONS = 16
const val MIB = 1024L * 1024L

/**
 * Splits `[0, total)` into at most [connections] contiguous ranges whose sizes differ by ≤ 1 byte.
 * Never makes chunks smaller than [minChunk] (small files → fewer connections). Unknown/zero length → empty.
 */
fun splitRanges(total: Long, connections: Int, minChunk: Long = MIB): List<ByteRange> {
    if (total <= 0) return emptyList()
    val wanted = connections.coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS)
    val bySize = max(1L, total / max(1L, minChunk))
    val n = min(wanted.toLong(), bySize).toInt()
    val base = total / n
    val extra = total % n
    val out = ArrayList<ByteRange>(n)
    var pos = 0L
    for (i in 0 until n) {
        val len = base + if (i < extra) 1 else 0
        out += ByteRange(pos, pos + len - 1)
        pos += len
    }
    return out
}

/** Builds fresh chunk rows for a download (single open-ended chunk when ranges are unusable). */
fun initialChunks(downloadId: String, total: Long, connections: Int, acceptRanges: Boolean, minChunk: Long = MIB): List<ChunkState> {
    if (!acceptRanges || total <= 0) {
        return listOf(ChunkState(downloadId, 0, 0, if (total > 0) total - 1 else -1, 0))
    }
    return splitRanges(total, connections, minChunk).mapIndexed { i, r -> ChunkState(downloadId, i, r.start, r.end, 0) }
}

/** Snapshot of one running connection used to plan work stealing. */
data class ChunkView(val index: Int, val position: Long, val endInclusive: Long, val bytesPerSec: Long) {
    val remaining: Long get() = if (endInclusive < 0) 0 else max(0L, endInclusive - position + 1)
}

/** Victim [victimIndex] keeps `[position, victimNewEnd]`, the idle connection takes [newRange]. */
data class StealPlan(val victimIndex: Int, val victimNewEnd: Long, val newRange: ByteRange)

/**
 * Dynamic work stealing: an idle connection takes the back half of the chunk that will finish LAST
 * (largest remaining / speed). Victims with less than `2 × minSplit` bytes left are not split.
 * The split point leaves the victim a share proportional to its speed vs. [thiefBps] (half when unknown).
 */
fun planSteal(chunks: List<ChunkView>, minSplit: Long = 512 * 1024L, thiefBps: Long = 0): StealPlan? {
    val victim = chunks
        .filter { it.remaining >= 2 * minSplit }
        .maxByOrNull { it.remaining.toDouble() / max(1L, it.bytesPerSec) }
        ?: return null
    val rem = victim.remaining
    val victimShare = if (victim.bytesPerSec > 0 && thiefBps > 0) {
        victim.bytesPerSec.toDouble() / (victim.bytesPerSec + thiefBps)
    } else {
        0.5
    }
    val keep = (rem * victimShare).toLong().coerceIn(minSplit, rem - minSplit)
    val newEnd = victim.position + keep - 1
    return StealPlan(victim.index, newEnd, ByteRange(newEnd + 1, victim.endInclusive))
}

/** Exponential backoff: base·2^(attempt-1), capped. attempt starts at 1. */
fun backoffMs(attempt: Int, baseMs: Long = 1_000, maxMs: Long = 30_000): Long {
    val a = attempt.coerceIn(1, 20)
    return min(maxMs, baseMs shl (a - 1))
}

/** Estimated size in bytes from an average bitrate (bits/s) × duration. Unknown → -1. */
fun estimateSizeBytes(bitrateBps: Long, durationSec: Long): Long =
    if (bitrateBps <= 0 || durationSec <= 0) -1 else bitrateBps * durationSec / 8

/** Default connections: 4 on Wi-Fi, 2 on mobile data, 2 on LOW-tier devices. */
fun defaultConnections(network: NetworkType, lowTier: Boolean): Int = when {
    lowTier -> 2
    network == NetworkType.METERED -> 2
    else -> 4
}

/** "1.4 GB", "820 MB", "12 KB". */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    val kb = 1024.0
    return when {
        bytes >= kb * kb * kb -> "%.2f GB".format(bytes / (kb * kb * kb))
        bytes >= kb * kb -> "%.1f MB".format(bytes / (kb * kb))
        bytes >= kb -> "%.0f KB".format(bytes / kb)
        else -> "$bytes B"
    }
}

fun formatSpeed(bps: Long): String = formatBytes(bps) + "/s"

fun formatDuration(sec: Long): String {
    if (sec < 0) return "—"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
