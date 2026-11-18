package com.mythbrix.cinetor.feature.comics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Chapter
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.ui.CinetorArtwork
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.SkeletonChapterList
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.design.hexColor
import kotlinx.coroutines.CancellationException

private sealed interface ComicLoad {
    data object Loading : ComicLoad
    data class Ready(val item: Item) : ComicLoad
    data class Error(val message: String) : ComicLoad
}

/** "Start reading" / "Continue Ch N · p M" target for a comic. */
data class ContinueTarget(val chapterId: String, val label: String)

fun continueTarget(item: Item, store: ComicsData): ContinueTarget? {
    val first = item.pages.firstOrNull() ?: return null
    val latest = store.progress.filter { it.comicId == item.id }.maxByOrNull { it.updatedAt }
        ?: return ContinueTarget(first.id, "Start reading")
    val idx = item.pages.indexOfFirst { it.id == latest.chapterId }
    if (idx < 0) return ContinueTarget(first.id, "Start reading")
    return if (latest.read) {
        val next = item.pages.getOrNull(idx + 1) ?: return ContinueTarget(item.pages[idx].id, "Read again · Ch ${item.pages[idx].n}")
        ContinueTarget(next.id, "Continue · Ch ${next.n}")
    } else {
        ContinueTarget(latest.chapterId, "Continue · Ch ${item.pages[idx].n} · p${latest.page + 1}")
    }
}

/** Comic details: cover, synopsis, genres, bookmark, continue reading, sorted/filtered chapter list with progress badges. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComicDetails(id: String, container: AppContainer, contentPadding: PaddingValues) {
    val comics = container.comics
    LaunchedEffect(container) {
        comics.attachCatalog(container.comicsCatalog) { container.dataSaver }
    }
    var reloadTick by remember { mutableIntStateOf(0) }
    var load by remember(id) { mutableStateOf<ComicLoad>(ComicLoad.Loading) }
    val peek = remember(id) { container.peek.get(id) }
    LaunchedEffect(id, reloadTick) {
        load = ComicLoad.Loading
        load = try {
            ComicLoad.Ready(container.catalog.item(id))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ComicLoad.Error("Could not load comic details.")
        }
    }

    val data by comics.store.data.collectAsStateWithLifecycle()
    val dl by comics.downloads.states.collectAsStateWithLifecycle()
    val tokens = Cinetor.tokens
    val tint = Cinetor.tint
    val header = (load as? ComicLoad.Ready)?.item ?: peek
    LaunchedEffect(header?.dominantColor) { header?.let { tint.setSource(hexColor(it.dominantColor)) } }

    var sortName by rememberSaveable(id) { mutableStateOf(ChapterSort.ASCENDING.name) }
    var filterName by rememberSaveable(id) { mutableStateOf(ChapterFilter.ALL.name) }
    var chapterQuery by rememberSaveable(id) { mutableStateOf("") }
    var synopsisExpanded by rememberSaveable(id) { mutableStateOf(false) }

    val sort = runCatching { ChapterSort.valueOf(sortName) }.getOrDefault(ChapterSort.ASCENDING)
    val filter = runCatching { ChapterFilter.valueOf(filterName) }.getOrDefault(ChapterFilter.ALL)

    val readyItem = (load as? ComicLoad.Ready)?.item
    val summary = remember(readyItem, data) {
        if (readyItem == null) null else comicReadSummary(readyItem.pages, data, readyItem.id)
    }
    val downloadedIds = remember(dl) {
        dl.entries.asSequence().filter { it.value == ChapterDownload.Done }.map { it.key }.toSet()
    }
    val progressByChapter = remember(data, readyItem?.id) {
        val targetId = readyItem?.id ?: return@remember emptyMap()
        data.progress.asSequence().filter { it.comicId == targetId }.associateBy { it.chapterId }
    }
    val shownChapters = remember(readyItem?.pages, data, readyItem?.id, downloadedIds, chapterQuery, sort, filter) {
        val targetItem = readyItem ?: return@remember emptyList()
        filterAndSortChapters(
            chapters = targetItem.pages,
            data = data,
            comicId = targetItem.id,
            downloadedIds = downloadedIds,
            query = chapterQuery,
            sort = sort,
            filter = filter,
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            ComicHeader(header, summary, contentPadding)
        }
        when (val l = load) {
            is ComicLoad.Error -> item(key = "error") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .glass(RoundedCornerShape(18.dp), GlassLevel.SURFACE)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(l.message, color = tokens.textSecondary, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { reloadTick += 1 }) {
                        Text("Retry", color = tokens.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            ComicLoad.Loading -> item(key = "skeleton") {
                SkeletonChapterList(Modifier.padding(top = 16.dp), count = 6)
            }
            is ComicLoad.Ready -> {
                val item = l.item
                val target = continueTarget(item, data)
                val bookmarked = item.id in data.bookmarks

                item(key = "actions") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { if (item.pages.isNotEmpty()) comics.openReader(item, target?.chapterId) },
                            enabled = item.pages.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tokens.accent,
                                contentColor = tokens.onAccent,
                            ),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Read" },
                        ) {
                            Icon(Icons.Rounded.MenuBook, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (item.pages.isEmpty()) "No chapters yet" else (target?.label ?: "Read"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = { comics.store.toggleBookmark(item.id) },
                            modifier = Modifier.semantics { contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark" },
                        ) {
                            Icon(
                                if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                contentDescription = null,
                                tint = if (bookmarked) tokens.accent else tokens.textPrimary,
                            )
                        }
                    }
                }

                if (item.synopsis.isNotBlank() || item.genres.isNotEmpty()) {
                    item(key = "synopsis") {
                        Column(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (item.synopsis.isNotBlank()) {
                                Text(
                                    item.synopsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = tokens.textSecondary,
                                    maxLines = if (synopsisExpanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (item.synopsis.length > 160) {
                                    Text(
                                        if (synopsisExpanded) "Show less" else "Show more",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = tokens.accent,
                                        modifier = Modifier.clickable { synopsisExpanded = !synopsisExpanded },
                                    )
                                }
                            }
                            if (item.genres.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    item.genres.forEach { g ->
                                        Text(
                                            g,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = tokens.textPrimary,
                                            modifier = Modifier
                                                .glass(RoundedCornerShape(50), GlassLevel.SURFACE)
                                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "chapters-controls") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (shownChapters.size == item.pages.size) {
                                        "${item.pages.size} chapters"
                                    } else {
                                        "${shownChapters.size} of ${item.pages.size} chapters"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = tokens.textPrimary,
                                )
                                Text(
                                    (comics.store.modeOverride(item.id) ?: defaultReaderMode(item)).label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.textSecondary,
                                )
                            }
                            if (item.pages.size > 1) {
                                Row(
                                    Modifier
                                        .glass(CircleShape, GlassLevel.SURFACE)
                                        .clickable {
                                            sortName = if (sort == ChapterSort.ASCENDING) {
                                                ChapterSort.DESCENDING.name
                                            } else {
                                                ChapterSort.ASCENDING.name
                                            }
                                        }
                                        .semantics { contentDescription = "Sort chapters" }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.Sort,
                                        contentDescription = null,
                                        tint = tokens.accent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        sort.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = tokens.textPrimary,
                                    )
                                }
                            }
                        }

                        if (item.pages.isNotEmpty()) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ChapterFilter.entries.forEach { entry ->
                                    FilterChip(
                                        selected = filter == entry,
                                        onClick = { filterName = entry.name },
                                        label = { Text(entry.label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = tokens.accent,
                                            selectedLabelColor = tokens.onAccent,
                                        ),
                                    )
                                }
                            }
                        }

                        if (item.pages.size > 8) {
                            TextField(
                                value = chapterQuery,
                                onValueChange = { chapterQuery = it.take(30) },
                                singleLine = true,
                                leadingIcon = {
                                    Icon(Icons.Rounded.Search, contentDescription = null, tint = tokens.textSecondary)
                                },
                                trailingIcon = {
                                    if (chapterQuery.isNotEmpty()) {
                                        IconButton(onClick = { chapterQuery = "" }) {
                                            Icon(Icons.Rounded.Close, contentDescription = "Clear chapter filter", tint = tokens.textSecondary)
                                        }
                                    }
                                },
                                placeholder = { Text("Jump to chapter…", color = tokens.textSecondary) },
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
                                    .glass(RoundedCornerShape(14.dp), GlassLevel.SURFACE),
                            )
                        }
                    }
                }

                if (item.pages.isEmpty()) {
                    item(key = "chapters-empty") {
                        Text(
                            "No readable English chapters are available for this title right now.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.textSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                } else if (shownChapters.isEmpty()) {
                    item(key = "chapters-filtered-empty") {
                        Text(
                            "No chapters match the current filter.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.textSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                } else {
                    items(shownChapters, key = { it.id }) { ch ->
                        ChapterRow(
                            chapter = ch,
                            progress = progressByChapter[ch.id],
                            download = dl[ch.id],
                            onOpen = { comics.openReader(item, ch.id) },
                            onDownload = { comics.downloads.download(item, ch) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComicHeader(item: Item?, summary: ComicReadSummary?, contentPadding: PaddingValues) {
    val tokens = Cinetor.tokens
    Box(Modifier.fillMaxWidth().height(340.dp)) {
        if (item != null) {
            CinetorArtwork(
                seedKey = item.id,
                path = item.backdrop.ifBlank { item.poster },
                width = 412.dp,
                height = 340.dp,
                large = true,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.38f),
                    0.5f to tokens.bg.copy(alpha = 0.52f),
                    1f to tokens.bg,
                ),
            ),
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .padding(top = contentPadding.calculateTopPadding()),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier
                    .width(112.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tokens.skeletonBase)
                    .border(1.dp, tokens.glassStroke, RoundedCornerShape(14.dp)),
            ) {
                if (item != null) {
                    CinetorArtwork(
                        seedKey = item.id,
                        path = item.poster,
                        width = 112.dp,
                        height = 168.dp,
                        contentDescription = item.title,
                        fallbackTitle = item.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    item?.title ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.textPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                item?.altTitle?.takeIf { it.isNotBlank() && !it.equals(item.title, ignoreCase = true) }?.let { alt ->
                    Text(
                        alt,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item != null) {
                    val metaParts = buildList {
                        add(categoryLabel(item))
                        if (!item.artist.isNullOrBlank()) add(item.artist)
                        if (item.year > 0) add(item.year.toString())
                        if (item.rating > 0.0) add("★ %.1f".format(java.util.Locale.US, item.rating))
                        if (item.maturity.isNotBlank() && item.maturity != "All") add(item.maturity)
                    }
                    Text(
                        metaParts.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.textSecondary,
                    )
                }
                summary?.badgeLabel?.let { badge ->
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.onAccent,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(tokens.accent)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private fun categoryLabel(item: Item): String = when {
    item.categoryId.endsWith("manga") -> "Manga"
    item.categoryId.endsWith("manhwa") -> "Manhwa"
    item.categoryId.endsWith("manhua") -> "Manhua"
    else -> "Comic"
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    progress: ChapterProgress?,
    download: ChapterDownload?,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val isRead = progress?.read == true
    val isInProgress = progress != null && !progress.read
    val badgeBg = when {
        isRead -> tokens.success.copy(alpha = 0.16f)
        isInProgress -> tokens.accent.copy(alpha = 0.16f)
        else -> tokens.glassFill
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .glass(RoundedCornerShape(16.dp), GlassLevel.SURFACE)
            .clickable(onClick = onOpen)
            .semantics { contentDescription = "Chapter ${chapter.n}" }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = chapter.pages.firstOrNull()
        Box(
            Modifier
                .size(44.dp, 56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(badgeBg),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                AsyncImage(
                    model = rememberSizedImage(thumb, 44.dp, 56.dp),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    if (chapter.n > 0) "#${chapter.n}" else "•",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        isRead -> tokens.success
                        isInProgress -> tokens.accent
                        else -> tokens.textSecondary
                    },
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                chapter.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = if (isRead) tokens.textSecondary else tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isRead) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = tokens.success,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    chapterStatusBadge(chapter, progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isInProgress) tokens.accent else tokens.textSecondary,
                )
                if (download == ChapterDownload.Done) {
                    Text(
                        "· Saved offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.success,
                    )
                }
            }
            if (isInProgress && progress != null) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    color = tokens.accent,
                    trackColor = tokens.divider,
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
        }
        ChapterDownloadIcon(download, onDownload)
    }
}
