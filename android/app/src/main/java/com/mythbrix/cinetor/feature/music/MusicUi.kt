package com.mythbrix.cinetor.feature.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.mythbrix.cinetor.core.legal.UserErrors
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.feature.player.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    return remember(all) {
        all.filter { it.record.episodeId == null && it.record.space == "MUSIC" }.associateBy { it.record.itemId }
    }
}

/**
 * Resolves a track's stream/download URL (FLAC or 320kbps) and queues it into `AppContainer.downloads`.
 */
fun downloadTrack(
    container: AppContainer,
    track: Item,
    quality: MusicAudioQuality = container.music.state.value.preferredQuality,
    onFeedback: ((String) -> Unit)? = null,
) {
    if (!isDownloadable(track)) return
    container.appScope.launch {
        val resolved = runCatching { container.musicLibrary.resolveDownloadItem(track, quality) }.getOrNull()
        val src = resolved?.sources?.firstOrNull()
        val kind = src?.let { downloadKindOf(it.kind) }
        if (resolved == null || src == null || kind == null) {
            if (onFeedback != null) withContext(Dispatchers.Main) { onFeedback(UserErrors.DOWNLOAD) }
            return@launch
        }
        val label = src.label?.takeIf { it.isNotBlank() } ?: quality.badgeLabel
        val ok = runCatching {
            container.downloads.start(
                resolved,
                null,
                null,
                DownloadChoice(
                    QualityOption(
                        source = src,
                        kind = kind,
                        label = label,
                        codecs = src.codec,
                        estimatedBytes = src.sizeBytes ?: -1L,
                    ),
                    connections = container.downloads.defaultConnectionCount(),
                ),
            )
        }.isSuccess
        if (onFeedback != null) {
            withContext(Dispatchers.Main) {
                onFeedback(if (ok) "Downloading ${track.title} · $label" else UserErrors.DOWNLOAD)
            }
        }
    }
}

/** Album / playlist batch download = every track not already queued or downloaded. */
fun downloadAlbum(
    container: AppContainer,
    tracks: List<Item>,
    existing: Map<String, DownloadInfo>,
    quality: MusicAudioQuality = container.music.state.value.preferredQuality,
    onFeedback: ((String) -> Unit)? = null,
) {
    val pending = tracks.filter { it.id !in existing && isDownloadable(it) }
    if (pending.isEmpty()) return
    container.appScope.launch {
        var queued = 0
        for (track in pending) {
            val resolved = runCatching { container.musicLibrary.resolveDownloadItem(track, quality) }.getOrNull() ?: continue
            val src = resolved.sources.firstOrNull() ?: continue
            val kind = downloadKindOf(src.kind) ?: continue
            val label = src.label?.takeIf { it.isNotBlank() } ?: quality.badgeLabel
            val ok = runCatching {
                container.downloads.start(
                    resolved,
                    null,
                    null,
                    DownloadChoice(
                        QualityOption(
                            source = src,
                            kind = kind,
                            label = label,
                            codecs = src.codec,
                            estimatedBytes = src.sizeBytes ?: -1L,
                        ),
                        connections = container.downloads.defaultConnectionCount(),
                    ),
                )
            }.isSuccess
            if (ok) queued++
        }
        if (onFeedback != null) {
            withContext(Dispatchers.Main) {
                onFeedback(
                    if (queued > 0) "Queued $queued tracks · ${quality.badgeLabel}" else UserErrors.DOWNLOAD,
                )
            }
        }
    }
}

@Composable
fun TrackDownloadIcon(
    state: TrackDl,
    title: String,
    onClick: () -> Unit,
    tint: Color = Cinetor.tokens.textSecondary,
) {
    val tokens = Cinetor.tokens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(if (focused) Modifier.border(2.dp, tokens.focusRing, CircleShape).background(tokens.accent.copy(alpha = 0.16f)) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = state == TrackDl.NONE || state == TrackDl.FAILED,
                onClick = onClick,
            )
            .semantics { contentDescription = if (state == TrackDl.DONE) "Downloaded $title" else "Download $title" },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            TrackDl.ACTIVE -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = tokens.accent)
            TrackDl.DONE -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = tokens.success, modifier = Modifier.size(20.dp))
            else -> Icon(Icons.Rounded.Download, contentDescription = null, tint = if (focused) tokens.accent else tint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun Artwork(path: String, size: Dp, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    AsyncImage(
        model = rememberSizedImage(path, size, size),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Cinetor.tokens.surfaceElevated),
    )
}

/** Interactive Liquid Glass pill toggling between `FLAC Lossless` and `320kbps Studio Master`. */
@Composable
fun QualityTogglePill(
    quality: MusicAudioQuality,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    fullLabel: Boolean = false,
    darkSurface: Boolean = false,
) {
    val tokens = Cinetor.tokens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    val label = if (fullLabel) quality.badgeLabel else quality.shortLabel
    val isFlac = quality == MusicAudioQuality.FLAC
    val textColor = when {
        isFlac -> tokens.accent
        darkSurface -> Color.White.copy(alpha = 0.88f)
        else -> tokens.textPrimary
    }
    Row(
        modifier = modifier
            .then(
                if (darkSurface) {
                    Modifier
                        .clip(shape)
                        .background(if (isFlac) tokens.accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.10f))
                        .border(
                            if (focused) 2.dp else 1.dp,
                            if (focused) Color.White else if (isFlac) tokens.accent.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.22f),
                            shape,
                        )
                } else {
                    Modifier
                        .glass(shape, GlassLevel.SURFACE)
                        .then(
                            if (focused || isFlac) {
                                Modifier.border(
                                    if (focused) 2.dp else 1.dp,
                                    if (focused) tokens.focusRing else tokens.accent.copy(alpha = 0.7f),
                                    shape,
                                )
                            } else {
                                Modifier
                            },
                        )
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .semantics { contentDescription = "Audio quality $label" }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Icons.Rounded.HighQuality, contentDescription = null, tint = textColor, modifier = Modifier.size(16.dp))
        Text(label, color = textColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Floating Liquid Glass banner shown briefly when tracks are queued into Downloads. */
@Composable
fun MusicFeedbackBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(CinetorTokens.Shape.md)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(shape, GlassLevel.FLOATING)
            .border(1.dp, tokens.accent.copy(alpha = 0.55f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Download, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(18.dp))
        Text(
            message,
            color = tokens.textPrimary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onDismiss)
                .semantics { contentDescription = "Dismiss notification" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = tokens.textSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

/** List row: number or art, title, artist · album, quality pill, duration, download state; D-pad focusable. */
@Composable
fun TrackRow(
    track: Item,
    playing: Boolean,
    dl: TrackDl,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    number: Int? = null,
    modifier: Modifier = Modifier,
    resolving: Boolean = false,
) {
    val tokens = Cinetor.tokens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, label = "trackRowScale")
    val rowShape = RoundedCornerShape(CinetorTokens.Shape.md)
    val durationText = remember(track.durationSec, track.altTitle) {
        val sec = track.durationSec ?: 0
        if (sec > 0) formatTime(sec * 1000L) else track.altTitle?.takeIf { it.contains(':') } ?: "0:00"
    }
    val qualityTag = if (track.maturity == "320K") "320K" else "FLAC"

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(rowShape)
            .background(
                when {
                    playing -> tokens.accent.copy(alpha = 0.14f)
                    focused -> tokens.surfaceElevated.copy(alpha = 0.85f)
                    else -> Color.Transparent
                },
            )
            .then(
                if (focused) Modifier.border(2.dp, tokens.focusRing, rowShape) else Modifier,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "Track ${track.title}" }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (number != null) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                when {
                    resolving -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = tokens.accent)
                    playing -> Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(20.dp))
                    else -> Text("$number", color = tokens.textSecondary, style = MaterialTheme.typography.labelLarge)
                }
            }
        } else {
            Box(contentAlignment = Alignment.Center) {
                Artwork(track.poster, 48.dp, corner = 10.dp)
                if (resolving) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(tokens.scrim),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = tokens.accent)
                    }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (playing) tokens.accent else tokens.textPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(track.artist, track.album?.takeIf { number == null && it.isNotBlank() }).joinToString(" · "),
                    color = tokens.textSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    qualityTag,
                    color = tokens.accent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(tokens.accent.copy(alpha = 0.12f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Text(durationText, color = tokens.textTertiary, style = MaterialTheme.typography.labelMedium)
        TrackDownloadIcon(dl, track.title, onDownload)
    }
}
