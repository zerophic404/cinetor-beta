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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.SkeletonDetails
import com.mythbrix.cinetor.design.glass
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Album & Playlist detail screen: ArtTint radial wash, artwork, lossless quality badge,
 * Play / Shuffle / Download All actions, and D-pad focusable tracklist.
 */
@Composable
fun AlbumScreen(albumId: String, container: AppContainer, contentPadding: PaddingValues) {
    val repo = container.musicLibrary
    val music = container.music
    val ms by music.state.collectAsStateWithLifecycle()
    val downloads = rememberMusicDownloads(container)
    val tokens = Cinetor.tokens

    var collection by remember(albumId) { mutableStateOf<MusicCollection?>(null) }
    var loading by remember(albumId) { mutableStateOf(true) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(albumId) {
        loading = true
        collection = runCatching { repo.loadCollection(albumId) }.getOrNull()
        loading = false
    }

    LaunchedEffect(feedbackMessage) {
        if (feedbackMessage != null) {
            delay(4_500)
            feedbackMessage = null
        }
    }

    val col = collection
    if (col == null) {
        if (loading) {
            ShimmerHost { SkeletonDetails(Modifier.padding(contentPadding)) }
        } else {
            Box(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Collection unavailable right now.", color = tokens.textSecondary, style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    val album = col.header
    val tracks = col.tracks
    val (_, glowColor) = rememberMusicArtTint(container, album.poster, album.dominantColor, album.accentColor)
    val totalMin = remember(tracks) { (tracks.sumOf { it.durationSec ?: 0 } / 60).coerceAtLeast(1) }
    val doneCount = tracks.count { trackDlOf(downloads[it.id]) == TrackDl.DONE }
    val queuedCount = tracks.count { downloads[it.id] != null }

    Box(
        Modifier
            .fillMaxSize()
            .background(tokens.bg)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to glowColor.copy(alpha = 0.28f),
                            0.55f to glowColor.copy(alpha = 0.08f),
                            1f to Color.Transparent,
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.12f),
                        radius = max(size.width, size.height) * 0.72f,
                    ),
                )
            },
    ) {
        LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item(key = "header", contentType = "header") {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Artwork(
                        album.poster,
                        216.dp,
                        Modifier.shadow(24.dp, RoundedCornerShape(18.dp), ambientColor = glowColor, spotColor = glowColor),
                        corner = 18.dp,
                    )
                    Text(
                        album.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = tokens.textPrimary,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                    )
                    if (!album.artist.isNullOrBlank()) {
                        Text(
                            album.artist,
                            style = MaterialTheme.typography.titleSmall,
                            color = tokens.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        listOfNotNull(
                            if (col.isPlaylist) "Curated Playlist" else album.year.takeIf { it > 0 }?.toString(),
                            "${tracks.size} tracks",
                            if (tracks.isNotEmpty()) "$totalMin min" else null,
                            album.genres.firstOrNull(),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.textSecondary,
                    )

                    QualityTogglePill(
                        quality = ms.preferredQuality,
                        onToggle = { music.toggleQuality() },
                        fullLabel = true,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        AlbumAction(
                            label = "Play",
                            icon = Icons.Rounded.PlayArrow,
                            desc = "Play album",
                            primary = true,
                            enabled = tracks.isNotEmpty(),
                        ) {
                            music.playQueue(tracks, 0)
                        }
                        AlbumAction(
                            label = "Shuffle",
                            icon = Icons.Rounded.Shuffle,
                            desc = "Shuffle album",
                            primary = false,
                            enabled = tracks.isNotEmpty(),
                        ) {
                            if (tracks.isNotEmpty()) {
                                music.playQueue(tracks, tracks.indices.random())
                                if (music.state.value.queue?.shuffle != true) music.toggleShuffle()
                            }
                        }
                        AlbumAction(
                            label = when {
                                doneCount == tracks.size && tracks.isNotEmpty() -> "Downloaded"
                                queuedCount > 0 -> "$doneCount/${tracks.size}"
                                else -> "Download"
                            },
                            icon = if (doneCount == tracks.size && tracks.isNotEmpty()) Icons.Rounded.CheckCircle else Icons.Rounded.Download,
                            desc = "Download album",
                            primary = false,
                            enabled = tracks.isNotEmpty() && doneCount < tracks.size,
                        ) {
                            downloadAlbum(container, tracks, downloads, ms.preferredQuality) { msg ->
                                feedbackMessage = msg
                            }
                        }
                    }
                }
            }

            itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { i, t ->
                TrackRow(
                    track = t,
                    playing = ms.current?.id == t.id,
                    resolving = ms.resolvingTrackId == t.id,
                    dl = trackDlOf(downloads[t.id]),
                    onClick = { music.playQueue(tracks, i) },
                    onDownload = {
                        downloadTrack(container, t, ms.preferredQuality) { msg -> feedbackMessage = msg }
                    },
                    number = t.trackNumber ?: (i + 1),
                )
            }

            item(key = "end", contentType = "end") { Box(Modifier.height(24.dp)) }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = feedbackMessage != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = contentPadding.calculateBottomPadding() + 12.dp,
                ),
        ) {
            MusicFeedbackBanner(
                message = feedbackMessage.orEmpty(),
                onDismiss = { feedbackMessage = null },
            )
        }
    }
}

@Composable
private fun AlbumAction(
    label: String,
    icon: ImageVector,
    desc: String,
    primary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "albumActionScale")
    val shape = RoundedCornerShape(CinetorTokens.Shape.md)
    val contentTint = if (primary) tokens.onAccent else tokens.textPrimary

    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (primary) {
                    Modifier.clip(shape).background(tokens.accent)
                } else {
                    Modifier.glass(shape, GlassLevel.SURFACE)
                },
            )
            .then(if (focused) Modifier.border(2.dp, tokens.focusRing, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = desc }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = contentTint, modifier = Modifier.size(20.dp))
        Text(label, color = contentTint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}
