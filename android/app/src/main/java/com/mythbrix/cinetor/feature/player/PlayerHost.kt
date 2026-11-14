package com.mythbrix.cinetor.feature.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.feature.player.gestures.PlayerGestures
import com.mythbrix.cinetor.feature.player.subs.SubtitleController
import com.mythbrix.cinetor.feature.player.subs.SubtitleOverlay
import com.mythbrix.cinetor.feature.player.subs.SubtitleStyleSheet
import com.mythbrix.cinetor.feature.player.subs.SubtitleSyncSheet
import com.mythbrix.cinetor.feature.player.subs.applySubtitleStyle
import com.mythbrix.cinetor.feature.player.subs.displayNameOf
import kotlinx.coroutines.flow.MutableStateFlow

private val idleState = MutableStateFlow(PlayerUiState())

/**
 * Hosts the player ABOVE the NavHost so playback survives navigation and rotation (the player
 * lives in AppContainer). Draws nothing while HIDDEN.
 */
@Composable
fun PlayerHost(holder: PlayerStateHolder, subtitles: SubtitleController) {
    val screen by holder.state.collectAsStateWithLifecycle()
    val player by holder.player.collectAsStateWithLifecycle()
    val ui by (player?.state ?: idleState).collectAsStateWithLifecycle()
    val subState by subtitles.state.collectAsStateWithLifecycle()

    val current by rememberUpdatedState(screen)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, holder) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && current.active) {
                holder.onBackground()
                // No background video audio (MediaSession service + setting arrive in 3.6).
                if (!current.inPip) holder.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // New title → side-loaded subs + offset reset.
    LaunchedEffect(screen.generation) { subtitles.reset() }

    if (screen.mode == HostMode.HIDDEN || screen.item == null) return

    val context = LocalContext.current
    var showStyle by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            subtitles.loadExternal(uri.toString(), displayNameOf(context, uri)) { holder.selectText(null) }
        }
    }
    val known = knownSubFor(ui.tracks.text.firstOrNull { it.selected }, screen.subs)
    val positionNow: () -> Long = { player?.media3Player()?.currentPosition ?: ui.positionMs }

    Box(Modifier.fillMaxSize()) {
        PlayerScreen(
            screen = screen,
            cinetorPlayer = player,
            ui = ui,
            holder = holder,
            onBack = { holder.close() },
            layers = PlayerLayers(
                gestures = { m, toggle -> PlayerGestures(holder, screen, ui, m, toggle) },
                overVideo = { SubtitleOverlay(subState.sideLoaded, subState.offsetMs, subState.style, positionNow) },
                configureView = { v -> applySubtitleStyle(v, subState.style, hidden = subState.sideLoaded != null) },
            ),
            subtitleExtras = SubtitleExtras(
                onLoadFile = { picker.launch(arrayOf("*/*")) },
                onStyle = { showStyle = true },
                onSync = { showSync = true },
                sideLoadedLabel = subState.sideLoaded?.label,
                onClearSideLoaded = { subtitles.clearSideLoaded() },
            ),
        )
        if (showStyle) {
            SubtitleStyleSheet(subState.style, onChange = subtitles::setStyle, onDismiss = { showStyle = false })
        }
        if (showSync) {
            SubtitleSyncSheet(
                state = subState,
                onNudge = { d -> subtitles.nudge(d, known?.url, known?.label) { holder.selectText(null) } },
                onReset = { subtitles.setOffset(0) },
                onDismiss = { showSync = false },
            )
        }
    }
}
