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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.comics.ComicKind
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.feature.home.SpaceScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Comics hub (`Manga · Manhwa · Manhua`):
 * - Inline Liquid Glass live search across MangaDex + local offline fallback, filterable by kind.
 * - Dedicated Manga / Manhwa / Manhua tabs & shelves via [SpaceScreen].
 * - Floating Liquid Glass "Continue reading" capsule isolated from global Continue Watching.
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

    val continuePad = if (last != null) 68.dp else 0.dp
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
                SpaceScreen(
                    space = Space.COMICS,
                    container = container,
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + continuePad,
                    ),
                    onOpenItem = onOpenItem,
                )
            } else {
                ComicsSearchResults(
                    results = searchResults,
                    loading = searching,
                    selectedKind = selectedKind,
                    onSelectKind = { k -> kindFilterName = k?.name },
                    bottomPadding = contentPadding.calculateBottomPadding() + continuePad,
                    onOpen = { item -> onOpenItem(item, null) },
                )
            }
        }

        if (last != null) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = contentPadding.calculateBottomPadding())
                    .padding(horizontal = 20.dp)
                    .glass(RoundedCornerShape(50), GlassLevel.BAR)
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
private fun ComicsSearchResults(
    results: List<Item>,
    loading: Boolean,
    selectedKind: ComicKind?,
    onSelectKind: (ComicKind?) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
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
                    ComicSearchCard(item = item, onClick = { onOpen(item) })
                }
            }
        }
    }
}

@Composable
private fun ComicSearchCard(item: Item, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(12.dp)
    val badge = when {
        item.categoryId.endsWith("manhwa") -> "Manhwa"
        item.categoryId.endsWith("manhua") -> "Manhua"
        else -> "Manga"
    }
    Column(
        Modifier
            .fillMaxWidth()
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
            AsyncImage(
                model = rememberSizedImage(item.poster, 124.dp, 186.dp),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
            )
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Text(
            item.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
