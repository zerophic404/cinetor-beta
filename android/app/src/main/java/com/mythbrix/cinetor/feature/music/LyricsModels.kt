package com.mythbrix.cinetor.feature.music

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class LyricLine(
    val timeMs: Long,
    val text: String,
)

@Immutable
@Serializable
data class MusicLyrics(
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val lines: List<LyricLine> = emptyList(),
) {
    val hasSynced: Boolean get() = lines.isNotEmpty()
    val hasPlain: Boolean get() = !plainLyrics.isNullOrBlank()
}

/**
 * Pure LRC time-synced lyrics parser & active-verse locator.
 * Supports `[mm:ss.xx]`, `[mm:ss.xxx]`, `[mm:ss:xx]`, `[mm:ss]`, and multi-timestamp lines.
 */
object LrcParser {
    private val TAG_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")

    fun parse(lrc: String?): List<LyricLine> {
        if (lrc.isNullOrBlank()) return emptyList()
        val parsed = ArrayList<LyricLine>()
        for (rawLine in lrc.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            val matches = TAG_REGEX.findAll(trimmed).toList()
            if (matches.isEmpty()) continue
            val text = trimmed.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> (frac.toLongOrNull() ?: 0L) * 100L
                    2 -> (frac.toLongOrNull() ?: 0L) * 10L
                    else -> frac.take(3).toLongOrNull() ?: 0L
                }
                parsed.add(LyricLine(min * 60_000L + sec * 1_000L + ms, text))
            }
        }
        return parsed.sortedBy { it.timeMs }
    }

    /** Active verse index at [positionMs], or `-1` before the first timestamp / when empty. */
    fun activeLineIndex(lines: List<LyricLine>, positionMs: Long): Int =
        lines.indexOfLast { positionMs >= it.timeMs }
}
