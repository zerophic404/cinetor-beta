package com.mythbrix.cinetor.tvui

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.SkeletonPosterRow
import com.mythbrix.cinetor.feature.home.SpaceUi
import com.mythbrix.cinetor.feature.home.SpaceUiState
import com.mythbrix.cinetor.feature.home.SpaceViewModel
import com.mythbrix.cinetor.feature.home.rememberTintFeeder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** FocusMemory key of a space's TV home. */
fun tvScreenKey(space: Space): String = "space-${space.name}"

/**
 * TV home for a video space (Home / Anime / Drama, also non-special isolated spaces): hero, category
 * tabs, rows. Same [SpaceViewModel] as the phone screen. Focus: rows follow [TvGrid] (Up/Down keep the
 * row's remembered column); coming back from Details/player restores the last card via [FocusMemory].
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
                Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(24.dp)).background(Cinetor.tokens.surfaceElevated))
                SkeletonPosterRow()
                SkeletonPosterRow()
            }
        }
        is SpaceUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.message, color = Cinetor.tokens.textSecondary)
                Spacer(Modifier.height(16.dp))
                TvButton("Retry", null, vm::load, primary = true)
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
        )
    }
}

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
) {
    val listState = rememberLazyListState()
    val registry = remember { TvFocusRegistry() }
    val playRequester = remember { FocusRequester() }
    val feeder = rememberTintFeeder()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf<FocusPos?>(null) }
    val rowSizes = ui.rows.map { it.items.size }
    // LazyColumn index of row 0: hero (+ tabs).
    val firstRow = if (ui.showTabs) 2 else 1
    val heroVisible by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    // Initial focus / restore after back or reload (clamped to the current rows).
    val rowsKey = ui.rows.joinToString(",") { it.id + ":" + it.items.size }
    val refocus = LocalTvRefocus.current
    LaunchedEffect(rowsKey, refocus) {
        withFrameNanos { }
        val pos = memory.resolve(screen, rowSizes)
        if (pos == null || pos.isHero) {
            listState.scrollToItem(0)
            runCatching { playRequester.requestFocus() }
            return@LaunchedEffect
        }
        listState.scrollToItem(firstRow + pos.row)
        withFrameNanos { }
        registry.rowStates[pos.row]?.let { rs ->
            if (rs.layoutInfo.visibleItemsInfo.none { it.index == pos.index }) rs.scrollToItem(pos.index)
        }
        repeat(5) {
            withFrameNanos { }
            if (registry.request(pos)) return@LaunchedEffect
            delay(16)
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = maxHeight * 0.62f
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
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
                        GridMove.ExitLeft, GridMove.ExitUp -> false // default search → side nav / tabs / hero
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
                    // Focus in hero → grid rules off until a card is focused again; keep the banner fully
                    // visible (TV's default pivot scrolling would push it half off-screen).
                    modifier = Modifier.onFocusChanged {
                        if (it.hasFocus) {
                            focused = null
                            memory.save(screen, FocusPos.HERO)
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
            itemsIndexed(ui.rows, key = { _, r -> r.id }, contentType = { _, r -> r.style }) { i, row ->
                TvRow(
                    rowIndex = i,
                    row = row,
                    registry = registry,
                    onOpen = { onOpen(it, null) },
                    onFocus = { pos, item ->
                        focused = pos
                        memory.save(screen, pos)
                        feeder.offer(com.mythbrix.cinetor.design.hexColor(item.dominantColor))
                    },
                )
            }
            if (ui.rows.isEmpty() && !ui.tabLoading) {
                item(key = "empty") {
                    Text("Nothing here yet", color = Cinetor.tokens.textSecondary, modifier = Modifier.padding(24.dp))
                }
            }
        }
    }
}

/** Category tabs as focusable pills (Movies | Series, K | C | J …). */
@Composable
fun TvTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.focusGroup(),
    ) {
        itemsIndexed(titles) { i, t ->
            val tokens = Cinetor.tokens
            val interaction = remember { MutableInteractionSource() }
            val f by interaction.collectIsFocusedAsState()
            val shape = RoundedCornerShape(50)
            Text(
                t,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(shape)
                    .background(
                        when {
                            f -> tokens.accent
                            i == selected -> Color.White.copy(alpha = 0.2f)
                            else -> Color.White.copy(alpha = 0.06f)
                        },
                    )
                    .border(if (f) 2.dp else 0.dp, Color.White.copy(alpha = if (f) 0.9f else 0f), shape)
                    .clickable(interactionSource = interaction, indication = null) { onSelect(i) }
                    .semantics { contentDescription = "Tab $t" }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}
