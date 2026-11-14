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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.ui.PosterCard
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.SkeletonHero
import com.mythbrix.cinetor.design.SkeletonPosterRow
import com.mythbrix.cinetor.design.hexColor

/** Shared space screen (placeholder for Task 2.1): categories + first-row posters from assets. */
@Composable
fun SpaceScreen(
    space: Space,
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenItem: (String) -> Unit,
) {
    val vm: SpaceViewModel = viewModel(
        key = "space-${space.name}",
        factory = viewModelFactory { initializer { SpaceViewModel(space, container.catalog) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val tint = Cinetor.tint
    LaunchedEffect(state) {
        (state as? SpaceUiState.Ready)?.heroColor?.let { tint.setSource(hexColor(it)) }
    }
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
            is SpaceUiState.Ready -> SpaceContent(space, s, contentPadding, onOpenItem)
        }
    }
}

@Composable
private fun SpaceSkeleton(contentPadding: PaddingValues) {
    ShimmerHost {
        LazyColumn(contentPadding = contentPadding, userScrollEnabled = false) {
            item { SkeletonHero() }
            items(3) {
                SkeletonPosterRow(Modifier.padding(vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun SpaceContent(
    space: Space,
    state: SpaceUiState.Ready,
    contentPadding: PaddingValues,
    onOpenItem: (String) -> Unit,
) {
    val tokens = Cinetor.tokens
    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header", contentType = "header") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(space.displayName, style = MaterialTheme.typography.headlineMedium, color = tokens.textPrimary)
                Text(
                    "${state.sections.size} categories",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSecondary,
                )
            }
        }
        items(state.sections, key = { it.categoryId }, contentType = { "section" }) { section ->
            Column {
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.textPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    section.rowTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(section.items, key = { it.id }, contentType = { "poster" }) { item ->
                        PosterCard(item, onClick = { onOpenItem(item.id) })
                    }
                }
            }
        }
    }
}
