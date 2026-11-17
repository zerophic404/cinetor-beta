package com.mythbrix.cinetor.tvui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.core.player.ResumeEntry
import com.mythbrix.cinetor.core.player.continueEntries
import com.mythbrix.cinetor.core.player.progressByItem
import com.mythbrix.cinetor.core.player.toCardItem
import com.mythbrix.cinetor.core.ui.LocalResumeEntries
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.feature.home.RowUi
import com.mythbrix.cinetor.feature.home.mockProgress
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Focus requesters + row scroll states of the cards currently composed on a TV screen, so D-pad
 * moves and [FocusMemory] restores can target an exact (row, index).
 */
class TvFocusRegistry {
    private val cards = HashMap<FocusPos, FocusRequester>()
    val rowStates = HashMap<Int, LazyListState>()

    fun register(pos: FocusPos, r: FocusRequester) { cards[pos] = r }
    fun unregister(pos: FocusPos, r: FocusRequester) { if (cards[pos] === r) cards.remove(pos) }

    fun requesterFor(pos: FocusPos): FocusRequester? = cards[pos]

    /** True if the card was composed and accepted focus. */
    fun request(pos: FocusPos): Boolean {
        val r = cards[pos] ?: return false
        return runCatching { r.requestFocus(); true }.getOrDefault(false)
    }
}

/**
 * Resolves the exact deduplicated items rendered for a TV shelf:
 * - `TOP10` caps strictly at 10 ranked chart entries (`1`–`10`).
 * - `CONTINUE` prepends active profile resume entries for the space before fallback items.
 */
fun tvRowItems(row: RowUi, resumeEntries: List<ResumeEntry> = emptyList()): List<Item> = when (row.style) {
    RowStyle.TOP10 -> row.items.distinctBy { it.id }.take(10)
    RowStyle.CONTINUE -> {
        val space = row.items.firstOrNull()?.space
        val resumed = space?.let { s -> continueEntries(resumeEntries, s).map { it.toCardItem() } }.orEmpty()
        (resumed + row.items).distinctBy { it.id }
    }
    else -> row.items.distinctBy { it.id }
}

/** True when D-pad focus or visible scroll nears the end of a pageable shelf (`PagedCatalog`). */
fun tvShouldLoadMore(index: Int, totalCount: Int, style: RowStyle): Boolean {
    if (totalCount < 6) return false
    if (style == RowStyle.TOP10 || style == RowStyle.CONTINUE || style == RowStyle.CHANNEL) return false
    return index >= totalCount - 4
}

/**
 * One Movira-headed shelf of [TvCard]s / [TvTop10Card]s on Android TV.
 * Reports card focus to [onFocus] (→ [FocusMemory] + ambient tint) and triggers [onNearEnd]
 * for endless `PagedCatalog` row pagination as D-pad focus approaches the end of the row.
 */
@Composable
fun TvRow(
    rowIndex: Int,
    row: RowUi,
    registry: TvFocusRegistry,
    onOpen: (Item) -> Unit,
    onFocus: (FocusPos, Item) -> Unit,
    modifier: Modifier = Modifier,
    startPadding: Dp = 8.dp,
    onNearEnd: (() -> Unit)? = null,
) {
    val resumeEntries = LocalResumeEntries.current
    val items = remember(row, resumeEntries) { tvRowItems(row, resumeEntries) }
    if (items.isEmpty()) return

    val state = rememberLazyListState()
    DisposableEffect(rowIndex, state) {
        registry.rowStates[rowIndex] = state
        onDispose { if (registry.rowStates[rowIndex] === state) registry.rowStates.remove(rowIndex) }
    }

    val latestNearEnd by rememberUpdatedState(onNearEnd)
    if (onNearEnd != null) {
        LaunchedEffect(state, items.size, row.style) {
            snapshotFlow {
                val lastVisible = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                tvShouldLoadMore(lastVisible, items.size, row.style)
            }
                .distinctUntilChanged()
                .filter { it }
                .collect { latestNearEnd?.invoke() }
        }
    }

    val tokens = Cinetor.tokens
    val isTop10 = row.style == RowStyle.TOP10
    val landscape = row.style == RowStyle.LANDSCAPE || row.style == RowStyle.CONTINUE || row.style == RowStyle.CHANNEL
    val width = if (landscape) 256.dp else 148.dp
    val resumeMap = remember(resumeEntries) { progressByItem(resumeEntries) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Movira-style shelf header: vertical Cinema Red accent bar + bold title.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = startPadding, end = startPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(width = 4.dp, height = 20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tokens.accent),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleLarge,
                color = tokens.textPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = startPadding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTop10) 12.dp else 18.dp),
            modifier = Modifier.focusGroup(),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }, contentType = { _, _ -> row.style }) { i, item ->
                val pos = FocusPos(rowIndex, i)
                val requester = remember { FocusRequester() }
                DisposableEffect(pos, requester) {
                    registry.register(pos, requester)
                    onDispose { registry.unregister(pos, requester) }
                }
                val handleFocused: () -> Unit = {
                    onFocus(pos, item)
                    if (tvShouldLoadMore(i, items.size, row.style)) {
                        latestNearEnd?.invoke()
                    }
                }
                if (isTop10) {
                    TvTop10Card(
                        rank = i + 1,
                        item = item,
                        onClick = { onOpen(item) },
                        onFocused = handleFocused,
                        modifier = Modifier.focusRequester(requester),
                        posterWidth = width,
                    )
                } else {
                    TvCard(
                        item = item,
                        onClick = { onOpen(item) },
                        onFocused = handleFocused,
                        modifier = Modifier.focusRequester(requester),
                        width = width,
                        landscape = landscape,
                        progress = if (row.style == RowStyle.CONTINUE) {
                            resumeMap[item.id] ?: mockProgress(item.id)
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}
