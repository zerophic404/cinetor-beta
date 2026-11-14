package com.mythbrix.cinetor.feature.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Season
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens

/** Season tabs ("Season 1", "Season 2", …); a single season shows an "Episodes" header. */
@Composable
fun SeasonTabs(seasons: List<Season>, selected: Int, onSelect: (Int) -> Unit) {
    val tokens = Cinetor.tokens
    if (seasons.size <= 1) {
        Text(
            "Episodes",
            style = MaterialTheme.typography.titleMedium,
            color = tokens.textPrimary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        return
    }
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(seasons, key = { _, s -> s.n }) { i, s ->
            val isSelected = i == selected
            Text(
                s.title ?: "Season ${s.n}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) tokens.onAccent else tokens.textSecondary,
                modifier = Modifier
                    .clip(shape)
                    .background(if (isSelected) tokens.accent else Color.White.copy(alpha = 0.06f))
                    .border(1.dp, if (isSelected) Color.Transparent else tokens.glassStroke, shape)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Episode row: still (exact size) + progress, number/title, runtime, synopsis. */
@Composable
fun EpisodeRow(episode: Episode, progress: Float?, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(CinetorTokens.Shape.card)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(144.dp, 81.dp).clip(shape).background(tokens.surfaceElevated)) {
            AsyncImage(
                model = rememberSizedImage(episode.still, 144.dp, 81.dp),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(144.dp, 81.dp),
            )
            if (progress != null && progress > 0f) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                        .background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(Modifier.fillMaxWidth(progress).height(3.dp).background(tokens.accent))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${episode.n}. ${episode.title}",
                style = MaterialTheme.typography.titleSmall,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${(episode.durationSec / 60).coerceAtLeast(1)}m",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
            )
            episode.synopsis?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
