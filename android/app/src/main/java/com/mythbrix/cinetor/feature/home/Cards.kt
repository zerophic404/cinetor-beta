package com.mythbrix.cinetor.feature.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.EventStatus
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.core.ui.sharedPoster
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.hexColor

private val CardShape = RoundedCornerShape(CinetorTokens.Shape.card)

/** Deterministic mock watch progress (0.15–0.85) until playback history exists (Phase 3/5). */
fun mockProgress(id: String): Float = ((id.hashCode() and 0x7fffffff) % 71 + 15) / 100f

/**
 * Shared card chrome: press/focus scales 1.0→1.08 with a glow in the item's accent colour;
 * becoming active reports the item colour (→ debounced app tint).
 */
@Composable
fun CardFrame(
    item: Item,
    onClick: () -> Unit,
    onActive: (Color) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active = pressed || focused
    val scale by animateFloatAsState(if (active) CinetorTokens.Motion.cardFocusScale else 1f, label = "cardScale")
    val glow by animateDpAsState(if (active) 18.dp else 0.dp, label = "cardGlow")
    val dominant = remember(item.dominantColor) { hexColor(item.dominantColor) }
    val accent = remember(item.accentColor) { hexColor(item.accentColor) }
    LaunchedEffect(active) { if (active) onActive(dominant) }
    Box(
        modifier
            .zIndex(if (active) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(glow, shape, clip = false, ambientColor = accent, spotColor = accent)
            .clip(shape)
            .background(dominant)
            .border(
                if (active) 2.dp else 1.dp,
                if (active) accent.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.06f),
                shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = item.title },
        content = content,
    )
}

/** 2:3 poster. [sharedKey] makes it the shared element into Details. */
@Composable
fun PosterCard(
    item: Item,
    onClick: () -> Unit,
    onActive: (Color) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 112.dp,
    sharedKey: String? = null,
) {
    val height = width * 1.5f
    CardFrame(item, onClick, onActive, modifier.sharedPoster(sharedKey).size(width, height)) {
        AsyncImage(
            model = rememberSizedImage(item.poster, width, height),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 16:9 backdrop tile with title. */
@Composable
fun LandscapeCard(item: Item, onClick: () -> Unit, onActive: (Color) -> Unit, width: Dp = 220.dp) {
    val height = width * 9f / 16f
    val tokens = Cinetor.tokens
    CardFrame(item, onClick, onActive, Modifier.size(width, height)) {
        AsyncImage(
            model = rememberSizedImage(item.backdrop, width, height),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)),
            ),
        )
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            color = tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
        )
    }
}

/** Top 10: big outlined numeral behind/left of the poster. */
@Composable
fun Top10Card(rank: Int, item: Item, onClick: () -> Unit, onActive: (Color) -> Unit, sharedKey: String?) {
    val tokens = Cinetor.tokens
    Box(Modifier.width(if (rank >= 10) 190.dp else 160.dp).height(168.dp)) {
        Text(
            rank.toString(),
            style = TextStyle(
                fontSize = 128.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-8).sp,
                drawStyle = Stroke(width = 5f, join = StrokeJoin.Round),
            ),
            color = tokens.textPrimary.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.BottomStart).offset(y = 26.dp),
        )
        PosterCard(
            item, onClick, onActive,
            modifier = Modifier.align(Alignment.BottomEnd),
            width = 112.dp,
            sharedKey = sharedKey,
        )
    }
}

/** Continue watching: 16:9 tile + progress bar + time left. */
@Composable
fun ContinueCard(item: Item, onClick: () -> Unit, onActive: (Color) -> Unit, width: Dp = 200.dp) {
    val tokens = Cinetor.tokens
    val height = width * 9f / 16f
    val progress = remember(item.id) { mockProgress(item.id) }
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CardFrame(item, onClick, onActive, Modifier.size(width, height)) {
            AsyncImage(
                model = rememberSizedImage(item.backdrop, width, height),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White)
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(progress).height(4.dp).background(tokens.accent))
            }
        }
        Text(item.title, style = MaterialTheme.typography.labelLarge, color = tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val total = (item.durationSec ?: 2700) / 60
        Text(
            "${(total * (1f - progress)).toInt().coerceAtLeast(1)} min left",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textSecondary,
        )
    }
}

/** Circle (artists). */
@Composable
fun CircleCard(item: Item, onClick: () -> Unit, onActive: (Color) -> Unit, size: Dp = 96.dp) {
    val tokens = Cinetor.tokens
    Column(Modifier.width(size + 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CardFrame(item, onClick, onActive, Modifier.size(size), shape = CircleShape) {
            AsyncImage(
                model = rememberSizedImage(item.poster, size, size),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.artist ?: item.title,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Channel / event tile: logo on brand colour + LIVE badge + channel number. */
@Composable
fun ChannelCard(item: Item, onClick: () -> Unit, onActive: (Color) -> Unit, width: Dp = 168.dp) {
    val tokens = Cinetor.tokens
    val height = width * 9f / 16f
    val live = item.live || item.status == EventStatus.LIVE
    val dominant = remember(item.dominantColor) { hexColor(item.dominantColor) }
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CardFrame(item, onClick, onActive, Modifier.size(width, height)) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(dominant, Color.Black.copy(alpha = 0.9f))),
                ),
            )
            AsyncImage(
                model = rememberSizedImage(item.logo ?: item.poster, width, height),
                contentDescription = null,
                contentScale = if (item.logo != null) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(if (item.logo != null) 18.dp else 0.dp),
            )
            Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                if (live) {
                    Text(
                        "LIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(tokens.liveBadge)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                } else {
                    Spacer(Modifier.size(1.dp))
                }
                item.channelNumber?.let {
                    Text(
                        it.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(item.title, style = MaterialTheme.typography.labelLarge, color = tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
