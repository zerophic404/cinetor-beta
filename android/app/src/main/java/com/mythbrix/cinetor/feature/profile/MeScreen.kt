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

/** Me (placeholder for Task 5.1): profile header + debug theme/effects toggles + links. */
@Composable
fun MeScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onDownloads: () -> Unit = {},
) {
    val tokens = Cinetor.tokens
    val scope = rememberCoroutineScope()
    val theme = Cinetor.theme
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(tokens.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text("M", style = MaterialTheme.typography.headlineMedium, color = tokens.onAccent)
            }
            Column {
                Text("Main profile", style = MaterialTheme.typography.titleLarge, color = tokens.textPrimary)
                Text("Profiles arrive in Phase 5", style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
        }
        MeRow(Icons.Rounded.Palette, "Theme", CinetorTokens.of(theme).name) {
            scope.launch { container.settings.setTheme(theme.next(AppTheme.entries.toTypedArray())) }
        }
        MeRow(
            Icons.Rounded.Speed,
            "Effects quality",
            "${effects.name.lowercase().replaceFirstChar { it.uppercase() }} · now ${Cinetor.tier.name} (auto ${autoTier.name})",
        ) {
            scope.launch { container.settings.setEffects(effects.next(EffectsQuality.entries.toTypedArray())) }
        }
        Text(
            "Device: ${"%.1f".format(profile.totalRamBytes / 1_073_741_824.0)} GB RAM · API ${profile.sdkInt} · " +
                "${profile.cpuCores} cores${if (profile.isTv) " · TV" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textTertiary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        val dl by container.downloads.visible.collectAsStateWithLifecycle()
        val activeDl = dl.count { it.record.isActive }
        val doneDl = dl.count { it.record.status == DownloadStatus.COMPLETED }
        MeRow(Icons.Rounded.Download, "Downloads", if (activeDl > 0) "$activeDl active · $doneDl downloaded" else "$doneDl downloaded", onDownloads)
        MeRow(Icons.Rounded.Settings, "Settings", "App & profile", onSettings)
        MeRow(Icons.Rounded.Info, "About", "Cinetor", onAbout)
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
