package com.mythbrix.cinetor.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.mythbrix.cinetor.core.player.toCardItem
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.core.ui.posterKey
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.hexColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * One horizontal row in any of the 6 styles. Stable keys + per-style contentType; scrolling the
 * row offers the first visible item's colour to the (debounced) tint.
 */
@Composable
fun ContentRow(
    row: RowUi,
    onOpen: (Item, String?) -> Unit,
    onTint: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    val resumeEntries = com.mythbrix.cinetor.core.ui.LocalResumeEntries.current
    val rowItems = if (row.style == RowStyle.CONTINUE) {
        // Real Continue Watching (video spaces only) first, catalog's mock entries after.
        val space = row.items.firstOrNull()?.space
        val real = space?.let { s -> com.mythbrix.cinetor.core.player.continueEntries(resumeEntries, s).map { it.toCardItem() } }.orEmpty()
        (real + row.items).distinctBy { it.id }
    } else {
        row.items
    }
    val state = rememberLazyListState()
    LaunchedEffect(state, row) {
        snapshotFlow { state.isScrollInProgress to state.firstVisibleItemIndex }
            .filter { it.first }
            .distinctUntilChanged()
            .collect { (_, i) -> rowItems.getOrNull(i)?.let { onTint(hexColor(it.dominantColor)) } }
    }
    Column(modifier) {
        Text(
            row.title,
            style = MaterialTheme.typography.titleMedium,
            color = tokens.textPrimary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(4.dp))
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(if (row.style == RowStyle.TOP10) 4.dp else 12.dp),
        ) {
            itemsIndexed(rowItems, key = { _, it -> it.id }, contentType = { _, _ -> row.style }) { index, item ->
                val key = posterKey(row.id, item.id)
                val open = { onOpen(item, key) }
                val openPlain = { onOpen(item, null) }
                when (row.style) {
                    RowStyle.POSTER -> PosterCard(item, open, onTint, sharedKey = key)
                    RowStyle.LANDSCAPE -> LandscapeCard(item, openPlain, onTint)
                    RowStyle.TOP10 -> Top10Card(index + 1, item, open, onTint, sharedKey = key)
                    RowStyle.CONTINUE -> ContinueCard(item, openPlain, onTint)
                    RowStyle.CIRCLE -> CircleCard(item, openPlain, onTint)
                    RowStyle.CHANNEL -> ChannelCard(item, openPlain, onTint)
                }
            }
        }
    }
}
