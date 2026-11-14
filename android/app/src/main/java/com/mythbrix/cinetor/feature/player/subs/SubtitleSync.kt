package com.mythbrix.cinetor.feature.player.subs

/** One subtitle cue (times in media ms). */
data class SubCue(val startMs: Long, val endMs: Long, val text: String)

/** Sync offset limits: ±10 s in 50 ms steps. Positive = subtitles appear later. */
object SubtitleSync {
    const val STEP_MS = 50L
    const val RANGE_MS = 10_000L

    fun clamp(offsetMs: Long): Long {
        val c = offsetMs.coerceIn(-RANGE_MS, RANGE_MS)
        return Math.round(c.toDouble() / STEP_MS) * STEP_MS
    }

    /** Cues moved by [offsetMs] (both directions); cues pushed entirely before 0 are dropped. */
    fun shift(cues: List<SubCue>, offsetMs: Long): List<SubCue> =
        if (offsetMs == 0L) cues else cues.mapNotNull { c ->
            val s = c.startMs + offsetMs
            val e = c.endMs + offsetMs
            if (e <= 0) null else SubCue(s.coerceAtLeast(0), e, c.text)
        }

    /** Cues visible at [positionMs] with [offsetMs] applied. [cues] sorted by start. */
    fun active(cues: List<SubCue>, positionMs: Long, offsetMs: Long): List<SubCue> {
        val t = positionMs - offsetMs
        // Binary search the last cue starting at/before t, then scan back over overlaps.
        var lo = 0
        var hi = cues.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= t) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (idx < 0) return emptyList()
        val out = ArrayList<SubCue>(2)
        var i = idx
        while (i >= 0 && idx - i < 8) {
            val c = cues[i]
            if (t >= c.startMs && t < c.endMs) out.add(0, c)
            i--
        }
        return out
    }

    fun label(offsetMs: Long): String {
        val sign = if (offsetMs > 0) "+" else if (offsetMs < 0) "−" else ""
        val abs = kotlin.math.abs(offsetMs)
        return "$sign${abs / 1000}.${"%03d".format(abs % 1000)} s"
    }
}

/** SRT + WebVTT parser (tags stripped, cue settings ignored). */
object SubtitleParser {
    private val timeLine = Regex("""^\s*([\d:.,]+)\s*-->\s*([\d:.,]+)""")
    private val tags = Regex("""<[^>]+>|\{\\[^}]*\}""")

    /** Format from [nameHint] (".vtt") or content ("WEBVTT" header). */
    fun parse(text: String, nameHint: String? = null): List<SubCue> {
        val clean = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val vtt = clean.trimStart().startsWith("WEBVTT") || nameHint?.lowercase()?.endsWith(".vtt") == true
        return if (vtt) parseVtt(clean) else parseSrt(clean)
    }

    fun parseSrt(text: String): List<SubCue> = parseBlocks(text, skipHeader = false)

    fun parseVtt(text: String): List<SubCue> = parseBlocks(text, skipHeader = true)

    private fun parseBlocks(text: String, skipHeader: Boolean): List<SubCue> {
        val out = ArrayList<SubCue>()
        val blocks = text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            if (skipHeader) {
                val first = lines[0].trim()
                if (first.startsWith("WEBVTT") && lines.none { timeLine.containsMatchIn(it) }) continue
                if (first.startsWith("NOTE") || first.startsWith("STYLE") || first.startsWith("REGION")) continue
            }
            val ti = lines.indexOfFirst { timeLine.containsMatchIn(it) }
            if (ti < 0) continue
            val m = timeLine.find(lines[ti]) ?: continue
            val start = parseTime(m.groupValues[1]) ?: continue
            val end = parseTime(m.groupValues[2]) ?: continue
            val body = lines.drop(ti + 1).joinToString("\n") { it.replace(tags, "").trim() }.trim()
            if (body.isEmpty() || end <= start) continue
            out += SubCue(start, end, body)
        }
        return out.sortedBy { it.startMs }
    }

    /** "01:02:03,500", "02:03.500", "1:02:03.5" → ms. */
    fun parseTime(raw: String): Long? {
        val s = raw.trim().replace(',', '.')
        val parts = s.split(':')
        if (parts.size !in 2..3) return null
        val secPart = parts.last()
        val sec = secPart.substringBefore('.').toLongOrNull() ?: return null
        val fracStr = secPart.substringAfter('.', "0")
        val frac = (fracStr + "000").take(3).toLongOrNull() ?: return null
        val min = parts[parts.size - 2].toLongOrNull() ?: return null
        val hour = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        return ((hour * 60 + min) * 60 + sec) * 1000 + frac
    }
}
