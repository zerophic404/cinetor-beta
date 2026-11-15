package com.mythbrix.cinetor.tvui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.player.CinetorPlayer
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.feature.player.PlayerLayers
import com.mythbrix.cinetor.feature.player.PlayerOverlays
import com.mythbrix.cinetor.feature.player.PlayerScreenState
import com.mythbrix.cinetor.feature.player.PlayerStateHolder
import com.mythbrix.cinetor.feature.player.SettingsSheet
import com.mythbrix.cinetor.feature.player.SubtitleExtras
import com.mythbrix.cinetor.feature.player.VideoSurface
import com.mythbrix.cinetor.feature.player.formatTime
import kotlinx.coroutines.delay

/**
 * 10-foot player: no gestures. Hidden overlay: Left/Right ±10 s (hold = accelerating scrub),
 * OK play/pause, Up = controls (settings row focused), Down = episode/related rail, Back = exit.
 * Controls/rail shown: D-pad moves focus, Back hides. Media keys always work. Live: Up/Down zap
 * (MainActivity), CH± and digits too.
 */
@Composable
fun TvPlayerScreen(
    screen: PlayerScreenState,
    cinetorPlayer: CinetorPlayer?,
    ui: PlayerUiState,
    holder: PlayerStateHolder,
    onExit: () -> Unit,
    layers: PlayerLayers = PlayerLayers(),
    subtitleExtras: SubtitleExtras = SubtitleExtras(),
    related: suspend (Item) -> List<Item> = { emptyList() },
) {
    val view = LocalView.current
    DisposableEffect(view, ui.isPlaying) {
        view.keepScreenOn = ui.isPlaying
        onDispose { view.keepScreenOn = false }
    }
    val keys = remember { TvPlayerKeys() }
    var overlay by remember { mutableStateOf(TvOverlay.NONE) }
    var focusSettings by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    val catcher = remember { FocusRequester() }
    val playBtn = remember { FocusRequester() }
    val settingsBtn = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }

    LaunchedEffect(screen.generation) { overlay = TvOverlay.CONTROLS; interaction++ }
    // Controls auto-hide after 6 s of no input while playing.
    LaunchedEffect(overlay, interaction, ui.isPlaying, showSettings) {
        if (overlay == TvOverlay.CONTROLS && ui.isPlaying && !showSettings) {
            delay(6_000)
            overlay = TvOverlay.NONE
        }
    }
    // Move focus with the overlay: catcher (keys) / play or settings button / rail.
    LaunchedEffect(overlay, showSettings) {
        if (showSettings) return@LaunchedEffect
        withFrameNanos { }
        runCatching {
            when (overlay) {
                TvOverlay.NONE -> catcher.requestFocus()
                TvOverlay.CONTROLS -> if (focusSettings) settingsBtn.requestFocus() else playBtn.requestFocus()
                TvOverlay.RAIL -> railFocus.requestFocus()
            }
        }
    }
    // Scrub preview clears shortly after a tap.
    LaunchedEffect(scrubTarget) {
        if (scrubTarget != null && keys.scrubber.target == null) {
            delay(2_000)
            scrubTarget = null
        }
    }
    BackHandler {
        when {
            overlay != TvOverlay.NONE -> overlay = TvOverlay.NONE
            else -> onExit()
        }
    }

    fun handle(a: TvPlayerAction): Boolean = when (a) {
        is TvPlayerAction.Scrub -> {
            scrubTarget = a.targetMs
            if (a.commit) holder.seekTo(a.targetMs)
            true
        }
        is TvPlayerAction.SeekTo -> { holder.seekTo(a.targetMs); scrubTarget = a.targetMs; true }
        TvPlayerAction.TogglePlay -> {
            holder.togglePlay()
            // OK while hidden: play/pause + bring up the controls (play button focused).
            if (overlay == TvOverlay.NONE) { focusSettings = false; overlay = TvOverlay.CONTROLS }
            interaction++
            true
        }
        TvPlayerAction.Play -> { holder.resumePlay(); true }
        TvPlayerAction.Pause -> { holder.pause(); true }
        is TvPlayerAction.ShowControls -> { focusSettings = a.focusSettings; overlay = TvOverlay.CONTROLS; interaction++; true }
        TvPlayerAction.OpenRail -> { overlay = TvOverlay.RAIL; true }
        TvPlayerAction.OpenSettings -> { showSettings = true; true }
        TvPlayerAction.Hide -> { overlay = TvOverlay.NONE; true }
        TvPlayerAction.Exit -> { onExit(); true }
        TvPlayerAction.Next -> { holder.next(); true }
        TvPlayerAction.Consumed -> true
        TvPlayerAction.PassThrough -> false
    }

    val position = scrubTarget ?: ui.positionMs
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (showSettings) return@onPreviewKeyEvent false
                val key = tvKeyOf(e.nativeKeyEvent.keyCode)
                if (key == TvKey.OTHER) return@onPreviewKeyEvent false
                if (overlay != TvOverlay.NONE) interaction++
                // Back handled here on key-up (hide overlay → exit), consumed on both edges so the
                // focus system / NavHost never see it.
                if (key == TvKey.BACK) {
                    if (e.type == KeyEventType.KeyUp) {
                        if (overlay != TvOverlay.NONE) overlay = TvOverlay.NONE else onExit()
                    }
                    return@onPreviewKeyEvent true
                }
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        handle(
                            keys.onKeyDown(
                                key, e.nativeKeyEvent.repeatCount, overlay, ui.positionMs, ui.durationMs,
                                seekable = ui.seekable && !screen.live, live = screen.live,
                            ),
                        )
                    }
                    KeyEventType.KeyUp -> handle(keys.onKeyUp(key))
                    else -> false
                }
            },
    ) {
        VideoSurface(cinetorPlayer?.media3Player(), screen.aspect, Modifier.fillMaxSize(), layers.zoom, layers.configureView)
        layers.overVideo()
        // Invisible key catcher: holds focus while no overlay is shown.
        Box(Modifier.fillMaxSize().focusRequester(catcher).focusable().semantics { contentDescription = "Player" })
        if (cinetorPlayer == null || (ui.buffering && ui.playWhenReady)) {
            CircularProgressIndicator(color = Cinetor.tokens.accent, modifier = Modifier.align(Alignment.Center).size(56.dp))
        }
        ui.error?.let { err ->
            Text(
                "Playback error: $err", color = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(16.dp),
            )
        }
        // Scrub OSD (also while controls are hidden).
        if (scrubTarget != null && overlay == TvOverlay.NONE) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 64.dp, vertical = 40.dp)) {
                Text("${formatTime(position)} / ${formatTime(ui.durationMs)}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                TvProgress(position, ui.bufferedMs, ui.durationMs)
            }
        }
        AnimatedVisibility(
            visible = overlay == TvOverlay.CONTROLS,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            TvControls(
                screen = screen,
                ui = ui,
                position = position,
                playBtn = playBtn,
                settingsBtn = settingsBtn,
                onTogglePlay = { holder.togglePlay(); interaction++ },
                onSeek = { d -> holder.seekBy(d); interaction++ },
                onNext = holder::next,
                onEpisodes = { overlay = TvOverlay.RAIL },
                onSettings = { showSettings = true },
            )
        }
        AnimatedVisibility(
            visible = overlay == TvOverlay.RAIL,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            TvRail(screen, railFocus, related, onPickEpisode = { holder.playEpisode(it); overlay = TvOverlay.NONE }) { item ->
                holder.play(item)
                overlay = TvOverlay.NONE
            }
        }
        PlayerOverlays(screen, ui, holder, fullscreen = true)
    }

    if (showSettings && cinetorPlayer != null) {
        SettingsSheet(ui, screen, holder, onDismiss = { showSettings = false; interaction++ }, extras = subtitleExtras)
    }
}

@Composable
private fun TvProgress(position: Long, buffered: Long, duration: Long) {
    val tokens = Cinetor.tokens
    val d = duration.coerceAtLeast(1).toFloat()
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxWidth((buffered / d).coerceIn(0f, 1f)).height(6.dp).background(Color.White.copy(alpha = 0.35f)))
        Box(Modifier.fillMaxWidth((position / d).coerceIn(0f, 1f)).height(6.dp).background(tokens.accent))
    }
}

@Composable
private fun TvControls(
    screen: PlayerScreenState,
    ui: PlayerUiState,
    position: Long,
    playBtn: FocusRequester,
    settingsBtn: FocusRequester,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onEpisodes: () -> Unit,
    onSettings: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.35f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)),
        ),
    ) {
        // Top: title + settings row (Up lands here).
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 64.dp, vertical = 36.dp).focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(screen.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                screen.subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.titleSmall) }
            }
            TvButton("Settings", Icons.Rounded.Settings, onSettings, Modifier.focusRequester(settingsBtn))
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 64.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (screen.live) {
                Text("● LIVE", color = Cinetor.tokens.accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(position), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Box(Modifier.weight(1f).padding(horizontal = 16.dp)) { TvProgress(position, ui.bufferedMs, ui.durationMs) }
                    Text(formatTime(ui.durationMs), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!screen.live) TvButton("−10s", Icons.Rounded.Replay10, { onSeek(-10_000) })
                TvButton(
                    if (ui.playWhenReady) "Pause" else "Play",
                    if (ui.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    onTogglePlay, Modifier.focusRequester(playBtn), primary = true,
                )
                if (!screen.live) TvButton("+10s", Icons.Rounded.Forward10, { onSeek(10_000) })
                if (screen.nextEpisode != null) TvButton("Next", Icons.Rounded.SkipNext, onNext)
                if (!screen.live) TvButton(if (screen.episodes.isNotEmpty()) "Episodes" else "More", Icons.AutoMirrored.Rounded.List, onEpisodes)
            }
        }
    }
}

/** Down rail: episodes of the current series, else related titles (same genre). */
@Composable
private fun TvRail(
    screen: PlayerScreenState,
    firstFocus: FocusRequester,
    related: suspend (Item) -> List<Item>,
    onPickEpisode: (String) -> Unit,
    onPickItem: (Item) -> Unit,
) {
    val item = screen.item
    val relatedItems by produceState(emptyList<Item>(), item?.id) {
        value = if (item != null && screen.episodes.isEmpty()) runCatching { related(item) }.getOrDefault(emptyList()) else emptyList()
    }
    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
            .padding(top = 40.dp, bottom = 36.dp),
    ) {
        Text(
            if (screen.episodes.isNotEmpty()) "Episodes" else "More like this",
            color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 64.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 64.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.focusRequester(firstFocus).focusGroup(),
        ) {
            if (screen.episodes.isNotEmpty()) {
                items(screen.episodes, key = { it.id }) { ep ->
                    val asItem = item!!.copy(id = item.id + "#" + ep.id, title = "${ep.n}. ${ep.title}", backdrop = ep.still.ifBlank { item.backdrop })
                    TvCard(asItem, onClick = { onPickEpisode(ep.id) }, onFocused = {}, width = 240.dp, landscape = true)
                }
            } else {
                items(relatedItems, key = { it.id }) { r ->
                    TvCard(r, onClick = { onPickItem(r) }, onFocused = {}, width = 140.dp)
                }
            }
        }
    }
}
