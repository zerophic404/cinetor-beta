package com.mythbrix.cinetor.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.player.Readiness
import com.mythbrix.cinetor.core.ui.posterKey
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.SkeletonHero
import com.mythbrix.cinetor.design.SkeletonPosterRow
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.feature.details.sourceReady
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

private const val HERO_KEY = "hero"
private const val HERO_AUTOPLAY_ORIGIN = "autoplay"

/**
 * Shared screen for every space: category tabs + Cinetor Beta showcase hero + genre chips + styled shelves
 * + Liquid Glass "See all" endless grid overlay.
 * [onOpenItem] gets the item and the shared-element key of the poster that was tapped (or `"autoplay"`
 * when Hero `Play` triggers inline playback inside `DetailsScreen`).
 */
@Composable
fun SpaceScreen(
    space: Space,
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenItem: (Item, String?) -> Unit,
    onPlayItem: ((Item) -> Unit)? = null,
) {
    val vm: SpaceViewModel = viewModel(
        key = "space-${space.name}",
        factory = viewModelFactory { initializer { SpaceViewModel(space, container.catalog) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val myList by container.myList.collectAsStateWithLifecycle()
    val readinessMap by container.preloader.readiness.collectAsStateWithLifecycle()
    val feeder = rememberTintFeeder()
    val fadeMs = Cinetor.tier.scaled(CinetorTokens.Motion.longMs)
    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn(tween(fadeMs)) togetherWith fadeOut(tween(fadeMs)) },
        contentKey = { it::class },
        label = "spaceState",
    ) { s ->
        when (s) {
            SpaceUiState.Loading -> SpaceSkeleton(contentPadding)
            is SpaceUiState.Error -> SpaceErrorState(message = s.message, onRetry = vm::load)
            is SpaceUiState.Ready -> SpaceContent(
                space = space,
                ui = s.ui,
                myList = myList,
                readinessMap = readinessMap,
                contentPadding = contentPadding,
                feeder = feeder,
                onOpen = onOpenItem,
                onSelectTab = vm::selectTab,
                onSelectGenre = vm::selectGenre,
                onToggleMyList = { container.toggleMyList(it) },
                onPlay = { item ->
                    when {
                        onPlayItem != null -> onPlayItem(item)
                        space.isVideo -> onOpenItem(item, HERO_AUTOPLAY_ORIGIN)
                        else -> container.playback.play(item)
                    }
                },
                onLoadMore = vm::loadMore,
                onSeeAll = vm::openSeeAll,
                onCloseSeeAll = vm::closeSeeAll,
            )
        }
    }
}

@Composable
private fun SpaceErrorState(message: String, onRetry: () -> Unit) {
    val tokens = Cinetor.tokens
    val cardShape = RoundedCornerShape(CinetorTokens.Shape.lg)
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .glass(cardShape, GlassLevel.SURFACE)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.accent,
                    contentColor = tokens.onAccent,
                ),
            ) {
                Text("Retry", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** First load: SkeletonHero + 3 SkeletonPosterRow under one shimmer clock. */
@Composable
private fun SpaceSkeleton(contentPadding: PaddingValues) {
    ShimmerHost {
        LazyColumn(contentPadding = contentPadding, userScrollEnabled = false) {
            item { SkeletonHero() }
            items(3) { SkeletonPosterRow(Modifier.padding(vertical = 12.dp)) }
        }
    }
}

@Composable
private fun SpaceContent(
    space: Space,
    ui: SpaceUi,
    myList: Set<String>,
    readinessMap: Map<String, Readiness>,
    contentPadding: PaddingValues,
    feeder: TintFeeder,
    onOpen: (Item, String?) -> Unit,
    onSelectTab: (Int) -> Unit,
    onSelectGenre: (String?) -> Unit,
    onToggleMyList: (Item) -> Unit,
    onPlay: (Item) -> Unit,
    onLoadMore: (String) -> Unit = {},
    onSeeAll: (String) -> Unit = {},
    onCloseSeeAll: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val heroVisible by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == HERO_KEY } }
    }

    // Space.HOME never exposes regional drama tabs/rows ("Indian Dramas", "Bengali", etc. live in Space.DRAMA).
    val indexedTabs = remember(space, ui.tabs) {
        if (space == Space.HOME) {
            ui.tabs.withIndex().filterNot { (_, t) ->
                isExcludedFromHomeSpace(t.title) || isExcludedFromHomeSpace(t.id)
            }
        } else {
            ui.tabs.withIndex().toList()
        }
    }
    val visibleTabs = remember(indexedTabs) { indexedTabs.map { it.value } }
    val visibleSelectedTab = remember(indexedTabs, ui.selectedTab) {
        indexedTabs.indexOfFirst { it.index == ui.selectedTab }.coerceAtLeast(0)
    }
    LaunchedEffect(space, indexedTabs, ui.selectedTab) {
        if (space == Space.HOME && indexedTabs.isNotEmpty() && indexedTabs.none { it.index == ui.selectedTab }) {
            onSelectTab(indexedTabs.first().index)
        }
    }

    val visibleRows = remember(space, ui.rows) {
        if (space == Space.HOME) {
            ui.rows.filterNot { r -> isExcludedFromHomeSpace(r.title) || isExcludedFromHomeSpace(r.id) }
        } else {
            ui.rows
        }
    }
    val visibleGenres = remember(space, ui.genres) {
        if (space == Space.HOME) ui.genres.filterNot(::isExcludedFromHomeSpace) else ui.genres
    }

    val heroKey = remember(ui.heroes) { ui.heroes.joinToString(",") { it.id } }
    val onTint: (Color) -> Unit = remember(feeder) { { c -> feeder.offer(c) } }
    val seeAllRow = ui.seeAllRow
    var retainedSeeAll by remember { mutableStateOf<RowUi?>(null) }
    if (seeAllRow != null) retainedSeeAll = seeAllRow
    val animMs = Cinetor.tier.scaled(CinetorTokens.Motion.mediumMs)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (visibleTabs.size > 1) {
                item(key = "tabs", contentType = "tabs") {
                    CategoryTabs(
                        tabs = visibleTabs,
                        selected = visibleSelectedTab,
                        onSelect = { visibleIdx ->
                            indexedTabs.getOrNull(visibleIdx)?.let { onSelectTab(it.index) }
                        },
                    )
                }
            }
            item(key = HERO_KEY, contentType = HERO_KEY) {
                key(heroKey) {
                    HeroCarousel(
                        heroes = ui.heroes,
                        myList = myList,
                        visible = heroVisible && seeAllRow == null,
                        onOpen = { onOpen(it, null) },
                        onPlay = onPlay,
                        onToggleMyList = onToggleMyList,
                        onColor = feeder::immediate,
                        isReady = { item -> readinessMap[item.id].sourceReady() || item.sources.isNotEmpty() },
                    )
                }
            }
            if (visibleGenres.isNotEmpty()) {
                item(key = "genres", contentType = "genres") {
                    GenreChips(visibleGenres, ui.selectedGenre, onSelectGenre)
                }
            }
            if (ui.tabLoading) {
                items(3, key = { "row-skeleton-$it" }, contentType = { "skeleton" }) {
                    SkeletonPosterRow()
                }
            } else {
                items(visibleRows, key = { it.id }, contentType = { it.style }) { row ->
                    val nearEnd = remember(row.id, onLoadMore) { { onLoadMore(row.id) } }
                    val openAll = remember(row.id, onSeeAll) { { onSeeAll(row.id) } }
                    ContentRow(
                        row = row,
                        onOpen = onOpen,
                        onTint = onTint,
                        onNearEnd = nearEnd,
                        onSeeAll = openAll,
                    )
                }
                if (visibleRows.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        Text(
                            "Nothing in ${ui.selectedGenre ?: "this category"} yet",
                            color = Cinetor.tokens.textSecondary,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }

        BackHandler(enabled = seeAllRow != null, onBack = onCloseSeeAll)
        androidx.compose.animation.AnimatedVisibility(
            visible = seeAllRow != null,
            enter = fadeIn(tween(animMs)) + slideInVertically(tween(animMs)) { it / 10 },
            exit = fadeOut(tween(animMs)) + slideOutVertically(tween(animMs)) { it / 10 },
        ) {
            val activeRow = seeAllRow ?: retainedSeeAll
            if (activeRow != null) {
                SeeAllGridOverlay(
                    row = activeRow,
                    loadingMore = activeRow.id in ui.loadingRows,
                    contentPadding = contentPadding,
                    onBack = onCloseSeeAll,
                    onOpen = onOpen,
                    onTint = onTint,
                    onNearEnd = { onLoadMore(activeRow.id) },
                )
            }
        }
    }
}

@Composable
private fun SeeAllGridOverlay(
    row: RowUi,
    loadingMore: Boolean,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpen: (Item, String?) -> Unit,
    onTint: (Color) -> Unit,
    onNearEnd: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val gridState = rememberLazyGridState()
    val latestNearEnd by rememberUpdatedState(onNearEnd)

    LaunchedEffect(row.id, gridState, row.items.size) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val lastIdx = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastIdx >= (info.totalItemsCount - 6).coerceAtLeast(0)
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { latestNearEnd() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bg)
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .glass(CircleShape, GlassLevel.SURFACE)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = tokens.textPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 16.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(tokens.accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cols = when {
                maxWidth >= 840.dp -> 6
                maxWidth >= 600.dp -> 4
                else -> 3
            }
            val spacing = 10.dp
            val hPad = 16.dp
            val cardWidth = ((maxWidth - hPad * 2 - spacing * (cols - 1)) / cols).coerceAtLeast(88.dp)

            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                state = gridState,
                contentPadding = PaddingValues(
                    start = hPad,
                    end = hPad,
                    top = 6.dp,
                    bottom = contentPadding.calculateBottomPadding() + 28.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(row.items, key = { it.id }, contentType = { "poster" }) { item ->
                    val sharedKey = posterKey("seeall-${row.id}", item.id)
                    PosterCard(
                        item = item,
                        onClick = { onOpen(item, sharedKey) },
                        onActive = onTint,
                        width = cardWidth,
                        sharedKey = sharedKey,
                    )
                }
                if (loadingMore) {
                    item(key = "seeall-loading", span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = tokens.accent,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
