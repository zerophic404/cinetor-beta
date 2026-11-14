package com.mythbrix.cinetor.feature.comics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.mythbrix.cinetor.core.data.AssetUris
import com.mythbrix.cinetor.core.data.Chapter
import com.mythbrix.cinetor.core.data.Item
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

/** Comic details: cover, synopsis, genres, bookmark, continue reading, chapter list (skeleton while loading). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComicDetails(id: String, container: AppContainer, contentPadding: PaddingValues) {
    val comics = container.comics
    // Always starts in Loading: header from the card peek, chapter list = SkeletonChapterList until the item resolves.
    var load by remember(id) { mutableStateOf<ComicLoad>(ComicLoad.Loading) }
    val peek = remember(id) { container.peek.get(id) }
    LaunchedEffect(id) {
        load = try {
            ComicLoad.Ready(container.catalog.item(id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ComicLoad.Error(e.message ?: "Not found")
        }
    }
    val data by comics.store.data.collectAsStateWithLifecycle()
    val dl by comics.downloads.states.collectAsStateWithLifecycle()
    val tokens = Cinetor.tokens
    val tint = Cinetor.tint
    val header = (load as? ComicLoad.Ready)?.item ?: peek
    LaunchedEffect(header?.dominantColor) { header?.let { tint.setSource(hexColor(it.dominantColor)) } }

    LazyColumn(
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") { ComicHeader(header, contentPadding) }
        when (val l = load) {
            is ComicLoad.Error -> item(key = "error") {
                Text("Comic not found ($id)", color = tokens.textSecondary, modifier = Modifier.padding(24.dp))
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
                            onClick = { comics.openReader(item, target?.chapterId) },
                            colors = ButtonDefaults.buttonColors(containerColor = tokens.accent),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Read" },
                        ) {
                            Icon(Icons.Rounded.MenuBook, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(target?.label ?: "Read", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                item(key = "synopsis") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(item.synopsis, style = MaterialTheme.typography.bodyMedium, color = tokens.textSecondary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                item(key = "chapters-title") {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${item.pages.size} chapters",
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            (comics.store.modeOverride(item.id) ?: defaultReaderMode(item)).label,
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.textSecondary,
                        )
                    }
                }
                items(item.pages, key = { it.id }) { ch ->
                    ChapterRow(
                        chapter = ch,
                        progress = data.progress.firstOrNull { it.comicId == item.id && it.chapterId == ch.id },
                        download = dl[ch.id],
                        onOpen = { comics.openReader(item, ch.id) },
                        onDownload = { comics.downloads.download(item, ch) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ComicHeader(item: Item?, contentPadding: PaddingValues) {
    val tokens = Cinetor.tokens
    Box(Modifier.fillMaxWidth().height(340.dp)) {
        if (item != null) {
            AsyncImage(
                model = AssetUris.of(item.backdrop),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent, tokens.bg)),
            ),
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(16.dp).padding(top = contentPadding.calculateTopPadding()),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier
                    .width(112.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.skeletonBase),
            ) {
                if (item != null) {
                    AsyncImage(
                        model = rememberSizedImage(item.poster, 112.dp, 168.dp),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item?.title ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.textPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                item?.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.textSecondary) }
                if (item != null) {
                    Text(
                        "${item.year} · ★ ${item.rating} · ${categoryLabel(item)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.textSecondary,
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
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .semantics { contentDescription = "Chapter ${chapter.n}" }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp, 64.dp).clip(RoundedCornerShape(8.dp)).background(tokens.skeletonBase)) {
            chapter.pages.firstOrNull()?.let { p ->
                AsyncImage(
                    model = rememberSizedImage(p, 48.dp, 64.dp),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(chapter.title, style = MaterialTheme.typography.titleSmall, color = tokens.textPrimary)
            val sub = when {
                progress == null -> "${chapter.pages.size} pages"
                progress.read -> "Read"
                else -> "Page ${progress.page + 1} of ${progress.pageCount}"
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (progress?.read == true) Icon(Icons.Rounded.CheckCircle, null, tint = tokens.success, modifier = Modifier.size(14.dp))
                Text(sub, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
            if (progress != null && !progress.read) {
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
