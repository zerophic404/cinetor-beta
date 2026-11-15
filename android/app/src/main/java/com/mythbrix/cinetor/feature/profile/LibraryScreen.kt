package com.mythbrix.cinetor.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import kotlinx.coroutines.launch

/** Library of the active profile (Task 5.1): My List (watchlist) + watch history (video spaces only). */
@Composable
fun LibraryScreen(
    manager: ProfileManager,
    contentPadding: PaddingValues,
    onOpen: (String) -> Unit,
    onClearHistory: suspend () -> Unit = { manager.clearHistory() },
) {
    val tokens = Cinetor.tokens
    val scope = rememberCoroutineScope()
    val profile by manager.active.collectAsStateWithLifecycle()
    val watchlist by manager.watchlist.collectAsStateWithLifecycle()
    val history by manager.history.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tiles: List<LibraryTile> = (if (tab == 0) {
        watchlist.map { LibraryTile(it.itemId, it.title, it.poster, null, null) }
    } else {
        history.filter { it.space.isVideo }.map {
            LibraryTile(it.itemId, it.title, it.poster, it.episodeLabel, it.progress().takeIf { f -> f > 0f && !it.isFinished() })
        }
    }).distinctBy { it.id }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProfileAvatar(profile, 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text("Library", style = MaterialTheme.typography.headlineSmall, color = tokens.textPrimary)
                        Text(profile.name, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
                    }
                    if (tab == 1 && history.isNotEmpty()) {
                        TextButton(onClick = { scope.launch { onClearHistory() } }) { Text("Clear", color = tokens.accent) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("My List (${watchlist.size})", "History (${history.size})").forEachIndexed { i, label ->
                        FilterChip(
                            selected = tab == i,
                            onClick = { tab = i },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = tokens.accent, selectedLabelColor = tokens.onAccent),
                            modifier = Modifier.semantics { contentDescription = if (i == 0) "Library My List" else "Library History" },
                        )
                    }
                }
            }
        }
        if (tiles.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (tab == 0) "Nothing in My List yet — tap + on any title." else "Nothing watched yet on this profile.",
                    color = tokens.textSecondary,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(tiles, key = { it.id }) { t -> LibraryCard(t) { onOpen(t.id) } }
    }
}

private data class LibraryTile(val id: String, val title: String, val poster: String, val label: String?, val progress: Float?)

@Composable
private fun LibraryCard(tile: LibraryTile, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).semantics { contentDescription = tile.title },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).glass(RoundedCornerShape(12.dp), GlassLevel.SURFACE)) {
            if (tile.poster.isNotBlank()) {
                AsyncImage(
                    model = rememberSizedImage(tile.poster, 110.dp, 165.dp),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            tile.progress?.let { f ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(tokens.textPrimary.copy(alpha = 0.25f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(f).height(3.dp).background(tokens.accent))
            }
        }
        Text(tile.title, color = tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        tile.label?.let { Text(it, color = tokens.textTertiary, style = MaterialTheme.typography.labelSmall) }
    }
}
