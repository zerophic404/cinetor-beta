package com.mythbrix.cinetor.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.download.DownloadLocations
import com.mythbrix.cinetor.core.download.StorageInfo
import com.mythbrix.cinetor.core.prefs.AppSettings
import com.mythbrix.cinetor.design.AppTheme
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.design.themeUsesSpecularShine
import com.mythbrix.cinetor.feature.adult.AdultGate
import com.mythbrix.cinetor.feature.adult.AdultSetupAction
import com.mythbrix.cinetor.feature.adult.AdultSetupFlowDialog
import java.util.Locale
import kotlinx.coroutines.launch

/** The six themes, signature Cinema first, light Daylight last. */
internal val ThemeDisplayOrder: List<AppTheme> = listOf(
    AppTheme.CINEMA,
    AppTheme.MIDNIGHT,
    AppTheme.EMERALD,
    AppTheme.SUNSET,
    AppTheme.ROYAL,
    AppTheme.DAYLIGHT,
)

internal val DoubleTapChoices: List<Int> = listOf(5, 10, 15, 30)

internal fun themeBadge(theme: AppTheme): String = when (theme) {
    AppTheme.CINEMA -> "Black · Red"
    AppTheme.MIDNIGHT -> "Navy · Blue"
    AppTheme.EMERALD -> "Forest · Mint"
    AppTheme.SUNSET -> "Plum · Coral"
    AppTheme.ROYAL -> "Violet · Gold"
    AppTheme.DAYLIGHT -> "Light"
}

internal fun themeDescription(theme: AppTheme): String = when (theme) {
    AppTheme.CINEMA -> "Pure black, white and Cinema Red: the signature Cinetor look"
    AppTheme.MIDNIGHT -> "Deep navy glass with electric blue light, cool and calm"
    AppTheme.EMERALD -> "Dark forest canvas with fresh mint highlights"
    AppTheme.SUNSET -> "Warm plum dusk with coral and rose light"
    AppTheme.ROYAL -> "Deep violet velvet with gold trim"
    AppTheme.DAYLIGHT -> "Bright warm paper, ink text and red accents for daytime"
}

/**
 * Cinetor Beta streamlined Settings:
 * Appearance · Player · Downloads · Content & Privacy · Reset settings.
 * Audio, subtitle, and quality pickers live inside the player controls where they are used.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    @Suppress("UnusedParameter") onProfiles: () -> Unit = {},
    @Suppress("UnusedParameter") onAbout: () -> Unit = {},
) {
    val tokens = Cinetor.tokens
    val holder = container.settingsState
    val s by holder.state.collectAsStateWithLifecycle()
    val theme by holder.theme.collectAsStateWithLifecycle()
    val sub by container.subtitles.state.collectAsStateWithLifecycle()
    val dlPolicy by container.downloads.prefs.policy.collectAsStateWithLifecycle()
    val dlStorage by container.downloads.storage.collectAsStateWithLifecycle()
    val dlLocation by container.downloads.location.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val set: ((AppSettings) -> AppSettings) -> Unit = { f -> holder.update(f) }

    var confirmClearSearch by remember { mutableStateOf(false) }
    var confirmClearWatch by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var searchStatus by remember { mutableStateOf<String?>(null) }
    var watchStatus by remember { mutableStateOf<String?>(null) }
    var resetStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { container.downloads.refreshStorage() }
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { container.downloads.setLocation(uri) }
                runCatching { container.downloads.refreshStorage() }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = tokens.textPrimary,
        )

        resetStatus?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.accent,
                fontWeight = FontWeight.Medium,
            )
        }

        Group("Appearance") {
            ThemeGridPicker(selected = theme, onSelect = holder::setTheme)
            ActiveThemeSummary(theme = theme)
        }

        Group("Player") {
            val activeSkip = if (s.doubleTapSec in DoubleTapChoices) s.doubleTapSec else 10
            Choice(
                title = "Double-tap skip",
                options = DoubleTapChoices,
                selected = activeSkip,
                label = { "${it}s" },
            ) { v ->
                set { it.copy(doubleTapSec = v) }
            }
            SectionDivider()
            val holdEnabled = s.gestures && s.holdSpeed >= 1.9f
            Toggle(
                title = "Hold for 2× speed",
                subtitle = "Press and hold the video to play at 2× speed",
                checked = holdEnabled,
            ) { v ->
                set { it.copy(gestures = true, holdSpeed = if (v) 2f else 1.25f) }
            }
            SectionDivider()
            Toggle(
                title = "Floating Mini-Player / PiP",
                subtitle = "Keep video playing in a small floating window when browsing or leaving the app",
                checked = s.autoPip,
            ) { v ->
                set { it.copy(autoPip = v) }
            }
        }

        Group("Downloads") {
            Toggle(
                title = "Download on Wi-Fi only",
                subtitle = "Pause queued downloads while on mobile data",
                checked = dlPolicy.wifiOnly,
            ) { v ->
                scope.launch { container.downloads.prefs.update { it.copy(wifiOnly = v) } }
            }
            SectionDivider()
            StorageSummaryRow(dlStorage)
            SectionDivider()
            ActionRow(
                title = "Download folder",
                subtitle = DownloadLocations.label(dlLocation, adult = false),
            ) {
                runCatching { pickFolder.launch(null) }
            }
            if (dlLocation.chosen) {
                ActionRow(
                    title = "Use app storage",
                    subtitle = "Save future downloads inside Cinetor app storage",
                ) {
                    scope.launch {
                        runCatching { container.downloads.clearLocation() }
                        runCatching { container.downloads.refreshStorage() }
                    }
                }
            }
        }

        Group("Content & Privacy") {
            AdultPrivacyToggleRow(container.adult)
            SectionDivider()
            ActionRow(
                title = "Clear search history",
                subtitle = searchStatus ?: "Remove recent search queries across all sections",
            ) {
                confirmClearSearch = true
            }
            SectionDivider()
            ActionRow(
                title = "Clear watch history",
                subtitle = watchStatus ?: "Remove Continue Watching and saved playback positions",
            ) {
                confirmClearWatch = true
            }
            SectionDivider()
            CacheRow(container)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .glass(Cinetor.cardShape, GlassLevel.SURFACE)
                .clickable { confirmReset = true }
                .semantics { contentDescription = "Reset settings" }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.error.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.RestartAlt,
                    contentDescription = null,
                    tint = tokens.error,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Reset settings",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.error,
                )
                Text(
                    "Restore theme, player, and download preferences to default. History and My List are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    if (confirmClearSearch) {
        AlertDialog(
            onDismissRequest = { confirmClearSearch = false },
            title = { Text("Clear search history?") },
            text = { Text("Recent searches across Home, Anime, Drama, Music, Comics, and 18+ will be removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearSearch = false
                        scope.launch {
                            val id = container.activeProfileId
                            runCatching { container.searchHistory.clear(id, null) }
                            Space.entries.forEach { sp -> runCatching { container.searchHistory.clear(id, sp) } }
                            searchStatus = "Search history cleared"
                        }
                    },
                ) {
                    Text("Clear", color = tokens.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearSearch = false }) {
                    Text("Cancel", color = tokens.textSecondary)
                }
            },
            containerColor = tokens.surfaceElevated,
        )
    }

    if (confirmClearWatch) {
        AlertDialog(
            onDismissRequest = { confirmClearWatch = false },
            title = { Text("Clear watch history?") },
            text = { Text("Continue Watching items and saved playback positions will be removed. My List is kept.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearWatch = false
                        scope.launch {
                            val id = container.activeProfileId
                            runCatching { container.profiles.clearHistory(id) }
                            runCatching { container.resume.clearProfile(id) }
                            watchStatus = "Watch history cleared"
                        }
                    },
                ) {
                    Text("Clear", color = tokens.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearWatch = false }) {
                    Text("Cancel", color = tokens.textSecondary)
                }
            },
            containerColor = tokens.surfaceElevated,
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset settings?") },
            text = { Text("Appearance, player gestures, floating mini-player, and Wi-Fi download settings return to their defaults.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        holder.resetToDefaults()
                        container.subtitles.setStyle(
                            sub.style.copy(bgOpacity = SettingsStateHolder.DEFAULT_SUBTITLE_BG_OPACITY),
                        )
                        scope.launch {
                            runCatching { container.downloads.prefs.update { it.copy(wifiOnly = false) } }
                        }
                        resetStatus = "Settings restored to default."
                    },
                ) {
                    Text("Reset", color = tokens.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text("Cancel", color = tokens.textSecondary)
                }
            },
            containerColor = tokens.surfaceElevated,
        )
    }
}

@Composable
private fun AdultPrivacyToggleRow(gate: AdultGate) {
    val tokens = Cinetor.tokens
    val enabled by gate.enabled.collectAsStateWithLifecycle()
    val kids = !gate.canEnable
    var setupAction by remember { mutableStateOf<AdultSetupAction?>(null) }

    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Show 18+ section" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Show 18+ section",
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.textPrimary,
            )
            Text(
                if (kids) {
                    "Unavailable while Kids mode is active"
                } else if (enabled) {
                    "Adult Content is shown under Me"
                } else {
                    "Hidden everywhere until you turn it on and confirm you are 18+"
                },
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }
        Switch(
            checked = enabled,
            enabled = !kids,
            onCheckedChange = { on ->
                setupAction = if (on) AdultSetupAction.ENABLE else AdultSetupAction.DISABLE
            },
            colors = SwitchDefaults.colors(checkedTrackColor = tokens.accent),
            modifier = Modifier.semantics { contentDescription = "Toggle 18+ section" },
        )
    }

    AdultSetupFlowDialog(
        gate = gate,
        action = setupAction,
        onDismiss = { setupAction = null },
    )
}

@Composable
private fun StorageSummaryRow(storage: StorageInfo) {
    val tokens = Cinetor.tokens
    val usedText = formatStorageBytes(storage.usedByCinetor)
    val freeText = if (storage.freeBytes > 0 && storage.freeBytes < Long.MAX_VALUE / 2) {
        "${formatStorageBytes(storage.freeBytes)} free on device"
    } else {
        "Offline media storage"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Downloaded media storage",
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.textPrimary,
        )
        Text(
            "$usedText used by Cinetor · $freeText",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
    }
}

private fun formatStorageBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / 1_048_576.0
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

/**
 * Multi-column grid of all 8 themes (`Glass`, `Matte`, `Material`, `Aura`, `Pearl`, `Noir`, `Obsidian`, `Ember`)
 * so every theme card is visible on-screen simultaneously without horizontal scrolling.
 */
@Composable
private fun ThemeGridPicker(
    selected: AppTheme,
    onSelect: (AppTheme) -> Unit,
) {
    val widthDp = LocalConfiguration.current.screenWidthDp
    val columns = if (widthDp < 340) 2 else 4
    val ordered = remember {
        ThemeDisplayOrder + AppTheme.entries.filterNot { it in ThemeDisplayOrder }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ordered.chunked(columns).forEach { rowThemes ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowThemes.forEach { t ->
                    ThemePreviewCard(
                        theme = t,
                        selected = t == selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(t) },
                    )
                }
                repeat(columns - rowThemes.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Mini material + color swatch card of a theme — tap applies immediately without activity restart. */
@Composable
private fun ThemePreviewCard(
    theme: AppTheme,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val t = CinetorTokens.of(theme)
    val current = Cinetor.tokens
    val specular = themeUsesSpecularShine(theme)
    val outerShape = RoundedCornerShape(14.dp)
    val innerCardShape = RoundedCornerShape(7.dp)
    val buttonShape = RoundedCornerShape(4.dp)

    val idleBorder = if (t.isLight == current.isLight) {
        t.glassStroke.copy(alpha = t.glassStroke.alpha.coerceAtLeast(0.18f))
    } else {
        current.divider
    }
    val canvasBrush = if (specular && t.tintStrength > 0f) {
        Brush.verticalGradient(
            listOf(
                t.accent.copy(alpha = if (t.isLight) 0.12f else 0.24f),
                t.bg,
                t.bg,
            ),
        )
    } else {
        Brush.verticalGradient(listOf(t.bg, t.bg))
    }

    Column(
        modifier
            .clip(outerShape)
            .background(t.bg)
            .background(canvasBrush)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) current.accent else idleBorder,
                shape = outerShape,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Theme ${t.name}" }
            .padding(horizontal = 7.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(innerCardShape)
                .background(t.surfaceElevated)
                .then(
                    if (specular) {
                        Modifier.border(1.dp, t.glassStroke, innerCardShape)
                    } else {
                        Modifier
                    },
                )
                .padding(5.dp),
        ) {
            Column(
                Modifier.align(Alignment.TopStart),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(Modifier.width(26.dp).height(4.dp).clip(CircleShape).background(t.textPrimary.copy(alpha = 0.85f)))
                Box(Modifier.width(18.dp).height(3.dp).clip(CircleShape).background(t.textSecondary.copy(alpha = 0.65f)))
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(width = 18.dp, height = 7.dp)
                    .clip(buttonShape)
                    .background(t.accent),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwatchDot(t.bg, t.divider)
            SwatchDot(t.surfaceElevated, t.divider)
            SwatchDot(t.accent, Color.Transparent)
            SwatchDot(t.textPrimary, t.divider)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = t.accent,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = t.name,
                color = t.textPrimary,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SwatchDot(color: Color, ring: Color, sizeDp: Int = 8) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(color)
            .then(if (ring != Color.Transparent) Modifier.border(1.dp, ring, CircleShape) else Modifier),
    )
}

/** Shows the active theme's clear material description and labeled palette swatches right below the grid. */
@Composable
private fun ActiveThemeSummary(theme: AppTheme) {
    val tokens = Cinetor.tokens
    val pillShape = RoundedCornerShape(percent = 50)
    val cardShape = RoundedCornerShape(14.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(tokens.surfaceElevated.copy(alpha = if (tokens.isLight) 0.85f else 0.65f))
            .border(1.dp, tokens.divider, cardShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = tokens.name,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = tokens.textPrimary,
            )
            Box(
                Modifier
                    .clip(pillShape)
                    .background(tokens.accent.copy(alpha = if (tokens.isLight) 0.12f else 0.18f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = themeBadge(theme),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tokens.isLight) tokens.accent else tokens.textPrimary,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                SwatchDot(tokens.bg, tokens.glassStroke, sizeDp = 11)
                SwatchDot(tokens.surfaceElevated, tokens.glassStroke, sizeDp = 11)
                SwatchDot(tokens.accent, Color.Transparent, sizeDp = 11)
                SwatchDot(tokens.textPrimary, tokens.glassStroke, sizeDp = 11)
            }
        }
        Text(
            text = themeDescription(theme),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
    }
}

@Composable
private fun CacheRow(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var bytes by remember { mutableLongStateOf(-1L) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { bytes = runCatching { container.cacheBytes() }.getOrDefault(0L) }
    val label = if (bytes < 0) "Calculating…" else String.format(Locale.US, "%.1f MB used", bytes / 1_048_576.0)
    ActionRow("Clear image & stream cache", "Artwork & temporary stream cache · $label") {
        scope.launch {
            runCatching { container.clearImageCache() }
            refresh++
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass(Cinetor.cardShape, GlassLevel.SURFACE).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Cinetor.tokens.textPrimary,
        )
        content()
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(color = Cinetor.tokens.divider, thickness = 1.dp)
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tokens = Cinetor.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .semantics { contentDescription = title }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tokens.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = tokens.accent),
        )
    }
}

@Composable
private fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    val tokens = Cinetor.tokens
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = tokens.textPrimary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                val l = label(o)
                FilterChip(
                    selected = o == selected,
                    onClick = { onPick(o) },
                    label = { Text(l) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tokens.accent,
                        selectedLabelColor = tokens.onAccent,
                    ),
                    modifier = Modifier.semantics { contentDescription = "$title $l" },
                )
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = title }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tokens.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = tokens.textSecondary)
    }
}
