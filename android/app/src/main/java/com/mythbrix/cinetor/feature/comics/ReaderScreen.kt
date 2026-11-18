package com.mythbrix.cinetor.feature.comics

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.mythbrix.cinetor.core.data.AssetUris
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.feature.player.findActivity
import com.mythbrix.cinetor.feature.player.gestures.BrightnessController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Draws the reader above the NavHost while [ComicsModule.reader] holds a request. */
@Composable
fun ComicsReaderHost(comics: ComicsModule) {
    val request by comics.reader.collectAsStateWithLifecycle()
    val r = request ?: return
    val tier = Cinetor.tier
    val hostScope = rememberCoroutineScope()
    var holder by remember(r) { mutableStateOf<ReaderStateHolder?>(null) }
    LaunchedEffect(r) {
        comics.store.ensureLoaded()
        val h = ReaderStateHolder(
            comic = r.comic,
            startChapterId = r.chapterId,
            store = comics.store,
            tier = tier,
            scope = hostScope,
            resolvePages = comics.pageResolver,
            resolveFallbackPages = comics.fallbackPageResolver,
        )
        if (comics.isDefaultDataSaver()) {
            h.setDataSaverPages(true)
        }
        holder = h
    }
    BackHandler { comics.closeReader() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val h = holder
        if (h == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Cinetor.tokens.accent)
        } else {
            ReaderScreen(h, comics.downloads, onClose = comics::closeReader)
        }
    }
}

/**
 * Comic reader: RTL/LTR paged (HorizontalPager) or vertical webtoon (LazyColumn), pinch/double-tap zoom, tap zones,
 * page slider, brightness, keep-screen-on, preload, next-chapter auto-load, D-pad page turns, and data-saver / backup
 * server auto-recovery.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(holder: ReaderStateHolder, downloads: ComicDownloads, onClose: () -> Unit) {
    val state by holder.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var settingsOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val dlStates by downloads.states.collectAsStateWithLifecycle()
    val tokens = Cinetor.tokens

    // Keep screen on while reading; brightness & immersive system bars restored on exit.
    val activity = remember(context) { context.findActivity() }
    val brightness = remember(activity) { BrightnessController(activity) }
    DisposableEffect(view, activity) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            brightness.restore()
            runCatching {
                activity?.window?.let { win ->
                    WindowCompat.getInsetsController(win, win.decorView).show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }
    LaunchedEffect(state.brightness) {
        state.brightness?.let { brightness.set(it) } ?: brightness.restore()
    }
    LaunchedEffect(state.uiVisible, activity) {
        runCatching {
            activity?.window?.let { win ->
                val controller = WindowCompat.getInsetsController(win, win.decorView)
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (state.uiVisible) {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    // Preload next pages (3, LOW: 1) into Coil's caches.
    LaunchedEffect(state.chapterIndex, state.page, state.reloadGeneration) {
        val loader = context.imageLoader
        holder.preload().forEach { path ->
            val ch = state.chapters.firstOrNull { path in it.pages }
            val model: Any = ch?.let { downloads.pageModel(state.comic.id, it, it.pages.indexOf(path)) }
                ?: AssetUris.of(path)
            loader.enqueue(ImageRequest.Builder(context).data(model).build())
        }
    }
    // UI auto-hides after a few seconds of reading.
    LaunchedEffect(state.uiVisible, state.page, settingsOpen) {
        if (state.uiVisible && !settingsOpen) {
            delay(4_000)
            holder.setUiVisible(false)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val onTap: (Float, Float) -> Unit = { x, w ->
        when (tapZone(x, w, state.mode)) {
            TapZone.TOGGLE -> holder.toggleUi()
            TapZone.NEXT -> holder.nextPage()
            TapZone.PREV -> holder.prevPage()
        }
    }
    val listState = key(state.chapterGeneration) { rememberLazyListState(initialFirstVisibleItemIndex = state.page) }
    val screenHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

    // Prevent runaway chapter-level fallback loops within one chapter open.
    var autoFallbackUsed by remember(state.chapter.id) { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val rtl = state.mode == ReaderMode.RTL_PAGED
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (rtl) holder.prevPage() else holder.nextPage(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (rtl) holder.nextPage() else holder.prevPage(); true }
                    AndroidKeyEvent.KEYCODE_PAGE_DOWN, AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> { holder.nextPage(); true }
                    AndroidKeyEvent.KEYCODE_PAGE_UP, AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> { holder.prevPage(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (state.mode == ReaderMode.VERTICAL) {
                            scope.launch { listState.animateScrollBy(screenHeightPx * 0.8f) }
                        } else holder.nextPage()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        if (state.mode == ReaderMode.VERTICAL) {
                            scope.launch { listState.animateScrollBy(-screenHeightPx * 0.8f) }
                        } else holder.prevPage()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> { holder.toggleUi(); true }
                    else -> false
                }
            }
            .semantics { contentDescription = "Reader ${state.mode.name}" },
    ) {
        when {
            state.loadingPages -> CircularProgressIndicator(
                Modifier.align(Alignment.Center),
                color = tokens.accent,
            )
            state.pageCount == 0 -> ChapterEmptyState(
                message = state.unavailable ?: CHAPTER_UNAVAILABLE,
                canRetry = state.unavailable != null,
                onRetry = holder::retryChapter,
                onTryBackup = { holder.reloadChapterWithFallback(preferDataSaver = true, preferBackup = true) },
                modifier = Modifier.align(Alignment.Center),
            )
            state.mode.paged -> PagedReader(
                holder = holder,
                state = state,
                downloads = downloads,
                onTap = onTap,
                onAutoFallback = {
                    if (!autoFallbackUsed) {
                        autoFallbackUsed = true
                        holder.reloadChapterWithFallback(preferDataSaver = true, preferBackup = true)
                    }
                },
            )
            else -> VerticalReader(
                holder = holder,
                state = state,
                downloads = downloads,
                listState = listState,
                onTap = onTap,
                onAutoFallback = {
                    if (!autoFallbackUsed) {
                        autoFallbackUsed = true
                        holder.reloadChapterWithFallback(preferDataSaver = true, preferBackup = true)
                    }
                },
            )
        }

        ReaderBars(
            state = state,
            downloadState = dlStates[state.chapter.id],
            onClose = onClose,
            onSettings = { settingsOpen = true },
            onSeek = holder::onPageChanged,
            onPrevChapter = holder::prevChapter,
            onNextChapter = holder::nextChapter,
            onDownload = { downloads.download(state.comic, state.chapter) },
        )
        if (state.endOfComic) {
            Text(
                "You're all caught up",
                color = tokens.textPrimary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.Center)
                    .glass(RoundedCornerShape(16.dp), GlassLevel.SURFACE)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        ReaderSettingsPanel(
            visible = settingsOpen,
            state = state,
            onMode = holder::setMode,
            onBrightness = holder::setBrightness,
            onDataSaverPages = holder::setDataSaverPages,
            onSwitchServer = { holder.reloadChapterWithFallback(preferDataSaver = true, preferBackup = true) },
            onDismiss = { settingsOpen = false },
        )
    }
}

/** Chapter with no resolvable pages: generic message (no host/provider) + retry & backup server options. */
@Composable
private fun ChapterEmptyState(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onTryBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    Column(
        modifier = modifier
            .padding(24.dp)
            .glass(RoundedCornerShape(20.dp), GlassLevel.SURFACE)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, color = tokens.textPrimary, style = MaterialTheme.typography.bodyMedium)
        if (canRetry) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) {
                    Text("Retry", color = tokens.accent, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onTryBackup) {
                    Text("Try backup server", color = tokens.textSecondary)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedReader(
    holder: ReaderStateHolder,
    state: ReaderState,
    downloads: ComicDownloads,
    onTap: (Float, Float) -> Unit,
    onAutoFallback: () -> Unit,
) {
    // One trailing slot = "next chapter" page; settling on it auto-loads the next chapter.
    val count = state.pageCount + if (state.hasNextChapter) 1 else 0
    key(state.chapterGeneration) {
        val pager = rememberPagerState(initialPage = state.page) { count }
        var zoomed by remember { mutableStateOf(false) }
        LaunchedEffect(pager) {
            snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { p ->
                if (p >= state.pageCount) holder.onEndReached() else holder.onPageChanged(p)
            }
        }
        LaunchedEffect(state.page) {
            if (pager.currentPage != state.page && !pager.isScrollInProgress) pager.animateScrollToPage(state.page)
        }
        HorizontalPager(
            state = pager,
            reverseLayout = state.mode == ReaderMode.RTL_PAGED,
            beyondViewportPageCount = 1,
            userScrollEnabled = !zoomed,
            key = { it },
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            if (index >= state.pageCount) {
                NextChapterSlot(state)
            } else {
                ZoomableBox(
                    resetKey = index,
                    modifier = Modifier.fillMaxSize(),
                    onTap = onTap,
                    onZoomChanged = { zoomed = it },
                ) {
                    ComicPageImage(
                        rawModel = downloads.pageModel(state.comic.id, state.chapter, index),
                        pageIndex = index,
                        reloadGeneration = state.reloadGeneration,
                        fitScreen = true,
                        downloads = downloads,
                        onAutoFallback = onAutoFallback,
                        onManualSwitchServer = { holder.reloadChapterWithFallback(preferDataSaver = true, preferBackup = true) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VerticalReader(
    holder: ReaderStateHolder,
    state: ReaderState,
    downloads: ComicDownloads,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onTap: (Float, Float) -> Unit,
    onAutoFallback: () -> Unit,
) {
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { i ->
            if (i < state.pageCount) holder.onPageChanged(i)
        }
    }
    // End of the strip visible (footer) → auto-load the next chapter after a short beat.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == "next" } }
            .distinctUntilChanged()
            .collectLatest { atEnd ->
                if (atEnd) {
                    delay(700)
                    holder.onEndReached()
                }
            }
    }
    LaunchedEffect(state.page) {
        if (!listState.isScrollInProgress && listState.firstVisibleItemIndex != state.page) {
            listState.scrollToItem(state.page)
        }
    }
    ZoomableBox(
        resetKey = state.chapterGeneration,
        modifier = Modifier.fillMaxSize(),
        singleFingerPan = false,
        onTap = onTap,
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(state.chapter.pages, key = { i, _ -> "p$i" }) { i, _ ->
                ComicPageImage(
                    rawModel = downloads.pageModel(state.comic.id, state.chapter, i),
                    pageIndex = i,
                    reloadGeneration = state.reloadGeneration,
                    fitScreen = false,
                    downloads = downloads,
                    onAutoFallback = onAutoFallback,
                    onManualSwitchServer = { holder.reloadChapterWithFallback(preferDataSaver = true, preferBackup = true) },
                )
            }
            item(key = "next") { NextChapterSlot(state, Modifier.height(220.dp)) }
        }
    }
}

private enum class PagePhase { LOADING, READY, FAILED }

/**
 * Single comic page with automatic cache-bypassing retry → data-saver mirror switch → chapter host fallback,
 * and a zero-crash inline recovery card if every mirror is unreachable.
 */
@Composable
private fun ComicPageImage(
    rawModel: Any,
    pageIndex: Int,
    reloadGeneration: Int,
    fitScreen: Boolean,
    downloads: ComicDownloads,
    onAutoFallback: () -> Unit,
    onManualSwitchServer: () -> Unit,
) {
    val context = LocalContext.current
    val tokens = Cinetor.tokens
    var attempt by remember(rawModel, reloadGeneration) { mutableIntStateOf(0) }
    var useDataSaverMirror by remember(rawModel, reloadGeneration) { mutableStateOf(false) }
    var phase by remember(rawModel, reloadGeneration) { mutableStateOf(PagePhase.LOADING) }

    val backupUrl = remember(rawModel) { (rawModel as? String)?.let(downloads::backupPageUrl) }
    val activeModel: Any = if (useDataSaverMirror && backupUrl != null) backupUrl else rawModel

    val request = remember(activeModel, attempt, reloadGeneration) {
        ImageRequest.Builder(context)
            .data(activeModel)
            .apply {
                if (attempt > 0) {
                    memoryCachePolicy(CachePolicy.DISABLED)
                    diskCachePolicy(CachePolicy.DISABLED)
                }
            }
            .build()
    }

    LaunchedEffect(phase, attempt) {
        if (phase == PagePhase.FAILED && attempt == 1) {
            delay(650)
            phase = PagePhase.LOADING
            attempt = 2
        }
    }

    val containerMod = if (fitScreen) {
        Modifier.fillMaxSize()
    } else {
        Modifier
            .fillMaxWidth()
            .then(if (phase != PagePhase.READY) Modifier.heightIn(min = 360.dp) else Modifier)
    }

    Box(containerMod, contentAlignment = Alignment.Center) {
        if (phase != PagePhase.FAILED || attempt == 1) {
            AsyncImage(
                model = request,
                contentDescription = "Page ${pageIndex + 1}",
                contentScale = if (fitScreen) ContentScale.Fit else ContentScale.FillWidth,
                modifier = if (fitScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                onLoading = { phase = PagePhase.LOADING },
                onSuccess = { phase = PagePhase.READY },
                onError = {
                    when {
                        attempt == 0 -> {
                            phase = PagePhase.FAILED
                            attempt = 1
                        }
                        !useDataSaverMirror && backupUrl != null -> {
                            useDataSaverMirror = true
                            phase = PagePhase.LOADING
                            attempt = 2
                        }
                        attempt == 2 -> {
                            attempt = 3
                            phase = PagePhase.FAILED
                            onAutoFallback()
                        }
                        else -> {
                            phase = PagePhase.FAILED
                        }
                    }
                },
            )
            if (phase == PagePhase.LOADING) {
                CircularProgressIndicator(
                    color = tokens.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp),
                )
            }
        } else {
            Column(
                Modifier
                    .padding(20.dp)
                    .glass(RoundedCornerShape(18.dp), GlassLevel.SURFACE)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Page ${pageIndex + 1} couldn't load",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textPrimary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            phase = PagePhase.LOADING
                            attempt += 1
                        },
                    ) {
                        Text("Retry page", color = tokens.accent, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = onManualSwitchServer) {
                        Text("Switch server", color = tokens.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun NextChapterSlot(state: ReaderState, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.hasNextChapter) {
                CircularProgressIndicator(color = Cinetor.tokens.accent)
                Text("Loading ${state.chapters[state.chapterIndex + 1].title}…", color = Color.White)
            } else {
                Text("End of ${state.comic.title}", color = Color.White)
            }
        }
    }
}

@Composable
private fun ReaderBars(
    state: ReaderState,
    downloadState: ChapterDownload?,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onSeek: (Int) -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onDownload: () -> Unit,
) {
    val tokens = Cinetor.tokens
    Box(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(
            visible = state.uiVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp), GlassLevel.SURFACE)
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Close reader" }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = tokens.textPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        state.comic.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(state.chapter.title)
                            append(" · ")
                            append(state.mode.label)
                            if (state.dataSaverPages) append(" · Data saver")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ChapterDownloadIcon(downloadState, onDownload)
                IconButton(onClick = onSettings, modifier = Modifier.semantics { contentDescription = "Reader settings" }) {
                    Icon(Icons.Rounded.Tune, contentDescription = null, tint = tokens.textPrimary)
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = state.uiVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), GlassLevel.SURFACE)
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevChapter, enabled = state.hasPrevChapter) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous chapter", tint = tokens.textPrimary)
                    }
                    // RTL manga: slider runs right → left like the pages.
                    val dir = if (state.mode == ReaderMode.RTL_PAGED) LayoutDirection.Rtl else LayoutDirection.Ltr
                    CompositionLocalProvider(LocalLayoutDirection provides dir) {
                        Slider(
                            value = state.page.toFloat(),
                            onValueChange = { onSeek(it.toInt()) },
                            valueRange = 0f..(state.pageCount - 1).coerceAtLeast(1).toFloat(),
                            steps = (state.pageCount - 2).coerceAtLeast(0),
                            colors = SliderDefaults.colors(thumbColor = tokens.accent, activeTrackColor = tokens.accent),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Page slider" },
                        )
                    }
                    IconButton(onClick = onNextChapter, enabled = state.hasNextChapter) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next chapter", tint = tokens.textPrimary)
                    }
                }
                Text(
                    "Page ${state.page + 1} / ${state.pageCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}
