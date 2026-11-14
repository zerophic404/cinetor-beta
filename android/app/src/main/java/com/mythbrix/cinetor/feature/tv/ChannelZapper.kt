package com.mythbrix.cinetor.feature.tv

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.feature.player.PlayerScreenState
import com.mythbrix.cinetor.feature.player.PlayerStateHolder
import com.mythbrix.cinetor.feature.player.atLiveEdge
import com.mythbrix.cinetor.feature.player.zapDeltaForSwipe
import kotlinx.coroutines.delay

/**
 * Live gesture layer: tap toggles controls; in fullscreen a vertical swipe zaps (up = next channel,
 * down = previous). Replaces the VOD gestures (seek/brightness/volume) for live streams.
 */
@Composable
fun LiveGestures(holder: PlayerStateHolder, modifier: Modifier, onToggleControls: () -> Unit) {
    val fullscreen = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val toggle by rememberUpdatedState(onToggleControls)
    val fs by rememberUpdatedState(fullscreen)
    Box(
        modifier
            .pointerInput(holder) { detectTapGestures(onTap = { toggle() }) }
            .pointerInput(holder) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onVerticalDrag = { change, dy ->
                        if (fs) {
                            total += dy
                            change.consume()
                        }
                    },
                    onDragEnd = {
                        val d = zapDeltaForSwipe(total, threshold)
                        if (fs && d != 0) holder.zap(d)
                        total = 0f
                    },
                )
            },
    )
}

/** Red LIVE (at the edge) or grey "Go live" pill (behind, DVR) for the live control bar. */
@Composable
fun LiveEdgeBadge(ui: PlayerUiState, onGoLive: () -> Unit) {
    val edge = atLiveEdge(ui)
    val tokens = Cinetor.tokens
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (edge) tokens.liveBadge else Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onGoLive)
            .semantics { contentDescription = if (edge) "Live" else "Go to live" }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (edge) Color.White else tokens.liveBadge))
        Text(if (edge) "LIVE" else "GO LIVE", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

/**
 * TV-style on-screen display: channel number + name + now/next for 3 s after every zap, and the digits
 * typed on a remote. Drawn over the player (not part of the auto-hiding controls).
 */
@Composable
fun BoxScope.LiveOsd(screen: PlayerScreenState, data: TvData?, nowSec: Long) {
    val item = screen.item ?: return
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(screen.generation) {
        visible = true
        delay(3_000)
        visible = false
    }
    val safe = Modifier.windowInsetsPadding(WindowInsets.displayCutout)
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart).then(safe).padding(top = 64.dp, start = 16.dp)) {
        val nn = data?.let { nowNext(item, nowSec, it.anchorSec) }
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xE0101010))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                item.channelNumber?.toString() ?: "LIVE", color = Cinetor.tokens.accent,
                fontSize = 26.sp, fontWeight = FontWeight.Black,
            )
            Column(Modifier.width(220.dp)) {
                Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                nn?.now?.let { Text("Now: ${it.title}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                nn?.next?.let { Text("Next: ${it.title}", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                nn?.takeIf { it.now != null }?.let { ProgressLine(it.progress, Modifier.fillMaxWidth().padding(top = 6.dp)) }
            }
        }
    }
    screen.channelInput?.let { digits ->
        Text(
            digits, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.TopEnd).then(safe).padding(24.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 16.dp, vertical = 6.dp)
                .semantics { contentDescription = "Channel input $digits" },
        )
    }
}

/** Quick channel list overlay (right side): number, logo, name, now playing; current highlighted. */
@Composable
fun BoxScope.QuickChannelList(
    visible: Boolean,
    screen: PlayerScreenState,
    data: TvData?,
    nowSec: Long,
    onPick: (Item) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onDismiss))
    }
    AnimatedVisibility(
        visible,
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it },
        modifier = Modifier.align(Alignment.CenterEnd),
    ) {
        val list = screen.channels
        val state = rememberLazyListState(initialFirstVisibleItemIndex = list.indexOfFirst { it.id == screen.item?.id }.coerceAtLeast(0))
        Column(
            Modifier.fillMaxHeight().width(320.dp).background(Color(0xF20C0C0C))
                .windowInsetsPadding(WindowInsets.displayCutout).padding(top = 12.dp)
                .semantics { contentDescription = "Quick channel list" },
        ) {
            Text("Channels", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(state = state) {
                items(list, key = { it.id }) { ch ->
                    val current = ch.id == screen.item?.id
                    val nn = data?.let { nowNext(ch, nowSec, it.anchorSec) }
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (current) Cinetor.tokens.accent.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { onPick(ch) }
                            .semantics { contentDescription = "Switch to ${ch.title}" }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            ch.channelNumber?.toString() ?: "•", color = if (current) Cinetor.tokens.accent else Color.White.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp),
                        )
                        AsyncImage(
                            model = rememberSizedImage(ch.logo ?: ch.poster, 48.dp, 28.dp),
                            contentDescription = null, contentScale = ContentScale.Fit,
                            modifier = Modifier.size(48.dp, 28.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(ch.title, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                nn?.now?.title ?: (ch.league ?: ""), color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
