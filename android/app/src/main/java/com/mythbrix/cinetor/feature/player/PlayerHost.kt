package com.mythbrix.cinetor.feature.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.flow.StateFlow
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.feature.tv.LiveGestures
import com.mythbrix.cinetor.feature.tv.LiveOsd
import com.mythbrix.cinetor.feature.tv.QuickChannelList
import com.mythbrix.cinetor.feature.tv.TvData
import com.mythbrix.cinetor.feature.tv.rememberGuideNow

private val idleState = MutableStateFlow(PlayerUiState())
private val idleTv = MutableStateFlow<TvData?>(null)

/**
 * Hosts the player ABOVE the NavHost so playback survives navigation and rotation (the player
 * lives in AppContainer). Draws nothing while HIDDEN.
 */
@Composable
fun PlayerHost(
    holder: PlayerStateHolder,
    subtitles: SubtitleController,
    tvData: StateFlow<TvData?>? = null,
    gesturePrefs: com.mythbrix.cinetor.feature.player.gestures.GesturePrefs = com.mythbrix.cinetor.feature.player.gestures.GesturePrefs(),
    autoSkipIntro: Boolean = false,
    /** Android TV: 10-foot remote player (no gestures) instead of the touch player. */
    isTv: Boolean = false,
    /** TV Down rail for movies: related titles. */
    related: suspend (com.mythbrix.cinetor.core.data.Item) -> List<com.mythbrix.cinetor.core.data.Item> = { emptyList() },
) {
    val screen by holder.state.collectAsStateWithLifecycle()
    val player by holder.player.collectAsStateWithLifecycle()
    val ui by (player?.state ?: idleState).collectAsStateWithLifecycle()
    val subState by subtitles.state.collectAsStateWithLifecycle()

    // Background / PiP close handling lives in MainActivity (Background play setting + PiP state).
    // New title → side-loaded subs + offset reset.
    LaunchedEffect(screen.generation) { subtitles.reset() }

    if (screen.mode == HostMode.HIDDEN || screen.item == null) return

    // System PiP window: video only, no controls.
    if (screen.inPip) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(
                player?.media3Player(), screen.aspect, Modifier.fillMaxSize(),
                configure = { v -> applySubtitleStyle(v, subState.style, hidden = subState.sideLoaded != null) },
            )
            SubtitleOverlay(subState.sideLoaded, subState.offsetMs, subState.style, { player?.media3Player()?.currentPosition ?: ui.positionMs })
        }
        return
    }
    if (screen.mode == HostMode.MINI) {
        MiniPlayer(screen, ui, holder) { m -> VideoSurface(player?.media3Player(), AspectMode.FIT, m) }
        return
    }

    val context = LocalContext.current
    // Settings → Playback → Skip intro automatically.
    val inIntro = autoSkipIntro && !screen.live && screen.showSkipIntro(ui.positionMs)
    LaunchedEffect(inIntro) { if (inIntro) holder.skipIntro() }
    var showStyle by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var showChannels by remember { mutableStateOf(false) }
    val tv by (tvData ?: idleTv).collectAsStateWithLifecycle()
    val guideNow = rememberGuideNow(tv?.anchorSec)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            subtitles.loadExternal(uri.toString(), displayNameOf(context, uri)) { holder.selectText(null) }
        }
    }
    val known = knownSubFor(ui.tracks.text.firstOrNull { it.selected }, screen.subs)
    val positionNow: () -> Long = { player?.media3Player()?.currentPosition ?: ui.positionMs }

    Box(Modifier.fillMaxSize()) {
        if (isTv) {
            com.mythbrix.cinetor.tvui.TvPlayerScreen(
                screen = screen,
                cinetorPlayer = player,
                ui = ui,
                holder = holder,
                // TV Back (controls hidden) leaves the player; position is saved on close.
                onExit = { holder.close() },
            layers = PlayerLayers(
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
                related = related,
            )
        } else {
        PlayerScreen(
            screen = screen,
            cinetorPlayer = player,
            ui = ui,
            holder = holder,
            // Back / ⌄ → in-app floating mini player (playback continues across navigation).
            onBack = { holder.minimize() },
            layers = PlayerLayers(
                gestures = { m, toggle ->
                    if (screen.live) LiveGestures(holder, m, toggle) else PlayerGestures(holder, screen, ui, m, toggle, gesturePrefs)
                },
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
            extraTopActions = {
                if (screen.live && screen.channels.size > 1) {
                    PlayerIconButton(Icons.AutoMirrored.Rounded.List, "Channel list", { showChannels = true }, size = 40.dp, iconSize = 20.dp)
                }
            },
        )
        }
        if (screen.live) {
            LiveOsd(screen, tv, guideNow)
            QuickChannelList(
                visible = showChannels,
                screen = screen,
                data = tv,
                nowSec = guideNow,
                onPick = { ch -> showChannels = false; holder.playChannel(ch, screen.channels) },
                onDismiss = { showChannels = false },
            )
            BackHandler(enabled = showChannels) { showChannels = false }
        }
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
