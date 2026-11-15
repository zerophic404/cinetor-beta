package com.mythbrix.cinetor.tvui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.feature.home.RowUi
import com.mythbrix.cinetor.feature.home.mockProgress

/**
 * Focus requesters + row scroll states of the cards currently composed on a TV screen, so D-pad
 * moves and [FocusMemory] restores can target an exact (row, index).
 */
class TvFocusRegistry {
    private val cards = HashMap<FocusPos, FocusRequester>()
    val rowStates = HashMap<Int, LazyListState>()

    fun register(pos: FocusPos, r: FocusRequester) { cards[pos] = r }
    fun unregister(pos: FocusPos, r: FocusRequester) { if (cards[pos] === r) cards.remove(pos) }

    /** True if the card was composed and accepted focus. */
    fun request(pos: FocusPos): Boolean {
        val r = cards[pos] ?: return false
        return runCatching { r.requestFocus(); true }.getOrDefault(false)
    }
}

/** One titled row of [TvCard]s. Reports focus to [onFocus] (→ FocusMemory). */
@Composable
fun TvRow(
    rowIndex: Int,
    row: RowUi,
    registry: TvFocusRegistry,
    onOpen: (Item) -> Unit,
    onFocus: (FocusPos, Item) -> Unit,
    modifier: Modifier = Modifier,
    startPadding: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val state = rememberLazyListState()
    DisposableEffect(rowIndex, state) {
        registry.rowStates[rowIndex] = state
        onDispose { if (registry.rowStates[rowIndex] === state) registry.rowStates.remove(rowIndex) }
    }
    val landscape = row.style == RowStyle.LANDSCAPE || row.style == RowStyle.CONTINUE || row.style == RowStyle.CHANNEL
    val width = if (landscape) 260.dp else 150.dp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            row.title,
            style = MaterialTheme.typography.titleLarge,
            color = Cinetor.tokens.textPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = startPadding),
        )
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = startPadding, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.focusGroup(),
        ) {
            itemsIndexed(row.items, key = { _, it -> it.id }) { i, item ->
                val pos = FocusPos(rowIndex, i)
                val requester = remember { FocusRequester() }
                DisposableEffect(pos, requester) {
                    registry.register(pos, requester)
                    onDispose { registry.unregister(pos, requester) }
                }
                TvCard(
                    item = item,
                    onClick = { onOpen(item) },
                    onFocused = { onFocus(pos, item) },
                    modifier = Modifier.focusRequester(requester),
                    width = width,
                    landscape = landscape,
                    progress = if (row.style == RowStyle.CONTINUE) mockProgress(item.id) else null,
                )
            }
        }
    }
}
