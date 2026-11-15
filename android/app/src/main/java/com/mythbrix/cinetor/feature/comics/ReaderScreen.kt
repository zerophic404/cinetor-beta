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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import com.mythbrix.cinetor.core.data.ComicFormat
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
        holder = ReaderStateHolder(r.comic, r.chapterId, comics.store, tier, hostScope, comics.pageResolver)
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
 * page slider, brightness, keep-screen-on, preload, next-chapter auto-load, D-pad page turns.
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

    // Keep screen on while reading; brightness override restored on exit.
    val brightness = remember(context) { BrightnessController(context.findActivity()) }
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            brightness.restore()
        }
    }
    LaunchedEffect(state.brightness) {
        state.brightness?.let { brightness.set(it) } ?: brightness.restore()
    }

    // Preload next pages (3, LOW: 1) into Coil's caches.
    LaunchedEffect(state.chapterIndex, state.page) {
        val loader = context.imageLoader
        holder.preload().forEach { path ->
            val ch = state.chapters.firstOrNull { path in it.pages }
            val model: Any = ch?.let { downloads.pageModel(state.comic.id, it, it.pages.indexOf(path)) }
                ?: com.mythbrix.cinetor.core.data.AssetUris.of(path)
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

    val aspect = if (state.comic.format == ComicFormat.LONGSTRIP) 720f / 2000f else 720f / 1080f
    val onTap: (Float, Float) -> Unit = { x, w ->
        when (tapZone(x, w, state.mode)) {
            TapZone.TOGGLE -> holder.toggleUi()
            TapZone.NEXT -> holder.nextPage()
            TapZone.PREV -> holder.prevPage()
        }
    }
    val listState = key(state.chapterGeneration) { rememberLazyListState(initialFirstVisibleItemIndex = state.page) }
    val screenHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

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
                color = Cinetor.tokens.accent,
            )
            state.pageCount == 0 -> ChapterEmptyState(
                message = state.unavailable ?: CHAPTER_UNAVAILABLE,
                canRetry = state.unavailable != null,
                onRetry = holder::retryChapter,
                modifier = Modifier.align(Alignment.Center),
            )
            state.mode.paged -> PagedReader(holder, state, downloads, aspect, onTap)
            else -> VerticalReader(holder, state, downloads, aspect, listState, onTap)
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
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .glass(RoundedCornerShape(16.dp), GlassLevel.SHEET)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        ReaderSettingsPanel(
            visible = settingsOpen,
            state = state,
            onMode = holder::setMode,
            onBrightness = holder::setBrightness,
            onDismiss = { settingsOpen = false },
        )
    }
}

/** Chapter with no resolvable pages: generic message (no host/provider) + retry. */
@Composable
private fun ChapterEmptyState(message: String, canRetry: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .glass(RoundedCornerShape(16.dp), GlassLevel.SHEET)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        if (canRetry) {
            androidx.compose.material3.TextButton(onClick = onRetry) {
                Text("Retry", color = Cinetor.tokens.accent)
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
    aspect: Float,
    onTap: (Float, Float) -> Unit,
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
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            model = downloads.pageModel(state.comic.id, state.chapter, index),
                            contentDescription = "Page ${index + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
                        )
                    }
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
    aspect: Float,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onTap: (Float, Float) -> Unit,
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
            // Slider/D-pad jumps.
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
                AsyncImage(
                    model = downloads.pageModel(state.comic.id, state.chapter, i),
                    contentDescription = "Page ${i + 1}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
                )
            }
            item(key = "next") { NextChapterSlot(state, Modifier.height(220.dp)) }
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
                    .glass(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp), GlassLevel.BAR)
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Close reader" }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = tokens.textPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(state.comic.title, style = MaterialTheme.typography.titleSmall, color = tokens.textPrimary, maxLines = 1)
                    Text(
                        "${state.chapter.title} · ${state.mode.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        maxLines = 1,
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
                    .glass(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), GlassLevel.BAR)
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
