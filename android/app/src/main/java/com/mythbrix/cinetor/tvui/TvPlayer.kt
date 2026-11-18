package com.mythbrix.cinetor.tvui

import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.player.CinetorPlayer
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.feature.player.PlayerLayers
import com.mythbrix.cinetor.feature.player.PlayerOverlays
import com.mythbrix.cinetor.feature.player.PlayerScreenState
import com.mythbrix.cinetor.feature.player.PlayerStateHolder
import com.mythbrix.cinetor.feature.player.SettingsSheet
import com.mythbrix.cinetor.feature.player.SubtitleExtras
import com.mythbrix.cinetor.feature.player.VideoSurface
import com.mythbrix.cinetor.feature.player.dubOfSource
import com.mythbrix.cinetor.feature.player.extractYouTubeVideoId
import com.mythbrix.cinetor.feature.player.formatTime
import com.mythbrix.cinetor.feature.player.subs.SubEdge
import com.mythbrix.cinetor.feature.player.subs.SubtitleStyle
import kotlinx.coroutines.delay

private enum class TvTrackModal { AUDIO, SUBTITLES }

/**
 * Ensures TV subtitles default to a 0% transparent background with a crisp drop shadow,
 * while preserving explicit custom background opacities chosen by the user.
 */
fun tvEffectiveSubtitleStyle(style: SubtitleStyle): SubtitleStyle {
    val isLegacyOrZeroDefault = style.bgOpacity <= 0.001f ||
        (style.bgOpacity == 0.35f && style.edge == SubEdge.OUTLINE && style.bgColor == 0xFF000000L)
    return if (isLegacyOrZeroDefault) {
        style.copy(bgOpacity = 0f, edge = SubEdge.SHADOW)
    } else {
        style
    }
}

@OptIn(UnstableApi::class)
internal fun enforceTvTransparentSubtitleFallback(view: PlayerView) {
    val sv = view.subtitleView ?: return
    if (sv.visibility != android.view.View.VISIBLE) return
    sv.setApplyEmbeddedStyles(false)
    sv.setApplyEmbeddedFontSizes(false)
    sv.setStyle(
        CaptionStyleCompat(
            android.graphics.Color.WHITE,
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            android.graphics.Color.BLACK,
            Typeface.DEFAULT_BOLD,
        ),
    )
}

/**
 * 10-foot Android TV player:
 * - Hidden overlay: Left/Right ±10 s (hold = accelerating scrub), OK play/pause, Up = controls
 *   (settings row focused), Down = Episodes / For You rail, Back = exit.
 * - Prominent Audio / Dub (`Hindi`, `English`, `Original`, `Bangla`, etc.) and Subtitles controls
 *   displaying full human language names everywhere, plus 0% transparent drop-shadow subtitles.
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
    var trackModal by remember { mutableStateOf<TvTrackModal?>(null) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    val catcher = remember { FocusRequester() }
    val playBtn = remember { FocusRequester() }
    val settingsBtn = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }

    val modalOpen = showSettings || trackModal != null

    LaunchedEffect(screen.generation) { overlay = TvOverlay.CONTROLS; interaction++ }
    LaunchedEffect(overlay, interaction, ui.isPlaying, modalOpen) {
        if (overlay == TvOverlay.CONTROLS && ui.isPlaying && !modalOpen) {
            delay(6_000)
            overlay = TvOverlay.NONE
        }
    }
    LaunchedEffect(overlay, modalOpen) {
        if (modalOpen) return@LaunchedEffect
        withFrameNanos { }
        runCatching {
            when (overlay) {
                TvOverlay.NONE -> catcher.requestFocus()
                TvOverlay.CONTROLS -> if (focusSettings) settingsBtn.requestFocus() else playBtn.requestFocus()
                TvOverlay.RAIL -> railFocus.requestFocus()
            }
        }
    }
    LaunchedEffect(scrubTarget) {
        if (scrubTarget != null && keys.scrubber.target == null) {
            delay(2_000)
            scrubTarget = null
        }
    }
    BackHandler {
        when {
            trackModal != null -> { trackModal = null; interaction++ }
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

    val activeSourceDub = remember(screen.sources, screen.sourceIndex) {
        screen.sources.getOrNull(screen.sourceIndex)
            ?.let { src -> src.audioLang?.takeIf { it.isNotBlank() } ?: dubOfSource(src) }
            ?.let(::tvFullLanguageName)
    }
    val activeAudioSummary = remember(activeSourceDub, ui.tracks.audio) {
        activeSourceDub
            ?: ui.tracks.audio.firstOrNull { it.selected }?.let { tvTrackLanguageLabel(it.label, it.language) }
            ?: "Original"
    }
    val activeSubSummary = remember(subtitleExtras.sideLoadedLabel, ui.tracks.text) {
        subtitleExtras.sideLoadedLabel?.let(::tvFullLanguageName)
            ?: ui.tracks.text.firstOrNull { it.selected }?.let { tvTrackLanguageLabel(it.label, it.language) }
            ?: "Off"
    }

    val position = scrubTarget ?: ui.positionMs
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (showSettings) return@onPreviewKeyEvent false
                val key = tvKeyOf(e.nativeKeyEvent.keyCode)
                if (trackModal != null) {
                    if (key == TvKey.BACK) {
                        if (e.type == KeyEventType.KeyUp) {
                            trackModal = null
                            interaction++
                        }
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                if (key == TvKey.OTHER) return@onPreviewKeyEvent false
                if (overlay != TvOverlay.NONE) interaction++
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
        val activeYoutubeId = ui.youtubeVideoId ?: screen.sources.getOrNull(screen.sourceIndex)?.url?.let(::extractYouTubeVideoId)
        VideoSurface(
            player = cinetorPlayer?.media3Player(),
            aspect = screen.aspect,
            modifier = Modifier.fillMaxSize(),
            zoom = layers.zoom,
            configure = { v ->
                layers.configureView(v)
                enforceTvTransparentSubtitleFallback(v)
            },
            youtubeVideoId = activeYoutubeId,
            youtubeSessionKey = ui.key,
            youtubeHolder = layers.youtubeHolder,
            cinetorPlayer = cinetorPlayer,
            startPositionMs = ui.positionMs,
            playWhenReady = ui.playWhenReady,
        )
        layers.overVideo()
        Box(Modifier.fillMaxSize().focusRequester(catcher).focusable().semantics { contentDescription = "Player" })
        if (screen.notice == null && (cinetorPlayer == null || (ui.buffering && ui.playWhenReady))) {
            CircularProgressIndicator(color = Cinetor.tokens.accent, modifier = Modifier.align(Alignment.Center).size(56.dp))
        }
        (screen.notice ?: ui.error?.let { "Playback error: $it" })?.let { err ->
            Text(
                err,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(16.dp),
            )
        }
        if (scrubTarget != null && overlay == TvOverlay.NONE) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 64.dp, vertical = 40.dp)) {
                Text("${formatTime(position)} / ${formatTime(ui.durationMs)}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                TvProgress(position, ui.bufferedMs, ui.durationMs)
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = overlay == TvOverlay.CONTROLS,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            TvControls(
                screen = screen,
                ui = ui,
                position = position,
                audioSummary = activeAudioSummary,
                subtitleSummary = activeSubSummary,
                playBtn = playBtn,
                settingsBtn = settingsBtn,
                onTogglePlay = { holder.togglePlay(); interaction++ },
                onSeek = { d -> holder.seekBy(d); interaction++ },
                onNext = holder::next,
                onAudio = { trackModal = TvTrackModal.AUDIO; interaction++ },
                onSubtitles = { trackModal = TvTrackModal.SUBTITLES; interaction++ },
                onEpisodes = { overlay = TvOverlay.RAIL },
                onSettings = { showSettings = true },
            )
        }
        androidx.compose.animation.AnimatedVisibility(
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

        trackModal?.let { mode ->
            TvAudioSubModal(
                mode = mode,
                screen = screen,
                ui = ui,
                holder = holder,
                extras = subtitleExtras,
                onDismiss = { trackModal = null; interaction++ },
            )
        }
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
    audioSummary: String,
    subtitleSummary: String,
    playBtn: FocusRequester,
    settingsBtn: FocusRequester,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onEpisodes: () -> Unit,
    onSettings: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.62f),
                0.35f to Color.Transparent,
                0.58f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.88f),
            ),
        ),
    ) {
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = 64.dp, vertical = 34.dp)
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    screen.title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaText = listOfNotNull(
                    screen.subtitle,
                    if (!screen.live) "Audio: $audioSummary" else null,
                    if (!screen.live) "Subtitles: $subtitleSummary" else null,
                ).joinToString("  •  ")
                if (metaText.isNotEmpty()) {
                    Text(metaText, color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.titleSmall)
                }
            }
            TvButton("Settings", Icons.Rounded.Settings, onSettings, Modifier.focusRequester(settingsBtn))
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 64.dp, vertical = 34.dp),
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
            Row(
                Modifier.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!screen.live) TvButton("−10s", Icons.Rounded.Replay10, { onSeek(-10_000) })
                TvButton(
                    if (ui.playWhenReady) "Pause" else "Play",
                    if (ui.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    onTogglePlay,
                    Modifier.focusRequester(playBtn),
                    primary = true,
                )
                if (!screen.live) TvButton("+10s", Icons.Rounded.Forward10, { onSeek(10_000) })
                if (!screen.live) {
                    TvButton("Audio · $audioSummary", Icons.Rounded.Audiotrack, onAudio)
                    TvButton("Subtitles · $subtitleSummary", Icons.Rounded.ClosedCaption, onSubtitles)
                }
                if (screen.nextEpisode != null) TvButton("Next", Icons.Rounded.SkipNext, onNext)
                if (!screen.live) {
                    TvButton(
                        if (screen.episodes.isNotEmpty()) "Episodes" else "For You",
                        Icons.AutoMirrored.Rounded.List,
                        onEpisodes,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvAudioSubModal(
    mode: TvTrackModal,
    screen: PlayerScreenState,
    ui: PlayerUiState,
    holder: PlayerStateHolder,
    extras: SubtitleExtras,
    onDismiss: () -> Unit,
) {
    val firstOptionFocus = remember { FocusRequester() }
    LaunchedEffect(mode) {
        withFrameNanos { }
        runCatching { firstOptionFocus.requestFocus() }
    }

    val rawDubPairs = remember(screen.sources, screen.item?.dubs) {
        val fromSources = screen.sources.mapNotNull { src ->
            val raw = src.audioLang?.takeIf { it.isNotBlank() } ?: dubOfSource(src) ?: return@mapNotNull null
            raw to tvFullLanguageName(raw)
        }
        val fromItemDubs = screen.item?.dubs.orEmpty().mapNotNull { d ->
            val raw = d.languageName.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            raw to tvFullLanguageName(raw)
        }
        (fromSources + fromItemDubs).distinctBy { it.second.lowercase() }
    }
    val activeRawDub = remember(screen.sources, screen.sourceIndex) {
        screen.sources.getOrNull(screen.sourceIndex)?.let { src ->
            src.audioLang?.takeIf { it.isNotBlank() } ?: dubOfSource(src)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val cardShape = RoundedCornerShape(22.dp)
        Column(
            Modifier
                .width(520.dp)
                .clip(cardShape)
                .background(Color(0xF2121216))
                .glass(cardShape, GlassLevel.SURFACE)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 26.dp, vertical = 22.dp)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (mode == TvTrackModal.AUDIO) "Audio & Dubs" else "Subtitles",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                TvButton("Done", null, onDismiss)
            }

            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (mode == TvTrackModal.AUDIO) {
                    if (rawDubPairs.isNotEmpty()) {
                        item(key = "hdr-dub") {
                            Text(
                                "DUB / AUDIO VERSION",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.62f),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                            )
                        }
                        items(rawDubPairs.size, key = { "dub-$it" }) { idx ->
                            val (rawDub, displayDub) = rawDubPairs[idx]
                            val selected = activeRawDub?.let { tvFullLanguageName(it) == displayDub } == true
                            TvTrackOptionRow(
                                label = displayDub,
                                selected = selected,
                                modifier = if (idx == 0) Modifier.focusRequester(firstOptionFocus) else Modifier,
                            ) {
                                holder.selectDub(rawDub)
                                onDismiss()
                            }
                        }
                    }

                    if (ui.tracks.audio.isNotEmpty()) {
                        item(key = "hdr-stream-audio") {
                            Text(
                                "STREAM AUDIO TRACKS",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.62f),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                            )
                        }
                        items(ui.tracks.audio.size, key = { "aud-${ui.tracks.audio[it].id}" }) { idx ->
                            val track = ui.tracks.audio[idx]
                            val label = tvTrackLanguageLabel(track.label, track.language)
                            TvTrackOptionRow(
                                label = label,
                                selected = track.selected,
                                modifier = if (rawDubPairs.isEmpty() && idx == 0) Modifier.focusRequester(firstOptionFocus) else Modifier,
                            ) {
                                holder.selectAudio(track.id)
                                onDismiss()
                            }
                        }
                    }

                    if (rawDubPairs.isEmpty() && ui.tracks.audio.isEmpty()) {
                        item(key = "aud-default") {
                            TvTrackOptionRow(
                                label = activeRawDub?.let(::tvFullLanguageName) ?: "Original",
                                selected = true,
                                modifier = Modifier.focusRequester(firstOptionFocus),
                                onClick = onDismiss,
                            )
                        }
                    }
                } else {
                    val anySelected = extras.sideLoadedLabel != null || ui.tracks.text.any { it.selected }
                    item(key = "sub-off") {
                        TvTrackOptionRow(
                            label = "Off",
                            selected = !anySelected,
                            modifier = Modifier.focusRequester(firstOptionFocus),
                        ) {
                            extras.onClearSideLoaded?.invoke()
                            holder.selectText(null)
                            onDismiss()
                        }
                    }
                    extras.sideLoadedLabel?.let { sideLabel ->
                        item(key = "sub-side") {
                            TvTrackOptionRow(
                                label = tvFullLanguageName(sideLabel),
                                selected = true,
                                onClick = onDismiss,
                            )
                        }
                    }
                    items(ui.tracks.text, key = { "sub-${it.id}" }) { track ->
                        val label = tvTrackLanguageLabel(track.label, track.language)
                        TvTrackOptionRow(
                            label = label,
                            selected = track.selected && extras.sideLoadedLabel == null,
                        ) {
                            extras.onClearSideLoaded?.invoke()
                            holder.selectText(track.id)
                            onDismiss()
                        }
                    }
                    extras.onStyle?.let { openStyle ->
                        item(key = "sub-style") {
                            TvTrackOptionRow(label = "Subtitle style…", selected = false) {
                                onDismiss()
                                openStyle()
                            }
                        }
                    }
                    extras.onSync?.let { openSync ->
                        item(key = "sub-sync") {
                            TvTrackOptionRow(label = "Sync offset…", selected = false) {
                                onDismiss()
                                openSync()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvTrackOptionRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val sound = rememberFocusSound()
    LaunchedEffect(focused) { if (focused) sound() }
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = if (focused) 1.02f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(
                when {
                    focused -> tokens.accent
                    selected -> Color.White.copy(alpha = 0.14f)
                    else -> Color.White.copy(alpha = 0.05f)
                },
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.12f),
                shape = shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = if (focused) Color.White else tokens.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Down rail: episodes of the current series, else For You recommendations (with visible titles). */
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
        value = if (item != null && screen.episodes.isEmpty()) {
            runCatching { related(item) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.94f))))
            .padding(top = 40.dp, bottom = 32.dp),
    ) {
        Text(
            text = if (screen.episodes.isNotEmpty()) "Episodes" else "For You",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 64.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 64.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.focusRequester(firstFocus).focusGroup(),
        ) {
            if (screen.episodes.isNotEmpty()) {
                items(screen.episodes, key = { it.id }) { ep ->
                    val asItem = item!!.copy(
                        id = item.id + "#" + ep.id,
                        title = "${ep.n}. ${ep.title}",
                        backdrop = ep.still.ifBlank { item.backdrop },
                    )
                    TvCard(
                        item = asItem,
                        onClick = { onPickEpisode(ep.id) },
                        onFocused = {},
                        width = 240.dp,
                        landscape = true,
                        showSubtitle = false,
                    )
                }
            } else {
                items(relatedItems, key = { it.id }) { r ->
                    TvCard(r, onClick = { onPickItem(r) }, onFocused = {}, width = 140.dp)
                }
            }
        }
    }
}
