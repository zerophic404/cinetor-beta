package com.mythbrix.cinetor.feature.details

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.view.TextureView
import android.webkit.WebView
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.download.DownloadInfo
import com.mythbrix.cinetor.core.download.DownloadStatus
import com.mythbrix.cinetor.core.download.downloadIdFor
import com.mythbrix.cinetor.core.download.isDownloadable
import com.mythbrix.cinetor.core.download.nextUnwatched
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.core.player.Readiness
import com.mythbrix.cinetor.core.player.ResumeEntry
import com.mythbrix.cinetor.core.player.SourceResolver
import com.mythbrix.cinetor.core.ui.LocalResumeEntries
import com.mythbrix.cinetor.core.ui.posterKey
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.core.ui.sharedPoster
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.SkeletonBox
import com.mythbrix.cinetor.design.SkeletonDetails
import com.mythbrix.cinetor.design.SkeletonEpisodeList
import com.mythbrix.cinetor.design.SkeletonText
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.design.hexColor
import com.mythbrix.cinetor.feature.downloads.BatchSeriesDownloadSheet
import com.mythbrix.cinetor.feature.downloads.DownloadButton
import com.mythbrix.cinetor.feature.downloads.DownloadDetailsSheet
import com.mythbrix.cinetor.feature.downloads.DownloadOptionsSheet
import com.mythbrix.cinetor.feature.downloads.DownloadTarget
import com.mythbrix.cinetor.feature.downloads.EpisodeDownloadButton
import com.mythbrix.cinetor.feature.downloads.batchQueuedSnackbarMessage
import com.mythbrix.cinetor.feature.home.PosterCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

private val HeaderHeight = 292.dp
private val PosterW = 118.dp
private val PosterH = 177.dp
private const val PREVIEW_DELAY_MS = 1_400L
private const val PREVIEW_FIND_TIMEOUT_MS = 8_000L
private const val PREVIEW_MOVIE_START_MS = 300_000L
private const val PREVIEW_SERIES_START_MS = 150_000L
private const val PREVIEW_WINDOW_MS = 90_000L
private const val PREVIEW_MAX_HEIGHT = 480

/**
 * Shiopa-style Details screen:
 * - Shimmer skeleton while loading (peek poster/header shown immediately when known)
 * - Muted inline backdrop clip/trailer preview on MID/HIGH tiers (strictly off on LOW & Data Saver)
 * - 4K UHD, ★ rating, dub language, year, maturity & runtime pills
 * - Liquid Glass action bar with Play/Resume + live ReadyDot, Multi-Season Batch Download (series) /
 *   Single Download (movies), My List, and Share
 * - Season selector tabs, episode cards with per-episode download buttons, and More Like This.
 */
@Composable
fun DetailsScreen(
    id: String,
    origin: String,
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenItem: (Item, String?) -> Unit,
    /** "Show downloads" action of the download-started snackbar (hardening §4). */
    onOpenDownloads: () -> Unit = {},
) {
    val vm: DetailsViewModel = viewModel(
        key = "details-$id",
        factory = viewModelFactory {
            initializer { DetailsViewModel(id, container.catalog, container.preloader, container.peek::get) }
        },
    )
    val holder = vm.holder
    val state by holder.state.collectAsStateWithLifecycle()
    val readiness by holder.readiness.collectAsStateWithLifecycle()
    val myList by container.myList.collectAsStateWithLifecycle()
    val settings by container.settingsState.state.collectAsStateWithLifecycle()
    val playerState by container.playback.state.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, holder) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> holder.pauseWarm()
                Lifecycle.Event.ON_START -> holder.resumeWarm()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val tint = Cinetor.tint
    val tier = Cinetor.tier
    val isTv = container.deviceProfile.isTv
    val headerItem = when (val s = state) {
        is DetailsUiState.Ready -> s.ui.item
        is DetailsUiState.Loading -> s.peek
        is DetailsUiState.Error -> null
    }
    LaunchedEffect(headerItem?.dominantColor) {
        headerItem?.let { tint.setSource(hexColor(it.dominantColor)) }
    }

    val downloadsById by container.downloads.engine.downloads.collectAsStateWithLifecycle()
    val dlMap = remember(downloadsById) { downloadsById.associateBy { it.record.id } }
    var optionsTarget by remember { mutableStateOf<DownloadTarget?>(null) }
    var batchSheetSeason by remember { mutableStateOf<Int?>(null) }
    var batchPreselectedIds by remember { mutableStateOf<Set<String>?>(null) }
    var previewStopEpoch by remember { mutableIntStateOf(0) }

    val snackbar = remember { SnackbarHostState() }
    val snackScope = rememberCoroutineScope()
    var dlDetailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var season by rememberSaveable(id) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val resumeEntries = LocalResumeEntries.current
    val playFocusRequester = remember { FocusRequester() }

    val readyItem = (state as? DetailsUiState.Ready)?.ui?.item
    var resolvedExtraSources by remember(id) { mutableStateOf<List<Source>>(emptyList()) }

    val itemReadiness = readyItem?.let { readiness[it.id] }
    LaunchedEffect(readyItem?.id, itemReadiness) {
        val targetItem = readyItem ?: return@LaunchedEffect
        if (itemReadiness == Readiness.READY && container.streams.handles(targetItem)) {
            val firstEpId = targetItem.seasons.firstOrNull()?.episodes?.firstOrNull()?.id
            val attached = withContext(Dispatchers.IO) {
                runCatching { container.streams.attach(targetItem, firstEpId) }.getOrNull()
            }
            if (attached != null) {
                resolvedExtraSources = if (firstEpId != null) {
                    SourceResolver.episodes(attached).firstOrNull { it.id == firstEpId }?.sources.orEmpty()
                } else {
                    attached.sources
                }
            }
        }
    }

    LaunchedEffect(readyItem?.id, isTv) {
        if (isTv && readyItem != null) {
            runCatching { playFocusRequester.requestFocus() }
        }
    }

    val allowInlinePreview = canAutoplayInlinePreview(
        tier = tier,
        dataSaver = settings.dataSaver,
        bannerAutoplay = settings.bannerAutoplay,
        reduceMotion = settings.reduceMotion,
        isTv = isTv,
        playerActive = playerState.active,
    )

    fun showQueuedSnackbar(message: String, withDownloadsAction: Boolean = true) {
        snackScope.launch {
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = if (withDownloadsAction) "Show downloads" else null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onOpenDownloads()
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        modifier = Modifier.fillMaxSize(),
    ) {
        when (val s = state) {
            is DetailsUiState.Error -> item(key = "error") {
                Text(
                    "Title not found (${s.id})",
                    color = Cinetor.tokens.textSecondary,
                    modifier = Modifier
                        .padding(top = contentPadding.calculateTopPadding())
                        .padding(24.dp),
                )
            }
            is DetailsUiState.Loading -> {
                val peek = s.peek
                if (peek == null) {
                    item(key = "skeleton", contentType = "skeleton") {
                        ShimmerHost {
                            Column(
                                Modifier.padding(top = contentPadding.calculateTopPadding()),
                                verticalArrangement = Arrangement.spacedBy(24.dp),
                            ) {
                                SkeletonDetails()
                                SkeletonEpisodeList()
                            }
                        }
                    }
                } else {
                    item(key = "header", contentType = "header") {
                        DetailsHeader(
                            item = peek,
                            origin = origin,
                            extraSources = emptyList(),
                            enableInlinePreview = false,
                            previewStopEpoch = previewStopEpoch,
                            container = container,
                        )
                    }
                    item(key = "skeleton", contentType = "skeleton") { BodySkeleton() }
                }
            }
            is DetailsUiState.Ready -> {
                val ui = s.ui
                val item = ui.item
                val isSeriesWithSeasons = item.type == ItemType.SERIES && item.seasons.isNotEmpty()
                val selectedSeasonIndex = season.coerceIn(0, (item.seasons.size - 1).coerceAtLeast(0))
                val activeSeasonObj = item.seasons.getOrNull(selectedSeasonIndex)

                item(key = "header", contentType = "header") {
                    DetailsHeader(
                        item = item,
                        origin = origin,
                        extraSources = resolvedExtraSources,
                        enableInlinePreview = allowInlinePreview,
                        previewStopEpoch = previewStopEpoch,
                        container = container,
                    )
                }
                item(key = "actions", contentType = "actions") {
                    DetailsActions(
                        item = item,
                        readiness = readiness[item.id],
                        inMyList = item.id in myList,
                        resume = resumeEntries.firstOrNull { it.itemId == item.id },
                        playFocusRequester = playFocusRequester,
                        onPlay = {
                            previewStopEpoch++
                            container.playback.play(item)
                        },
                        downloadInfo = dlMap[downloadIdFor(item.id, null)],
                        canDownload = isDownloadable(item),
                        isSeriesBatch = isSeriesWithSeasons,
                        onDownload = {
                            val existingMovieDl = dlMap[downloadIdFor(item.id, null)]
                            when {
                                isSeriesWithSeasons -> {
                                    batchPreselectedIds = null
                                    batchSheetSeason = activeSeasonObj?.n ?: item.seasons.first().n
                                }
                                existingMovieDl != null -> dlDetailsId = existingMovieDl.record.id
                                else -> optionsTarget = DownloadTarget(item)
                            }
                        },
                        onToggleMyList = { container.toggleMyList(item) },
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Watch \"${item.title}\" on Cinetor")
                            }
                            context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                    )
                }
                item(key = "synopsis", contentType = "synopsis") { Synopsis(item) }

                val seasons = item.seasons
                if (seasons.isNotEmpty()) {
                    item(key = "seasons", contentType = "seasons") {
                        Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                            SeasonTabs(seasons, selectedSeasonIndex) { season = it }
                        }
                    }
                    val seasonObj = seasons[selectedSeasonIndex]
                    if (isDownloadable(item)) {
                        item(key = "season-dl-$selectedSeasonIndex", contentType = "season-dl") {
                            SeasonDownloadActions(
                                seasonNumber = seasonObj.n,
                                multiSeason = seasons.size > 1,
                                onBatchDownload = {
                                    batchPreselectedIds = null
                                    batchSheetSeason = seasonObj.n
                                },
                                onNext3 = {
                                    val done = dlMap.keys
                                    val next = nextUnwatched(seasonObj.episodes, done, item.id)
                                    if (next.isEmpty()) {
                                        showQueuedSnackbar("All episodes in Season ${seasonObj.n} are already queued", withDownloadsAction = true)
                                    } else {
                                        batchPreselectedIds = next.map { it.id }.toSet()
                                        batchSheetSeason = seasonObj.n
                                    }
                                },
                            )
                        }
                    }
                    items(seasonObj.episodes, key = { "ep-${seasonObj.n}-${it.id}" }, contentType = { "episode" }) { ep ->
                        val epEntry = resumeEntries.firstOrNull { it.itemId == item.id && it.episodeId == ep.id }
                        val epProgress = epEntry?.takeIf { !it.finished }?.fraction
                        val epWatched = epEntry?.finished == true
                        val epInfo = dlMap[downloadIdFor(item.id, ep.id)]
                        EpisodeRow(
                            episode = ep,
                            progress = epProgress,
                            downloaded = epInfo?.record?.status == DownloadStatus.COMPLETED,
                            watched = epWatched,
                            trailing = if (isDownloadable(item)) {
                                {
                                    EpisodeDownloadButton(
                                        info = epInfo,
                                        onClick = {
                                            if (epInfo != null) dlDetailsId = epInfo.record.id
                                            else optionsTarget = DownloadTarget(item, listOf(ep), seasonObj.n)
                                        },
                                    )
                                }
                            } else {
                                null
                            },
                        ) {
                            previewStopEpoch++
                            container.playback.play(item, ep.id)
                        }
                    }
                }
                item(key = "more", contentType = "more") {
                    MoreLikeThis(ui, onOpenItem)
                }
            }
        }
    }

    // Multi-Season & Multi-Episode Batch Series Download Sheet
    val targetBatchSeason = batchSheetSeason
    if (targetBatchSeason != null && readyItem != null && readyItem.seasons.isNotEmpty()) {
        BatchSeriesDownloadSheet(
            item = readyItem,
            initialSeasonNumber = targetBatchSeason,
            module = container.downloads,
            downloadsById = dlMap,
            preselectedEpisodeIds = batchPreselectedIds,
            onDismiss = {
                batchSheetSeason = null
                batchPreselectedIds = null
            },
            onQueued = { count ->
                showQueuedSnackbar(batchQueuedSnackbarMessage(count), withDownloadsAction = true)
            },
            onNoStreamsFound = {
                showQueuedSnackbar("No downloadable streams available right now", withDownloadsAction = false)
            },
            resolve = if (container.streams.handles(readyItem)) {
                { seriesItem, _, ep ->
                    val attached = container.streams.attach(seriesItem, ep.id)
                    SourceResolver.episodes(attached).firstOrNull { it.id == ep.id }?.sources.orEmpty()
                }
            } else {
                null
            },
        )
    }

    // Single Movie / Single Episode Download Options Sheet
    optionsTarget?.let { target ->
        DownloadOptionsSheet(
            target = target,
            module = container.downloads,
            onDismiss = { optionsTarget = null },
            onStarted = { n ->
                showQueuedSnackbar(
                    if (n > 1) batchQueuedSnackbarMessage(n) else "Downloading started",
                    withDownloadsAction = true,
                )
            },
            resolve = if (container.streams.handles(target.item)) {
                { item, ep ->
                    val attached = container.streams.attach(item, ep?.id)
                    if (ep == null) attached.sources
                    else SourceResolver.episodes(attached).firstOrNull { it.id == ep.id }?.sources.orEmpty()
                }
            } else {
                null
            },
        )
    }

    dlDetailsId?.let { dlId ->
        DownloadDetailsSheet(
            dlId,
            container.downloads,
            onDismiss = { dlDetailsId = null },
            onOpen = { dlDetailsId = null },
            onPlay = { r ->
                dlDetailsId = null
                previewStopEpoch++
                container.playDownload(r)
            },
        )
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(
            snackbar,
            modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding() + 12.dp),
        )
    }
}

@Composable
private fun DetailsHeader(
    item: Item,
    origin: String,
    extraSources: List<Source>,
    enableInlinePreview: Boolean,
    previewStopEpoch: Int,
    container: AppContainer,
) {
    val tokens = Cinetor.tokens
    val tier = Cinetor.tier
    val dominant = remember(item.dominantColor) { hexColor(item.dominantColor) }
    var previewShowingVideo by remember(item.id) { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth().height(HeaderHeight + PosterH / 2)) {
        Box(Modifier.fillMaxWidth().height(HeaderHeight).background(dominant)) {
            AsyncImage(
                model = rememberSizedImage(item.backdrop, 400.dp, HeaderHeight, large = true),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (tier != PerformanceTier.LOW && !previewShowingVideo) Modifier.blur(4.dp) else Modifier),
            )
            if (enableInlinePreview) {
                DetailsInlinePreview(
                    item = item,
                    stopEpoch = previewStopEpoch,
                    container = container,
                    onShowingChanged = { previewShowingVideo = it },
                )
            }
            Box(Modifier.fillMaxSize().background(dominant.copy(alpha = if (previewShowingVideo) 0.14f else 0.28f)))
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to tokens.bg.copy(alpha = 0.32f),
                        0.48f to Color.Transparent,
                        1f to tokens.bg,
                    ),
                ),
            )
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier
                    .sharedPoster(origin.ifEmpty { null })
                    .size(PosterW, PosterH)
                    .clip(RoundedCornerShape(CinetorTokens.Shape.card))
                    .background(dominant)
                    .border(1.dp, tokens.glassStroke, RoundedCornerShape(CinetorTokens.Shape.card)),
            ) {
                AsyncImage(
                    model = rememberSizedImage(item.poster, PosterW, PosterH),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.logo != null) {
                    AsyncImage(
                        model = rememberSizedImage(item.logo, 200.dp, 64.dp),
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier.width(200.dp).heightIn(max = 64.dp),
                    )
                } else {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = tokens.textPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MetaChips(item, extraSources)
            }
        }
    }
}

/**
 * Muted inline preview over the Details backdrop (MID/HIGH tiers only, never on LOW or Data Saver).
 * Uses a tiny-buffer Media3 ExoPlayer on the Main looper (or chromeless YouTube embed fallback) and
 * disposes cleanly on lifecycle STOP / unmount / Play tap.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun DetailsInlinePreview(
    item: Item,
    stopEpoch: Int,
    container: AppContainer,
    onShowingChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val tokens = Cinetor.tokens
    val lifecycleOwner = LocalLifecycleOwner.current

    var exoPlayer by remember(item.id) { mutableStateOf<ExoPlayer?>(null) }
    var textureView by remember(item.id) { mutableStateOf<TextureView?>(null) }
    var youtubeId by remember(item.id) { mutableStateOf<String?>(null) }
    var firstFrame by remember(item.id) { mutableStateOf(false) }
    var muted by rememberSaveable(item.id) { mutableStateOf(true) }
    var failed by remember(item.id) { mutableStateOf(false) }

    fun releasePreview() {
        exoPlayer?.let { p ->
            textureView?.let(p::clearVideoTextureView)
            p.release()
        }
        exoPlayer = null
        youtubeId = null
        firstFrame = false
        onShowingChanged(false)
    }

    val currentStopEpoch by androidx.compose.runtime.rememberUpdatedState(stopEpoch)

    DisposableEffect(item.id, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> exoPlayer?.pause()
                Lifecycle.Event.ON_START -> if (firstFrame && currentStopEpoch == 0) exoPlayer?.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            releasePreview()
        }
    }

    LaunchedEffect(item.id, stopEpoch) {
        if (stopEpoch > 0) {
            releasePreview()
            return@LaunchedEffect
        }
        delay(PREVIEW_DELAY_MS)
        val firstEpId = if (item.type == ItemType.SERIES) item.seasons.firstOrNull()?.episodes?.firstOrNull()?.id else null
        val candidateSources = withContext(Dispatchers.IO) {
            if (container.streams.handles(item)) {
                val attached = withTimeoutOrNull(PREVIEW_FIND_TIMEOUT_MS) {
                    runCatching { container.streams.attach(item, firstEpId) }.getOrNull()
                }
                if (attached == null) emptyList()
                else if (firstEpId != null) SourceResolver.episodes(attached).firstOrNull { it.id == firstEpId }?.sources.orEmpty()
                else attached.sources
            } else if (firstEpId != null) {
                SourceResolver.episodes(item).firstOrNull { it.id == firstEpId }?.sources.orEmpty()
            } else {
                item.sources
            }
        }
        if (candidateSources.isEmpty() || stopEpoch > 0) return@LaunchedEffect

        val directPick = candidateSources
            .filter { s ->
                s.url.isNotBlank() &&
                    extractPreviewYouTubeId(s.url) == null &&
                    (s.kind == SourceKind.MP4 || s.kind == SourceKind.HLS || s.kind == SourceKind.WEBM || s.kind == SourceKind.MKV)
            }
            .sortedWith(
                compareBy<Source> { if ((it.height ?: 0) in 1..PREVIEW_MAX_HEIGHT) 0 else 1 }
                    .thenBy { it.height?.takeIf { h -> h > 0 } ?: Int.MAX_VALUE },
            )
            .firstOrNull()

        if (directPick != null) {
            val durSec = (if (firstEpId != null) SourceResolver.episodes(item).firstOrNull { it.id == firstEpId }?.durationSec else item.durationSec) ?: 0
            val rawStart = if (item.type == ItemType.SERIES) PREVIEW_SERIES_START_MS else PREVIEW_MOVIE_START_MS
            val clipStart = if (durSec <= 0 || durSec * 1_000L > rawStart + PREVIEW_WINDOW_MS) rawStart else 0L
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(SourceResolver.USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(directPick.headers)
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(4_000, 12_000, 1_000, 2_000)
                .build()
            val built = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(http))
                .setLoadControl(loadControl)
                .build()
                .apply {
                    volume = if (muted) 0f else 1f
                    repeatMode = Player.REPEAT_MODE_ONE
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setMaxVideoSize(854, PREVIEW_MAX_HEIGHT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            firstFrame = true
                            onShowingChanged(true)
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            failed = true
                            onShowingChanged(false)
                        }
                    })
                    textureView?.let(::setVideoTextureView)
                    val mediaItem = MediaItem.Builder()
                        .setUri(directPick.url)
                        .apply {
                            if (directPick.kind == SourceKind.HLS || directPick.url.substringBefore('?').endsWith(".m3u8", true)) {
                                setMimeType(MimeTypes.APPLICATION_M3U8)
                            }
                        }
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(clipStart)
                                .setEndPositionMs(clipStart + PREVIEW_WINDOW_MS)
                                .build(),
                        )
                        .build()
                    setMediaItem(mediaItem)
                    prepare()
                    play()
                }
            exoPlayer = built
        } else {
            val yt = candidateSources.firstNotNullOfOrNull { extractPreviewYouTubeId(it.url) }
            if (yt != null) {
                youtubeId = yt
                onShowingChanged(true)
            }
        }
    }

    val visible = !failed && (firstFrame || youtubeId != null)
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 360),
        label = "inlinePreviewAlpha",
    )

    if (exoPlayer != null || youtubeId != null) {
        Box(
            Modifier
                .fillMaxSize()
                .focusProperties { canFocus = false },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha },
            ) {
                val yt = youtubeId
                if (yt != null) {
                    YouTubeBackdropPreview(yt, Modifier.fillMaxSize())
                } else {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                isFocusable = false
                                isClickable = false
                                textureView = this
                                exoPlayer?.setVideoTextureView(this)
                            }
                        },
                        onRelease = { view ->
                            if (textureView === view) {
                                exoPlayer?.clearVideoTextureView(view)
                                textureView = null
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (visible && exoPlayer != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 64.dp, end = 16.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .glass(CircleShape, GlassLevel.SURFACE)
                        .clickable {
                            muted = !muted
                            exoPlayer?.volume = if (muted) 0f else 1f
                        }
                        .semantics { contentDescription = if (muted) "Unmute preview" else "Mute preview" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = null,
                        tint = tokens.textPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeBackdropPreview(videoId: String, modifier: Modifier = Modifier) {
    val safeKey = remember(videoId) { videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(20) }
    val embedHost = "https://www.youtube-nocookie.com"
    val html = remember(safeKey) {
        """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          *{box-sizing:border-box;margin:0;padding:0}
          html,body{width:100%;height:100%;background:#000;overflow:hidden}
          #w{position:absolute;inset:0;overflow:hidden;background:#000}
          iframe{position:absolute;top:-60px;left:0;width:100%;height:calc(100% + 120px);border:0;pointer-events:none}
        </style></head><body><div id="w">
          <iframe src="$embedHost/embed/$safeKey?autoplay=1&mute=1&controls=0&loop=1&playlist=$safeKey&playsinline=1&modestbranding=1&rel=0&iv_load_policy=3&disablekb=1&fs=0" allow="autoplay; encrypted-media"></iframe>
        </div></body></html>
        """.trimIndent()
    }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.domStorageEnabled = true
                setBackgroundColor(AndroidColor.BLACK)
                isClickable = false
                isFocusable = false
                loadDataWithBaseURL(embedHost, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { web ->
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        },
        modifier = modifier,
    )
}

private fun runtimeLabel(item: Item): String? = when {
    item.seasons.isNotEmpty() -> {
        val epTotal = item.seasons.sumOf { it.episodes.size }
        if (item.seasons.size == 1 && epTotal > 0) "$epTotal Episodes"
        else "${item.seasons.size} Seasons"
    }
    item.durationSec != null && item.durationSec > 0 -> {
        val m = item.durationSec / 60
        if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }
    else -> null
}

/**
 * Shiopa-style Metadata Pills: ★ Rating, 4K UHD badge, Dub language pills, Year, Maturity & Runtime,
 * followed by clean genre typography.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaChips(item: Item, extraSources: List<Source>) {
    val tokens = Cinetor.tokens
    val fourK = remember(item, extraSources) { hasUltraHdSource(item, extraSources) }
    val dubs = remember(item.title, item.altTitle, item.genres) { extractDubBadges(item) }
    val cleanGenres = remember(item.genres) { displayGenres(item) }
    val pillShape = RoundedCornerShape(6.dp)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (item.rating > 0.0) {
                Row(
                    Modifier
                        .clip(pillShape)
                        .glass(pillShape, GlassLevel.SURFACE)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = null,
                        tint = tokens.warning,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        String.format(Locale.US, "%.1f", item.rating),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = tokens.textPrimary,
                    )
                }
            }
            if (fourK) {
                Text(
                    "4K UHD",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(pillShape)
                        .background(tokens.accent.copy(alpha = 0.16f))
                        .border(1.dp, tokens.accent.copy(alpha = 0.65f), pillShape)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            dubs.forEach { dub ->
                Text(
                    dub,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(pillShape)
                        .glass(pillShape, GlassLevel.SURFACE)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            val facts = buildList {
                if (item.year > 0) add(item.year.toString())
                if (item.maturity.isNotBlank() && item.maturity != "NR") add(item.maturity)
                runtimeLabel(item)?.let { add(it) }
            }
            facts.forEach { fact ->
                Text(
                    fact,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(pillShape)
                        .glass(pillShape, GlassLevel.SURFACE)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
        if (cleanGenres.isNotEmpty()) {
            Text(
                cleanGenres.take(3).joinToString("  •  "),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Shiopa-style Liquid Glass Action Bar:
 * Primary Play/Resume capsule CTA (with preloader ReadyDot), Download button (Batch for series,
 * single options for movies), and Liquid Glass My List & Share pills.
 */
@Composable
private fun DetailsActions(
    item: Item,
    readiness: Readiness?,
    inMyList: Boolean,
    resume: ResumeEntry?,
    playFocusRequester: FocusRequester,
    onPlay: () -> Unit,
    downloadInfo: DownloadInfo?,
    canDownload: Boolean,
    isSeriesBatch: Boolean,
    onDownload: () -> Unit,
    onToggleMyList: () -> Unit,
    onShare: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val pillShape = RoundedCornerShape(CinetorTokens.Shape.pill)
    val barShape = RoundedCornerShape(CinetorTokens.Shape.lg)
    val firstEp = item.seasons.firstOrNull()?.episodes?.firstOrNull()
    val resumable = resume?.takeIf { !it.finished && it.positionMs >= 3_000 }
    val playLabel = when {
        resumable != null && resumable.episodeLabel != null -> "Resume ${resumable.episodeLabel}"
        resumable != null -> "Resume"
        item.type == ItemType.SERIES && firstEp != null -> "Play S1:E${firstEp.n}"
        else -> "Play"
    }
    val playInteraction = remember { MutableInteractionSource() }
    val playFocused by playInteraction.collectIsFocusedAsState()

    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .clip(barShape)
            .glass(barShape, GlassLevel.SURFACE)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(50.dp)
                    .focusRequester(playFocusRequester)
                    .clip(pillShape)
                    .background(Brush.horizontalGradient(listOf(tokens.accent, tokens.accentEnd)))
                    .then(
                        if (playFocused) Modifier.border(2.dp, tokens.focusRing, pillShape)
                        else Modifier,
                    )
                    .clickable(interactionSource = playInteraction, indication = null, onClick = onPlay)
                    .semantics { contentDescription = "Play" }
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = tokens.onAccent)
                Spacer(Modifier.width(6.dp))
                Text(
                    playLabel,
                    color = tokens.onAccent,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (readiness != null) {
                    Spacer(Modifier.width(8.dp))
                    ReadyDot(readiness)
                }
            }
            if (canDownload) {
                DownloadButton(
                    info = if (isSeriesBatch) null else downloadInfo,
                    onClick = onDownload,
                    overrideLabel = if (isSeriesBatch) "Episodes" else null,
                )
            }
        }
        if (resumable != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tokens.textPrimary.copy(alpha = 0.18f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(resumable.fraction.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(tokens.accent),
                    )
                }
                val leftMin = ((resumable.durationMs - resumable.positionMs) / 60_000).coerceAtLeast(1)
                Text(
                    "$leftMin min left",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textSecondary,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SecondaryGlassPill(
                icon = if (inMyList) Icons.Rounded.Check else Icons.Rounded.Add,
                label = if (inMyList) "In My List" else "My List",
                semanticDesc = "My List",
                highlighted = inMyList,
                onClick = onToggleMyList,
                modifier = Modifier.weight(1f),
            )
            SecondaryGlassPill(
                icon = Icons.Rounded.Share,
                label = "Share",
                semanticDesc = "Share",
                highlighted = false,
                onClick = onShare,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ReadyDot(readiness: Readiness?) {
    val tokens = Cinetor.tokens
    val tier = Cinetor.tier
    val reduceMotion = Cinetor.prefs.reduceMotion
    if (readiness == null) return
    val color = when (readiness) {
        Readiness.READY -> tokens.success
        Readiness.ERROR -> tokens.error
        else -> tokens.warning
    }
    val shouldAnimatePulse = readiness != Readiness.READY &&
        readiness != Readiness.ERROR &&
        tier != PerformanceTier.LOW &&
        !reduceMotion
    val pulse = if (!shouldAnimatePulse) {
        null
    } else {
        rememberInfiniteTransition(label = "readyPulse").animateFloat(
            0.35f,
            1f,
            infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "readyAlpha",
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = if (readiness == Readiness.READY) "Ready" else "Preparing" },
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = pulse?.value ?: 1f }
                .clip(CircleShape)
                .background(color),
        )
        if (readiness == Readiness.READY) {
            Spacer(Modifier.width(4.dp))
            Text(
                "Ready",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.onAccent.copy(alpha = 0.92f),
            )
        }
    }
}

@Composable
private fun SecondaryGlassPill(
    icon: ImageVector,
    label: String,
    semanticDesc: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        modifier
            .height(40.dp)
            .clip(shape)
            .then(
                if (highlighted) Modifier.background(tokens.accent.copy(alpha = 0.14f))
                else Modifier.background(tokens.surfaceElevated.copy(alpha = 0.65f)),
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    focused -> tokens.focusRing
                    highlighted -> tokens.accent.copy(alpha = 0.55f)
                    else -> tokens.glassStroke
                },
                shape = shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = semanticDesc }
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (highlighted) tokens.accent else tokens.textPrimary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = tokens.textPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun Synopsis(item: Item) {
    val tokens = Cinetor.tokens
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(CinetorTokens.Shape.md)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .then(if (focused) Modifier.border(2.dp, tokens.focusRing, shape) else Modifier)
            .animateContentSize()
            .clickable(interactionSource = interaction, indication = null) { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (item.synopsis.isNotBlank()) {
            Text(
                item.synopsis,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textPrimary,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (expanded) "Show less" else "Show more",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if ((expanded || item.synopsis.isBlank()) && item.cast.isNotEmpty()) {
            Text(
                "Cast: " + item.cast.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }
    }
}

@Composable
private fun MoreLikeThis(ui: DetailsUi, onOpenItem: (Item, String?) -> Unit) {
    val tokens = Cinetor.tokens
    val tint = Cinetor.tint
    if (!ui.moreLoading && ui.moreLikeThis.isEmpty()) return
    Column(Modifier.padding(top = 24.dp)) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tokens.accent),
            )
            Text(
                "More Like This",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tokens.textPrimary,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (ui.moreLoading) {
            ShimmerHost {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) {
                        SkeletonBox(Modifier.size(104.dp, 156.dp), RoundedCornerShape(CinetorTokens.Shape.card))
                    }
                }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ui.moreLikeThis, key = { it.id }, contentType = { "poster" }) { m ->
                    val key = posterKey("mlt-${ui.item.id}", m.id)
                    PosterCard(
                        m,
                        onClick = { onOpenItem(m, key) },
                        onActive = { tint.setSource(it) },
                        width = 104.dp,
                        sharedKey = key,
                    )
                }
            }
        }
    }
}

@Composable
private fun BodySkeleton() {
    ShimmerHost {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonBox(Modifier.weight(1f).height(50.dp), RoundedCornerShape(CinetorTokens.Shape.pill))
                SkeletonBox(Modifier.width(120.dp).height(50.dp), RoundedCornerShape(CinetorTokens.Shape.pill))
            }
            SkeletonText(lines = 3)
            Spacer(Modifier.height(8.dp))
            SkeletonEpisodeList(Modifier.padding(horizontal = 0.dp), count = 4)
        }
    }
}

/** Multi-season batch & "Next 3 episodes" Liquid Glass shortcuts above the episode list. */
@Composable
private fun SeasonDownloadActions(
    seasonNumber: Int,
    multiSeason: Boolean,
    onBatchDownload: () -> Unit,
    onNext3: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val batchLabel = if (multiSeason) "Batch download (S$seasonNumber+)" else "Download season"
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        listOf(batchLabel to onBatchDownload, "Next 3 episodes" to onNext3).forEach { (label, action) ->
            val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            Row(
                Modifier
                    .clip(shape)
                    .glass(shape, GlassLevel.SURFACE)
                    .then(if (focused) Modifier.border(2.dp, tokens.focusRing, shape) else Modifier)
                    .clickable(interactionSource = interaction, indication = null, onClick = action)
                    .semantics { contentDescription = label }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, color = tokens.textPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}
