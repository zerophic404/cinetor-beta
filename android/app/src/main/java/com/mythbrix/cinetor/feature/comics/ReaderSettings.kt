package com.mythbrix.cinetor.feature.comics

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass

/** Liquid Glass bottom panel: reading mode override, data-saver / server switch, and brightness. */
@Composable
fun ReaderSettingsPanel(
    visible: Boolean,
    state: ReaderState,
    onMode: (ReaderMode) -> Unit,
    onBrightness: (Float?) -> Unit,
    onDataSaverPages: (Boolean) -> Unit = {},
    onSwitchServer: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val tokens = Cinetor.tokens
    Box(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(tokens.scrim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), GlassLevel.SHEET)
                    .navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Reading mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                ReaderMode.entries.forEach { m ->
                    val selected = m == state.mode
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) tokens.accent.copy(alpha = 0.18f) else tokens.glassFill)
                            .clickable { onMode(m) }
                            .semantics { contentDescription = "Mode ${m.name}" }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.label, color = tokens.textPrimary, style = MaterialTheme.typography.bodyLarge)
                            if (m == state.defaultMode) {
                                Text("Default for this title", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = tokens.accent)
                    }
                }

                if (MangaDexChapterCheck.isRemoteComic(state.comic.id)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        Icon(Icons.Rounded.HighQuality, contentDescription = null, tint = tokens.textSecondary)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text("Data-saver pages", color = tokens.textPrimary, style = MaterialTheme.typography.bodyLarge)
                            Text("Faster compressed pages on slow networks", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = state.dataSaverPages,
                            onCheckedChange = onDataSaverPages,
                            colors = SwitchDefaults.colors(checkedTrackColor = tokens.accent),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, tint = tokens.textSecondary)
                        Text(
                            if (state.usingBackupServer) "Backup server active" else "Pages loading slowly?",
                            color = tokens.textPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 10.dp).weight(1f),
                        )
                        TextButton(onClick = onSwitchServer) {
                            Text("Switch server", color = tokens.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Rounded.Brightness6, contentDescription = null, tint = tokens.textSecondary)
                    Text("Brightness", color = tokens.textPrimary, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    Text("Auto", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = state.brightness == null,
                        onCheckedChange = { auto -> onBrightness(if (auto) null else 0.6f) },
                        colors = SwitchDefaults.colors(checkedTrackColor = tokens.accent),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (state.brightness != null) {
                    Slider(
                        value = state.brightness,
                        onValueChange = { onBrightness(it) },
                        valueRange = 0.02f..1f,
                        colors = SliderDefaults.colors(thumbColor = tokens.accent, activeTrackColor = tokens.accent),
                        modifier = Modifier.semantics { contentDescription = "Reader brightness" },
                    )
                }
            }
        }
    }
}

private object MangaDexChapterCheck {
    fun isRemoteComic(id: String): Boolean = id.startsWith("cm:")
}

/** Chapter download button with progress / done / failed states. */
@Composable
fun ChapterDownloadIcon(state: ChapterDownload?, onDownload: () -> Unit) {
    val tokens = Cinetor.tokens
    when (state) {
        is ChapterDownload.Running -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                color = tokens.accent,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(22.dp).semantics { contentDescription = "Downloading chapter" },
            )
        }
        ChapterDownload.Done -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.DownloadDone,
                contentDescription = "Chapter downloaded",
                tint = tokens.success,
            )
        }
        is ChapterDownload.Failed -> IconButton(onClick = onDownload) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = "Retry chapter download", tint = tokens.error)
        }
        null -> IconButton(onClick = onDownload) {
            Icon(Icons.Rounded.Download, contentDescription = "Download chapter", tint = tokens.textPrimary)
        }
    }
}
