package com.mythbrix.cinetor.feature.player

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.core.player.SourceResolver
import com.mythbrix.cinetor.feature.player.gestures.GesturePrefs
import com.mythbrix.cinetor.feature.player.gestures.PlayerGestures
import com.mythbrix.cinetor.feature.player.subs.SubtitleController
import com.mythbrix.cinetor.feature.player.subs.SubtitleOverlay
import com.mythbrix.cinetor.feature.player.subs.SubtitleStyleSheet
import com.mythbrix.cinetor.feature.player.subs.SubtitleSyncSheet
import com.mythbrix.cinetor.feature.player.subs.applySubtitleStyle
import com.mythbrix.cinetor.feature.player.subs.displayNameOf
import com.mythbrix.cinetor.feature.player.subs.fullSubtitleLanguageName
import com.mythbrix.cinetor.feature.tv.LiveGestures
import com.mythbrix.cinetor.feature.tv.LiveOsd
import com.mythbrix.cinetor.feature.tv.QuickChannelList
import com.mythbrix.cinetor.feature.tv.TvData
import com.mythbrix.cinetor.feature.tv.rememberGuideNow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val idleState = MutableStateFlow(PlayerUiState())
private val idleTv = MutableStateFlow<TvData?>(null)

private fun buildSubtitleExtras(
    screen: PlayerScreenState,
    holder: PlayerStateHolder,
    subtitles: SubtitleController,
    sideLoadedLabel: String?,
    onLoadFile: () -> Unit,
    onStyle: () -> Unit,
    onSync: () -> Unit,
): SubtitleExtras = SubtitleExtras(
    onLoadFile = onLoadFile,
    onStyle = onStyle,
    onSync = onSync,
    sideLoadedLabel = sideLoadedLabel,
    onClearSideLoaded = { subtitles.clearSideLoaded() },
    onSearchOnline = { query, lang ->
        val (se, ep) = seasonEpisodeNumbersOf(screen.item, screen.episodeId)
        SourceResolver.searchOnlineSubtitles(
            query = query.ifBlank { screen.title },
            itemId = screen.item?.id,
            season = se,
            episode = ep,
            lang = lang,
        )
    },
    onPickOnlineSub = { hit ->
        val cleanName = fullSubtitleLanguageName(hit.label.ifBlank { hit.language })
        subtitles.loadExternal(hit.url, cleanName) { holder.selectText(null) }
    },
)

/**
 * Embedded `16:9` inline player slot rendered inside `DetailsScreen`'s top media container when
 * `screen.mode == HostMode.INLINE`. Shares the single app-scoped `CinetorPlayer` and chromeless
 * YouTube `WebView` with [PlayerHost], without forcing landscape orientation or hiding system bars.
 */
@Composable
fun InlinePlayerSlot(
    holder: PlayerStateHolder,
    subtitles: SubtitleController,
    gesturePrefs: GesturePrefs = GesturePrefs(),
    modifier: Modifier = Modifier,
) {
    val screen by holder.state.collectAsStateWithLifecycle()
    val player by holder.player.collectAsStateWithLifecycle()
    val ui by (player?.state ?: idleState).collectAsStateWithLifecycle()
    val subState by subtitles.state.collectAsStateWithLifecycle()

    if (screen.item == null || screen.inPip) {
        Box(modifier.fillMaxSize().background(Color.Black))
        return
    }

    val context = LocalContext.current
    val activeYoutubeId = ui.youtubeVideoId ?: screen.sources.getOrNull(screen.sourceIndex)?.url?.let(::extractYouTubeVideoId)
    val youtubeHolder = holder.youtubeWebViewHolder ?: rememberYouTubeWebViewHolder(
        activeVideoId = activeYoutubeId,
        cinetorPlayer = player,
    )
    val positionNow: () -> Long = {
        if (activeYoutubeId != null) ui.positionMs else (player?.media3Player()?.currentPosition ?: ui.positionMs)
    }

    var showStyle by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            subtitles.loadExternal(uri.toString(), displayNameOf(context, uri)) { holder.selectText(null) }
        }
    }
    val known = knownSubFor(ui.tracks.text.firstOrNull { it.selected }, screen.subs)

    Box(modifier.fillMaxSize().background(Color.Black)) {
        PlayerScreen(
            screen = screen,
            cinetorPlayer = player,
            ui = ui,
            holder = holder,
            onBack = { holder.minimize() },
            inline = true,
            layers = PlayerLayers(
                gestures = { m, toggle ->
                    if (screen.live) LiveGestures(holder, m, toggle) else PlayerGestures(holder, screen, ui, m, toggle, gesturePrefs)
                },
                overVideo = { SubtitleOverlay(subState.sideLoaded, subState.offsetMs, subState.style, positionNow) },
                configureView = { v -> applySubtitleStyle(v, subState.style, hidden = subState.sideLoaded != null) },
                youtubeHolder = youtubeHolder,
            ),
            subtitleExtras = buildSubtitleExtras(
                screen = screen,
                holder = holder,
                subtitles = subtitles,
                sideLoadedLabel = subState.sideLoaded?.label,
                onLoadFile = { picker.launch(arrayOf("*/*")) },
                onStyle = { showStyle = true },
                onSync = { showSync = true },
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

/**
 * Hosts the player ABOVE the NavHost so playback survives navigation and rotation (the player
 * lives in AppContainer). Draws nothing while HIDDEN or INLINE (unless in system PiP).
 */
@Composable
fun PlayerHost(
    holder: PlayerStateHolder,
    subtitles: SubtitleController,
    tvData: StateFlow<TvData?>? = null,
    gesturePrefs: GesturePrefs = GesturePrefs(),
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

    val activeYoutubeId = if (screen.mode == HostMode.HIDDEN || screen.item == null) {
        null
    } else {
        ui.youtubeVideoId ?: screen.sources.getOrNull(screen.sourceIndex)?.url?.let(::extractYouTubeVideoId)
    }
    val youtubeHolder = rememberYouTubeWebViewHolder(
        activeVideoId = activeYoutubeId,
        cinetorPlayer = player,
    )
    DisposableEffect(holder, youtubeHolder) {
        holder.youtubeWebViewHolder = youtubeHolder
        onDispose {
            if (holder.youtubeWebViewHolder === youtubeHolder) {
                holder.youtubeWebViewHolder = null
            }
        }
    }

    // Background / PiP close handling lives in MainActivity (Background play setting + PiP state).
    // New title → side-loaded subs + offset reset.
    LaunchedEffect(screen.generation) { subtitles.reset() }

    if (screen.mode == HostMode.HIDDEN || screen.item == null) return

    val positionNow: () -> Long = {
        if (activeYoutubeId != null) ui.positionMs else (player?.media3Player()?.currentPosition ?: ui.positionMs)
    }

    // System PiP window: video only, no controls.
    if (screen.inPip) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(
                player = player?.media3Player(),
                aspect = screen.aspect,
                modifier = Modifier.fillMaxSize(),
                configure = { v -> applySubtitleStyle(v, subState.style, hidden = subState.sideLoaded != null) },
                youtubeVideoId = activeYoutubeId,
                youtubeSessionKey = ui.key,
                youtubeHolder = youtubeHolder,
                cinetorPlayer = player,
                startPositionMs = ui.positionMs,
                playWhenReady = ui.playWhenReady,
            )
            SubtitleOverlay(subState.sideLoaded, subState.offsetMs, subState.style, positionNow)
        }
        return
    }

    // Embedded inline mode is rendered inside DetailsScreen via InlinePlayerSlot.
    if (screen.mode == HostMode.INLINE) {
        val inIntroInline = autoSkipIntro && !screen.live && screen.showSkipIntro(ui.positionMs)
        LaunchedEffect(inIntroInline) { if (inIntroInline) holder.skipIntro() }
        return
    }

    if (screen.mode == HostMode.MINI) {
        MiniPlayer(screen, ui, holder) { m ->
            VideoSurface(
                player = player?.media3Player(),
                aspect = AspectMode.FIT,
                modifier = m,
                youtubeVideoId = activeYoutubeId,
                youtubeSessionKey = ui.key,
                youtubeHolder = youtubeHolder,
                cinetorPlayer = player,
                startPositionMs = ui.positionMs,
                playWhenReady = ui.playWhenReady,
            )
        }
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
    val extras = buildSubtitleExtras(
        screen = screen,
        holder = holder,
        subtitles = subtitles,
        sideLoadedLabel = subState.sideLoaded?.label,
        onLoadFile = { picker.launch(arrayOf("*/*")) },
        onStyle = { showStyle = true },
        onSync = { showSync = true },
    )

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
                    youtubeHolder = youtubeHolder,
                ),
                subtitleExtras = extras,
                related = related,
            )
        } else {
            PlayerScreen(
                screen = screen,
                cinetorPlayer = player,
                ui = ui,
                holder = holder,
                // Back / ⌄ → returns to INLINE when expanded from DetailsScreen, else floating MiniPlayer.
                onBack = { holder.collapseFullscreen() },
                layers = PlayerLayers(
                    gestures = { m, toggle ->
                        if (screen.live) LiveGestures(holder, m, toggle) else PlayerGestures(holder, screen, ui, m, toggle, gesturePrefs)
                    },
                    overVideo = { SubtitleOverlay(subState.sideLoaded, subState.offsetMs, subState.style, positionNow) },
                    configureView = { v -> applySubtitleStyle(v, subState.style, hidden = subState.sideLoaded != null) },
                    youtubeHolder = youtubeHolder,
                ),
                subtitleExtras = extras,
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
