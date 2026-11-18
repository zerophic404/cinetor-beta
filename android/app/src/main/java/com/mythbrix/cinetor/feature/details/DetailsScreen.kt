package com.mythbrix.cinetor.feature.details

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.TextureView
import android.webkit.WebView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.mythbrix.cinetor.core.data.CastMember
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.download.DownloadInfo
import com.mythbrix.cinetor.core.download.DownloadStatus
import com.mythbrix.cinetor.core.download.downloadIdFor
import com.mythbrix.cinetor.core.download.isDownloadable
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.core.player.Readiness
import com.mythbrix.cinetor.core.player.ResumeEntry
import com.mythbrix.cinetor.core.player.SourceResolver
import com.mythbrix.cinetor.core.ui.CinetorArtwork
import com.mythbrix.cinetor.core.ui.LocalResumeEntries
import com.mythbrix.cinetor.core.ui.posterKey
import com.mythbrix.cinetor.core.ui.rememberArtworkColor
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
import com.mythbrix.cinetor.design.liquidGlassButton
import com.mythbrix.cinetor.feature.downloads.BatchSeriesDownloadSheet
import com.mythbrix.cinetor.feature.downloads.DownloadButton
import com.mythbrix.cinetor.feature.downloads.DownloadDetailsSheet
import com.mythbrix.cinetor.feature.downloads.DownloadOptionsSheet
import com.mythbrix.cinetor.feature.downloads.DownloadTarget
import com.mythbrix.cinetor.feature.downloads.EpisodeDownloadButton
import com.mythbrix.cinetor.feature.downloads.batchQueuedSnackbarMessage
import com.mythbrix.cinetor.feature.home.PosterCard
import com.mythbrix.cinetor.feature.player.HostMode
import com.mythbrix.cinetor.feature.player.InlinePlayerSlot
import com.mythbrix.cinetor.feature.player.gestures.GesturePrefs
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val ReadyGreen = Color(0xFF22C55E)
private val PosterThumbW = 76.dp
private val PosterThumbH = 114.dp
private const val PREVIEW_DELAY_MS = 1_400L
private const val PREVIEW_FIND_TIMEOUT_MS = 8_000L
private const val PREVIEW_MOVIE_START_MS = 300_000L
private const val PREVIEW_SERIES_START_MS = 150_000L
private const val PREVIEW_WINDOW_MS = 90_000L
private const val PREVIEW_MAX_HEIGHT = 480
private const val INITIAL_EPISODE_LIMIT = 12

/**
 * Cinetor v2.1.0 Title Details screen:
 * - Concurrent stream pre-warming during shimmer load + synthesized fallback so titles never fail with "Title not found"
 * - Top 16:9 thumbnail slot that transforms IN PLACE into [InlinePlayerSlot] on Play / Episode tap / [autoPlay]
 * - Docks existing `HostMode.MINI` into `HostMode.INLINE` on open, and minimizes `HostMode.INLINE` -> `HostMode.MINI` on leave
 * - Strictly 3 action buttons (`Play` with `8.dp` green dot only, `Download`, `My List`)
 * - Always-visible `Seasons & Episodes`, clickable `Cast` (opening Filmography sheet), and `For You` below.
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
    /** True when opened from Hero Play -> starts inline playback in top 16:9 slot immediately. */
    autoPlay: Boolean = false,
) {
    val resumeEntries = LocalResumeEntries.current
    val latestResumes by rememberUpdatedState(resumeEntries)

    val vm: DetailsViewModel = viewModel(
        key = "details-$id",
        factory = viewModelFactory {
            initializer {
                DetailsViewModel(
                    id = id,
                    catalog = container.catalog,
                    preloader = container.preloader,
                    peek = { lookupId -> container.peek.get(lookupId) ?: container.gateway.summary(lookupId) },
                    resumeEpisodeOf = { itemId ->
                        latestResumes.firstOrNull { it.itemId == itemId && !it.finished && it.positionMs >= 3_000 }?.episodeId
                    },
                    searchFallback = { q ->
                        container.remoteSearch?.search(q).orEmpty().ifEmpty { container.search.search(q) }
                    },
                    onItemsDiscovered = { items -> items.forEach(container.peek::put) },
                )
            }
        },
    )
    val holder = vm.holder
    val state by holder.state.collectAsStateWithLifecycle()
    val readiness by holder.readiness.collectAsStateWithLifecycle()
    val castState by vm.castState.collectAsStateWithLifecycle()
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

    // Leaving Details while playing inline -> seamlessly continue in floating free-drag PiP MiniPlayer.
    DisposableEffect(id) {
        onDispose {
            val st = container.playback.state.value
            if (st.mode == HostMode.INLINE && st.item?.id == id) {
                container.playback.minimize()
            }
        }
    }

    val tint = Cinetor.tint
    val tier = Cinetor.tier
    val isTv = container.deviceProfile.isTv
    val configuration = LocalConfiguration.current
    val listState = rememberLazyListState()
    val uiScope = rememberCoroutineScope()

    val headerItem = when (val s = state) {
        is DetailsUiState.Ready -> s.ui.item
        is DetailsUiState.Loading -> s.peek
        is DetailsUiState.Error -> null
    }
    // The title's own colour from its poster: drives a gentle page wash + the app-wide tint.
    val artColor = rememberArtworkColor(headerItem?.let { it.poster.ifBlank { it.backdrop } })
    LaunchedEffect(headerItem?.dominantColor, artColor) {
        val source = artColor ?: headerItem?.let { hexColor(it.dominantColor) }
        source?.let(tint::setSource)
    }
    val washTarget = if (tint.enabled && artColor != null) artColor else Color.Transparent
    val wash by animateColorAsState(
        targetValue = washTarget,
        animationSpec = if (Cinetor.prefs.reduceMotion) snap() else tween(700, easing = CinetorTokens.Motion.standard),
        label = "detailsArtWash",
    )
    val washLight = Cinetor.tokens.isLight

    val downloadsById by container.downloads.engine.downloads.collectAsStateWithLifecycle()
    val dlMap = remember(downloadsById) { downloadsById.associateBy { it.record.id } }
    var optionsTarget by remember { mutableStateOf<DownloadTarget?>(null) }
    var batchSheetSeason by remember { mutableStateOf<Int?>(null) }
    var previewStopEpoch by remember { mutableIntStateOf(0) }
    var expandAllEpisodes by rememberSaveable(id) { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    var dlDetailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var season by rememberSaveable(id) { mutableIntStateOf(0) }
    var initialSeasonSynced by rememberSaveable(id) { mutableStateOf(false) }
    val playFocusRequester = remember { FocusRequester() }

    val readyItem = (state as? DetailsUiState.Ready)?.ui?.item
    var resolvedExtraSources by remember(id) { mutableStateOf<List<Source>>(emptyList()) }

    val isInlineHere = playerState.mode == HostMode.INLINE &&
        (playerState.item?.id == id || (readyItem != null && playerState.item?.id == readyItem.id))

    // Dock existing MiniPlayer (or collapse from Fullscreen) for this title right into HostMode.INLINE.
    var lastSeenMode by remember(id) { mutableStateOf<HostMode?>(null) }
    LaunchedEffect(id, readyItem?.id, playerState.mode, playerState.item?.id) {
        val prev = lastSeenMode
        lastSeenMode = playerState.mode
        val matchesItem = playerState.item?.id == id || (readyItem != null && playerState.item?.id == readyItem.id)
        if (matchesItem && playerState.mode == HostMode.MINI && (prev == null || prev == HostMode.FULL)) {
            previewStopEpoch++
            container.playback.enterInline()
        }
    }

    // Hero Play (`autoPlay = true`) -> start inline playback in the top 16:9 slot immediately.
    var autoPlayConsumed by rememberSaveable(id) { mutableStateOf(false) }
    val immediatePlayable = readyItem ?: (state as? DetailsUiState.Loading)?.peek?.let(::ensurePlayableItem)
    LaunchedEffect(id, autoPlay, immediatePlayable?.id) {
        if (autoPlay && !autoPlayConsumed && immediatePlayable != null) {
            autoPlayConsumed = true
            previewStopEpoch++
            val matchesActive = playerState.item?.id == id || playerState.item?.id == immediatePlayable.id
            if (matchesActive && (playerState.mode == HostMode.MINI || playerState.mode == HostMode.INLINE)) {
                container.playback.enterInline()
            } else {
                val resEp = resumeEntries.firstOrNull {
                    it.itemId == immediatePlayable.id && !it.finished && it.positionMs >= 3_000
                }?.episodeId
                val (se, ep) = resolvePlayTarget(immediatePlayable, resEp)
                container.playback.open(immediatePlayable, se, ep, true)
            }
        }
    }

    // Sync initial season tab to the user's resumed season when opening a multi-season series.
    LaunchedEffect(readyItem?.id) {
        val target = readyItem ?: return@LaunchedEffect
        if (!initialSeasonSynced && target.seasons.size > 1) {
            initialSeasonSynced = true
            val resEp = resumeEntries.firstOrNull { it.itemId == target.id && !it.finished && it.positionMs >= 3_000 }?.episodeId
            val (se, _) = resolvePlayTarget(target, resEp)
            val idx = target.seasons.indexOfFirst { it.n == se }
            if (idx >= 0) season = idx
        }
    }

    val itemReadiness = readyItem?.let { readiness[it.id] } ?: readiness[id]
    LaunchedEffect(readyItem?.id, itemReadiness == null, isInlineHere) {
        if (readyItem != null && itemReadiness == null && !isInlineHere) vm.holder.ensureWarm()
    }
    LaunchedEffect(readyItem?.id, itemReadiness) {
        val targetItem = readyItem ?: return@LaunchedEffect
        if (itemReadiness.sourceReady() && container.streams.handles(targetItem)) {
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
        if (isTv && readyItem != null && !isInlineHere) {
            runCatching { playFocusRequester.requestFocus() }
        }
    }

    val allowInlinePreview = canAutoplayInlinePreview(
        tier = tier,
        dataSaver = settings.dataSaver,
        bannerAutoplay = settings.bannerAutoplay,
        reduceMotion = settings.reduceMotion,
        isTv = isTv,
        playerActive = playerState.active || isInlineHere,
    )

    val gesturePrefs = remember(settings.doubleTapSec, settings.holdSpeed, settings.gestures) {
        GesturePrefs(settings.doubleTapSec, settings.holdSpeed, settings.gestures)
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topSafeInset = maxOf(contentPadding.calculateTopPadding(), statusTop)
    val pinInlineSlot = isInlineHere && configuration.screenHeightDp >= 520 && !isTv

    fun startInlinePlayback(targetItem: Item, seasonNumber: Int?, episodeNumber: Int?) {
        previewStopEpoch++
        val playable = ensurePlayableItem(targetItem)
        container.playback.open(playable, seasonNumber, episodeNumber, true)
        if (!pinInlineSlot) {
            uiScope.launch { runCatching { listState.animateScrollToItem(0) } }
        }
    }

    fun showQueuedSnackbar(message: String, withDownloadsAction: Boolean = true) {
        uiScope.launch {
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = if (withDownloadsAction) "Show downloads" else null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onOpenDownloads()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .drawBehind {
                if (wash.alpha <= 0f) return@drawBehind
                val k = if (washLight) 0.55f else 1f
                // Soft glow from the artwork down to mid-page, plus a faint full-page cast.
                drawRect(
                    Brush.verticalGradient(
                        0f to wash.copy(alpha = wash.alpha * 0.30f * k),
                        0.38f to wash.copy(alpha = wash.alpha * 0.12f * k),
                        0.75f to wash.copy(alpha = wash.alpha * 0.04f * k),
                        1f to Color.Transparent,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(wash.copy(alpha = wash.alpha * 0.22f * k), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.18f),
                        radius = size.maxDimension * 0.62f,
                    ),
                )
            },
    ) {
        if (pinInlineSlot && headerItem != null) {
            TopMediaSlot(
                item = headerItem,
                topInset = topSafeInset,
                isInlinePlaying = true,
                enableMutedPreview = false,
                previewStopEpoch = previewStopEpoch,
                gesturePrefs = gesturePrefs,
                container = container,
                onTapPlayInline = {},
                glow = artColor,
            )
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when (val s = state) {
                is DetailsUiState.Error -> item(key = "error") {
                    Text(
                        s.message,
                        color = Cinetor.tokens.textSecondary,
                        modifier = Modifier
                            .padding(top = topSafeInset)
                            .padding(24.dp),
                    )
                }
                is DetailsUiState.Loading -> {
                    val peek = s.peek
                    if (peek == null) {
                        item(key = "skeleton", contentType = "skeleton") {
                            ShimmerHost {
                                Column(
                                    Modifier.padding(top = topSafeInset),
                                    verticalArrangement = Arrangement.spacedBy(24.dp),
                                ) {
                                    SkeletonDetails()
                                    SkeletonEpisodeList()
                                }
                            }
                        }
                    } else {
                        if (!pinInlineSlot) {
                            item(key = "top-media", contentType = "top-media") {
                                TopMediaSlot(
                                    item = peek,
                                    topInset = topSafeInset,
                                    isInlinePlaying = isInlineHere,
                                    enableMutedPreview = false,
                                    previewStopEpoch = previewStopEpoch,
                                    gesturePrefs = gesturePrefs,
                                    container = container,
                                    onTapPlayInline = {
                                        val resEp = resumeEntries.firstOrNull {
                                            it.itemId == peek.id && !it.finished && it.positionMs >= 3_000
                                        }?.episodeId
                                        val (se, ep) = resolvePlayTarget(ensurePlayableItem(peek), resEp)
                                        startInlinePlayback(peek, se, ep)
                                    },
                                    glow = artColor,
                                )
                            }
                        }
                        item(key = "summary-peek", contentType = "summary") {
                            DetailsSummaryHeader(item = peek, origin = origin, extraSources = emptyList())
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
                    val resumable = resumeEntries.firstOrNull {
                        it.itemId == item.id && !it.finished && it.positionMs >= 3_000
                    }

                    if (!pinInlineSlot) {
                        item(key = "top-media", contentType = "top-media") {
                            TopMediaSlot(
                                item = item,
                                topInset = topSafeInset,
                                isInlinePlaying = isInlineHere,
                                enableMutedPreview = allowInlinePreview,
                                previewStopEpoch = previewStopEpoch,
                                gesturePrefs = gesturePrefs,
                                container = container,
                                onTapPlayInline = {
                                    val (se, ep) = resolvePlayTarget(item, resumable?.episodeId)
                                    startInlinePlayback(item, se, ep)
                                },
                                glow = artColor,
                            )
                        }
                    }

                    item(key = "summary", contentType = "summary") {
                        DetailsSummaryHeader(item = item, origin = origin, extraSources = resolvedExtraSources)
                    }

                    // Determine next episode when already playing inline on a series
                    val nextInlineEpisode: Pair<Int, Episode>? = if (isInlineHere && isSeriesWithSeasons) {
                        val activeEpId = playerState.episodeId
                        val currentSeason = item.seasons.firstOrNull { sn -> sn.episodes.any { it.id == activeEpId } }
                        val currentEp = currentSeason?.episodes?.firstOrNull { it.id == activeEpId }
                        if (currentSeason != null && currentEp != null) {
                            val nextInSame = currentSeason.episodes.firstOrNull { it.n == currentEp.n + 1 }
                            if (nextInSame != null) {
                                currentSeason.n to nextInSame
                            } else {
                                val nextSeason = item.seasons.firstOrNull { it.n > currentSeason.n }
                                nextSeason?.episodes?.firstOrNull()?.let { nextSeason.n to it }
                            }
                        } else {
                            null
                        }
                    } else {
                        null
                    }

                    item(key = "actions", contentType = "actions") {
                        DetailsActions(
                            item = item,
                            readiness = if (isInlineHere) null else (readiness[item.id] ?: readiness[id]),
                            inMyList = item.id in myList,
                            resumable = resumable,
                            isInlinePlaying = isInlineHere,
                            nextInlineEpisode = nextInlineEpisode,
                            playFocusRequester = playFocusRequester,
                            onPlay = {
                                if (isInlineHere && nextInlineEpisode != null) {
                                    startInlinePlayback(item, nextInlineEpisode.first, nextInlineEpisode.second.n)
                                } else if (isInlineHere) {
                                    container.playback.player.value?.play()
                                } else {
                                    val (se, ep) = resolvePlayTarget(item, resumable?.episodeId)
                                    startInlinePlayback(item, se, ep)
                                }
                            },
                            downloadInfo = dlMap[downloadIdFor(item.id, null)],
                            canDownload = isDownloadable(item),
                            isSeriesBatch = isSeriesWithSeasons,
                            onDownload = {
                                val existingMovieDl = dlMap[downloadIdFor(item.id, null)]
                                when {
                                    isSeriesWithSeasons -> {
                                        batchSheetSeason = activeSeasonObj?.n ?: item.seasons.first().n
                                    }
                                    existingMovieDl != null -> dlDetailsId = existingMovieDl.record.id
                                    else -> optionsTarget = DownloadTarget(item)
                                }
                            },
                            onToggleMyList = { container.toggleMyList(item) },
                        )
                    }

                    item(key = "synopsis", contentType = "synopsis") { Synopsis(item) }

                    // 1. Seasons & Episodes (Series)
                    val seasons = item.seasons
                    if (seasons.isNotEmpty()) {
                        item(key = "seasons", contentType = "seasons") {
                            Column(Modifier.padding(top = 18.dp, bottom = 6.dp)) {
                                SeasonTabs(seasons, selectedSeasonIndex) { season = it }
                            }
                        }
                        val seasonObj = seasons[selectedSeasonIndex]
                        val activeEpPair = resolveActivePlayingEpisode(playerState.episodeId)
                        val hasActiveBeyondLimit = seasonObj.episodes.indexOfFirst { ep ->
                            playerState.item?.id == item.id &&
                                (playerState.episodeId == ep.id || activeEpPair == (seasonObj.n to ep.n))
                        } >= INITIAL_EPISODE_LIMIT
                        val shownEpisodes = if (expandAllEpisodes || hasActiveBeyondLimit || seasonObj.episodes.size <= INITIAL_EPISODE_LIMIT) {
                            seasonObj.episodes
                        } else {
                            seasonObj.episodes.take(INITIAL_EPISODE_LIMIT)
                        }

                        items(shownEpisodes, key = { "ep-${seasonObj.n}-${it.id}" }, contentType = { "episode" }) { ep ->
                            val epEntry = resumeEntries.firstOrNull { it.itemId == item.id && it.episodeId == ep.id }
                            val epProgress = epEntry?.takeIf { !it.finished }?.fraction
                            val epWatched = epEntry?.finished == true
                            val epInfo = dlMap[downloadIdFor(item.id, ep.id)]
                            val isPlayingEp = playerState.active &&
                                (playerState.item?.id == item.id || playerState.item?.id == id) &&
                                (playerState.episodeId == ep.id || activeEpPair == (seasonObj.n to ep.n))

                            EpisodeRow(
                                episode = ep,
                                progress = epProgress,
                                downloaded = epInfo?.record?.status == DownloadStatus.COMPLETED,
                                watched = epWatched,
                                playing = isPlayingEp,
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
                                startInlinePlayback(item, seasonObj.n, ep.n)
                            }
                        }

                        if (shownEpisodes.size < seasonObj.episodes.size) {
                            item(key = "expand-episodes-${seasonObj.n}", contentType = "expand-episodes") {
                                val expandShape = RoundedCornerShape(CinetorTokens.Shape.pill)
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "Show all ${seasonObj.episodes.size} episodes",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Cinetor.tokens.accent,
                                        modifier = Modifier
                                            .clip(expandShape)
                                            .glass(expandShape, GlassLevel.SURFACE)
                                            .clickable { expandAllEpisodes = true }
                                            .padding(horizontal = 18.dp, vertical = 9.dp),
                                    )
                                }
                            }
                        }
                    }

                    // 2. Clickable Cast Section
                    val castPeople = castPeopleOf(item)
                    if (castPeople.isNotEmpty()) {
                        item(key = "cast", contentType = "cast") {
                            CastSection(
                                people = castPeople,
                                onSelectActor = { member -> vm.openCast(Person(name = member.name, role = member.role)) },
                            )
                        }
                    }

                    // 3. For You (More Like This) Section
                    item(key = "for-you", contentType = "for-you") {
                        ForYouSection(ui, onOpenItem)
                    }
                }
            }
        }
    }

    // Cast Filmography ModalBottomSheet
    val activePerson = castState.selectedCast
    if (activePerson != null) {
        CastWorksSheet(
            person = activePerson,
            loading = castState.castWorksLoading,
            works = castState.castWorks,
            onDismiss = vm::closeCast,
            onOpenWork = { work ->
                vm.closeCast()
                onOpenItem(work, null)
            },
        )
    }

    // Multi-Season & Multi-Episode Batch Series Download Sheet
    val targetBatchSeason = batchSheetSeason
    if (targetBatchSeason != null && readyItem != null && readyItem.seasons.isNotEmpty()) {
        BatchSeriesDownloadSheet(
            item = readyItem,
            initialSeasonNumber = targetBatchSeason,
            module = container.downloads,
            downloadsById = dlMap,
            preselectedEpisodeIds = null,
            onDismiss = { batchSheetSeason = null },
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

private fun resolveActivePlayingEpisode(episodeId: String?): Pair<Int, Int>? {
    if (episodeId.isNullOrBlank()) return null
    val match = Regex("""^s0*(\d+)e0*(\d+)$""", RegexOption.IGNORE_CASE).find(episodeId) ?: return null
    val s = match.groupValues[1].toIntOrNull() ?: return null
    val e = match.groupValues[2].toIntOrNull() ?: return null
    return s to e
}

/**
 * Top 16:9 media slot: renders the artwork + optional muted backdrop preview while browsing,
 * and transforms IN PLACE into [InlinePlayerSlot] when [isInlinePlaying] is true!
 */
private val MEDIA_FRAME_INSET = 12.dp
private val MediaFrameShape = RoundedCornerShape(18.dp)

@Composable
private fun TopMediaSlot(
    item: Item,
    topInset: Dp,
    isInlinePlaying: Boolean,
    enableMutedPreview: Boolean,
    previewStopEpoch: Int,
    gesturePrefs: GesturePrefs,
    container: AppContainer,
    onTapPlayInline: () -> Unit,
    /** The title's colour (sampled artwork); tints the frame's soft glow. */
    glow: Color? = null,
) {
    val tokens = Cinetor.tokens
    val dominant = remember(item.dominantColor) { hexColor(item.dominantColor) }
    var previewShowingVideo by remember(item.id) { mutableStateOf(false) }
    val glowColor = glow ?: dominant
    val lowTier = Cinetor.tier == PerformanceTier.LOW

    // Banner and inline player share one frame (inset, rounded, glowing), so pressing Play swaps
    // the artwork for video in exactly the same shape instead of jumping to a black full-bleed box.
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = topInset)
            .padding(horizontal = MEDIA_FRAME_INSET, vertical = 8.dp)
            .aspectRatio(16f / 9f)
            .then(
                if (lowTier) Modifier
                else Modifier.shadow(20.dp, MediaFrameShape, ambientColor = glowColor, spotColor = glowColor.copy(alpha = 0.6f)),
            )
            .clip(MediaFrameShape)
            .background(if (isInlinePlaying) Color.Black else dominant)
            .border(1.dp, Color.White.copy(alpha = 0.10f), MediaFrameShape),
    ) {
        if (isInlinePlaying) {
            InlinePlayerSlot(
                container.playback,
                container.subtitles,
                gesturePrefs,
                Modifier.fillMaxSize(),
            )
        } else {
            val artPath = item.backdrop.ifBlank { item.poster }
            CinetorArtwork(
                seedKey = item.id,
                path = artPath,
                width = 400.dp,
                height = 225.dp,
                fallbackTitle = item.title,
                large = true,
                modifier = Modifier.fillMaxSize(),
            )
            if (enableMutedPreview) {
                DetailsInlinePreview(
                    item = item,
                    stopEpoch = previewStopEpoch,
                    container = container,
                    onShowingChanged = { previewShowingVideo = it },
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.62f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.42f),
                        ),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Play inline",
                        onClick = onTapPlayInline,
                    ),
            ) {
                if (!previewShowingVideo) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.48f))
                            .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Compact foreground poster + title + Shiopa metadata pills directly under the 16:9 media slot. */
@Composable
private fun DetailsSummaryHeader(
    item: Item,
    origin: String,
    extraSources: List<Source>,
) {
    val tokens = Cinetor.tokens
    val dominant = remember(item.dominantColor) { hexColor(item.dominantColor) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .sharedPoster(origin.ifEmpty { null })
                .size(PosterThumbW, PosterThumbH)
                .clip(RoundedCornerShape(CinetorTokens.Shape.sm))
                .background(dominant)
                .border(1.dp, tokens.glassStroke, RoundedCornerShape(CinetorTokens.Shape.sm)),
        ) {
            CinetorArtwork(
                seedKey = item.id,
                path = item.poster,
                width = PosterThumbW,
                height = PosterThumbH,
                fallbackTitle = item.title,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (item.logo != null) {
                AsyncImage(
                    model = rememberSizedImage(item.logo, 180.dp, 52.dp),
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.width(180.dp).heightIn(max = 52.dp),
                )
            } else {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = tokens.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MetaChips(item, extraSources)
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

    val currentStopEpoch by rememberUpdatedState(stopEpoch)

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
        animationSpec = tween(durationMillis = 320),
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
                        .padding(top = 12.dp, end = 12.dp)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaChips(item: Item, extraSources: List<Source>) {
    val tokens = Cinetor.tokens
    val fourK = remember(item, extraSources) { hasUltraHdSource(item, extraSources) }
    val dubs = remember(item.title, item.altTitle, item.genres, item.dubs) { extractDubBadges(item) }
    val cleanGenres = remember(item.genres) { displayGenres(item) }
    val pillShape = RoundedCornerShape(6.dp)

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
                        tint = tokens.accent,
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
 * Strictly 3 action buttons (`Play`, `Download`, `My List`).
 * Play displays ONLY a crisp `8.dp` green circle (`#22C55E`) when ready — zero "Ready" / "Warming" text.
 */
@Composable
private fun DetailsActions(
    item: Item,
    readiness: Readiness?,
    inMyList: Boolean,
    resumable: ResumeEntry?,
    isInlinePlaying: Boolean,
    nextInlineEpisode: Pair<Int, Episode>?,
    playFocusRequester: FocusRequester,
    onPlay: () -> Unit,
    downloadInfo: DownloadInfo?,
    canDownload: Boolean,
    isSeriesBatch: Boolean,
    onDownload: () -> Unit,
    onToggleMyList: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val pillShape = RoundedCornerShape(CinetorTokens.Shape.pill)
    val firstSeason = item.seasons.firstOrNull()
    val firstEp = firstSeason?.episodes?.firstOrNull()

    val playLabel = when {
        isInlinePlaying && nextInlineEpisode != null -> "Next S${nextInlineEpisode.first}:E${nextInlineEpisode.second.n}"
        isInlinePlaying -> "Playing"
        resumable != null && resumable.episodeLabel != null -> "Resume ${resumable.episodeLabel}"
        resumable != null -> "Resume"
        item.type == ItemType.SERIES && firstSeason != null && firstEp != null -> "Play S${firstSeason.n}:E${firstEp.n}"
        else -> "Play"
    }
    val playInteraction = remember { MutableInteractionSource() }
    val playFocused by playInteraction.collectIsFocusedAsState()
    val myListInteraction = remember { MutableInteractionSource() }
    val myListFocused by myListInteraction.collectIsFocusedAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1. Play (with 8.dp green dot ONLY when ready — zero text label)
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
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (isInlinePlaying && nextInlineEpisode != null) Icons.Rounded.SkipNext else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = tokens.onAccent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    playLabel,
                    color = tokens.onAccent,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ReadyDot(readiness)
            }

            // 2. Download (opens BatchSeriesDownloadSheet for SERIES, DownloadOptionsSheet for MOVIE)
            if (canDownload) {
                DownloadButton(
                    info = if (isSeriesBatch) null else downloadInfo,
                    onClick = onDownload,
                    iconOnly = true,
                )
            }

            // 3. My List
            Box(
                Modifier
                    .size(50.dp)
                    .liquidGlassButton(CircleShape, active = inMyList, interaction = myListInteraction)
                    .then(if (myListFocused) Modifier.border(2.dp, tokens.focusRing, CircleShape) else Modifier)
                    .clickable(interactionSource = myListInteraction, indication = null, onClick = onToggleMyList)
                    .semantics { contentDescription = if (inMyList) "Remove from My List" else "Add to My List" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (inMyList) Icons.Rounded.Check else Icons.Rounded.Add,
                    contentDescription = null,
                    tint = if (inMyList) tokens.accent else tokens.textPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        if (resumable != null && !isInlinePlaying) {
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
    }
}

/**
 * Play button readiness indicator: ONLY an `8.dp` circle (`#22C55E` green when READY,
 * subtle composite-alpha pulse while warming) — NEVER renders "Ready" or "Warming" text.
 */
@Composable
private fun ReadyDot(readiness: Readiness?) {
    if (readiness == null || readiness == Readiness.ERROR) return
    val tier = Cinetor.tier
    val reduceMotion = Cinetor.prefs.reduceMotion
    val isReady = readiness.sourceReady()
    val color = if (isReady) ReadyGreen else Cinetor.tokens.onAccent.copy(alpha = 0.85f)
    val shouldAnimatePulse = !isReady && tier != PerformanceTier.LOW && !reduceMotion
    val pulse = if (!shouldAnimatePulse) {
        null
    } else {
        rememberInfiniteTransition(label = "readyDotPulse").animateFloat(
            0.35f,
            0.95f,
            infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "readyDotAlpha",
        )
    }
    Spacer(Modifier.width(7.dp))
    Box(
        Modifier
            .size(8.dp)
            .graphicsLayer { alpha = pulse?.value ?: 1f }
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun Synopsis(item: Item) {
    val tokens = Cinetor.tokens
    if (item.synopsis.isBlank()) return
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
}

/** Cast with photos when the source has them, else plain names (same order, no duplicates). */
internal fun castPeopleOf(item: Item): List<CastMember> {
    val members = item.castMembers.filter { it.name.isNotBlank() }
    val base = members.ifEmpty { item.cast.filter { it.isNotBlank() }.map { CastMember(name = it.trim()) } }
    return base.distinctBy { it.name.trim().lowercase() }
}

private const val CAST_SHOWN = 6

/**
 * Cinetor Beta cast grid: three people per row (six on tablets/TV), each a 64dp round photo with
 * the name and character underneath. Six show by default; "See all (N)" expands in place.
 * Tapping a person opens [CastWorksSheet] with their filmography.
 */
@Composable
private fun CastSection(
    people: List<CastMember>,
    onSelectActor: (CastMember) -> Unit,
) {
    val tokens = Cinetor.tokens
    var showAll by rememberSaveable(people.size) { mutableStateOf(false) }
    val perRow = if (LocalConfiguration.current.screenWidthDp >= 600) 6 else 3
    val shown = if (showAll) people else people.take(CAST_SHOWN)
    Column(Modifier.padding(top = 20.dp, start = 16.dp, end = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
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
                "Cast",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tokens.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (people.size > CAST_SHOWN) {
                Text(
                    if (showAll) "Show less" else "See all (${people.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(CinetorTokens.Shape.pill))
                        .clickable { showAll = !showAll }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        shown.chunked(perRow).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { member ->
                    CastPerson(member, Modifier.weight(1f)) { onSelectActor(member) }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CastPerson(member: CastMember, modifier: Modifier, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .clip(shape)
            .then(if (focused) Modifier.border(2.dp, tokens.focusRing, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "Cast ${member.name}" }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(tokens.surfaceElevated)
                .border(1.dp, tokens.glassStroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            var failed by remember(member.photo) { mutableStateOf(false) }
            val photo = member.photo
            if (photo != null && !failed) {
                AsyncImage(
                    model = rememberSizedImage(photo, 64.dp, 64.dp),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = tokens.textSecondary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            member.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = tokens.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 15.sp,
        )
        member.role?.let { role ->
            Text(
                role,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Liquid Glass ModalBottomSheet displaying all works of [person] as titled [PosterCard]s. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CastWorksSheet(
    person: Person,
    loading: Boolean,
    works: List<Item>,
    onDismiss: () -> Unit,
    onOpenWork: (Item) -> Unit,
) {
    val tokens = Cinetor.tokens
    val tint = Cinetor.tint

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tokens.surfaceElevated,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .heightIn(min = 240.dp, max = 560.dp)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        person.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (loading) "Loading filmography…" else "${works.size} ${if (works.size == 1) "title" else "titles"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.textSecondary,
                    )
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .glass(CircleShape, GlassLevel.SURFACE)
                        .clickable(onClick = onDismiss)
                        .semantics { contentDescription = "Close cast works" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = tokens.textPrimary, modifier = Modifier.size(18.dp))
                }
            }

            if (loading) {
                ShimmerHost {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        repeat(3) {
                            SkeletonBox(
                                Modifier.weight(1f).aspectRatio(2f / 3f),
                                RoundedCornerShape(CinetorTokens.Shape.card),
                            )
                        }
                    }
                }
            } else if (works.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No titles available for ${person.name} right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textSecondary,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(works.chunked(3), key = { row -> "cast-work-row-${row.first().id}" }) { rowItems ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowItems.forEach { work ->
                                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                                    PosterCard(
                                        item = work,
                                        onClick = { onOpenWork(work) },
                                        onActive = { tint.setSource(it) },
                                        width = 104.dp,
                                    )
                                }
                            }
                            repeat(3 - rowItems.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Always-visible "For You" recommendations row (titled PosterCards). */
@Composable
private fun ForYouSection(ui: DetailsUi, onOpenItem: (Item, String?) -> Unit) {
    val tokens = Cinetor.tokens
    val tint = Cinetor.tint
    if (!ui.moreLoading && ui.moreLikeThis.isEmpty()) return
    Column(Modifier.padding(top = 22.dp)) {
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
                "For You",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tokens.textPrimary,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (ui.moreLoading) {
            ShimmerHost {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) {
                        SkeletonBox(Modifier.size(104.dp, 156.dp), RoundedCornerShape(CinetorTokens.Shape.card))
                    }
                }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ui.moreLikeThis, key = { it.id }, contentType = { "poster" }) { m ->
                    val key = posterKey("fy-${ui.item.id}", m.id)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBox(Modifier.weight(1f).height(50.dp), RoundedCornerShape(CinetorTokens.Shape.pill))
                SkeletonBox(Modifier.width(104.dp).height(50.dp), RoundedCornerShape(CinetorTokens.Shape.pill))
                SkeletonBox(Modifier.width(96.dp).height(50.dp), RoundedCornerShape(CinetorTokens.Shape.pill))
            }
            SkeletonText(lines = 3)
            Spacer(Modifier.height(8.dp))
            SkeletonEpisodeList(Modifier.padding(horizontal = 0.dp), count = 4)
        }
    }
}
