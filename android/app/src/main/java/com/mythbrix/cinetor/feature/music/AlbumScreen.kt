package com.mythbrix.cinetor.feature.music

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.SkeletonDetails
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.hexColor

/** Album: tinted header (artwork, meta), Play / Shuffle / Download album, track list. */
@Composable
fun AlbumScreen(albumId: String, container: AppContainer, contentPadding: PaddingValues) {
    val repo = container.musicLibrary
    val data by repo.data.collectAsStateWithLifecycle()
    LaunchedEffect(repo) { runCatching { repo.load() } }
    val music = container.music
    val ms by music.state.collectAsStateWithLifecycle()
    val downloads = rememberMusicDownloads(container)
    val tokens = Cinetor.tokens
    val album = data?.albums?.firstOrNull { it.id == albumId }
    if (album == null) {
        ShimmerHost { SkeletonDetails(Modifier.padding(contentPadding)) }
        return
    }
    val tracks = remember(data, album) { data?.tracksOf(album).orEmpty() }
    val dominant = remember(album.dominantColor) { hexColor(album.dominantColor) }
    val totalMin = tracks.sumOf { it.durationSec ?: 0 } / 60
    val doneCount = tracks.count { trackDlOf(downloads[it.id]) == TrackDl.DONE }
    val queuedCount = tracks.count { downloads[it.id] != null }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to dominant.copy(alpha = 0.9f), 0.55f to tokens.bg))) {
        LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item(key = "header", contentType = "header") {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Artwork(album.poster, 220.dp, Modifier.shadow(24.dp, RoundedCornerShape(16.dp)), corner = 16.dp)
                    Text(album.title, style = MaterialTheme.typography.headlineSmall, color = tokens.textPrimary, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                    Text(album.artist.orEmpty(), style = MaterialTheme.typography.titleSmall, color = tokens.textPrimary.copy(alpha = 0.85f))
                    Text(
                        listOfNotNull(album.year.takeIf { it > 0 }?.toString(), "${tracks.size} tracks", "$totalMin min", album.genres.firstOrNull())
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                        AlbumAction("Play", Icons.Rounded.PlayArrow, "Play album", tokens.accent) { music.playQueue(tracks, 0) }
                        AlbumAction("Shuffle", Icons.Rounded.Shuffle, "Shuffle album", Color.White.copy(alpha = 0.12f)) {
                            if (tracks.isNotEmpty()) {
                                music.playQueue(tracks, tracks.indices.random())
                                if (music.state.value.queue?.shuffle != true) music.toggleShuffle()
                            }
                        }
                        AlbumAction(
                            when {
                                doneCount == tracks.size && tracks.isNotEmpty() -> "Downloaded"
                                queuedCount > 0 -> "$doneCount/${tracks.size}"
                                else -> "Download"
                            },
                            if (doneCount == tracks.size && tracks.isNotEmpty()) Icons.Rounded.CheckCircle else Icons.Rounded.Download,
                            "Download album",
                            Color.White.copy(alpha = 0.12f),
                        ) { downloadAlbum(container, tracks, downloads) }
                    }
                }
            }
            itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { i, t ->
                TrackRow(
                    track = t,
                    playing = ms.current?.id == t.id,
                    dl = trackDlOf(downloads[t.id]),
                    onClick = { music.playQueue(tracks, i) },
                    onDownload = { downloadTrack(container, t) },
                    number = t.trackNumber ?: (i + 1),
                )
            }
            item(key = "end", contentType = "end") { Box(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AlbumAction(label: String, icon: ImageVector, desc: String, bg: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .background(bg, RoundedCornerShape(CinetorTokens.Shape.md))
            .clickable(onClick = onClick)
            .semantics { contentDescription = desc }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}
