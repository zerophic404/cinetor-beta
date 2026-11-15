package com.mythbrix.cinetor.feature.tv

import com.mythbrix.cinetor.core.data.EventStatus
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Program

/**
 * Mock guide clock. Programme offsets are relative to the file's `epgAnchor` and cover ~12.5 h, so the
 * wall clock is folded into a 12 h window after the anchor: time keeps moving, the guide never runs dry.
 */
object EpgClock {
    const val SPAN_SEC: Long = 12 * 3600L

    fun nowSec(realNowSec: Long, anchorSec: Long): Long = anchorSec + Math.floorMod(realNowSec - anchorSec, SPAN_SEC)

    /** Real wall-clock ms of a guide instant (for reminders). */
    fun realMsOf(guideSec: Long, guideNowSec: Long, realNowMs: Long): Long = realNowMs + (guideSec - guideNowSec) * 1000L
}

data class NowNext(val now: Program?, val next: Program?, val progress: Float)

fun programStartSec(anchorSec: Long, p: Program): Long = anchorSec + p.startOffsetSec
fun programEndSec(anchorSec: Long, p: Program): Long = programStartSec(anchorSec, p) + p.durationSec

/** What's on [channel] at [nowSec] + what's next + progress (0..1) of the current programme. */
fun nowNext(channel: Item, nowSec: Long, anchorSec: Long): NowNext {
    val sorted = channel.programs.sortedBy { it.startOffsetSec }
    val i = sorted.indexOfFirst { nowSec >= programStartSec(anchorSec, it) && nowSec < programEndSec(anchorSec, it) }
    if (i < 0) {
        val next = sorted.firstOrNull { programStartSec(anchorSec, it) > nowSec }
        return NowNext(null, next, 0f)
    }
    val now = sorted[i]
    val progress = ((nowSec - programStartSec(anchorSec, now)).toFloat() / now.durationSec.coerceAtLeast(1)).coerceIn(0f, 1f)
    return NowNext(now, sorted.getOrNull(i + 1), progress)
}

/** Guide window start: the half-hour slot before now, minus 30 min of look-back. */
fun epgWindowStart(nowSec: Long): Long = nowSec - Math.floorMod(nowSec, 1800L) - 1800L

/** X of the now-line inside the programme area (dp), [dpPerMinute] scale, clamped to ≥ 0. */
fun epgNowLinePosition(nowSec: Long, windowStartSec: Long, dpPerMinute: Float): Float =
    ((nowSec - windowStartSec) / 60f * dpPerMinute).coerceAtLeast(0f)

/** One programme laid out on the guide (dp from window start). */
data class EpgBlock(val program: Program, val startDp: Float, val widthDp: Float, val startSec: Long, val endSec: Long)

/** Programmes of [channel] clipped to [windowStartSec, windowEndSec). */
fun epgBlocks(channel: Item, anchorSec: Long, windowStartSec: Long, windowEndSec: Long, dpPerMinute: Float): List<EpgBlock> =
    channel.programs.sortedBy { it.startOffsetSec }.mapNotNull { p ->
        val s = programStartSec(anchorSec, p)
        val e = programEndSec(anchorSec, p)
        if (e <= windowStartSec || s >= windowEndSec) return@mapNotNull null
        val cs = maxOf(s, windowStartSec)
        val ce = minOf(e, windowEndSec)
        EpgBlock(p, (cs - windowStartSec) / 60f * dpPerMinute, (ce - cs) / 60f * dpPerMinute, s, e)
    }

/** Channel groups in first-seen (guide) order. */
fun channelGroups(channels: List<Item>): List<String> = channels.mapNotNull { it.channelGroup }.distinct()

/**
 * Live grid filter: [group] tab (null = all), [genre] chip matched against the channel genres or the
 * programme on now, [favoritesOnly] star filter.
 */
fun filterChannels(
    channels: List<Item>,
    group: String?,
    genre: String?,
    favoritesOnly: Boolean,
    favorites: Set<String>,
    nowGenre: (Item) -> String? = { null },
): List<Item> = channels.filter { ch ->
    (group == null || ch.channelGroup == group) &&
        (genre == null || ch.genres.any { it.equals(genre, true) } || nowGenre(ch).equals(genre, true)) &&
        (!favoritesOnly || ch.id in favorites)
}

enum class SportsTab(val title: String, val status: EventStatus) {
    LIVE("Live now", EventStatus.LIVE),
    UPCOMING("Upcoming", EventStatus.UPCOMING),
    RESULTS("Results", EventStatus.FINISHED),
}

/** Events of a sports tab: live by kick-off, upcoming soonest first, results most recent first. */
fun eventsFor(tab: SportsTab, events: List<Item>): List<Item> {
    val list = events.filter { it.type == ItemType.EVENT && it.status == tab.status }
    return when (tab) {
        SportsTab.RESULTS -> list.sortedByDescending { it.startOffsetSec ?: 0 }
        else -> list.sortedBy { it.startOffsetSec ?: 0 }
    }
}

/** Mock match clock: minutes since kick-off (live), capped by duration. */
fun elapsedMinutes(event: Item, nowSec: Long, anchorSec: Long): Int {
    val start = anchorSec + (event.startOffsetSec ?: 0)
    val max = (event.durationSec ?: 7200) / 60
    return ((nowSec - start) / 60).toInt().coerceIn(0, max)
}

/** Seconds until kick-off (negative = started). */
fun secondsUntil(event: Item, nowSec: Long, anchorSec: Long): Long = anchorSec + (event.startOffsetSec ?: 0) - nowSec

/** "2h 05m" / "12m" / "now". */
fun formatCountdown(sec: Long): String = when {
    sec <= 0 -> "now"
    sec < 3600 -> "${(sec + 59) / 60}m"
    else -> "${sec / 3600}h ${"%02d".format((sec % 3600) / 60)}m"
}

/** "HH:mm" of a guide instant (UTC-based mock clock + device offset applied by caller). */
fun formatClock(sec: Long, offsetSec: Int = 0): String {
    val day = Math.floorMod(sec + offsetSec, 86_400L)
    return "%02d:%02d".format(day / 3600, (day % 3600) / 60)
}

/** Reminder fires [leadMin] before kick-off; never in the past (≥ 5 s from now). */
fun reminderAtMs(event: Item, guideNowSec: Long, anchorSec: Long, realNowMs: Long, leadMin: Int = 5): Long {
    val start = anchorSec + (event.startOffsetSec ?: 0)
    return EpgClock.realMsOf(start - leadMin * 60L, guideNowSec, realNowMs).coerceAtLeast(realNowMs + 5_000)
}
