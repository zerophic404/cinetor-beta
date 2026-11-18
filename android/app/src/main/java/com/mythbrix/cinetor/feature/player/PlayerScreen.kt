package com.mythbrix.cinetor.feature.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.player.CinetorPlayer
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import kotlinx.coroutines.delay

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** PlayerView resize mode for an [AspectMode] (+ extra zoom factor for ZOOM). */
@OptIn(UnstableApi::class)
fun resizeModeFor(mode: AspectMode): Int = when (mode) {
    AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    AspectMode.FILL, AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}

/** Media3 surface (or chromeless YouTube WebView when [youtubeVideoId] is non-null). */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(
    player: Player?,
    aspect: AspectMode,
    modifier: Modifier = Modifier,
    zoom: Float = 1f,
    configure: (PlayerView) -> Unit = {},
    youtubeVideoId: String? = null,
    youtubeSessionKey: String? = null,
    youtubeHolder: YouTubeWebViewHolder? = null,
    cinetorPlayer: CinetorPlayer? = null,
    startPositionMs: Long = 0L,
    playWhenReady: Boolean = true,
) {
    if (youtubeVideoId != null) {
        val activeHolder = youtubeHolder ?: rememberYouTubeWebViewHolder(
            activeVideoId = youtubeVideoId,
            cinetorPlayer = cinetorPlayer,
        )
        if (activeHolder != null) {
            ChromelessYouTubeSurface(
                youtubeHolder = activeHolder,
                sessionKey = youtubeSessionKey,
                videoId = youtubeVideoId,
                startPositionMs = startPositionMs,
                playWhenReady = playWhenReady,
                aspect = aspect,
                modifier = modifier,
                zoom = zoom,
            )
        }
        return
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                setBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        update = { v ->
            if (v.player !== player) v.player = player
            v.resizeMode = resizeModeFor(aspect)
            val z = zoom * (if (aspect == AspectMode.ZOOM) 1.25f else 1f)
            v.videoSurfaceView?.let { s ->
                s.scaleX = z
                s.scaleY = z
            }
            configure(v)
        },
        onRelease = { v -> v.player = null },
        modifier = modifier,
    )
}

/** Hook for Task 3.4/3.5 layers drawn over the video (gestures, subtitle overlay). */
class PlayerLayers(
    val gestures: @Composable (Modifier, () -> Unit) -> Unit = { m, toggle ->
        Box(m.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = toggle))
    },
    val overVideo: @Composable () -> Unit = {},
    val configureView: (PlayerView) -> Unit = {},
    val zoom: Float = 1f,
    val youtubeHolder: YouTubeWebViewHolder? = null,
)

/**
 * Player screen supporting both embedded 16:9 [inline] mode (`InlinePlayerSlot` inside `DetailsScreen` —
 * never forces landscape or hides system bars) and [HostMode.FULL] immersive fullscreen mode.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    screen: PlayerScreenState,
    cinetorPlayer: CinetorPlayer?,
    ui: PlayerUiState,
    holder: PlayerStateHolder,
    onBack: () -> Unit,
    layers: PlayerLayers = PlayerLayers(),
    subtitleExtras: SubtitleExtras = SubtitleExtras(),
    extraTopActions: @Composable () -> Unit = {},
    inline: Boolean = false,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (!inline) {
        DisposableEffect(activity, screen.openedInline) {
            activity?.requestedOrientation = if (screen.openedInline && !ui.isVertical) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }
            onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        // Immersive in landscape / expanded inline fullscreen; bars back in portrait / on exit.
        DisposableEffect(activity, landscape, screen.openedInline) {
            val window = activity?.window
            val ctrl = window?.let { WindowCompat.getInsetsController(it, view) }
            if (landscape || screen.openedInline) {
                ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctrl?.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                ctrl?.show(WindowInsetsCompat.Type.systemBars())
            }
            onDispose { ctrl?.show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    DisposableEffect(view, ui.isPlaying) {
        view.keepScreenOn = ui.isPlaying
        onDispose { view.keepScreenOn = false }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var settingsPage by remember { mutableStateOf<SettingsPage?>(null) }
    var showEpisodes by remember { mutableStateOf(false) }

    LaunchedEffect(controlsVisible, interaction, ui.isPlaying, settingsPage) {
        if (controlsVisible && ui.isPlaying && settingsPage == null) {
            delay(3_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(screen.generation) { controlsVisible = true }
    val poke: () -> Unit = { interaction++ }
    val toggle: () -> Unit = {
        controlsVisible = !controlsVisible
        interaction++
    }

    BackHandler(enabled = !inline || showEpisodes || screen.locked) {
        when {
            showEpisodes -> showEpisodes = false
            screen.locked -> controlsVisible = true
            else -> onBack()
        }
    }

    val enterPip: () -> Unit = {
        val enteredSystemPip = if (
            activity != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            val (w, h) = pipAspect(ui.videoWidth, ui.videoHeight)
            runCatching {
                activity.enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(w, h)).build(),
                )
            }.getOrDefault(false)
        } else {
            false
        }
        if (!enteredSystemPip) {
            holder.minimize()
        }
    }

    val toggleFullscreen: () -> Unit = {
        if (inline) {
            holder.expandFullscreen()
        } else if (screen.openedInline) {
            holder.collapseFullscreen()
        } else {
            activity?.requestedOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    val fill = !inline && (landscape || ui.isVertical || screen.openedInline)

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        val videoModifier = when {
            inline || fill -> Modifier.fillMaxSize()
            else -> Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxWidth().aspectRatio(16f / 9f)
        }
        Box(videoModifier.background(Color.Black)) {
            val activeYoutubeId = ui.youtubeVideoId ?: screen.sources.getOrNull(screen.sourceIndex)?.url?.let(::extractYouTubeVideoId)
            VideoSurface(
                player = cinetorPlayer?.media3Player(),
                aspect = screen.aspect,
                modifier = Modifier.fillMaxSize(),
                zoom = layers.zoom,
                configure = layers.configureView,
                youtubeVideoId = activeYoutubeId,
                youtubeSessionKey = ui.key,
                youtubeHolder = layers.youtubeHolder,
                cinetorPlayer = cinetorPlayer,
                startPositionMs = ui.positionMs,
                playWhenReady = ui.playWhenReady,
            )
            layers.overVideo()
            layers.gestures(Modifier.fillMaxSize(), toggle)
            if (screen.notice == null && (cinetorPlayer == null || (ui.buffering && !controlsVisible && ui.playWhenReady))) {
                CircularProgressIndicator(color = Cinetor.tokens.accent, modifier = Modifier.align(Alignment.Center).size(if (inline) 38.dp else 48.dp))
            }
            (screen.notice ?: ui.error?.let { "Playback error: $it" })?.let { err ->
                Text(
                    err,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(12.dp),
                )
            }
            PlayerControls(
                screen = screen,
                ui = ui,
                visible = controlsVisible,
                fullscreen = fill,
                inline = inline,
                onInteract = poke,
                onBack = onBack,
                onRotate = toggleFullscreen,
                onOpenPage = { page -> settingsPage = page },
                onPip = enterPip,
                onEpisodes = { showEpisodes = true },
                holder = holder,
                extraTopActions = extraTopActions,
            )
            PlayerOverlays(screen, ui, holder, fill)
            androidx.compose.animation.AnimatedVisibility(
                visible = showEpisodes && fill && !inline,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Column(
                    Modifier.fillMaxHeight().width(340.dp).background(Color(0xF2101010))
                        .windowInsetsPadding(WindowInsets.navigationBars).padding(top = 12.dp),
                ) {
                    Text(
                        "Episodes", color = Color.White, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    EpisodeList(screen, onPick = { holder.playEpisode(it); showEpisodes = false })
                }
            }
        }
        if (!inline && !fill) InfoPanel(screen, holder)
    }

    val openPage = settingsPage
    if (openPage != null && cinetorPlayer != null) {
        SettingsSheet(
            ui = ui,
            screen = screen,
            holder = holder,
            onDismiss = { settingsPage = null; poke() },
            extras = subtitleExtras,
            initialPage = openPage,
        )
    }
}

/** Portrait: title, meta, synopsis, episodes (current highlighted). */
@Composable
private fun ColumnScope.InfoPanel(screen: PlayerScreenState, holder: PlayerStateHolder) {
    val item = screen.item ?: return
    val tokens = Cinetor.tokens
    Column(Modifier.weight(1f).fillMaxWidth().background(tokens.bg)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleLarge, color = tokens.textPrimary, fontWeight = FontWeight.Bold)
            val meta = listOfNotNull(
                item.year.takeIf { it > 0 }?.toString(),
                item.maturity.takeIf { it.isNotBlank() },
                screen.subtitle,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
            if (item.synopsis.isNotBlank()) {
                Text(item.synopsis, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (screen.episodes.isNotEmpty()) {
            Text(
                "Episodes", style = MaterialTheme.typography.titleMedium, color = tokens.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            EpisodeList(screen, onPick = holder::playEpisode)
        }
    }
}

@Composable
private fun EpisodeList(screen: PlayerScreenState, onPick: (String) -> Unit) {
    val tokens = Cinetor.tokens
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(screen.episodes, key = { it.id }) { ep: Episode ->
            val current = ep.id == screen.episodeId
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (current) tokens.accent.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onPick(ep.id) }
                    .semantics { contentDescription = "Episode ${ep.n}" }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = rememberSizedImage(ep.still, 112.dp, 63.dp),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(112.dp, 63.dp).clip(RoundedCornerShape(8.dp)).background(tokens.surfaceElevated),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "${ep.n}. ${ep.title}",
                        color = if (current) tokens.accent else tokens.textPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text("${(ep.durationSec / 60).coerceAtLeast(1)}m", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item { Box(Modifier.height(8.dp)) }
    }
}
