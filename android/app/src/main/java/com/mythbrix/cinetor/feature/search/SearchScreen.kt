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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.AssetUris
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.design.hexColor

/**
 * Search. [space] = null → global search over video spaces only (local index + TMDB merge);
 * otherwise in-space search of that space (own index + own history bucket).
 */
@Composable
fun SearchScreen(
    space: Space?,
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenItem: (String) -> Unit,
) {
    val profileId = container.activeProfileId
    val vm: SearchViewModel = viewModel(
        key = "search-${space?.name ?: "global"}-$profileId",
        factory = viewModelFactory {
            initializer { SearchViewModel(container.searchEngine, container.searchHistory, space, profileId) }
        },
    )
    val tokens = Cinetor.tokens
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    // Voice search (TV remotes' mic button / phones): system speech recognizer → query.
    val context = androidx.compose.ui.platform.LocalContext.current
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { r ->
        val text = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) { vm.onQueryChange(text); vm.submit() }
    }
    val voiceIntent = remember {
        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search Cinetor")
    }
    val voiceAvailable = remember { voiceIntent.resolveActivity(context.packageManager) != null }

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        TextField(
            value = query,
            onValueChange = vm::onQueryChange,
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.onQueryChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear")
                    }
                } else if (voiceAvailable) {
                    IconButton(onClick = { runCatching { voice.launch(voiceIntent) } }) {
                        Icon(Icons.Rounded.Mic, contentDescription = "Voice search")
                    }
                }
            },
            placeholder = {
                Text(if (space == null) "Search movies, series, anime, drama" else "Search ${space.displayName}")
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.submit() }),
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
        Box(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 24.dp)) {
            if (results.remoteLoading && query.isNotBlank()) {
                LinearProgressIndicator(color = tokens.accent, modifier = Modifier.fillMaxWidth())
            }
        }
        val bottom = contentPadding.calculateBottomPadding()
        when {
            query.isBlank() -> RecentList(
                recent = recent,
                bottom = bottom,
                onPick = { vm.onQueryChange(it) },
                onRemove = vm::removeRecent,
                onClear = vm::clearRecent,
            )
            results.items.isEmpty() && !results.remoteLoading && results.query == query.trim() -> Text(
                "No results",
                color = tokens.textSecondary,
                modifier = Modifier.padding(24.dp),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, bottom),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(suggestions, key = { "s-$it" }, contentType = { "suggestion" }) { s ->
                    HistoryRow(s, onClick = { vm.onQueryChange(s) }, onRemove = null)
                }
                items(results.items, key = { it.id }, contentType = { "result" }) { item ->
                    ResultRow(item) {
                        vm.submit()
                        onOpenItem(item.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentList(
    recent: List<String>,
    bottom: androidx.compose.ui.unit.Dp,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    val tokens = Cinetor.tokens
    if (recent.isEmpty()) return
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, bottom)) {
        item(key = "header", contentType = "header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Recent searches",
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) { Text("Clear all", color = tokens.accent) }
            }
        }
        items(recent, key = { "r-$it" }, contentType = { "recent" }) { q ->
            HistoryRow(q, onClick = { onPick(q) }, onRemove = { onRemove(q) })
        }
    }
}

@Composable
private fun HistoryRow(text: String, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    val tokens = Cinetor.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.History, contentDescription = null, tint = tokens.textSecondary)
        Text(text, color = tokens.textPrimary, maxLines = 1, modifier = Modifier.weight(1f))
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = tokens.textSecondary)
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
