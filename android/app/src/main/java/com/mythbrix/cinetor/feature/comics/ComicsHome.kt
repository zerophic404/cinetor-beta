package com.mythbrix.cinetor.feature.comics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.feature.home.SpaceScreen
import kotlinx.coroutines.launch

/**
 * Comics home = shared SpaceScreen (tabs Manga | Manhwa | Manhua from the catalog, hero, chips, rows) + a floating
 * glass "Continue reading" pill for the last chapter read (comics progress never feeds global Continue Watching).
 */
@Composable
fun ComicsHome(container: AppContainer, contentPadding: PaddingValues, onOpenItem: (Item, String?) -> Unit) {
    val comics = container.comics
    val data by comics.store.data.collectAsStateWithLifecycle()
    val last = remember(data) { data.progress.maxByOrNull { it.updatedAt } }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        SpaceScreen(Space.COMICS, container, contentPadding, onOpenItem)
        if (last != null) {
            val tokens = Cinetor.tokens
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = contentPadding.calculateBottomPadding())
                    .padding(horizontal = 24.dp)
                    .glass(RoundedCornerShape(50), GlassLevel.BAR)
                    .clickable {
                        scope.launch {
                            runCatching { container.catalog.item(last.comicId) }.getOrNull()
                                ?.let { comics.openReader(it, last.chapterId) }
                        }
                    }
                    .semantics { contentDescription = "Continue reading" }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.MenuBook, contentDescription = null, tint = tokens.accent)
                Column {
                    Text(
                        last.comicTitle.ifBlank { "Continue reading" },
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (last.read) "Ch ${last.chapterN} · finished" else "Ch ${last.chapterN} · page ${last.page + 1}/${last.pageCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )
                }
            }
        }
    }
}
