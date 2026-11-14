package com.mythbrix.cinetor.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.feature.player.gestures.PlayerGestures
import kotlinx.coroutines.flow.MutableStateFlow

private val idleState = MutableStateFlow(PlayerUiState())

/**
 * Hosts the player ABOVE the NavHost so playback survives navigation and rotation (the player
 * lives in AppContainer). Draws nothing while HIDDEN.
 */
@Composable
fun PlayerHost(holder: PlayerStateHolder) {
    val screen by holder.state.collectAsStateWithLifecycle()
    val player by holder.player.collectAsStateWithLifecycle()
    val ui by (player?.state ?: idleState).collectAsStateWithLifecycle()

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

    if (screen.mode == HostMode.HIDDEN || screen.item == null) return
    PlayerScreen(
        screen = screen,
        cinetorPlayer = player,
        ui = ui,
        holder = holder,
        onBack = { holder.close() },
        layers = PlayerLayers(
            gestures = { m, toggle -> PlayerGestures(holder, screen, ui, m, toggle) },
        ),
    )
}
