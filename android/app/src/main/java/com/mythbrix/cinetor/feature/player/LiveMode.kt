package com.mythbrix.cinetor.feature.player

import com.mythbrix.cinetor.core.data.EventStatus
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.player.PlayerUiState

/** Channel, or a sports event that is on air right now → player live mode (no resume, no download). */
fun isLiveItem(item: Item): Boolean = when (item.type) {
    ItemType.CHANNEL -> true
    ItemType.EVENT -> item.status == EventStatus.LIVE
    else -> item.live
}

/** Next/previous channel in [lineup] (wraps around). Null when there is nothing to zap to. */
fun zapTarget(lineup: List<Item>, currentId: String?, delta: Int): Item? {
    if (lineup.size < 2 || delta == 0) return null
    val i = lineup.indexOfFirst { it.id == currentId }
    if (i < 0) return lineup.first()
    return lineup[Math.floorMod(i + delta, lineup.size)]
}

/** Fullscreen vertical swipe → zap: swipe up = next channel (+1), down = previous (−1), short = 0. */
fun zapDeltaForSwipe(dy: Float, thresholdPx: Float): Int = when {
    dy <= -thresholdPx -> 1
    dy >= thresholdPx -> -1
    else -> 0
}

/** Live edge tolerance (HLS live sits ~3 segments behind the real edge). */
const val LIVE_EDGE_TOLERANCE_MS = 45_000L

/** At (or near) the live edge → red LIVE badge; else "Go live" pill. Non-seekable live is always at the edge. */
fun atLiveEdge(ui: PlayerUiState): Boolean = when {
    !ui.seekable || ui.durationMs <= 0 -> true
    else -> ui.durationMs - ui.positionMs < LIVE_EDGE_TOLERANCE_MS
}

/** DVR seek bar only when the live window is seekable and long enough to be useful (≥ 2 min). */
fun hasDvrWindow(ui: PlayerUiState): Boolean = ui.seekable && ui.durationMs >= 120_000

/** Typed channel digits (TV remote number keys): max 4 digits. */
fun appendChannelDigit(buffer: String?, digit: Int): String =
    ((buffer ?: "") + digit.coerceIn(0, 9)).takeLast(4)
