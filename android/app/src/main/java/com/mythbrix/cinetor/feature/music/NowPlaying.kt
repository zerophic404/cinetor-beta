package com.mythbrix.cinetor.feature.music

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.feature.player.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.max

internal val idleMusicUi = MutableStateFlow(PlayerUiState())

/** 0..1 progress of [ui] (0 when duration unknown). */
fun progressOf(ui: PlayerUiState): Float = if (ui.durationMs > 0) (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f

/**
 * Full-screen Now Playing: dynamic ArtTint radial glow, lossless quality badge (`FLAC Lossless` /
 * `320kbps Studio Master`), interactive time-synced auto-scrolling LRC lyrics, queue sheet,
 * one-tap FLAC/320kbps download, and full TV D-pad focus & seek support.
 */
@Composable
fun NowPlaying(container: AppContainer) {
    val music = container.music
    val s by music.state.collectAsStateWithLifecycle()
    val player by music.player.collectAsStateWithLifecycle()
    val ui by (player?.state ?: idleMusicUi).collectAsStateWithLifecycle()
    val downloads = rememberMusicDownloads(container)
    val track = s.current ?: return
    val queue = s.queue ?: return
    val tier = Cinetor.tier
    val isTv = container.deviceProfile.isTv

    val (groundColor, glowColor) = rememberMusicArtTint(
        container = container,
        artworkPath = track.poster,
        fallbackDominantHex = track.dominantColor,
        fallbackAccentHex = track.accentColor,
    )

    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(isTv, track.id) {
        if (isTv) runCatching { playFocus.requestFocus() }
    }

    LaunchedEffect(feedbackMessage) {
        if (feedbackMessage != null) {
            delay(4_000)
            feedbackMessage = null
        }
    }

    BackHandler { if (showQueue) showQueue = false else music.collapse() }

    val qualityBadge = remember(track.id, track.maturity, s.preferredQuality, s.activeQualityLabel) {
        GatewayMusicMapper.qualityBadgeFor(track, s.preferredQuality, s.activeQualityLabel)
    }
    val isResolving = s.resolvingTrackId == track.id

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07070A))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > 160f) music.collapse() },
                ) { _, dy -> total += dy }
            }
            .semantics { contentDescription = "Now playing" },
    ) {
        if (tier != PerformanceTier.LOW && track.poster.isNotBlank()) {
            AsyncImage(
                model = rememberSizedImage(track.backdrop.ifBlank { track.poster }, 360.dp, 640.dp, large = true),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(48.dp).graphicsLayer { alpha = 0.55f },
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            listOf(
                                groundColor.copy(alpha = 0.72f),
                                Color(0xFF09090D).copy(alpha = 0.90f),
                                Color(0xFF050508).copy(alpha = 0.97f),
                            ),
                        ),
                    )
                    drawRect(
                        Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to glowColor.copy(alpha = if (tier == PerformanceTier.LOW) 0.24f else 0.36f),
                                0.55f to glowColor.copy(alpha = 0.10f),
                                1f to Color.Transparent,
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.34f),
                            radius = max(size.width, size.height) * 0.62f,
                        ),
                    )
                },
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Rounded.KeyboardArrowDown, "Collapse now playing") { music.collapse() }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "PLAYING FROM MUSIC",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        track.album?.takeIf { it.isNotBlank() } ?: qualityBadge,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RoundIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue") { showQueue = true }
            }

            Spacer(Modifier.height(8.dp))
            StageModeSwitcher(
                showLyrics = showLyrics,
                accent = glowColor,
                onSelect = { showLyrics = it },
            )

            Spacer(Modifier.weight(0.45f))

            AnimatedContent(
                targetState = showLyrics,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "artOrLyrics",
                modifier = Modifier.widthIn(max = 350.dp).fillMaxWidth().aspectRatio(1f),
            ) { lyricsMode ->
                if (lyricsMode) {
                    SyncedLyricsStage(
                        lyrics = s.lyrics,
                        loading = s.lyricsLoading,
                        positionMs = ui.positionMs,
                        accent = glowColor,
                        reduceMotion = tier == PerformanceTier.LOW || Cinetor.prefs.reduceMotion,
                        onSeek = { ms -> music.seekTo(ms) },
                    )
                } else {
                    val scale by animateFloatAsState(
                        if (ui.isPlaying) 1f else 0.90f,
                        tween(if (tier == PerformanceTier.LOW) 0 else 380),
                        label = "artScale",
                    )
                    AsyncImage(
                        model = rememberSizedImage(track.poster, 320.dp, 320.dp, large = true),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .shadow(32.dp, RoundedCornerShape(22.dp), ambientColor = glowColor, spotColor = glowColor)
                            .clip(RoundedCornerShape(22.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(22.dp))
                            .background(groundColor),
                    )
                }
            }

            Spacer(Modifier.weight(0.45f))

            if (s.error != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Cinetor.tokens.error.copy(alpha = 0.2f))
                        .border(1.dp, Cinetor.tokens.error.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        .clickable { music.togglePlay() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text(
                        s.error.orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("Retry", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            track.artist.orEmpty(),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        NowPlayingQualityBadge(
                            badgeText = qualityBadge,
                            accent = glowColor,
                            onClick = { music.toggleQuality() },
                        )
                    }
                }
                TrackDownloadIcon(
                    state = trackDlOf(downloads[track.id]),
                    title = track.title,
                    onClick = {
                        downloadTrack(container, track, s.preferredQuality) { msg -> feedbackMessage = msg }
                    },
                    tint = Color.White,
                )
            }

            Spacer(Modifier.height(10.dp))
            ProgressSlider(ui, glowColor) { music.seekTo(it) }
            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToggleIcon(
                    Icons.Rounded.Shuffle,
                    if (queue.shuffle) "Shuffle on" else "Shuffle",
                    queue.shuffle,
                    glowColor,
                ) { music.toggleShuffle() }
                RoundIcon(Icons.Rounded.SkipPrevious, "Previous track", big = true) { music.previous() }
                PlayPauseHeroButton(
                    playing = ui.playWhenReady,
                    loading = isResolving,
                    focusRequester = playFocus,
                    onClick = { music.togglePlay() },
                )
                RoundIcon(Icons.Rounded.SkipNext, "Next track", big = true) { music.next() }
                ToggleIcon(
                    if (queue.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Repeat ${queue.repeat.name.lowercase()}",
                    queue.repeat != RepeatMode.OFF,
                    glowColor,
                ) { music.cycleRepeat() }
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ToggleIcon(Icons.Rounded.MusicNote, "Lyrics", showLyrics, glowColor) { showLyrics = !showLyrics }
                Text(
                    queue.upNext.firstOrNull()?.let { "Next: ${it.title}" } ?: "End of queue",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp).align(Alignment.CenterVertically),
                    textAlign = TextAlign.Center,
                )
                ToggleIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Open queue", showQueue, glowColor) { showQueue = true }
            }
            Spacer(Modifier.height(8.dp))
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = feedbackMessage != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            MusicFeedbackBanner(
                message = feedbackMessage.orEmpty(),
                onDismiss = { feedbackMessage = null },
            )
        }
    }
    if (showQueue) QueueSheet(music, onDismiss = { showQueue = false })
}

@Composable
private fun StageModeSwitcher(
    showLyrics: Boolean,
    accent: Color,
    onSelect: (Boolean) -> Unit,
) {
    val pillShape = RoundedCornerShape(CinetorTokens.Shape.pill)
    Row(
        Modifier
            .clip(pillShape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), pillShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StageModeTab("Artwork", selected = !showLyrics, accent = accent) { onSelect(false) }
        StageModeTab("Synced Lyrics", selected = showLyrics, accent = accent) { onSelect(true) }
    }
}

@Composable
private fun StageModeTab(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    Text(
        label,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.68f),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.36f) else Color.Transparent)
            .then(
                if (focused || selected) {
                    Modifier.border(if (focused) 2.dp else 1.dp, if (focused) Color.White else accent, shape)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "View $label" }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun NowPlayingQualityBadge(
    badgeText: String,
    accent: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    Row(
        Modifier
            .clip(shape)
            .background(accent.copy(alpha = 0.22f))
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else accent.copy(alpha = 0.75f), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "Quality $badgeText" }
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Rounded.HighQuality, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
        Text(badgeText, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/** Interactive time-synced scrolling LRC lyrics stage (tap or D-pad select any verse to seek). */
@Composable
private fun SyncedLyricsStage(
    lyrics: MusicLyrics?,
    loading: Boolean,
    positionMs: Long,
    accent: Color,
    reduceMotion: Boolean,
    onSeek: (Long) -> Unit,
) {
    val stageShape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .fillMaxSize()
            .clip(stageShape)
            .background(Color.Black.copy(alpha = 0.46f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), stageShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> {
                CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.5.dp, color = accent)
            }
            lyrics?.hasSynced == true -> {
                val lines = lyrics.lines
                val listState = rememberLazyListState()
                val activeIndex = LrcParser.activeLineIndex(lines, positionMs)

                LaunchedEffect(activeIndex) {
                    if (activeIndex >= 0) {
                        val target = (activeIndex - 2).coerceAtLeast(0)
                        if (reduceMotion) listState.scrollToItem(target) else listState.animateScrollToItem(target)
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(vertical = 44.dp),
                ) {
                    itemsIndexed(lines, key = { idx, l -> "$idx-${l.timeMs}" }) { idx, line ->
                        val isCurrent = idx == activeIndex
                        val isPast = activeIndex >= 0 && idx < activeIndex
                        val interaction = remember { MutableInteractionSource() }
                        val focused by interaction.collectIsFocusedAsState()
                        val lineShape = RoundedCornerShape(10.dp)

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(lineShape)
                                .background(
                                    when {
                                        focused -> Color.White.copy(alpha = 0.14f)
                                        isCurrent -> accent.copy(alpha = 0.14f)
                                        else -> Color.Transparent
                                    },
                                )
                                .then(if (focused) Modifier.border(1.5.dp, Color.White, lineShape) else Modifier)
                                .clickable(interactionSource = interaction, indication = null) { onSeek(line.timeMs) }
                                .semantics { contentDescription = "Lyric ${line.text}" }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            if (isCurrent) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(accent),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = line.text,
                                style = if (isCurrent) {
                                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 24.sp)
                                } else {
                                    MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp)
                                },
                                color = when {
                                    isCurrent -> Color.White
                                    isPast -> Color.White.copy(alpha = 0.42f)
                                    else -> Color.White.copy(alpha = 0.70f)
                                },
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            lyrics?.hasPlain == true -> {
                val scroll = rememberScrollState()
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        lyrics.plainLyrics.orEmpty(),
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(28.dp))
                    Text(
                        "Lyrics unavailable for this track",
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Seek slider: animates smoothly between ticks; supports touch drag + TV D-pad Left/Right scrubbing. */
@Composable
private fun ProgressSlider(ui: PlayerUiState, accent: Color, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val target = progressOf(ui)
    val animated by animateFloatAsState(target, tween(if (ui.isPlaying) 260 else 0, easing = LinearEasing), label = "progress")
    val value = dragging ?: animated
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { ev ->
                if (ev.nativeKeyEvent.action != KeyEvent.ACTION_DOWN || ui.durationMs <= 0) return@onPreviewKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onSeek((ui.positionMs - 10_000L).coerceAtLeast(0L))
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onSeek((ui.positionMs + 10_000L).coerceAtMost(ui.durationMs))
                        true
                    }
                    else -> false
                }
            },
    ) {
        Slider(
            value = value,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { f -> onSeek((f * ui.durationMs).toLong()) }
                dragging = null
            },
            interactionSource = interaction,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = if (focused) Color.White else accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.22f),
            ),
            modifier = Modifier
                .focusable(interactionSource = interaction)
                .semantics { contentDescription = "Seek track" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val pos = dragging?.let { (it * ui.durationMs).toLong() } ?: ui.positionMs
            Text(formatTime(pos), color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(ui.durationMs), color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PlayPauseHeroButton(
    playing: Boolean,
    loading: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "playHeroScale")

    Box(
        Modifier
            .size(76.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(focusRequester)
            .clip(CircleShape)
            .background(Color.White)
            .then(if (focused) Modifier.border(3.dp, Cinetor.tokens.accent, CircleShape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = if (playing) "Pause music" else "Play music" },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp, color = Color.Black)
        } else {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(42.dp),
            )
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, big: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .size(if (big) 56.dp else 44.dp)
            .clip(CircleShape)
            .then(
                if (focused) {
                    Modifier.background(Color.White.copy(alpha = 0.18f)).border(2.dp, Color.White, CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(if (big) 36.dp else 26.dp))
    }
}

@Composable
private fun ToggleIcon(icon: ImageVector, label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                when {
                    focused -> Color.White.copy(alpha = 0.2f)
                    on -> accent.copy(alpha = 0.22f)
                    else -> Color.Transparent
                },
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (on) accent else Color.White.copy(alpha = 0.85f), modifier = Modifier.size(24.dp))
    }
}
