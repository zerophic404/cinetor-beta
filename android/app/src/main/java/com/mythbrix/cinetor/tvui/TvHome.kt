package com.mythbrix.cinetor.tvui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Season
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.player.CinetorPlayer
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.core.player.Readiness
import com.mythbrix.cinetor.core.player.SourceResolver
import com.mythbrix.cinetor.core.ui.LocalResumeEntries
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.SkeletonPosterRow
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.design.hexColor
import com.mythbrix.cinetor.feature.details.moreLikeThis
import com.mythbrix.cinetor.feature.downloads.BatchSeriesDownloadSheet
import com.mythbrix.cinetor.feature.downloads.DownloadOptionsSheet
import com.mythbrix.cinetor.feature.downloads.DownloadTarget
import com.mythbrix.cinetor.feature.home.SpaceUi
import com.mythbrix.cinetor.feature.home.SpaceUiState
import com.mythbrix.cinetor.feature.home.SpaceViewModel
import com.mythbrix.cinetor.feature.home.rememberTintFeeder
import com.mythbrix.cinetor.feature.player.AspectMode
import com.mythbrix.cinetor.feature.player.HostMode
import com.mythbrix.cinetor.feature.player.VideoSurface
import com.mythbrix.cinetor.feature.player.extractYouTubeVideoId
import com.mythbrix.cinetor.feature.player.subs.SubtitleOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Strictly 3 action buttons on TV Title Details (`Share`, `Next 3 episodes`, `Batch download` removed). */
val TV_DETAILS_ACTIONS: List<String> = listOf("Play", "Download", "My List")

/** FocusMemory key of a space's TV home. */
fun tvScreenKey(space: Space): String = "space-${space.name}"

/**
 * Decides whether a row-state emission should run full [FocusMemory] scroll/focus restoration.
 * In-place endless row pagination (`PagedCatalog` appending items with identical row IDs while a
 * card in the grid already holds focus) skips scroll-reset so browsing never jumps.
 */
fun tvShouldRestoreFocusOnRowsChange(
    previousRowIds: String?,
    currentRowIds: String,
    hasActiveRowFocus: Boolean,
    previousRefocus: Int,
    currentRefocus: Int,
): Boolean {
    if (previousRowIds == null) return true
    if (previousRefocus != currentRefocus) return true
    if (previousRowIds != currentRowIds) return true
    return !hasActiveRowFocus
}

private val idleInlineUi = MutableStateFlow(PlayerUiState())

private fun ensurePlayableTvItem(item: Item): Item {
    if (item.type != ItemType.SERIES || item.seasons.isNotEmpty()) return item
    val fallbackEp = Episode(
        id = "s1e1",
        n = 1,
        title = "Episode 1",
        durationSec = item.durationSec ?: 2400,
        still = item.backdrop.ifBlank { item.poster },
        sources = emptyList(),
    )
    return item.copy(seasons = listOf(Season(n = 1, title = "Season 1", episodes = listOf(fallbackEp))))
}

/**
 * TV home for a video space (`Home`, `Anime`, `Drama`, plus video-style isolated spaces):
 * widescreen showcase hero, Movira category pills, unnumbered titled poster rows, 10-foot
 * [TvDetailsScreen] sub-stack, and deterministic [FocusMemory] restoration.
 */
@Composable
fun TvHomeScreen(
    space: Space,
    container: AppContainer,
    memory: FocusMemory,
    onOpenItem: (Item, String?) -> Unit,
) {
    val vm: SpaceViewModel = viewModel(
        key = "space-${space.name}",
        factory = viewModelFactory { initializer { SpaceViewModel(space, container.catalog) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val myList by container.myList.collectAsStateWithLifecycle()
    val readiness by container.preloader.readiness.collectAsStateWithLifecycle()

    val detailsStack = remember(space) { mutableStateListOf<String>() }
    val navPickTick = LocalTvNavPickTick.current
    var seenNavTick by remember { mutableIntStateOf(navPickTick) }
    LaunchedEffect(navPickTick) {
        if (navPickTick != seenNavTick) {
            seenNavTick = navPickTick
            detailsStack.clear()
        }
    }

    val openTvCard: (Item, String?) -> Unit = { item, key ->
        container.peek.put(item)
        if (item.space == Space.COMICS) {
            onOpenItem(item, key)
        } else {
            detailsStack.add(item.id)
        }
    }

    val activeDetailsId = detailsStack.lastOrNull()
    if (activeDetailsId != null) {
        BackHandler {
            if (detailsStack.isNotEmpty()) {
                detailsStack.removeAt(detailsStack.lastIndex)
            }
        }
        TvDetailsScreen(
            itemId = activeDetailsId,
            container = container,
            onOpenItem = { nextItem ->
                container.peek.put(nextItem)
                if (nextItem.space == Space.COMICS) {
                    onOpenItem(nextItem, null)
                } else if (nextItem.id != activeDetailsId) {
                    detailsStack.add(nextItem.id)
                }
            },
        )
        return
    }

    when (val s = state) {
        SpaceUiState.Loading -> ShimmerHost {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(248.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Cinetor.tokens.skeletonBase),
                )
                SkeletonPosterRow()
                SkeletonPosterRow()
            }
        }
        is SpaceUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .glass(RoundedCornerShape(20.dp), GlassLevel.SURFACE)
                    .padding(horizontal = 28.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = s.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Cinetor.tokens.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                TvButton("Retry", null, vm::load, primary = true, onDarkScrim = false)
            }
        }
        is SpaceUiState.Ready -> TvHomeContent(
            screen = tvScreenKey(space),
            ui = s.ui,
            myList = myList,
            readiness = readiness,
            memory = memory,
            onOpen = openTvCard,
            onPlay = { container.playback.play(it) },
            onToggleMyList = { container.toggleMyList(it) },
            onSelectTab = vm::selectTab,
            onLoadMore = vm::loadMore,
        )
    }
}

/**
 * 10-foot Android TV Title Details screen:
 * - Warms stream immediately at `t = 0ms` during shimmer load so playback starts fast.
 * - Strictly 3 action buttons: `Play` (green `#22C55E` dot ONLY when ready, no `"Ready"` word;
 *   plays inline inside the top 16:9 stage and expands seamlessly to `TvPlayerScreen` on D-pad
 *   fullscreen), `Download`, and `My List`.
 * - Always shows `Seasons & Episodes` (for series), clickable `Cast` → D-pad navigable
 *   `Cast Filmography` overlay (`container.catalog.castWorks`), and `For You` below.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvDetailsScreen(
    itemId: String,
    container: AppContainer,
    onOpenItem: (Item) -> Unit,
) {
    val tokens = Cinetor.tokens
    val tint = Cinetor.tint
    val myList by container.myList.collectAsStateWithLifecycle()
    val readinessMap by container.preloader.readiness.collectAsStateWithLifecycle()
    val downloadsList by container.downloads.engine.downloads.collectAsStateWithLifecycle()
    val downloadsById = remember(downloadsList) { downloadsList.associateBy { it.record.id } }
    val subState by container.subtitles.state.collectAsStateWithLifecycle()
    val resumeEntries = LocalResumeEntries.current

    val peekItem = remember(itemId) { container.peek.get(itemId) }
    var resolvedItem by remember(itemId) { mutableStateOf<Item?>(null) }
    var loading by remember(itemId) { mutableStateOf(true) }
    var moreItems by remember(itemId) { mutableStateOf<List<Item>>(emptyList()) }
    var selectedSeasonIndex by remember(itemId) { mutableIntStateOf(0) }

    var inlinePlayer by remember(itemId) { mutableStateOf<CinetorPlayer?>(null) }
    var inlineEpisodeId by remember(itemId) { mutableStateOf<String?>(null) }
    var inlineActive by remember(itemId) { mutableStateOf(false) }

    var showBatchSheet by remember(itemId) { mutableStateOf(false) }
    var singleDlTarget by remember(itemId) { mutableStateOf<DownloadTarget?>(null) }
    var selectedCastPerson by remember(itemId) { mutableStateOf<String?>(null) }

    val playBtnFocus = remember(itemId) { FocusRequester() }
    val refocus = LocalTvRefocus.current

    // Close inline playback cleanly if full-screen player isn't owning it when leaving TvDetails.
    DisposableEffect(itemId) {
        onDispose {
            if (container.playback.state.value.mode == HostMode.HIDDEN) {
                if (inlineActive) {
                    container.preloader.release(itemId)
                } else {
                    container.preloader.cancel(itemId)
                }
            }
        }
    }

    // Immediate T=0ms stream pre-warming right during shimmer load + resilient item resolution.
    LaunchedEffect(itemId) {
        loading = true
        val resumeEp = resumeEntries.firstOrNull { it.itemId == itemId && !it.finished }?.episodeId
        peekItem?.let { summary ->
            val warmSummary = ensurePlayableTvItem(summary)
            val epGuess = resumeEp ?: warmSummary.seasons.firstOrNull()?.episodes?.firstOrNull()?.id
            container.preloader.warm(warmSummary, epGuess)
        }
        val full = withContext(Dispatchers.IO) {
            runCatching { container.catalog.item(itemId) }.getOrNull()
                ?: peekItem?.let(::ensurePlayableTvItem)
                ?: runCatching {
                    val words = itemId.substringAfter(':').replace('-', ' ').trim()
                    if (words.isNotEmpty()) container.remoteSearch?.search(words)?.firstOrNull() else null
                }.getOrNull()?.let(::ensurePlayableTvItem)
        }
        if (full != null) {
            val playable = ensurePlayableTvItem(full)
            resolvedItem = playable
            loading = false
            val targetEp = resumeEp ?: playable.seasons.firstOrNull()?.episodes?.firstOrNull()?.id
            container.preloader.warm(playable, targetEp)
            val candidates = withContext(Dispatchers.IO) {
                val byGenre = playable.genres.firstOrNull()
                    ?.let { g -> runCatching { container.catalog.byGenre(playable.space, g) }.getOrNull() }
                    .orEmpty()
                if (byGenre.size >= 8) byGenre else byGenre + runCatching { container.catalog.allItems(playable.space) }.getOrDefault(emptyList())
            }
            moreItems = moreLikeThis(playable, candidates, limit = 12)
        } else {
            loading = false
        }
    }

    val activeItem = resolvedItem ?: peekItem?.let(::ensurePlayableTvItem)
    LaunchedEffect(activeItem?.dominantColor) {
        activeItem?.let { tint.setSource(hexColor(it.dominantColor)) }
    }

    // If full-screen TvPlayerScreen took over and closed, sync inline state and restore Play focus.
    val hostScreen by container.playback.state.collectAsStateWithLifecycle()
    LaunchedEffect(hostScreen.mode) {
        if (hostScreen.mode == HostMode.FULL) {
            inlineActive = false
        }
    }
    LaunchedEffect(activeItem?.id, loading, refocus) {
        if (activeItem != null && selectedCastPerson == null) {
            withFrameNanos { }
            runCatching { playBtnFocus.requestFocus() }
        }
    }

    if (activeItem == null && loading) {
        ShimmerHost {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(236.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(tokens.skeletonBase),
                )
                SkeletonPosterRow()
            }
        }
        return
    }

    if (activeItem == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Unable to load title details.", color = tokens.textSecondary, style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    val seasons = activeItem.seasons
    val clampedSeasonIdx = selectedSeasonIndex.coerceIn(0, (seasons.size - 1).coerceAtLeast(0))
    val currentSeason = seasons.getOrNull(clampedSeasonIdx)
    val defaultEpisodeId = remember(activeItem.id, seasons, resumeEntries) {
        resumeEntries.firstOrNull { it.itemId == activeItem.id && !it.finished }?.episodeId
            ?: seasons.firstOrNull()?.episodes?.firstOrNull()?.id
    }
    val playReady = tvShowReadyDot(readinessMap[activeItem.id], activeItem.sources.isNotEmpty())
    val inMyList = activeItem.id in myList
    val pills = remember(activeItem) { tvHeroMetaPills(activeItem) }
    val genreLine = remember(activeItem.genres) { tvHeroGenreLine(activeItem) }

    fun startInline(episodeId: String?) {
        val targetEp = episodeId ?: defaultEpisodeId
        container.music.yieldToVideo()
        val p = container.preloader.open(activeItem, targetEp)
        p?.play()
        inlinePlayer = p
        inlineEpisodeId = targetEp
        inlineActive = p != null
    }

    fun expandToTvPlayer() {
        val ep = inlineEpisodeId ?: defaultEpisodeId
        inlineActive = false
        container.playback.play(activeItem, ep)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(key = "tv-details-top") {
                val stageShape = RoundedCornerShape(20.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(stageShape)
                        .background(tokens.surface.copy(alpha = 0.82f))
                        .glass(stageShape, GlassLevel.SURFACE)
                        .padding(22.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = activeItem.title,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = 28.sp,
                                lineHeight = 32.sp,
                                fontWeight = FontWeight.Black,
                            ),
                            color = tokens.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (pills.isNotEmpty() || genreLine.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                pills.forEach { pill -> TvHeroMetaTag(pill) }
                                if (genreLine.isNotEmpty()) {
                                    Text(
                                        text = genreLine,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = tokens.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (activeItem.synopsis.isNotBlank()) {
                            Text(
                                text = activeItem.synopsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = tokens.textSecondary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // Strictly 3 action buttons: Play, Download, My List.
                        Row(
                            Modifier
                                .focusProperties { enter = { playBtnFocus } }
                                .focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TvButton(
                                label = TV_DETAILS_ACTIONS[0],
                                icon = Icons.Rounded.PlayArrow,
                                onClick = {
                                    if (inlineActive) expandToTvPlayer() else startInline(defaultEpisodeId)
                                },
                                modifier = Modifier.focusRequester(playBtnFocus),
                                primary = true,
                                onDarkScrim = false,
                                readyDot = playReady,
                            )
                            TvButton(
                                label = TV_DETAILS_ACTIONS[1],
                                icon = Icons.Rounded.Download,
                                onClick = {
                                    if (activeItem.type == ItemType.SERIES && seasons.isNotEmpty()) {
                                        showBatchSheet = true
                                    } else {
                                        singleDlTarget = DownloadTarget(activeItem)
                                    }
                                },
                                onDarkScrim = false,
                            )
                            TvButton(
                                label = TV_DETAILS_ACTIONS[2],
                                icon = if (inMyList) Icons.Rounded.Check else Icons.Rounded.Add,
                                onClick = { container.toggleMyList(activeItem) },
                                onDarkScrim = false,
                            )
                        }
                    }

                    // Top 16:9 backdrop / in-place inline video player slot.
                    val mediaShape = RoundedCornerShape(16.dp)
                    Box(
                        Modifier
                            .width(368.dp)
                            .aspectRatio(16f / 9f)
                            .clip(mediaShape)
                            .background(Color.Black)
                            .border(1.dp, tokens.glassStroke, mediaShape),
                    ) {
                        val attachedInline = inlinePlayer
                        if (inlineActive && attachedInline != null) {
                            val inlineUi by attachedInline.state.collectAsStateWithLifecycle()
                            val ytId = inlineUi.youtubeVideoId
                            val posNow: () -> Long = {
                                if (ytId != null) inlineUi.positionMs
                                else attachedInline.media3Player()?.currentPosition ?: inlineUi.positionMs
                            }
                            VideoSurface(
                                player = attachedInline.media3Player(),
                                aspect = AspectMode.FIT,
                                modifier = Modifier.fillMaxSize(),
                                configure = { v -> enforceTvTransparentSubtitleFallback(v) },
                                youtubeVideoId = ytId,
                                youtubeSessionKey = inlineUi.key,
                                cinetorPlayer = attachedInline,
                                startPositionMs = inlineUi.positionMs,
                                playWhenReady = inlineUi.playWhenReady,
                            )
                            SubtitleOverlay(
                                subs = subState.sideLoaded,
                                offsetMs = subState.offsetMs,
                                style = tvEffectiveSubtitleStyle(subState.style),
                                positionMs = posNow,
                            )
                            if (inlineUi.buffering && inlineUi.playWhenReady) {
                                CircularProgressIndicator(
                                    color = tokens.accent,
                                    modifier = Modifier.align(Alignment.Center).size(36.dp),
                                )
                            }
                            Row(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TvButton(
                                    label = if (inlineUi.playWhenReady) "Pause" else "Play",
                                    icon = if (inlineUi.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    onClick = {
                                        if (inlineUi.playWhenReady) attachedInline.pause() else attachedInline.play()
                                    },
                                    onDarkScrim = true,
                                )
                                TvButton(
                                    label = "Fullscreen",
                                    icon = Icons.Rounded.Fullscreen,
                                    onClick = ::expandToTvPlayer,
                                    primary = true,
                                    onDarkScrim = true,
                                )
                            }
                        } else {
                            val art = activeItem.backdrop.ifBlank { activeItem.poster }
                            if (art.isBlank()) {
                                TvGeneratedArt(activeItem.id, Modifier.fillMaxSize())
                            } else {
                                AsyncImage(
                                    model = rememberSizedImage(art, 368.dp, 207.dp, large = true),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(
                                        0.45f to Color.Transparent,
                                        1f to Color(0xCC09090B),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }

            // 1. Seasons & Episodes (for series)
            if (seasons.isNotEmpty() && currentSeason != null) {
                item(key = "tv-details-episodes") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvSectionHeader("Seasons & Episodes")
                        if (seasons.size > 1) {
                            TvTabs(
                                titles = seasons.map { it.title ?: "Season ${it.n}" },
                                selected = clampedSeasonIdx,
                                onSelect = { selectedSeasonIndex = it },
                            )
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier.focusGroup(),
                        ) {
                            items(currentSeason.episodes, key = { it.id }) { ep ->
                                val epCardItem = activeItem.copy(
                                    id = "${activeItem.id}#${ep.id}",
                                    title = "${ep.n}. ${ep.title}",
                                    backdrop = ep.still.ifBlank { activeItem.backdrop },
                                    year = 0,
                                    genres = if (inlineActive && inlineEpisodeId == ep.id) listOf("Now Playing") else emptyList(),
                                )
                                TvCard(
                                    item = epCardItem,
                                    onClick = { startInline(ep.id) },
                                    onFocused = {},
                                    width = 236.dp,
                                    landscape = true,
                                )
                            }
                        }
                    }
                }
            }

            // 2. Clickable / Focusable Cast → Cast Filmography
            if (activeItem.cast.isNotEmpty()) {
                item(key = "tv-details-cast") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvSectionHeader("Cast")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.focusGroup(),
                        ) {
                            items(activeItem.cast.distinct(), key = { "cast-$it" }) { personName ->
                                TvCastChip(
                                    name = personName,
                                    onClick = { selectedCastPerson = personName },
                                )
                            }
                        }
                    }
                }
            }

            // 3. For You (More like this)
            if (moreItems.isNotEmpty()) {
                item(key = "tv-details-foryou") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvSectionHeader("For You")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier.focusGroup(),
                        ) {
                            items(moreItems, key = { it.id }) { related ->
                                TvCard(
                                    item = related,
                                    onClick = { onOpenItem(related) },
                                    onFocused = {},
                                    width = 148.dp,
                                )
                            }
                        }
                    }
                }
            }
        }

        // D-pad navigable Cast Filmography overlay
        selectedCastPerson?.let { personName ->
            BackHandler { selectedCastPerson = null }
            TvCastFilmographyOverlay(
                personName = personName,
                container = container,
                onPickItem = { work ->
                    selectedCastPerson = null
                    onOpenItem(work)
                },
                onClose = { selectedCastPerson = null },
            )
        }
    }

    if (showBatchSheet) {
        BatchSeriesDownloadSheet(
            item = activeItem,
            initialSeasonNumber = currentSeason?.n ?: 1,
            module = container.downloads,
            downloadsById = downloadsById,
            onDismiss = { showBatchSheet = false },
            onQueued = { showBatchSheet = false },
            resolve = { it, _, ep ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        val attached = container.streams.attach(it, ep.id)
                        SourceResolver.episodes(attached).firstOrNull { e -> e.id == ep.id }?.sources.orEmpty()
                    }.getOrDefault(emptyList())
                }
            },
        )
    }

    singleDlTarget?.let { target ->
        DownloadOptionsSheet(
            target = target,
            module = container.downloads,
            onDismiss = { singleDlTarget = null },
            onStarted = { singleDlTarget = null },
            resolve = { it, ep ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        val attached = container.streams.attach(it, ep?.id)
                        if (ep != null) {
                            SourceResolver.episodes(attached).firstOrNull { e -> e.id == ep.id }?.sources.orEmpty()
                        } else {
                            attached.sources
                        }
                    }.getOrDefault(emptyList())
                }
            },
        )
    }
}

@Composable
private fun TvSectionHeader(title: String) {
    val tokens = Cinetor.tokens
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tokens.accent),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = tokens.textPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TvCastChip(name: String, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    val prefs = Cinetor.prefs
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        if (focused && !prefs.reduceMotion) TV_FOCUS_SCALE else 1f,
        label = "tvCastScale",
    )
    val sound = rememberFocusSound()
    LaunchedEffect(focused) { if (focused) sound() }
    val isLight = tokens.bg.luminance() > 0.5f
    val spotlight = if (isLight) Color(0xFF141416) else Color.White
    val shape = RoundedCornerShape(50)
    val initials = remember(name) {
        name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
            .ifEmpty { "?" }
    }

    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (focused) tokens.accent else tokens.surfaceElevated)
            .border(if (focused) 2.5.dp else 1.dp, if (focused) spotlight else tokens.glassStroke, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "Cast $name" }
            .padding(start = 8.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (focused) Color.Black.copy(alpha = 0.28f) else tokens.accent.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = if (focused) tokens.onAccent else tokens.textPrimary,
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) tokens.onAccent else tokens.textPrimary,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun TvCastFilmographyOverlay(
    personName: String,
    container: AppContainer,
    onPickItem: (Item) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = Cinetor.tokens
    var works by remember(personName) { mutableStateOf<List<Item>>(emptyList()) }
    var loading by remember(personName) { mutableStateOf(true) }
    val closeFocus = remember { FocusRequester() }
    val firstWorkFocus = remember { FocusRequester() }

    LaunchedEffect(personName) {
        loading = true
        val resolved = withContext(Dispatchers.IO) {
            val fromCast = runCatching { container.catalog.castWorks(personName) }.getOrNull().orEmpty()
            val fromFallback = if (fromCast.isEmpty()) {
                runCatching { container.remoteSearch?.search(personName) }.getOrNull().orEmpty() +
                    runCatching { container.search.search(personName) }.getOrNull().orEmpty()
            } else {
                emptyList()
            }
            (fromCast + fromFallback).distinctBy { it.id }
        }
        works = resolved
        loading = false
        withFrameNanos { }
        runCatching {
            if (resolved.isNotEmpty()) firstWorkFocus.requestFocus() else closeFocus.requestFocus()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.80f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val panelShape = RoundedCornerShape(22.dp)
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 440.dp)
                .clip(panelShape)
                .background(tokens.surface.copy(alpha = 0.96f))
                .glass(panelShape, GlassLevel.SURFACE)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(24.dp)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = personName,
                        style = MaterialTheme.typography.headlineSmall,
                        color = tokens.textPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Movies & Series",
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.textSecondary,
                    )
                }
                TvButton(
                    label = "Close",
                    icon = null,
                    onClick = onClose,
                    modifier = Modifier.focusRequester(closeFocus),
                    onDarkScrim = false,
                )
            }

            when {
                loading -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = tokens.accent)
                }
                works.isEmpty() -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text("No playable titles found for $personName.", color = tokens.textSecondary)
                }
                else -> LazyRow(
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.focusGroup(),
                ) {
                    itemsIndexed(works, key = { _, it -> it.id }) { idx, work ->
                        TvCard(
                            item = work,
                            onClick = { onPickItem(work) },
                            onFocused = {},
                            modifier = if (idx == 0) Modifier.focusRequester(firstWorkFocus) else Modifier,
                            width = 144.dp,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvHomeContent(
    screen: String,
    ui: SpaceUi,
    myList: Set<String>,
    readiness: Map<String, Readiness>,
    memory: FocusMemory,
    onOpen: (Item, String?) -> Unit,
    onPlay: (Item) -> Unit,
    onToggleMyList: (Item) -> Unit,
    onSelectTab: (Int) -> Unit,
    onLoadMore: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val registry = remember { TvFocusRegistry() }
    val playRequester = remember { FocusRequester() }
    val feeder = rememberTintFeeder()
    val scope = rememberCoroutineScope()
    val resumeEntries = LocalResumeEntries.current

    val effectiveRows = remember(ui.rows, resumeEntries) {
        ui.rows
            .map { r -> r.copy(items = tvRowItems(r, resumeEntries)) }
            .filter { it.items.isNotEmpty() }
    }
    val rowSizes = remember(effectiveRows) { effectiveRows.map { it.items.size } }
    val latestRowSizes by rememberUpdatedState(rowSizes)

    var focused by remember { mutableStateOf<FocusPos?>(null) }
    var contentHasFocus by remember { mutableStateOf(false) }
    var restoringFocus by remember { mutableStateOf(true) }
    var lastRowIds by remember { mutableStateOf<String?>(null) }
    val refocus = LocalTvRefocus.current
    var lastRefocus by remember { mutableIntStateOf(refocus) }

    val firstRow = if (ui.showTabs) 2 else 1
    val heroVisible by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val currentRowIds = remember(effectiveRows) { effectiveRows.joinToString(",") { it.id } }

    LaunchedEffect(currentRowIds, refocus) {
        val shouldRestore = tvShouldRestoreFocusOnRowsChange(
            previousRowIds = lastRowIds,
            currentRowIds = currentRowIds,
            hasActiveRowFocus = focused != null && contentHasFocus,
            previousRefocus = lastRefocus,
            currentRefocus = refocus,
        )
        lastRowIds = currentRowIds
        lastRefocus = refocus
        if (!shouldRestore) return@LaunchedEffect

        restoringFocus = true
        try {
            withFrameNanos { }
            val pos = memory.resolve(screen, latestRowSizes)
            if (pos == null || pos.isHero) {
                focused = null
                listState.scrollToItem(0)
                withFrameNanos { }
                runCatching { playRequester.requestFocus() }
                delay(240)
                if (!contentHasFocus) runCatching { playRequester.requestFocus() }
                return@LaunchedEffect
            }
            listState.scrollToItem(firstRow + pos.row)
            withFrameNanos { }
            registry.rowStates[pos.row]?.let { rs ->
                if (rs.layoutInfo.visibleItemsInfo.none { it.index == pos.index }) {
                    rs.scrollToItem(pos.index)
                }
            }
            repeat(6) {
                withFrameNanos { }
                if (registry.request(pos)) {
                    focused = pos
                    delay(240)
                    if (!contentHasFocus) registry.request(pos)
                    return@LaunchedEffect
                }
                delay(16)
            }
        } finally {
            restoringFocus = false
        }
    }

    fun moveTo(pos: FocusPos) {
        scope.launch {
            if (!registry.request(pos)) {
                listState.animateScrollToItem(firstRow + pos.row)
                registry.rowStates[pos.row]?.scrollToItem(pos.index)
                repeat(5) {
                    withFrameNanos { }
                    if (registry.request(pos)) return@launch
                }
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onFocusChanged { contentHasFocus = it.hasFocus }
            .focusProperties {
                enter = {
                    val remembered = memory.resolve(screen, latestRowSizes)
                    when {
                        remembered != null && !remembered.isHero ->
                            registry.requesterFor(remembered)
                                ?: if (ui.heroes.isNotEmpty()) playRequester else FocusRequester.Default
                        ui.heroes.isNotEmpty() -> playRequester
                        else -> FocusRequester.Default
                    }
                }
            },
    ) {
        val heroHeight = tvHeroHeight(maxHeight, ui.showTabs)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val from = focused ?: return@onPreviewKeyEvent false
                    val dir = when (tvKeyOf(e.nativeKeyEvent.keyCode)) {
                        TvKey.UP -> Dpad.UP
                        TvKey.DOWN -> Dpad.DOWN
                        TvKey.LEFT -> Dpad.LEFT
                        TvKey.RIGHT -> Dpad.RIGHT
                        else -> return@onPreviewKeyEvent false
                    }
                    when (val m = TvGrid.move(from, dir, rowSizes) { memory.columnFor(screen, it) }) {
                        is GridMove.To -> { moveTo(m.pos); true }
                        GridMove.Stay -> true
                        GridMove.ExitLeft, GridMove.ExitUp -> false
                    }
                },
        ) {
            item(key = "hero", contentType = "hero") {
                TvHero(
                    heroes = ui.heroes,
                    myList = myList,
                    visible = heroVisible,
                    height = heroHeight,
                    playRequester = playRequester,
                    onPlay = onPlay,
                    onOpen = { onOpen(it, null) },
                    onToggleMyList = onToggleMyList,
                    onColor = feeder::immediate,
                    readiness = readiness,
                    modifier = Modifier.onFocusChanged {
                        if (it.hasFocus) {
                            focused = null
                            if (!restoringFocus) {
                                memory.save(screen, FocusPos.HERO)
                            }
                            scope.launch {
                                withFrameNanos { }
                                listState.animateScrollToItem(0)
                            }
                        }
                    },
                )
            }
            if (ui.showTabs) {
                item(key = "tabs", contentType = "tabs") {
                    Box(Modifier.onFocusChanged { if (it.hasFocus) focused = null }) {
                        TvTabs(ui.tabs.map { it.title }, ui.selectedTab, onSelectTab)
                    }
                }
            }
            itemsIndexed(effectiveRows, key = { _, r -> r.id }, contentType = { _, r -> r.style }) { i, row ->
                val nearEnd = remember(row.id, onLoadMore) { { onLoadMore(row.id) } }
                TvRow(
                    rowIndex = i,
                    row = row,
                    registry = registry,
                    onOpen = { onOpen(it, null) },
                    onFocus = { pos, item ->
                        focused = pos
                        if (!restoringFocus) {
                            memory.save(screen, pos)
                        }
                        feeder.offer(hexColor(item.dominantColor))
                    },
                    onNearEnd = nearEnd,
                )
            }
            if (effectiveRows.isEmpty() && !ui.tabLoading) {
                item(key = "empty") {
                    Text(
                        "Nothing here yet",
                        color = Cinetor.tokens.textSecondary,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

/** Category tabs as Movira-style Liquid Glass focusable pills (`Movies | Series`, `K | C | J`, etc.). */
@Composable
fun TvTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val tokens = Cinetor.tokens
    val prefs = Cinetor.prefs
    val isLight = tokens.bg.luminance() > 0.5f
    val spotlightRing = if (isLight) Color(0xFF141416) else Color.White

    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.focusGroup(),
    ) {
        itemsIndexed(titles) { i, t ->
            val interaction = remember { MutableInteractionSource() }
            val f by interaction.collectIsFocusedAsState()
            val active = i == selected
            val scale by animateFloatAsState(
                if (f && !prefs.reduceMotion) TV_FOCUS_SCALE else 1f,
                label = "tvTabScale",
            )
            val sound = rememberFocusSound()
            LaunchedEffect(f) { if (f) sound() }
            val shape = RoundedCornerShape(50)
            val fill = when {
                active -> tokens.accent
                f -> tokens.surfaceElevated
                else -> tokens.glassFill
            }
            val textColor = when {
                active -> tokens.onAccent
                else -> tokens.textPrimary
            }
            val borderColor = when {
                f -> spotlightRing
                active -> Color.Transparent
                else -> tokens.glassStroke
            }
            Text(
                text = t,
                color = textColor,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (active || f) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(shape)
                    .background(fill)
                    .border(if (f) 2.dp else 1.dp, borderColor, shape)
                    .clickable(interactionSource = interaction, indication = null) { onSelect(i) }
                    .semantics { contentDescription = "Tab $t" }
                    .padding(horizontal = 20.dp, vertical = 9.dp),
            )
        }
    }
}
