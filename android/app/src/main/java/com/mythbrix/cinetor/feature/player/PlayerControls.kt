package com.mythbrix.cinetor.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.feature.tv.LiveEdgeBadge

/** Glass circle button for the player (dark translucent fill + hairline stroke; video is not blur-sourced). */
@Composable
fun PlayerIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.38f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

/**
 * Auto-hiding glass controls over the video, with no catch-all Settings button: its items live in
 * the two existing rows. Top: minimize, title, then Speed, Sleep timer, PiP, Lock. Bottom: seek
 * bar, time, then Audio, Subtitles, Quality, Episodes, Next, Aspect ratio, Fullscreen.
 * Inline keeps the compact icon-only form. Locked → only the unlock button.
 */
@Composable
fun PlayerControls(
    screen: PlayerScreenState,
    ui: PlayerUiState,
    visible: Boolean,
    fullscreen: Boolean,
    onInteract: () -> Unit,
    onBack: () -> Unit,
    onRotate: () -> Unit,
    /** Opens one player sheet page directly (Quality, Speed, Audio, Subtitles, Sleep timer). */
    onOpenPage: (SettingsPage) -> Unit,
    onEpisodes: () -> Unit,
    holder: PlayerStateHolder,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    onPip: (() -> Unit)? = null,
    extraTopActions: @Composable () -> Unit = {},
) {
    val safe = if (fullscreen && !inline) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            if (screen.locked) {
                Box(Modifier.fillMaxSize().then(safe)) {
                    PlayerIconButton(
                        Icons.Rounded.Lock, "Unlock controls",
                        onClick = { holder.toggleLock(); onInteract() },
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp),
                        size = 52.dp,
                    )
                }
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.62f),
                            0.3f to Color.Transparent,
                            0.65f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
                ) {
                    Box(Modifier.fillMaxSize().then(safe).padding(horizontal = if (fullscreen && !inline) 20.dp else 8.dp, vertical = 6.dp)) {
                        TopBar(screen, ui, fullscreen, inline, onBack, onPip, holder, onInteract, onOpenPage, extraTopActions)
                        CenterButtons(screen, ui, holder, onInteract, big = fullscreen && !inline)
                        BottomBar(screen, ui, fullscreen, inline, holder, onInteract, onRotate, onEpisodes, onOpenPage)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.TopBar(
    screen: PlayerScreenState,
    ui: PlayerUiState,
    fullscreen: Boolean,
    inline: Boolean,
    onBack: () -> Unit,
    onPip: (() -> Unit)?,
    holder: PlayerStateHolder,
    onInteract: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    extraTopActions: @Composable () -> Unit,
) {
    val btnSize = if (inline) 36.dp else 40.dp
    val icSize = if (inline) 18.dp else 20.dp
    Row(
        Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PlayerIconButton(Icons.Rounded.KeyboardArrowDown, "Minimize player", onBack, size = btnSize, iconSize = icSize)
        Column(Modifier.weight(1f)) {
            Text(
                screen.title, color = Color.White, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            screen.subtitle?.let {
                Text(it, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        extraTopActions()
        if (!screen.live) {
            PlayerControlIcon(Icons.Rounded.Speed, "Playback speed ${speedLabel(ui.speed)}") {
                onOpenPage(SettingsPage.SPEED); onInteract()
            }
            if (!inline) {
                PlayerControlIcon(Icons.Rounded.Bedtime, "Sleep timer ${screen.sleep.label}") {
                    onOpenPage(SettingsPage.SLEEP); onInteract()
                }
            }
        }
        if (onPip != null) {
            PlayerIconButton(Icons.Rounded.PictureInPictureAlt, "Picture in picture", { onPip(); onInteract() }, size = btnSize, iconSize = icSize)
        }
        if (!inline) {
            PlayerIconButton(Icons.Rounded.LockOpen, "Lock controls", { holder.toggleLock(); onInteract() }, size = btnSize, iconSize = icSize)
        }
    }
}

@Composable
private fun BoxScope.CenterButtons(screen: PlayerScreenState, ui: PlayerUiState, holder: PlayerStateHolder, onInteract: () -> Unit, big: Boolean) {
    val zap = screen.live && screen.channels.size > 1
    val seekable = !screen.live || hasDvrWindow(ui)
    Row(
        Modifier.align(Alignment.Center),
        horizontalArrangement = Arrangement.spacedBy(if (big) 48.dp else 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (zap) {
            PlayerIconButton(Icons.Rounded.SkipPrevious, "Previous channel", { holder.zap(-1); onInteract() }, size = if (big) 52.dp else 42.dp)
        } else if (seekable) {
            PlayerIconButton(Icons.Rounded.Replay10, "Back 10 seconds", { holder.seekBy(-10_000); onInteract() }, size = if (big) 52.dp else 42.dp)
        }
        Box(contentAlignment = Alignment.Center) {
            val icon = when {
                ui.ended -> Icons.Rounded.Replay
                ui.playWhenReady -> Icons.Rounded.Pause
                else -> Icons.Rounded.PlayArrow
            }
            PlayerIconButton(
                icon, if (ui.playWhenReady) "Pause" else "Play",
                { holder.togglePlay(); onInteract() },
                size = if (big) 72.dp else 56.dp, iconSize = if (big) 40.dp else 32.dp,
            )
            if (ui.buffering && ui.playWhenReady) {
                CircularProgressIndicator(
                    color = Cinetor.tokens.accent, strokeWidth = 3.dp,
                    modifier = Modifier.size(if (big) 76.dp else 60.dp),
                )
            }
        }
        if (zap) {
            PlayerIconButton(Icons.Rounded.SkipNext, "Next channel", { holder.zap(1); onInteract() }, size = if (big) 52.dp else 42.dp)
        } else if (seekable) {
            PlayerIconButton(Icons.Rounded.Forward10, "Forward 10 seconds", { holder.seekBy(10_000); onInteract() }, size = if (big) 52.dp else 42.dp)
        }
    }
}

@Composable
private fun BoxScope.BottomBar(
    screen: PlayerScreenState,
    ui: PlayerUiState,
    fullscreen: Boolean,
    inline: Boolean,
    holder: PlayerStateHolder,
    onInteract: () -> Unit,
    onRotate: () -> Unit,
    onEpisodes: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
) {
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
        val marks = buildList {
            val s = screen.introStartMs
            val e = screen.introEndMs
            if (s != null && e != null && e > s) add(s..e)
        }
        if (!screen.live || hasDvrWindow(ui)) {
            SeekBar(
                positionMs = ui.positionMs,
                bufferedMs = ui.bufferedMs,
                durationMs = ui.durationMs,
                marks = marks,
                onSeek = { holder.seekTo(it); onInteract() },
                onScrubbing = { onInteract() },
            )
        }
        val timeLabel: @Composable () -> Unit = {
            if (screen.live) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LiveEdgeBadge(ui) { holder.goLive(); onInteract() }
                    if (!atLiveEdge(ui)) {
                        Text("−${formatTime(ui.durationMs - ui.positionMs)}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else {
                Text(
                    "${formatTime(ui.positionMs)} / ${formatTime(ui.durationMs)}",
                    color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                )
            }
        }
        val controls: @Composable () -> Unit = {
            if (!screen.live) {
                PlayerControlIcon(Icons.Rounded.Audiotrack, "Audio") { onOpenPage(SettingsPage.AUDIO); onInteract() }
                PlayerControlIcon(Icons.Rounded.ClosedCaption, "Subtitles") { onOpenPage(SettingsPage.SUBTITLES); onInteract() }
                PlayerControlIcon(Icons.Rounded.HighQuality, "Quality ${qualityLabel(ui, screen)}") { onOpenPage(SettingsPage.QUALITY); onInteract() }
            }
            if (!inline && screen.episodes.isNotEmpty()) {
                PlayerControlIcon(Icons.Rounded.VideoLibrary, "Episodes") { onEpisodes(); onInteract() }
            }
            if (screen.nextEpisode != null) {
                PlayerControlIcon(Icons.Rounded.SkipNext, "Next episode") { holder.next(); onInteract() }
            }
            if (!inline) {
                PlayerControlIcon(Icons.Rounded.AspectRatio, "Aspect ratio ${screen.aspect.label}") { holder.cycleAspect(); onInteract() }
            }
            val fsExpanded = fullscreen && !inline
            PlayerControlIcon(
                if (fsExpanded) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                if (fsExpanded) "Exit fullscreen" else "Fullscreen",
            ) { onRotate(); onInteract() }
        }
        // Narrow (portrait fullscreen, vertical videos): time on its own line, controls spread evenly
        // beneath it, so nothing overlaps or gets clipped. Wide: one row, controls on the right.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < NARROW_CONTROLS_WIDTH && !inline) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    timeLabel()
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) { controls() }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    timeLabel()
                    Spacer(Modifier.weight(1f))
                    controls()
                }
            }
        }
    }
}

/** Below this width the bottom controls wrap under the time label (portrait fullscreen). */
private val NARROW_CONTROLS_WIDTH = 520.dp

@Composable
private fun PlayerControlIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    PlayerIconButton(icon, description, onClick, size = 36.dp, iconSize = 18.dp)
}

/** Skip Intro pill + autoplay-next countdown card (shown even when controls are hidden). */
@Composable
fun BoxScope.PlayerOverlays(screen: PlayerScreenState, ui: PlayerUiState, holder: PlayerStateHolder, fullscreen: Boolean) {
    val pad = if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(24.dp) else Modifier.padding(12.dp)
    Column(
        Modifier.align(Alignment.BottomEnd).then(pad).padding(bottom = 52.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AnimatedVisibility(
            screen.showSkipIntro(ui.positionMs) && !screen.locked,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Text(
                "Skip Intro",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.92f))
                    .clickable { holder.skipIntro() }
                    .semantics { contentDescription = "Skip Intro" }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        val countdown = screen.nextCountdown
        val next = screen.nextEpisode
        AnimatedVisibility(countdown != null && next != null, enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier
                    .width(230.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xE6101010))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Next episode in ${countdown ?: 0}s", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                Text(
                    next?.let { "${it.n}. ${it.title}" }.orEmpty(), color = Color.White,
                    style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Play now", color = Color.White, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Cinetor.tokens.accent)
                            .clickable { holder.next() }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    Text(
                        "Cancel", color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.12f))
                            .clickable { holder.cancelAutoplay() }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
