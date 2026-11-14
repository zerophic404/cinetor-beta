package com.mythbrix.cinetor.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.SkeletonHero
import com.mythbrix.cinetor.design.SkeletonPosterRow

private const val HERO_KEY = "hero"

/**
 * Shared screen for every space: category tabs + auto-sliding hero + genre chips + styled rows.
 * [onOpenItem] gets the item and the shared-element key of the poster that was tapped (or null).
 */
@Composable
fun SpaceScreen(
    space: Space,
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenItem: (Item, String?) -> Unit,
) {
    val vm: SpaceViewModel = viewModel(
        key = "space-${space.name}",
        factory = viewModelFactory { initializer { SpaceViewModel(space, container.catalog) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val myList by container.myList.collectAsStateWithLifecycle()
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
            is SpaceUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.message, color = Cinetor.tokens.textSecondary)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = vm::load) { Text("Retry") }
                }
            }
            is SpaceUiState.Ready -> SpaceContent(
                ui = s.ui,
                myList = myList,
                contentPadding = contentPadding,
                feeder = feeder,
                onOpen = onOpenItem,
                onSelectTab = vm::selectTab,
                onSelectGenre = vm::selectGenre,
                onToggleMyList = { container.toggleMyList(it.id) },
            )
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
    ui: SpaceUi,
    myList: Set<String>,
    contentPadding: PaddingValues,
    feeder: TintFeeder,
    onOpen: (Item, String?) -> Unit,
    onSelectTab: (Int) -> Unit,
    onSelectGenre: (String?) -> Unit,
    onToggleMyList: (Item) -> Unit,
) {
    val listState = rememberLazyListState()
    val heroVisible by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == HERO_KEY } }
    }
    val heroKey = remember(ui.heroes) { ui.heroes.joinToString(",") { it.id } }
    val onTint: (Color) -> Unit = remember(feeder) { { c -> feeder.offer(c) } }
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (ui.showTabs) {
            item(key = "tabs", contentType = "tabs") {
                CategoryTabs(ui.tabs, ui.selectedTab, onSelectTab)
            }
        }
        item(key = HERO_KEY, contentType = HERO_KEY) {
            key(heroKey) {
                HeroCarousel(
                    heroes = ui.heroes,
                    myList = myList,
                    visible = heroVisible,
                    onOpen = { onOpen(it, null) },
                    // Player arrives in Phase 3: Play opens Details (which warms playback).
                    onPlay = { onOpen(it, null) },
                    onToggleMyList = onToggleMyList,
                    onColor = feeder::immediate,
                )
            }
        }
        if (ui.genres.isNotEmpty()) {
            item(key = "genres", contentType = "genres") {
                GenreChips(ui.genres, ui.selectedGenre, onSelectGenre)
            }
        }
        if (ui.tabLoading) {
            items(3, key = { "row-skeleton-$it" }, contentType = { "skeleton" }) {
                SkeletonPosterRow()
            }
        } else {
            items(ui.rows, key = { it.id }, contentType = { it.style }) { row ->
                ContentRow(row, onOpen, onTint)
            }
            if (ui.rows.isEmpty()) {
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
}
