package com.mythbrix.cinetor.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.core.player.continueEntries
import com.mythbrix.cinetor.core.player.toCardItem
import com.mythbrix.cinetor.core.ui.LocalResumeEntries
import com.mythbrix.cinetor.core.ui.posterKey
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.hexColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** True when a shelf supports opening the full-grid "See all" view. */
fun canSeeAllRow(row: RowUi): Boolean =
    (row.style == RowStyle.POSTER || row.style == RowStyle.LANDSCAPE) && row.items.size >= 4

/** True when the user has scrolled within 4 cards of the end of a non-empty shelf. */
fun shouldLoadMoreRow(lastVisibleIndex: Int, totalItems: Int): Boolean =
    totalItems > 0 && lastVisibleIndex >= (totalItems - 4).coerceAtLeast(0)

/**
 * One horizontal shelf in any of the 6 styles, featuring Movira-style section headers
 * (`3.dp x 16.dp` vertical Cinema Red bar + bold title + optional `See all` trigger),
 * unnumbered `TOP10` poster shelves, unclipped titles below every card, and endless
 * horizontal `onNearEnd` pagination (`PagedCatalog`).
 */
@Composable
fun ContentRow(
    row: RowUi,
    onOpen: (Item, String?) -> Unit,
    onTint: (Color) -> Unit,
    modifier: Modifier = Modifier,
    /** Paged rows (gateway lists): called once the user scrolls near the end. */
    onNearEnd: (() -> Unit)? = null,
    /** Optional "See all" sheet/grid trigger for poster and landscape shelves. */
    onSeeAll: (() -> Unit)? = null,
) {
    val tokens = Cinetor.tokens
    val resumeEntries = LocalResumeEntries.current
    val rowItems = remember(row, resumeEntries) {
        when (row.style) {
            RowStyle.CONTINUE -> {
                val space = row.items.firstOrNull()?.space
                val real = space?.let { s -> continueEntries(resumeEntries, s).map { it.toCardItem() } }.orEmpty()
                (real + row.items).distinctBy { it.id }
            }
            else -> row.items.distinctBy { it.id }
        }
    }
    if (rowItems.isEmpty()) return

    val state = rememberLazyListState()
    if (onNearEnd != null && row.style != RowStyle.CONTINUE) {
        val latestNearEnd by rememberUpdatedState(onNearEnd)
        LaunchedEffect(state, rowItems.size) {
            snapshotFlow {
                val info = state.layoutInfo
                val lastIdx = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                shouldLoadMoreRow(lastIdx, info.totalItemsCount)
            }
                .distinctUntilChanged()
                .filter { it }
                .collect { latestNearEnd() }
        }
    }
    LaunchedEffect(state, row.id) {
        snapshotFlow { state.isScrollInProgress to state.firstVisibleItemIndex }
            .filter { it.first }
            .distinctUntilChanged()
            .collect { (_, i) -> rowItems.getOrNull(i)?.let { onTint(hexColor(it.dominantColor)) } }
    }

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 16.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(tokens.accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onSeeAll != null && canSeeAllRow(row)) {
                val seeAllInteraction = remember { MutableInteractionSource() }
                val seeAllFocused by seeAllInteraction.collectIsFocusedAsState()
                val btnShape = RoundedCornerShape(8.dp)
                Row(
                    modifier = Modifier
                        .clip(btnShape)
                        .then(if (seeAllFocused) Modifier.border(2.dp, tokens.focusRing, btnShape) else Modifier)
                        .clickable(
                            interactionSource = seeAllInteraction,
                            indication = null,
                            role = Role.Button,
                            onClick = onSeeAll,
                        )
                        .semantics { contentDescription = "See all ${row.title}" }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "See all",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textSecondary,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = tokens.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(
            state = state,
            contentPadding = PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            itemsIndexed(rowItems, key = { _, it -> it.id }, contentType = { _, _ -> row.style }) { index, item ->
                val key = posterKey(row.id, item.id)
                val open = { onOpen(item, key) }
                val openPlain = { onOpen(item, null) }
                when (row.style) {
                    RowStyle.POSTER -> PosterCard(item, open, onTint, sharedKey = key)
                    RowStyle.LANDSCAPE -> WideCard(item, openPlain, onTint)
                    RowStyle.TOP10 -> Top10Card(index + 1, item, open, onTint, sharedKey = key)
                    RowStyle.CONTINUE -> ContinueCard(item, openPlain, onTint)
                    RowStyle.CIRCLE -> CircleCard(item, openPlain, onTint)
                    RowStyle.CHANNEL -> ChannelCard(item, openPlain, onTint)
                }
            }
        }
    }
}
