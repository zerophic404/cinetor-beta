package com.mythbrix.cinetor.feature.search

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.RemoteSearch
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.ui.posterKey
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.SkeletonBox
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.feature.home.PosterCard
import com.mythbrix.cinetor.feature.home.rememberTintFeeder

/**
 * Global (`space == null`) or in-space Search screen:
 * - Debounced (`280ms`, `>= 2` chars) hybrid local + remote catalog search (`Bigg Boss`, `Doctor X White Mafia`, etc.).
 * - Never flashes premature `"No titles found"` while debounce or remote resolution is in flight.
 * - Displays responsive poster thumbnail cards with visible 1–2 line titles underneath every card,
 *   seeding `container.peek` on tap so `DetailsScreen` opens & plays immediately.
 */
@Composable
fun SearchScreen(
    space: Space?,
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenItem: (String) -> Unit,
) {
    val profileId = container.activeProfileId
    val remoteForSpace: RemoteSearch? = remember(space, container) {
        when (space) {
            null -> container.remoteSearch
            Space.COMICS -> container.comicsCatalog as? RemoteSearch
            Space.ADULT -> container.gateway
            else -> null
        }
    }
    val vm: SearchViewModel = viewModel(
        key = "search-${space?.name ?: "global"}-$profileId",
        factory = viewModelFactory {
            initializer {
                SearchViewModel(
                    engine = container.searchEngine,
                    history = container.searchHistory,
                    space = space,
                    profileId = profileId,
                    localRepo = container.search,
                    remoteSearch = remoteForSpace,
                )
            }
        },
    )
    val tokens = Cinetor.tokens
    val feeder = rememberTintFeeder()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val voice = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            vm.onQueryChange(text)
            vm.submit(text)
        }
    }
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search Cinetor")
    }
    val voiceAvailable = remember { voiceIntent.resolveActivity(context.packageManager) != null }

    val trimmedQuery = query.trim()
    val isPendingRemote = trimmedQuery.length >= SearchViewModel.MIN_REMOTE_CHARS &&
        (results.remoteLoading || results.query != trimmedQuery)

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        TextField(
            value = query,
            onValueChange = vm::onQueryChange,
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = tokens.textSecondary) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.onQueryChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = tokens.textSecondary)
                    }
                } else if (voiceAvailable) {
                    IconButton(onClick = { runCatching { voice.launch(voiceIntent) } }) {
                        Icon(Icons.Rounded.Mic, contentDescription = "Voice search", tint = tokens.textSecondary)
                    }
                }
            },
            placeholder = {
                Text(
                    text = if (space == null) "Search movies, series, anime, drama" else "Search ${space.displayName}",
                    color = tokens.textSecondary,
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.submit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = tokens.textPrimary,
                unfocusedTextColor = tokens.textPrimary,
                cursorColor = tokens.accent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .glass(RoundedCornerShape(18.dp), GlassLevel.SURFACE),
        )
        Box(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 24.dp)) {
            if (isPendingRemote) {
                LinearProgressIndicator(
                    color = tokens.accent,
                    trackColor = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        val bottom = contentPadding.calculateBottomPadding()
        when {
            trimmedQuery.isEmpty() -> RecentList(
                recent = recent,
                bottom = bottom,
                onPick = { picked ->
                    vm.onQueryChange(picked)
                    vm.submit(picked)
                },
                onRemove = vm::removeRecent,
                onClear = vm::clearRecent,
            )

            results.items.isEmpty() && isPendingRemote -> SearchGridSkeleton(bottom = bottom)

            results.items.isEmpty() && trimmedQuery.length < SearchViewModel.MIN_REMOTE_CHARS -> Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = "Keep typing to search the full catalog",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSecondary,
                )
            }

            results.items.isEmpty() && !isPendingRemote && results.query == trimmedQuery -> SearchEmptyState(
                remoteError = results.remoteError,
                onRetry = vm::retry,
            )

            else -> SearchResultsGrid(
                items = results.items,
                suggestions = suggestions,
                bottom = bottom,
                onSuggestionClick = { s ->
                    vm.onQueryChange(s)
                    vm.submit(s)
                },
                onTint = feeder::offer,
                onItemClick = { item ->
                    container.peek.put(item)
                    vm.submit()
                    onOpenItem(item.id)
                },
            )
        }
    }
}

@Composable
private fun SearchResultsGrid(
    items: List<Item>,
    suggestions: List<String>,
    bottom: Dp,
    onSuggestionClick: (String) -> Unit,
    onTint: (Color) -> Unit,
    onItemClick: (Item) -> Unit,
) {
    val tokens = Cinetor.tokens
    BoxWithConstraints(Modifier.fillMaxSize()) {
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
            contentPadding = PaddingValues(
                start = hPad,
                end = hPad,
                top = 10.dp,
                bottom = bottom + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (suggestions.isNotEmpty()) {
                item(key = "suggestions-bar", span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(suggestions, key = { "sug-$it" }) { sug ->
                            val chipShape = RoundedCornerShape(CinetorTokens.Shape.pill)
                            Text(
                                text = sug,
                                style = MaterialTheme.typography.labelMedium,
                                color = tokens.textPrimary,
                                modifier = Modifier
                                    .clip(chipShape)
                                    .background(tokens.textPrimary.copy(alpha = 0.07f))
                                    .border(1.dp, tokens.textPrimary.copy(alpha = 0.20f), chipShape)
                                    .clickable { onSuggestionClick(sug) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            items(items, key = { it.id }, contentType = { "search-poster" }) { item ->
                val key = posterKey("search", item.id)
                PosterCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onActive = onTint,
                    width = cardWidth,
                    sharedKey = key,
                )
            }
        }
    }
}

@Composable
private fun SearchGridSkeleton(bottom: Dp) {
    ShimmerHost {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cols = when {
                maxWidth >= 840.dp -> 6
                maxWidth >= 600.dp -> 4
                else -> 3
            }
            val spacing = 10.dp
            val hPad = 16.dp
            val cardWidth = ((maxWidth - hPad * 2 - spacing * (cols - 1)) / cols).coerceAtLeast(88.dp)
            val cardHeight = cardWidth * 1.5f

            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                userScrollEnabled = false,
                contentPadding = PaddingValues(start = hPad, end = hPad, top = 12.dp, bottom = bottom + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(cols * 2) {
                    Column {
                        SkeletonBox(Modifier.fillMaxWidth().height(cardHeight), RoundedCornerShape(CinetorTokens.Shape.card))
                        Spacer(Modifier.height(6.dp))
                        SkeletonBox(Modifier.fillMaxWidth(0.82f).height(13.dp), RoundedCornerShape(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchEmptyState(remoteError: Boolean, onRetry: () -> Unit) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(CinetorTokens.Shape.lg)
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 36.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .glass(shape, GlassLevel.SURFACE)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (remoteError) "Search couldn't reach the catalog" else "No titles found",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tokens.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (remoteError) "Check your connection and try again." else "Try another title, actor, or keyword.",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (remoteError) {
                Spacer(Modifier.height(14.dp))
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
}

@Composable
private fun RecentList(
    recent: List<String>,
    bottom: Dp,
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
