package com.mythbrix.cinetor.tvui

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.ui.LocalResumeEntries
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.SkeletonPosterRow
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.design.hexColor
import com.mythbrix.cinetor.feature.home.SpaceUi
import com.mythbrix.cinetor.feature.home.SpaceUiState
import com.mythbrix.cinetor.feature.home.SpaceViewModel
import com.mythbrix.cinetor.feature.home.rememberTintFeeder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/**
 * TV home for a video space (`Home`, `Anime`, `Drama`, plus video-style isolated spaces):
 * widescreen Liquid Glass hero, Movira category pills, ranked Top-10 & endless pageable rows,
 * and deterministic [FocusMemory] restoration across Details / Player / Side-Nav transitions.
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
            memory = memory,
            onOpen = onOpenItem,
            onPlay = { container.playback.play(it) },
            onToggleMyList = { container.toggleMyList(it) },
            onSelectTab = vm::selectTab,
            onLoadMore = vm::loadMore,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvHomeContent(
    screen: String,
    ui: SpaceUi,
    myList: Set<String>,
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
                // Second attempt after NavHost transition settles if focus wasn't claimed yet.
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
                    // Re-verify after NavHost popExitTransition (~220 ms) so outgoing screen disposal never steals focus.
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
                    // Focus in hero → grid rules off until a card is focused again; animateScrollToItem(0)
                    // ensures Compose 1.7 LazyColumn pivot scrolling never clips the top half of the banner.
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
