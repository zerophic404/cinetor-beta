package com.mythbrix.cinetor.feature.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.download.DownloadChoice
import com.mythbrix.cinetor.core.download.DownloadInfo
import com.mythbrix.cinetor.core.download.DownloadStatus
import com.mythbrix.cinetor.core.download.QualityOption
import com.mythbrix.cinetor.core.download.downloadKindOf
import com.mythbrix.cinetor.core.download.isDownloadable
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.feature.player.formatTime
import kotlinx.coroutines.launch

/** Track download state for icons. */
enum class TrackDl { NONE, ACTIVE, DONE, FAILED }

fun trackDlOf(info: DownloadInfo?): TrackDl = when (info?.record?.status) {
    null -> TrackDl.NONE
    DownloadStatus.COMPLETED -> TrackDl.DONE
    DownloadStatus.FAILED -> TrackDl.FAILED
    else -> TrackDl.ACTIVE
}

/** itemId → download (tracks have no episodes). */
@Composable
fun rememberMusicDownloads(container: AppContainer): Map<String, DownloadInfo> {
    val all by container.downloads.visible.collectAsStateWithLifecycle()
    return remember(all) { all.filter { it.record.episodeId == null && it.record.space == "MUSIC" }.associateBy { it.record.itemId } }
}

/** Queue a track through the existing download engine (progressive MP3, original quality, no options sheet). */
fun downloadTrack(container: AppContainer, track: Item) {
    if (!isDownloadable(track)) return
    val src = track.sources.firstOrNull() ?: return
    val kind = downloadKindOf(src.kind) ?: return
    container.appScope.launch {
        runCatching {
            container.downloads.start(
                track, null, null,
                DownloadChoice(QualityOption(src, kind, "Original · ${src.kind.name}"), connections = container.downloads.defaultConnectionCount()),
            )
        }
    }
}

/** Album download = every track not already queued/downloaded. */
fun downloadAlbum(container: AppContainer, tracks: List<Item>, existing: Map<String, DownloadInfo>) {
    tracks.filter { it.id !in existing }.forEach { downloadTrack(container, it) }
}

@Composable
fun TrackDownloadIcon(state: TrackDl, title: String, onClick: () -> Unit, tint: Color = Cinetor.tokens.textSecondary) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(enabled = state == TrackDl.NONE || state == TrackDl.FAILED, onClick = onClick)
            .semantics { contentDescription = if (state == TrackDl.DONE) "Downloaded $title" else "Download $title" },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            TrackDl.ACTIVE -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Cinetor.tokens.accent)
            TrackDl.DONE -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Cinetor.tokens.success, modifier = Modifier.size(20.dp))
            else -> Icon(Icons.Rounded.Download, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun Artwork(path: String, size: Dp, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    AsyncImage(
        model = rememberSizedImage(path, size, size),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(RoundedCornerShape(corner)).background(Cinetor.tokens.surfaceElevated),
    )
}

/** List row: number or art, title, artist · album, duration, download state; playing row in accent. */
@Composable
fun TrackRow(
    track: Item,
    playing: Boolean,
    dl: TrackDl,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    number: Int? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Track ${track.title}" }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (number != null) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (playing) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(20.dp))
                } else {
                    Text("$number", color = tokens.textSecondary, style = MaterialTheme.typography.labelLarge)
                }
            }
        } else {
            Artwork(track.poster, 48.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title, color = if (playing) tokens.accent else tokens.textPrimary,
                style = MaterialTheme.typography.titleSmall, fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(track.artist, track.album.takeIf { number == null }).joinToString(" · "),
                color = tokens.textSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(formatTime((track.durationSec ?: 0) * 1000L), color = tokens.textTertiary, style = MaterialTheme.typography.labelMedium)
        TrackDownloadIcon(dl, track.title, onDownload)
    }
}
