package com.mythbrix.cinetor.feature.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.hexColor

private val ChannelColWidth = 92.dp
private val RowHeight = 62.dp
private const val DP_PER_MIN = 4f
private const val WINDOW_HOURS = 6

/**
 * TV guide: time ruler + channel column + programme blocks (time × channel). Every row and the ruler share
 * ONE horizontal [androidx.compose.foundation.ScrollState] → scrolling any row scrolls them all. Red now-line
 * runs through the ruler and every row; opens scrolled to now. Tap a programme → play its channel live.
 */
@Composable
fun EpgGrid(
    d: TvData,
    nowSec: Long,
    favorites: Set<String>,
    padding: PaddingValues,
    onPlay: (Item) -> Unit,
) {
    val tokens = Cinetor.tokens
    val windowStart = remember(nowSec / 1800) { epgWindowStart(nowSec) }
    val windowEnd = windowStart + WINDOW_HOURS * 3600L
    val totalWidth = (WINDOW_HOURS * 60 * DP_PER_MIN).dp
    val tz = remember { localOffsetSec() }
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    val nowX = epgNowLinePosition(nowSec, windowStart, DP_PER_MIN)
    LaunchedEffect(windowStart) {
        hScroll.scrollTo(with(density) { (nowX - 48f).coerceAtLeast(0f).dp.roundToPx() })
    }
    // Favourites first, then guide order.
    val channels = remember(d, favorites) { d.channels.sortedByDescending { it.id in favorites } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(30.dp)) {
            Box(Modifier.width(ChannelColWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(
                    "Now ${formatClock(nowSec, tz)}", color = tokens.accent,
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                Box(Modifier.width(totalWidth).fillMaxHeight()) {
                    for (i in 0 until WINDOW_HOURS * 2) {
                        Text(
                            formatClock(windowStart + i * 1800L, tz),
                            color = tokens.textSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.offset(x = (i * 30 * DP_PER_MIN).dp).padding(start = 4.dp, top = 6.dp),
                        )
                    }
                    Box(Modifier.offset(x = nowX.dp - 4.dp, y = 2.dp).size(8.dp).clip(CircleShape).background(tokens.accent))
                    Box(Modifier.offset(x = nowX.dp - 1.dp).width(2.dp).fillMaxHeight().background(tokens.accent))
                }
            }
        }
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier.weight(1f).semantics { contentDescription = "TV guide" },
        ) {
            items(channels, key = { it.id }, contentType = { "epgRow" }) { ch ->
                Row(Modifier.fillMaxWidth().height(RowHeight).padding(vertical = 2.dp)) {
                    ChannelCell(ch, ch.id in favorites) { onPlay(ch) }
                    Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                        Box(Modifier.width(totalWidth).fillMaxHeight()) {
                            epgBlocks(ch, d.anchorSec, windowStart, windowEnd, DP_PER_MIN).forEach { b ->
                                ProgramBlock(
                                    block = b,
                                    nowSec = nowSec,
                                    tz = tz,
                                    onClick = { onPlay(ch) },
                                    modifier = Modifier
                                        .offset(x = b.startDp.dp)
                                        .width((b.widthDp.dp - 3.dp).coerceAtLeast(1.dp))
                                        .fillMaxHeight(),
                                )
                            }
                            Box(Modifier.offset(x = nowX.dp - 1.dp).width(2.dp).fillMaxHeight().background(tokens.accent))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCell(ch: Item, favorite: Boolean, onClick: () -> Unit) {
    val dominant = remember(ch.dominantColor) { hexColor(ch.dominantColor) }
    Box(
        Modifier
            .width(ChannelColWidth)
            .fillMaxHeight()
            .padding(end = 4.dp, start = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(dominant.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Guide channel ${ch.channelNumber ?: ""}" },
    ) {
        AsyncImage(
            model = rememberSizedImage(ch.logo ?: ch.poster, 80.dp, 40.dp),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 12.dp),
        )
        Text(
            "${ch.channelNumber ?: ""}", color = Color.White, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
        )
        if (favorite) {
            Icon(
                Icons.Rounded.Star, contentDescription = null, tint = Color(0xFFFFC233),
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(12.dp),
            )
        }
    }
}

@Composable
private fun ProgramBlock(block: EpgBlock, nowSec: Long, tz: Int, onClick: () -> Unit, modifier: Modifier) {
    val tokens = Cinetor.tokens
    val airing = nowSec >= block.startSec && nowSec < block.endSec
    val past = block.endSec <= nowSec
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(
                when {
                    airing -> tokens.accent.copy(alpha = 0.26f)
                    past -> tokens.surface
                    else -> tokens.surfaceElevated
                },
            )
            .border(1.dp, if (airing) tokens.accent.copy(alpha = 0.7f) else tokens.glassStroke, shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Program ${block.program.title}" },
    ) {
        if (airing) {
            val p = ((nowSec - block.startSec).toFloat() / (block.endSec - block.startSec).coerceAtLeast(1)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(tokens.accent.copy(alpha = 0.14f)))
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                block.program.title,
                color = if (past) tokens.textTertiary else tokens.textPrimary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (airing) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatClock(block.startSec, tz)}–${formatClock(block.endSec, tz)}" + (block.program.genre?.let { " · $it" } ?: ""),
                color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
