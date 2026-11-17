package com.mythbrix.cinetor.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.mythbrix.cinetor.core.perf.EffectsQuality
import com.mythbrix.cinetor.core.perf.detectTier
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.feature.adult.AdultSetupAction
import com.mythbrix.cinetor.feature.adult.AdultSetupFlowDialog
import com.mythbrix.cinetor.navigation.spaceEntryLabel
import java.util.Locale

/**
 * More / Profile hub:
 * - Active profile Liquid Glass card (tap → profile switcher).
 * - Prominent 2-column Liquid Glass **More & Spaces** grid (`Live TV & Sports`, `Music`, `Comics`,
 *   and PIN-gated `18+ Adult` when enabled + quick inline opt-in/manage on non-Kids profiles).
 * - Unified Liquid Glass account & app menu (`Switch profile`, `Library`, `Downloads`, `Settings`, `About`).
 */
@Composable
fun MeScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onDownloads: () -> Unit = {},
    onProfiles: () -> Unit = {},
    onLibrary: () -> Unit = {},
    /** Isolated spaces (Live TV & Sports, Music, Comics, 18+ when enabled). */
    spaces: List<Space> = emptyList(),
    onSpace: (Space) -> Unit = {},
) {
    val tokens = Cinetor.tokens
    val effects by container.settings.effects.collectAsStateWithLifecycle(EffectsQuality.AUTO)
    val device = container.deviceProfile
    val autoTier = detectTier(device)
    val active by container.profiles.active.collectAsStateWithLifecycle()
    val all by container.profiles.profiles.collectAsStateWithLifecycle()
    val watchlist by container.profiles.watchlist.collectAsStateWithLifecycle()
    val dl by container.downloads.visible.collectAsStateWithLifecycle()
    val comicsData by container.comics.store.data.collectAsStateWithLifecycle()
    val adultEnabled by container.adult.enabled.collectAsStateWithLifecycle()
    val adultUnlocked by container.adult.unlocked.collectAsStateWithLifecycle()

    var adultSetupAction by remember { mutableStateOf<AdultSetupAction?>(null) }

    val activeDl = dl.count { it.record.isActive }
    val doneDl = dl.count { it.record.status == DownloadStatus.COMPLETED }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Active Profile Header
        Row(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(20.dp), GlassLevel.SURFACE)
                .clickable(onClick = onProfiles)
                .semantics { contentDescription = "Current profile" }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProfileAvatar(active, 54.dp)
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        active.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (active.kids) {
                        Text(
                            "KIDS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = tokens.onAccent,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(tokens.accent)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    if (active.kids) {
                        "Kids safe mode · ${watchlist.size} in My List"
                    } else {
                        "${watchlist.size} in My List · ${all.size} of ${ProfileManager.MAX_PROFILES} profiles"
                    },
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

        // More & Spaces 2-Column Liquid Glass Hub
        if (spaces.isNotEmpty()) {
            SpacesHubSection(
                spaces = spaces,
                isKids = active.kids,
                adultEnabled = adultEnabled,
                adultUnlocked = adultUnlocked,
                comicsBookmarksCount = comicsData.bookmarks.size,
                onSpace = onSpace,
                onLockAdult = { container.adult.lock() },
                onManageAdult = { action -> adultSetupAction = action },
            )
        }

        // Grouped Account, Library & App Menu (single Liquid Glass container — zero nested cards)
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(20.dp), GlassLevel.SURFACE),
        ) {
            MeMenuItem(
                icon = Icons.Rounded.People,
                title = "Switch profile",
                subtitle = "Add, edit or switch profiles",
                onClick = onProfiles,
            )
            MenuDivider()
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
            MenuDivider()
            MeMenuItem(
                icon = Icons.Rounded.Settings,
                title = "Settings",
                subtitle = "Appearance, playback & parental controls",
                onClick = onSettings,
            )
            MenuDivider()
            MeMenuItem(
                icon = Icons.Rounded.Info,
                title = "About",
                subtitle = "Cinetor · Credits & community",
                onClick = onAbout,
            )
        }

        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "Effects ${effects.name.lowercase()} · tier ${Cinetor.tier.name} (auto ${autoTier.name})",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textTertiary,
            )
            Text(
                "Device: ${"%.1f".format(Locale.US, device.totalRamBytes / 1_073_741_824.0)} GB RAM · API ${device.sdkInt} · " +
                    "${device.cpuCores} cores${if (device.isTv) " · TV" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textTertiary,
            )
        }
        Spacer(Modifier.height(4.dp))
    }

    AdultSetupFlowDialog(
        gate = container.adult,
        action = adultSetupAction,
        onDismiss = { adultSetupAction = null },
    )
}

@Composable
private fun SpacesHubSection(
    spaces: List<Space>,
    isKids: Boolean,
    adultEnabled: Boolean,
    adultUnlocked: Boolean,
    comicsBookmarksCount: Int,
    onSpace: (Space) -> Unit,
    onLockAdult: () -> Unit,
    onManageAdult: (AdultSetupAction) -> Unit,
) {
    val tokens = Cinetor.tokens
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Spaces" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "More & Spaces",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (!isKids && adultEnabled) {
                if (adultUnlocked) {
                    Text(
                        "Lock 18+",
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(tokens.glassFill)
                            .clickable(onClick = onLockAdult)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Text(
                    "18+ On",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(tokens.glassFill)
                        .clickable { onManageAdult(AdultSetupAction.DISABLE) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        val showAdultOptInTile = !isKids && Space.ADULT !in spaces
        val rows = spaces.chunked(2)
        rows.forEachIndexed { rowIndex, pair ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SpaceTile(
                    space = pair[0],
                    adultUnlocked = adultUnlocked,
                    comicsBookmarksCount = comicsBookmarksCount,
                    onClick = { onSpace(pair[0]) },
                    modifier = Modifier.weight(1f),
                )
                if (pair.size > 1) {
                    SpaceTile(
                        space = pair[1],
                        adultUnlocked = adultUnlocked,
                        comicsBookmarksCount = comicsBookmarksCount,
                        onClick = { onSpace(pair[1]) },
                        modifier = Modifier.weight(1f),
                    )
                } else if (rowIndex == rows.lastIndex && showAdultOptInTile) {
                    AdultOptInTile(
                        onEnable = { onManageAdult(AdultSetupAction.ENABLE) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpaceTile(
    space: Space,
    adultUnlocked: Boolean,
    comicsBookmarksCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    // Keep exact `spaceEntryLabel(space).first` for `contentDescription = "Space $semanticTitle"`!
    val (semanticTitle, defaultSub) = spaceEntryLabel(space)
    val displayTitle = if (space == Space.ADULT) "18+ Adult" else semanticTitle
    val subtitle = when (space) {
        Space.TV -> "Channels, sports & EPG"
        Space.MUSIC -> "Streaming · FLAC · MP3"
        Space.COMICS -> if (comicsBookmarksCount > 0) "Manga · Manhwa · $comicsBookmarksCount saved" else "Manga · Manhwa · Manhua"
        Space.ADULT -> if (adultUnlocked) "Unlocked · Tap to open" else "PIN protected"
        else -> defaultSub
    }
    val icon = when (space) {
        Space.TV -> Icons.Rounded.LiveTv
        Space.MUSIC -> Icons.Rounded.MusicNote
        Space.COMICS -> Icons.Rounded.AutoStories
        Space.ADULT -> if (adultUnlocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock
        else -> Icons.Rounded.LiveTv
    }

    Row(
        modifier
            .glass(RoundedCornerShape(18.dp), GlassLevel.SURFACE)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Space $semanticTitle" }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(tokens.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = tokens.accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AdultOptInTile(onEnable: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .clip(shape)
            .background(tokens.glassFill)
            .border(1.dp, tokens.divider, shape)
            .clickable(onClick = onEnable)
            .semantics { contentDescription = "Enable 18+ space" }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(tokens.glassFill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = tokens.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                "18+ Adult",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = tokens.textSecondary,
                maxLines = 1,
            )
            Text(
                "Off · Tap to set PIN",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
