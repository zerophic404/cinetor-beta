package com.mythbrix.cinetor.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.download.DownloadStatus
import com.mythbrix.cinetor.core.download.isActive
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.navigation.spaceEntryLabel
import kotlinx.coroutines.launch

/**
 * Single-profile Cinetor Beta `Me` hub:
 * - Clean on-device profile summary card with inline display-name edit.
 * - One Liquid Glass menu list in Cinetor Beta order: `Library`, `Downloads`, `Comics`, `Music`,
 *   `Adult Content` (ONLY when enabled in Settings), `Settings`, `About`.
 */
@Composable
fun MeScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onDownloads: () -> Unit = {},
    @Suppress("UnusedParameter") onProfiles: () -> Unit = {},
    onLibrary: () -> Unit = {},
    /** Isolated spaces (`Music`, `Comics`, plus `18+` only when enabled in Settings). */
    spaces: List<Space> = emptyList(),
    onSpace: (Space) -> Unit = {},
) {
    val tokens = Cinetor.tokens
    val scope = rememberCoroutineScope()
    val active by container.profiles.active.collectAsStateWithLifecycle()
    val watchlist by container.profiles.watchlist.collectAsStateWithLifecycle()
    val history by container.profiles.history.collectAsStateWithLifecycle()
    val dl by container.downloads.visible.collectAsStateWithLifecycle()
    val comicsData by container.comics.store.data.collectAsStateWithLifecycle()
    val adultEnabled by container.adult.enabled.collectAsStateWithLifecycle()

    var editingName by remember { mutableStateOf(false) }
    var draftName by remember(active.name) { mutableStateOf(active.name) }

    val visibleSpaces = remember(spaces, adultEnabled, active.kids) {
        spaces.filter { sp ->
            sp != Space.TV && (sp != Space.ADULT || (adultEnabled && !active.kids))
        }
    }

    val activeDl = dl.count { it.record.isActive }
    val doneDl = dl.count { it.record.status == DownloadStatus.COMPLETED }
    val displayName = active.name.ifBlank { "Your Cinetor" }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // On-device profile summary card (single profile — no profile switcher)
        Row(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(20.dp), GlassLevel.SURFACE)
                .semantics { contentDescription = "Current profile" }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProfileAvatar(active, 56.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${watchlist.size} in My List · ${history.size} watched",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(tokens.glassFill)
                    .border(1.dp, tokens.glassStroke, CircleShape)
                    .clickable {
                        draftName = active.name
                        editingName = true
                    }
                    .semantics { contentDescription = "Edit display name" }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = null,
                    tint = tokens.textPrimary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    "Edit",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
            }
        }

        // Grouped Library & App Menu (single Liquid Glass container — zero nested cards)
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(20.dp), GlassLevel.SURFACE),
        ) {
            MeMenuItem(
                icon = Icons.Rounded.VideoLibrary,
                title = "Library",
                subtitle = "My List (${watchlist.size}) · Watch history",
                onClick = onLibrary,
            )
            MenuDivider()
            MeMenuItem(
                icon = Icons.Rounded.Download,
                title = "Downloads",
                subtitle = if (activeDl > 0) "$activeDl active · $doneDl downloaded" else "$doneDl downloaded",
                onClick = onDownloads,
            )
            // Cinetor Beta order: Comics, Music, then Adult Content as plain rows, one after another.
            orderedSpaces(visibleSpaces).forEach { space ->
                MenuDivider()
                SpaceMenuItem(
                    space = space,
                    comicsBookmarksCount = comicsData.bookmarks.size,
                    onClick = { onSpace(space) },
                )
            }
            MenuDivider()
            MeMenuItem(
                icon = Icons.Rounded.Settings,
                title = "Settings",
                subtitle = "Appearance, player, downloads & privacy",
                onClick = onSettings,
            )
            MenuDivider()
            MeMenuItem(
                icon = Icons.Rounded.Info,
                title = "About",
                subtitle = "Cinetor · Mythbrix & community",
                onClick = onAbout,
            )
        }

        Spacer(Modifier.height(4.dp))
    }

    if (editingName) {
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Display name") },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { if (it.length <= 24) draftName = it },
                    label = { Text("Name on this device") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draftName.trim().isNotEmpty(),
                    onClick = {
                        val trimmed = draftName.trim()
                        editingName = false
                        if (trimmed.isNotEmpty()) {
                            scope.launch {
                                runCatching { container.profiles.update(active.copy(name = trimmed)) }
                            }
                        }
                    },
                ) {
                    Text("Save", color = tokens.accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingName = false }) {
                    Text("Cancel", color = tokens.textSecondary)
                }
            },
            containerColor = tokens.surfaceElevated,
        )
    }
}

/** Comics, Music, then 18+, matching the Cinetor Beta `Me` list order. */
private fun orderedSpaces(spaces: List<Space>): List<Space> {
    val order = listOf(Space.COMICS, Space.MUSIC, Space.ADULT)
    return spaces.sortedBy { sp -> order.indexOf(sp).let { if (it < 0) order.size else it } }
}

/** One space as a plain menu row (icon, title, one-line subtitle, chevron), like the Beta `Me` list. */
@Composable
private fun SpaceMenuItem(
    space: Space,
    comicsBookmarksCount: Int,
    onClick: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val (semanticTitle, defaultSub) = spaceEntryLabel(space)
    val title = when (space) {
        Space.ADULT -> "Adult Content"
        Space.COMICS -> "Comics"
        else -> semanticTitle
    }
    val subtitle = when (space) {
        Space.MUSIC -> "Streaming · Lossless · MP3"
        Space.COMICS -> if (comicsBookmarksCount > 0) "Manga, Manhwa & Manhua · $comicsBookmarksCount saved" else "Manga, Manhwa & Manhua"
        Space.ADULT -> "Movies & series for adults"
        else -> defaultSub
    }
    val icon = when (space) {
        Space.MUSIC -> Icons.Rounded.MusicNote
        Space.COMICS -> Icons.Rounded.AutoStories
        Space.ADULT -> Icons.Rounded.VideoLibrary
        else -> Icons.Rounded.VideoLibrary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Space $semanticTitle" }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                maxLines = 1,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (space == Space.ADULT) {
            Text(
                "18+",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = tokens.onAccent,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(tokens.accent)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = tokens.textSecondary,
        )
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(
        color = Cinetor.tokens.divider,
        thickness = 1.dp,
        modifier = Modifier.padding(start = 52.dp, end = 14.dp),
    )
}

@Composable
private fun MeMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val tokens = Cinetor.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = title }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                maxLines = 1,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = tokens.textSecondary,
        )
    }
}
