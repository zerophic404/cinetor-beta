package com.mythbrix.cinetor.feature.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.AssetUris
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.SkeletonDetails
import com.mythbrix.cinetor.design.SkeletonEpisodeList
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.design.hexColor
import kotlinx.coroutines.CancellationException

private sealed interface DetailsState {
    data object Loading : DetailsState
    data class Ready(val item: Item) : DetailsState
    data class Missing(val id: String) : DetailsState
}

/** Placeholder details (Task 2.2 builds the real screen): skeleton → backdrop + meta + synopsis. */
@Composable
fun DetailsScreen(id: String, container: AppContainer, contentPadding: PaddingValues) {
    val state by produceState<DetailsState>(DetailsState.Loading, id) {
        value = try {
            DetailsState.Ready(container.catalog.item(id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DetailsState.Missing(id)
        }
    }
    val tint = Cinetor.tint
    LaunchedEffect(state) { (state as? DetailsState.Ready)?.let { tint.setSource(hexColor(it.item.dominantColor)) } }
    val tokens = Cinetor.tokens
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
    ) {
        when (val s = state) {
            DetailsState.Loading -> ShimmerHost {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    SkeletonDetails()
                    SkeletonEpisodeList()
                }
            }
            is DetailsState.Missing -> Text(
                "Title not found (${s.id})",
                color = tokens.textSecondary,
                modifier = Modifier.padding(24.dp),
            )
            is DetailsState.Ready -> DetailsContent(s.item)
        }
    }
}

@Composable
private fun DetailsContent(item: Item) {
    val tokens = Cinetor.tokens
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(hexColor(item.dominantColor))) {
        AsyncImage(
            model = AssetUris.of(item.backdrop),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, tokens.bg.copy(alpha = 0.9f))),
            ),
        )
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(item.title, style = MaterialTheme.typography.headlineMedium, color = tokens.textPrimary)
        Text(
            listOf(item.year.toString(), "★ ${item.rating}", item.maturity, item.genres.joinToString(" · "))
                .joinToString("  •  "),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.textSecondary,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(16.dp), GlassLevel.SURFACE)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = tokens.accent)
            Text("Play — player arrives in Phase 3", color = tokens.textPrimary)
        }
        Text(item.synopsis, style = MaterialTheme.typography.bodyLarge, color = tokens.textPrimary)
    }
}
