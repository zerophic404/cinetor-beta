package com.mythbrix.cinetor.feature.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.design.hexColor
import com.mythbrix.cinetor.navigation.BottomBarHeight
import com.mythbrix.cinetor.navigation.NavLayout
import com.mythbrix.cinetor.navigation.navLayoutFor

/** Space the mini bar occupies above the nav (shell adds it to content padding while music is active). */
val MusicMiniBarHeight = 72.dp

/**
 * Music overlay above the shell (like PlayerHost for video): glass mini bar above the bottom nav, or the
 * Now Playing full screen. Draws nothing without a queue.
 */
@Composable
fun MusicHost(container: AppContainer) {
    val s by container.music.state.collectAsStateWithLifecycle()
    if (!s.active) return
    val config = LocalConfiguration.current
    val layout = navLayoutFor(config.screenWidthDp, config.screenHeightDp, container.deviceProfile.isTv)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Bottom bar = nav inset + 8 dp margin + bar + 8 dp margin.
    val bottom = navBottom + if (layout == NavLayout.BOTTOM_BAR) BottomBarHeight + 22.dp else 12.dp
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = !s.expanded,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom, start = 12.dp, end = 12.dp),
        ) {
            MusicMiniBar(container.music)
        }
        AnimatedVisibility(
            visible = s.expanded,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlaying(container)
        }
    }
}

/** Glass mini bar: artwork, title/artist, animated progress, play/pause, next, stop; tap/swipe up = Now Playing. */
@Composable
fun MusicMiniBar(music: MusicController) {
    val s by music.state.collectAsStateWithLifecycle()
    val player by music.player.collectAsStateWithLifecycle()
    val ui by (player?.state ?: idleMusicUi).collectAsStateWithLifecycle()
    val track = s.current ?: return
    val tokens = Cinetor.tokens
    val accent = remember(track.accentColor) { hexColor(track.accentColor) }
    val progress by animateFloatAsState(progressOf(ui), tween(if (ui.isPlaying) 260 else 0, easing = LinearEasing), label = "miniProgress")
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .glass(shape, GlassLevel.FLOATING)
            .clip(shape)
            .clickable { music.expand() }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total < -40f) music.expand() }) { _, dy -> total += dy }
            }
            .semantics { contentDescription = "Open now playing" },
    ) {
        Row(
            Modifier.fillMaxWidth().height(MusicMiniBarHeight - 10.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Artwork(track.poster, 46.dp, corner = 10.dp)
            Column(Modifier.weight(1f)) {
                Text(track.title, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist.orEmpty(), color = tokens.textSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MiniIcon(if (ui.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (ui.playWhenReady) "Pause music" else "Play music") { music.togglePlay() }
            MiniIcon(Icons.Rounded.SkipNext, "Next track") { music.next() }
            MiniIcon(Icons.Rounded.Close, "Stop music") { music.stop() }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxWidth(progress).height(3.dp).background(accent))
        }
        Box(Modifier.height(7.dp))
    }
}

@Composable
private fun MiniIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Cinetor.tokens.textPrimary, modifier = Modifier.size(26.dp))
    }
}
