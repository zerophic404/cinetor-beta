package com.mythbrix.cinetor.feature.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.design.Cinetor

private val QueueRowHeight = 64.dp

/**
 * Queue sheet: current track + up next. Drag the handle to reorder (moves one slot per row height
 * crossed, current track keeps playing), tap or D-pad select a row to jump, ✕ removes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(music: MusicController, onDismiss: () -> Unit) {
    val s by music.state.collectAsStateWithLifecycle()
    val queue = s.queue ?: return
    val tokens = Cinetor.tokens
    val rowPx = with(LocalDensity.current) { QueueRowHeight.toPx() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tokens.surface,
    ) {
        Column(Modifier.fillMaxWidth().semantics { contentDescription = "Queue sheet" }) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Queue",
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    listOfNotNull(
                        "${queue.items.size} tracks",
                        s.preferredQuality.shortLabel,
                        "shuffle".takeIf { queue.shuffle },
                        "repeat ${queue.repeat.name.lowercase()}".takeIf { queue.repeat != RepeatMode.OFF },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(queue.items, key = { _, t -> t.id }) { i, t ->
                    val index by rememberUpdatedState(i)
                    val size by rememberUpdatedState(queue.items.size)
                    var drag by remember { mutableFloatStateOf(0f) }
                    val current = i == queue.index
                    val rowInteraction = remember { MutableInteractionSource() }
                    val rowFocused by rowInteraction.collectIsFocusedAsState()
                    val rowShape = RoundedCornerShape(10.dp)

                    Row(
                        Modifier
                            .animateItem()
                            .zIndex(if (drag != 0f) 1f else 0f)
                            .graphicsLayer { translationY = drag }
                            .then(if (drag != 0f) Modifier.shadow(12.dp) else Modifier)
                            .fillMaxWidth()
                            .height(QueueRowHeight)
                            .padding(horizontal = 8.dp)
                            .clip(rowShape)
                            .background(
                                when {
                                    current -> tokens.accent.copy(alpha = 0.16f)
                                    rowFocused -> tokens.surfaceElevated
                                    else -> tokens.surface
                                },
                            )
                            .then(if (rowFocused) Modifier.border(2.dp, tokens.focusRing, rowShape) else Modifier)
                            .clickable(interactionSource = rowInteraction, indication = null) { music.playAt(i) }
                            .semantics { contentDescription = "Queue ${i + 1} ${t.title}" }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .pointerInput(t.id) {
                                    detectVerticalDragGestures(
                                        onDragEnd = { drag = 0f },
                                        onDragCancel = { drag = 0f },
                                    ) { change, dy ->
                                        change.consume()
                                        drag += dy
                                        val idx = index
                                        if (drag > rowPx * 0.6f && idx < size - 1) {
                                            music.move(idx, idx + 1)
                                            drag -= rowPx
                                        } else if (drag < -rowPx * 0.6f && idx > 0) {
                                            music.move(idx, idx - 1)
                                            drag += rowPx
                                        }
                                    }
                                }
                                .semantics { contentDescription = "Reorder ${t.title}" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.DragHandle, contentDescription = null, tint = tokens.textSecondary)
                        }
                        Artwork(t.poster, 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                t.title,
                                color = if (current) tokens.accent else tokens.textPrimary,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                            )
                            Text(
                                t.artist.orEmpty(),
                                color = tokens.textSecondary,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (current) {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = "Now playing", tint = tokens.accent, modifier = Modifier.padding(10.dp))
                        } else {
                            val remInteraction = remember { MutableInteractionSource() }
                            val remFocused by remInteraction.collectIsFocusedAsState()
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .then(if (remFocused) Modifier.border(2.dp, tokens.focusRing, CircleShape) else Modifier)
                                    .clickable(interactionSource = remInteraction, indication = null) { music.removeAt(i) }
                                    .semantics { contentDescription = "Remove ${t.title}" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = null, tint = tokens.textTertiary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
