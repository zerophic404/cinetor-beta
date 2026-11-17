package com.mythbrix.cinetor.feature.music

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Music overlay above the shell (like PlayerHost for video): Liquid Glass mini bar above the bottom nav,
 * or the Now Playing full screen. Draws nothing without a queue.
 */
@Composable
fun MusicHost(container: AppContainer) {
    val s by container.music.state.collectAsStateWithLifecycle()
    if (!s.active) return
    val config = LocalConfiguration.current
    val layout = navLayoutFor(config.screenWidthDp, config.screenHeightDp, container.deviceProfile.isTv)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottom = navBottom + if (layout == NavLayout.BOTTOM_BAR) BottomBarHeight + 22.dp else 12.dp

    Box(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(
            visible = !s.expanded,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom, start = 12.dp, end = 12.dp),
        ) {
            MusicMiniBar(container.music)
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = s.expanded,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlaying(container)
        }
    }
}

/**
 * Liquid Glass mini bar: artwork, title/artist + lossless badge, animated progress, play/pause,
 * next, stop; tap or swipe up opens Now Playing. Supports TV D-pad focus.
 */
@Composable
fun MusicMiniBar(music: MusicController) {
    val s by music.state.collectAsStateWithLifecycle()
    val player by music.player.collectAsStateWithLifecycle()
    val ui by (player?.state ?: idleMusicUi).collectAsStateWithLifecycle()
    val track = s.current ?: return
    val tokens = Cinetor.tokens
    val accent = remember(track.accentColor, tokens.accent) {
        runCatching { hexColor(track.accentColor) }.getOrDefault(tokens.accent)
    }
    val progress by animateFloatAsState(
        progressOf(ui),
        tween(if (ui.isPlaying) 260 else 0, easing = LinearEasing),
        label = "miniProgress",
    )
    val shape = RoundedCornerShape(18.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shortQuality = if (s.preferredQuality == MusicAudioQuality.FLAC && track.maturity != "320K") "FLAC" else "320K"
    val resolving = s.resolvingTrackId == track.id

    Column(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .glass(shape, GlassLevel.FLOATING)
            .then(if (focused) Modifier.border(2.dp, tokens.focusRing, shape) else Modifier)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null) { music.expand() }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -40f) music.expand() },
                ) { _, dy -> total += dy }
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
                Text(
                    track.title,
                    color = tokens.textPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        track.artist.orEmpty(),
                        color = tokens.textSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "· $shortQuality",
                        color = tokens.accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (resolving) {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = tokens.accent)
                }
            } else {
                MiniIcon(
                    if (ui.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (ui.playWhenReady) "Pause music" else "Play music",
                ) { music.togglePlay() }
            }
            MiniIcon(Icons.Rounded.SkipNext, "Next track") { music.next() }
            MiniIcon(Icons.Rounded.Close, "Stop music") { music.stop() }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tokens.divider),
        ) {
            Box(Modifier.fillMaxWidth(progress).height(3.dp).background(accent))
        }
        Box(Modifier.height(7.dp))
    }
}

@Composable
private fun MiniIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .then(
                if (focused) {
                    Modifier.background(tokens.accent.copy(alpha = 0.16f)).border(2.dp, tokens.focusRing, CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (focused) tokens.accent else tokens.textPrimary, modifier = Modifier.size(26.dp))
    }
}
