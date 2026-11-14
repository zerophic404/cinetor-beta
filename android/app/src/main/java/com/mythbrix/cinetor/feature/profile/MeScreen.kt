package com.mythbrix.cinetor.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.download.DownloadStatus
import com.mythbrix.cinetor.core.download.isActive
import com.mythbrix.cinetor.core.perf.EffectsQuality
import com.mythbrix.cinetor.core.perf.detectTier
import com.mythbrix.cinetor.design.AppTheme
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import kotlinx.coroutines.launch

private fun <E : Enum<E>> E.next(values: Array<E>): E = values[(ordinal + 1) % values.size]

/** Me: active profile header (tap → picker), switch profile, library, downloads, settings, about. */
@Composable
fun MeScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onDownloads: () -> Unit = {},
    onProfiles: () -> Unit = {},
    onLibrary: () -> Unit = {},
) {
    val tokens = Cinetor.tokens
    val scope = rememberCoroutineScope()
    val effects by container.settings.effects.collectAsStateWithLifecycle(EffectsQuality.AUTO)
    val profile = container.deviceProfile
    val autoTier = detectTier(profile)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val active by container.profiles.active.collectAsStateWithLifecycle()
        val all by container.profiles.profiles.collectAsStateWithLifecycle()
        val watchlist by container.profiles.watchlist.collectAsStateWithLifecycle()
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onProfiles)
                .semantics { contentDescription = "Current profile" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProfileAvatar(active, 64.dp)
            Column {
                Text(active.name, style = MaterialTheme.typography.titleLarge, color = tokens.textPrimary)
                Text(
                    if (active.kids) "Kids profile" else "${all.size} of ${ProfileManager.MAX_PROFILES} profiles",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
        }
        MeRow(Icons.Rounded.People, "Switch profile", "Add, edit or switch profiles", onProfiles)
        MeRow(Icons.Rounded.VideoLibrary, "Library", "My List ${watchlist.size} · history", onLibrary)
        val dl by container.downloads.visible.collectAsStateWithLifecycle()
        val activeDl = dl.count { it.record.isActive }
        val doneDl = dl.count { it.record.status == DownloadStatus.COMPLETED }
        MeRow(Icons.Rounded.Download, "Downloads", if (activeDl > 0) "$activeDl active · $doneDl downloaded" else "$doneDl downloaded", onDownloads)
        MeRow(Icons.Rounded.Settings, "Settings", "App & profile", onSettings)
        MeRow(Icons.Rounded.Info, "About", "Cinetor", onAbout)
        // Theme / effects live in Settings → Appearance (Task 5.2); tier shown here for perf debugging.
        Text(
            "Effects ${effects.name.lowercase()} · now ${Cinetor.tier.name} (auto ${autoTier.name})",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textTertiary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            "Device: ${"%.1f".format(profile.totalRamBytes / 1_073_741_824.0)} GB RAM · API ${profile.sdkInt} · " +
                "${profile.cpuCores} cores${if (profile.isTv) " · TV" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textTertiary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun MeRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(18.dp), GlassLevel.SURFACE)
            .clickable(onClick = onClick)
            .semantics { contentDescription = title }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tokens.accent)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = tokens.textPrimary)
            Text(value, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = tokens.textSecondary)
    }
}
