package com.mythbrix.cinetor.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.AssetUris
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.SkeletonChapterList
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.design.hexColor
import kotlinx.coroutines.delay

/**
 * Placeholder search (Task 2.3 adds history, FTS, TMDB). [space] = null → global search over
 * video spaces only; otherwise in-space search of that isolated space.
 */
@Composable
fun SearchScreen(
    space: Space?,
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenItem: (String) -> Unit,
) {
    val tokens = Cinetor.tokens
    var query by rememberSaveable(space) { mutableStateOf("") }
    var results by remember(space) { mutableStateOf<List<Item>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query, space) {
        if (query.isBlank()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(250)
        results = if (space == null) container.search.search(query) else container.search.searchInSpace(space, query)
        searching = false
    }
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        TextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            placeholder = {
                Text(if (space == null) "Search movies, series, anime, drama" else "Search ${space.displayName}")
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = tokens.accent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .glass(RoundedCornerShape(18.dp), GlassLevel.SURFACE),
        )
        when {
            searching && results.isEmpty() -> ShimmerHost { SkeletonChapterList(Modifier.padding(top = 16.dp), count = 6) }
            query.isNotBlank() && results.isEmpty() -> Text(
                "No results",
                color = tokens.textSecondary,
                modifier = Modifier.padding(24.dp),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, contentPadding.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(results, key = { it.id }, contentType = { "result" }) { item ->
                    ResultRow(item) { onOpenItem(item.id) }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(item: Item, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp, 72.dp).clip(RoundedCornerShape(8.dp)).background(hexColor(item.dominantColor)),
        ) {
            AsyncImage(
                model = AssetUris.of(item.poster),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, color = tokens.textPrimary, maxLines = 1)
            Text(
                "${item.space.displayName} · ${item.year} · ${item.genres.firstOrNull().orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }
    }
}
