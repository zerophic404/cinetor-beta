package com.mythbrix.cinetor.feature.details

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Season
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.feature.downloads.DownloadedBadge

/**
 * Seasons & Episodes header + Liquid Glass season selector pills ("Season 1", "Season 2", …).
 */
@Composable
fun SeasonTabs(seasons: List<Season>, selected: Int, onSelect: (Int) -> Unit) {
    val tokens = Cinetor.tokens
    val safeSelected = selected.coerceIn(0, (seasons.size - 1).coerceAtLeast(0))
    val activeSeason = seasons.getOrNull(safeSelected)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tokens.accent),
            )
            Text(
                if (seasons.size > 1) "Seasons & Episodes" else "Episodes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tokens.textPrimary,
            )
            activeSeason?.let { s ->
                Text(
                    "${s.episodes.size} ${if (s.episodes.size == 1) "Episode" else "Episodes"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                )
            }
        }

        if (seasons.size > 1) {
            val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(seasons, key = { idx, s -> "season-tab-$idx-${s.n}" }) { i, s ->
                    val isSelected = i == safeSelected
                    val interaction = remember { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    val label = s.title ?: "Season ${s.n}"

                    Row(
                        Modifier
                            .clip(shape)
                            .then(
                                if (isSelected) Modifier.background(tokens.accent)
                                else Modifier.glass(shape, GlassLevel.SURFACE),
                            )
                            .border(
                                width = if (focused) 2.dp else 1.dp,
                                color = when {
                                    focused -> tokens.focusRing
                                    isSelected -> Color.Transparent
                                    else -> tokens.glassStroke
                                },
                                shape = shape,
                            )
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                role = Role.Tab,
                            ) { onSelect(i) }
                            .semantics { contentDescription = label }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) tokens.onAccent else tokens.textPrimary,
                        )
                        Text(
                            "${s.episodes.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) tokens.onAccent.copy(alpha = 0.85f) else tokens.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shiopa-style Episode card: 16:9 still with play emblem (or active Playing indicator),
 * red watch-progress line, episode number/title, runtime + watched/playing status,
 * 2-line synopsis, and per-episode download action.
 */
@Composable
fun EpisodeRow(
    episode: Episode,
    progress: Float?,
    downloaded: Boolean = false,
    watched: Boolean = false,
    playing: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val cardShape = RoundedCornerShape(CinetorTokens.Shape.card)
    val thumbShape = RoundedCornerShape(CinetorTokens.Shape.sm)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val effectiveProgress = when {
        watched -> 1f
        progress != null && progress > 0f -> progress.coerceIn(0f, 1f)
        else -> 0f
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(cardShape)
            .then(
                if (playing) {
                    Modifier
                        .background(tokens.accent.copy(alpha = 0.12f))
                        .border(
                            width = if (focused) 2.dp else 1.5.dp,
                            color = if (focused) tokens.focusRing else tokens.accent,
                            shape = cardShape,
                        )
                } else {
                    Modifier
                        .glass(cardShape, GlassLevel.SURFACE)
                        .then(if (focused) Modifier.border(2.dp, tokens.focusRing, cardShape) else Modifier)
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Play episode ${episode.n}",
                onClick = onClick,
            )
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(136.dp, 77.dp)
                .clip(thumbShape)
                .background(tokens.surfaceElevated),
        ) {
            AsyncImage(
                model = rememberSizedImage(episode.still, 136.dp, 77.dp),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (playing) {
                Row(
                    Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(CinetorTokens.Shape.pill))
                        .background(tokens.accent.copy(alpha = 0.92f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(tokens.onAccent))
                    Text(
                        "Playing",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = tokens.onAccent,
                    )
                }
            } else {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.52f))
                        .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (downloaded) {
                DownloadedBadge(Modifier.align(Alignment.TopStart).padding(4.dp))
            }
            if (effectiveProgress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.45f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(effectiveProgress)
                            .height(3.dp)
                            .background(tokens.accent),
                    )
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "${episode.n}. ${episode.title}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.SemiBold,
                color = if (playing) tokens.accent else tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (playing) {
                    Text(
                        "Now playing",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.accent,
                    )
                }
                if (episode.durationSec > 0) {
                    Text(
                        "${(episode.durationSec / 60).coerceAtLeast(1)}m",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.textSecondary,
                    )
                }
                if (watched && !playing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = tokens.success, modifier = Modifier.size(12.dp))
                        Text("Watched", style = MaterialTheme.typography.labelSmall, color = tokens.success)
                    }
                }
            }
            episode.synopsis?.takeIf { it.isNotBlank() }?.let { syn ->
                Text(
                    syn,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}
