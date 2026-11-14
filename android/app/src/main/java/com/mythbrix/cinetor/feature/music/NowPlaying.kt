package com.mythbrix.cinetor.feature.music

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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.hexColor
import com.mythbrix.cinetor.feature.player.formatTime
import kotlinx.coroutines.flow.MutableStateFlow

internal val idleMusicUi = MutableStateFlow(PlayerUiState())

/** 0..1 progress of [ui] (0 when duration unknown). */
fun progressOf(ui: PlayerUiState): Float = if (ui.durationMs > 0) (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f

/**
 * Now Playing full screen: blurred artwork background tinted by the album colour, breathing artwork,
 * animated progress (smooth between player ticks), shuffle / repeat, lyrics placeholder, queue sheet.
 * Swipe down / Back collapses to the mini bar (playback continues).
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
    val tokens = Cinetor.tokens
    val dominant = remember(track.dominantColor) { hexColor(track.dominantColor) }
    val accent = remember(track.accentColor) { hexColor(track.accentColor) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (showQueue) showQueue = false else music.collapse() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
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
        AsyncImage(
            model = rememberSizedImage(track.backdrop.ifBlank { track.poster }, 360.dp, 640.dp),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(48.dp).graphicsLayer { alpha = 0.75f },
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(dominant.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.93f)))))
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Rounded.KeyboardArrowDown, "Collapse now playing") { music.collapse() }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PLAYING FROM ALBUM", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                    Text(track.album.orEmpty(), color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RoundIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue") { showQueue = true }
            }
            Spacer(Modifier.weight(0.6f))
            AnimatedContent(
                targetState = showLyrics,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "artOrLyrics",
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f),
            ) { lyrics ->
                if (lyrics) {
                    LyricsPlaceholder(track.title, track.artist.orEmpty())
                } else {
                    val scale by animateFloatAsState(if (ui.isPlaying) 1f else 0.88f, tween(420), label = "artScale")
                    AsyncImage(
                        model = rememberSizedImage(track.poster, 320.dp, 320.dp, large = true),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .shadow(32.dp, RoundedCornerShape(20.dp), ambientColor = accent, spotColor = accent)
                            .clip(RoundedCornerShape(20.dp))
                            .background(dominant),
                    )
                }
            }
            Spacer(Modifier.weight(0.5f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist.orEmpty(), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
                TrackDownloadIcon(trackDlOf(downloads[track.id]), track.title, { downloadTrack(container, track) }, tint = Color.White)
            }
            Spacer(Modifier.height(12.dp))
            ProgressSlider(ui, accent) { music.seekTo(it) }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ToggleIcon(Icons.Rounded.Shuffle, if (queue.shuffle) "Shuffle on" else "Shuffle", queue.shuffle, accent) { music.toggleShuffle() }
                RoundIcon(Icons.Rounded.SkipPrevious, "Previous track", big = true) { music.previous() }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(Color.White)
                        .clickable { music.togglePlay() }
                        .semantics { contentDescription = if (ui.playWhenReady) "Pause music" else "Play music" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (ui.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(42.dp))
                }
                RoundIcon(Icons.Rounded.SkipNext, "Next track", big = true) { music.next() }
                ToggleIcon(
                    if (queue.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Repeat ${queue.repeat.name.lowercase()}", queue.repeat != RepeatMode.OFF, accent,
                ) { music.cycleRepeat() }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ToggleIcon(Icons.Rounded.MusicNote, "Lyrics", showLyrics, accent) { showLyrics = !showLyrics }
                Text(
                    queue.upNext.firstOrNull()?.let { "Next: ${it.title}" } ?: "End of queue",
                    color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp).align(Alignment.CenterVertically),
                    textAlign = TextAlign.Center,
                )
                ToggleIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Open queue", showQueue, accent) { showQueue = true }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (showQueue) QueueSheet(music, onDismiss = { showQueue = false })
}

/** Seek slider: animates smoothly between the player's 250 ms ticks; drag = scrub, release = seek. */
@Composable
private fun ProgressSlider(ui: PlayerUiState, accent: Color, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val target = progressOf(ui)
    val animated by animateFloatAsState(target, tween(if (ui.isPlaying) 260 else 0, easing = LinearEasing), label = "progress")
    val value = dragging ?: animated
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = value,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { f -> onSeek((f * ui.durationMs).toLong()) }
                dragging = null
            },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent, inactiveTrackColor = Color.White.copy(alpha = 0.22f)),
            modifier = Modifier.semantics { contentDescription = "Seek track" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val pos = dragging?.let { (it * ui.durationMs).toLong() } ?: ui.positionMs
            Text(formatTime(pos), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(ui.durationMs), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LyricsPlaceholder(title: String, artist: String) {
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            .verticalScroll(scroll).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Lyrics", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        listOf(
            "♪ $title — $artist",
            "Lyrics aren't available for mock tracks yet.",
            "Synced lyrics will scroll here line by line,",
            "highlighting the current verse as the song plays.",
            "♪ ♪ ♪",
        ).forEachIndexed { i, line ->
            Text(line, color = if (i == 0) Color.White else Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, big: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(if (big) 56.dp else 44.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(if (big) 36.dp else 26.dp))
    }
}

@Composable
private fun ToggleIcon(icon: ImageVector, label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(if (on) accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (on) accent else Color.White.copy(alpha = 0.8f), modifier = Modifier.size(24.dp))
    }
}
