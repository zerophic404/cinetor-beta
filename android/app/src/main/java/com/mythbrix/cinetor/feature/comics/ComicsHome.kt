package com.mythbrix.cinetor.feature.comics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Category
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.RowStyle
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.comics.ComicKind
import com.mythbrix.cinetor.core.ui.CinetorArtwork
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private data class ComicShelfData(
    val id: String,
    val title: String,
    val ranked: Boolean,
    val items: List<Item>,
)

/**
 * Comics hub (`Manga · Manhwa · Manhua`):
 * - Signed-gateway AniList shelves (`Trending`, `Popular`, `Top Rated`, `Recently Updated`) with offline fallback.
 * - Inline Liquid Glass live search across Manga / Manhwa / Manhua.
 * - Full comic title cleanly rendered underneath every cover thumbnail.
 * - Floating "Continue reading" capsule isolated from global Continue Watching.
 */
@Composable
fun ComicsHome(container: AppContainer, contentPadding: PaddingValues, onOpenItem: (Item, String?) -> Unit) {
    val comics = container.comics
    LaunchedEffect(container) {
        comics.attachCatalog(container.comicsCatalog) { container.dataSaver }
    }
    val data by comics.store.data.collectAsStateWithLifecycle()
    val last = remember(data) { data.progress.maxByOrNull { it.updatedAt } }
    val scope = rememberCoroutineScope()
    val tokens = Cinetor.tokens
    val focusManager = LocalFocusManager.current

    var query by rememberSaveable { mutableStateOf("") }
    var submittedQuery by rememberSaveable { mutableStateOf("") }
    var kindFilterName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedKind = remember(kindFilterName) { kindFilterName?.let { runCatching { ComicKind.valueOf(it) }.getOrNull() } }
    var searching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<Item>>(emptyList()) }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            submittedQuery = ""
            searchResults = emptyList()
            searching = false
        } else {
            delay(280)
            submittedQuery = trimmed
        }
    }

    LaunchedEffect(submittedQuery, selectedKind) {
        if (submittedQuery.isEmpty()) return@LaunchedEffect
        searching = true
        val hits = try {
            val remote = container.comicsCatalog.search(submittedQuery, selectedKind)
            if (remote.isNotEmpty()) {
                remote
            } else {
                container.search.searchInSpace(Space.COMICS, submittedQuery).filter { item ->
                    selectedKind == null || item.categoryId.endsWith(selectedKind.name.lowercase())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            runCatching { container.search.searchInSpace(Space.COMICS, submittedQuery) }.getOrDefault(emptyList())
        }
        searchResults = hits
        searching = false
    }

    val continuePad = if (last != null) 72.dp else 0.dp
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(contentPadding.calculateTopPadding()))
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = tokens.textSecondary) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear comics search", tint = tokens.textSecondary)
                        }
                    }
                },
                placeholder = {
                    Text(
                        "Search Manga, Manhwa, Manhua…",
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    submittedQuery = query.trim()
                    focusManager.clearFocus()
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = tokens.accent,
                    focusedTextColor = tokens.textPrimary,
                    unfocusedTextColor = tokens.textPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .glass(RoundedCornerShape(18.dp), GlassLevel.SURFACE)
                    .semantics { contentDescription = "Search comics" },
            )

            if (query.isBlank()) {
                ComicsBrowseContent(
                    container = container,
                    bottomPadding = contentPadding.calculateBottomPadding() + continuePad + 16.dp,
                    onOpenItem = onOpenItem,
                )
            } else {
                ComicsSearchResults(
                    results = searchResults,
                    loading = searching,
                    selectedKind = selectedKind,
                    onSelectKind = { k -> kindFilterName = k?.name },
                    bottomPadding = contentPadding.calculateBottomPadding() + continuePad + 16.dp,
                    onOpen = { item -> onOpenItem(item, null) },
                )
            }
        }

        if (last != null) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = contentPadding.calculateBottomPadding() + 6.dp)
                    .padding(horizontal = 20.dp)
                    .glass(RoundedCornerShape(50), GlassLevel.SURFACE)
                    .clickable {
                        scope.launch {
                            runCatching { container.catalog.item(last.comicId) }.getOrNull()
                                ?.let { comics.openReader(it, last.chapterId) }
                        }
                    }
                    .semantics { contentDescription = "Continue reading" }
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.MenuBook, contentDescription = null, tint = tokens.accent)
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        last.comicTitle.ifBlank { "Continue reading" },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (last.read) "Ch ${last.chapterN} · finished" else "Ch ${last.chapterN} · page ${last.page + 1}/${last.pageCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        maxLines = 1,
                    )
                }
                IconButton(
                    onClick = { comics.store.forgetComic(last.comicId) },
                    modifier = Modifier.size(32.dp).semantics { contentDescription = "Dismiss continue reading" },
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = tokens.textSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ComicsBrowseContent(
    container: AppContainer,
    bottomPadding: Dp,
    onOpenItem: (Item, String?) -> Unit,
) {
    val tokens = Cinetor.tokens
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var shelvesByCategory by remember { mutableStateOf<Map<String, List<ComicShelfData>>>(emptyMap()) }
    var loadingCategories by remember { mutableStateOf(true) }
    var loadingShelves by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(container, retryTick) {
        loadingCategories = true
        loadError = null
        try {
            categories = container.catalog.categories(Space.COMICS)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            loadError = "Could not load comics catalog."
        } finally {
            loadingCategories = false
        }
    }

    val activeCategory = categories.getOrNull(selectedTabIndex.coerceIn(0, (categories.size - 1).coerceAtLeast(0)))

    LaunchedEffect(activeCategory?.id, retryTick) {
        val cat = activeCategory ?: return@LaunchedEffect
        if (shelvesByCategory[cat.id]?.isNotEmpty() == true) return@LaunchedEffect
        loadingShelves = true
        loadError = null
        try {
            val loaded = coroutineScope {
                cat.rows.map { row ->
                    async {
                        val list = runCatching { container.catalog.row(row.id) }.getOrDefault(emptyList())
                        if (list.isEmpty()) {
                            null
                        } else {
                            ComicShelfData(
                                id = row.id,
                                title = row.title,
                                ranked = row.style == RowStyle.TOP10,
                                items = list,
                            )
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            if (loaded.isNotEmpty()) {
                shelvesByCategory = shelvesByCategory + (cat.id to loaded)
            } else {
                loadError = "No titles available right now."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            loadError = "Could not load ${cat.title} shelves."
        } finally {
            loadingShelves = false
        }
    }

    val activeShelves = activeCategory?.let { shelvesByCategory[it.id] }.orEmpty()
    val featuredItem = remember(activeShelves) {
        activeShelves.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.poster.isNotBlank() }
            ?: activeShelves.firstOrNull()?.items?.firstOrNull()
    }

    Column(Modifier.fillMaxSize()) {
        // Segmented Manga · Manhwa · Manhua tabs (preserves exact "Tab Manga", "Tab Manhwa", "Tab Manhua" semantics)
        val tabTitles = if (categories.isNotEmpty()) {
            categories.map { it.title }
        } else {
            ComicKind.entries.map { it.title }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .glass(RoundedCornerShape(16.dp), GlassLevel.SURFACE)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabTitles.forEachIndexed { index, title ->
                val selected = index == selectedTabIndex
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) tokens.accent else Color.Transparent)
                        .clickable { selectedTabIndex = index }
                        .semantics { contentDescription = "Tab $title" }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) tokens.onAccent else tokens.textPrimary,
                        maxLines = 1,
                    )
                }
            }
        }

        when {
            (loadingCategories || loadingShelves) && activeShelves.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = tokens.accent)
                }
            }
            loadError != null && activeShelves.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(loadError.orEmpty(), color = tokens.textSecondary, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = {
                            shelvesByCategory = emptyMap()
                            retryTick += 1
                        }) {
                            Text("Retry", color = tokens.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = bottomPadding),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (featuredItem != null) {
                        item(key = "featured-${featuredItem.id}") {
                            ComicFeaturedBanner(
                                item = featuredItem,
                                onOpen = { onOpenItem(featuredItem, null) },
                            )
                        }
                    }

                    items(activeShelves, key = { it.id }) { shelf ->
                        ComicShelfSection(
                            shelf = shelf,
                            onOpen = { item -> onOpenItem(item, null) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComicFeaturedBanner(
    item: Item,
    onOpen: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(20.dp)
    val kindLabel = comicBadgeLabel(item)

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(228.dp)
            .clip(shape)
            .background(tokens.surfaceElevated)
            .border(1.dp, tokens.glassStroke, shape)
            .clickable(onClick = onOpen)
            .semantics { contentDescription = "Featured ${item.title}" },
    ) {
        CinetorArtwork(
            seedKey = item.id,
            path = item.backdrop.ifBlank { item.poster },
            width = 380.dp,
            height = 228.dp,
            large = true,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.86f),
                        0.62f to Color.Black.copy(alpha = 0.68f),
                        1f to Color.Black.copy(alpha = 0.38f),
                    ),
                ),
        )
        Row(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(106.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.skeletonBase)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
            ) {
                CinetorArtwork(
                    seedKey = item.id,
                    path = item.poster,
                    width = 106.dp,
                    height = 159.dp,
                    fallbackTitle = item.title,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = kindLabel.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = tokens.onAccent,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(tokens.accent)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    if (item.rating > 0.0) {
                        Text(
                            text = "★ %.1f".format(Locale.US, item.rating),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFFD54F),
                        )
                    }
                    if (!item.artist.isNullOrBlank()) {
                        Text(
                            text = "· ${item.artist}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f),
                            maxLines = 1,
                        )
                    }
                }

                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (item.synopsis.isNotBlank()) {
                    Text(
                        text = item.synopsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (item.genres.isNotEmpty()) {
                    Text(
                        text = item.genres.take(3).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(2.dp))

                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                        .border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.AutoStories,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = "Open Chapters",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComicShelfSection(
    shelf: ComicShelfData,
    onOpen: (Item) -> Unit,
) {
    val tokens = Cinetor.tokens
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = shelf.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = tokens.textPrimary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(shelf.items, key = { _, item -> "${shelf.id}:${item.id}" }) { idx, item ->
                ComicPosterCard(
                    item = item,
                    rank = if (shelf.ranked) idx + 1 else null,
                    onClick = { onOpen(item) },
                    modifier = Modifier.width(128.dp),
                )
            }
        }
    }
}

@Composable
private fun ComicsSearchResults(
    results: List<Item>,
    loading: Boolean,
    selectedKind: ComicKind?,
    onSelectKind: (ComicKind?) -> Unit,
    bottomPadding: Dp,
    onOpen: (Item) -> Unit,
) {
    val tokens = Cinetor.tokens
    val wide = LocalConfiguration.current.smallestScreenWidthDp >= 600
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedKind == null,
                onClick = { onSelectKind(null) },
                label = { Text("All") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = tokens.accent,
                    selectedLabelColor = tokens.onAccent,
                ),
            )
            ComicKind.entries.forEach { kind ->
                FilterChip(
                    selected = selectedKind == kind,
                    onClick = { onSelectKind(if (selectedKind == kind) null else kind) },
                    label = { Text(kind.title) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tokens.accent,
                        selectedLabelColor = tokens.onAccent,
                    ),
                )
            }
        }
        if (loading) {
            LinearProgressIndicator(
                color = tokens.accent,
                trackColor = tokens.divider,
                modifier = Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 16.dp),
            )
        }
        when {
            results.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = tokens.accent)
            }
            results.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No matching comics found. Try another title or switch between Manga, Manhwa and Manhua.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSecondary,
                )
            }
            else -> LazyVerticalGrid(
                columns = if (wide) GridCells.Adaptive(minSize = 148.dp) else GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(results, key = { it.id }) { item ->
                    ComicPosterCard(
                        item = item,
                        rank = null,
                        onClick = { onOpen(item) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Shared Comics thumbnail card used across every browse shelf and search grid:
 * renders crisp 2:3 cover art + format/rank/rating pills + visible 2-line title underneath.
 */
@Composable
private fun ComicPosterCard(
    item: Item,
    rank: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(12.dp)
    val badge = comicBadgeLabel(item)

    Column(
        modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = item.title },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(tokens.skeletonBase)
                .border(1.dp, tokens.glassStroke, shape),
        ) {
            CinetorArtwork(
                seedKey = item.id,
                path = item.poster,
                width = 128.dp,
                height = 192.dp,
                fallbackTitle = item.title,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))),
            )
            Text(
                text = if (rank != null) "#$rank" else badge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (rank != null) tokens.onAccent else Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(if (rank != null) tokens.accent else Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            if (item.rating > 0.0) {
                Text(
                    text = "★ %.1f".format(Locale.US, item.rating),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFFD54F),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.textPrimary,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        val sub = item.genres.firstOrNull() ?: item.artist ?: item.year.takeIf { it > 0 }?.toString()
        if (!sub.isNullOrBlank()) {
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun comicBadgeLabel(item: Item): String = when {
    item.categoryId.endsWith("manhwa") -> "Manhwa"
    item.categoryId.endsWith("manhua") -> "Manhua"
    else -> "Manga"
}
