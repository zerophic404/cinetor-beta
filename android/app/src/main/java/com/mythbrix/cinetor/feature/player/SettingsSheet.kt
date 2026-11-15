package com.mythbrix.cinetor.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.player.PLAYBACK_SPEEDS
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.design.Cinetor

enum class SettingsPage { MAIN, QUALITY, SPEED, AUDIO, SUBTITLES, SLEEP }

/** "Normal" for 1×, else "1.25×". */
fun speedLabel(speed: Float): String =
    if (speed == 1f) "Normal" else (if (speed % 1f == 0f) "${speed.toInt()}" else speed.toString().trimEnd('0')) + "×"

fun qualityLabel(ui: PlayerUiState, screen: PlayerScreenState? = null): String {
    screen?.sources?.getOrNull(screen.sourceIndex)?.let { src ->
        if (screen.sources.size > 1 && ui.tracks.forcedHeight == null) return src.label ?: "Source ${screen.sourceIndex + 1}"
    }
    val t = ui.tracks
    return t.forcedHeight?.let { "${it}p" } ?: ("Auto" + (t.playingHeight?.takeIf { it > 0 }?.let { " (${it}p)" } ?: ""))
}

/** Extra subtitle rows contributed by Task 3.5 (load file, style, sync). */
class SubtitleExtras(
    val onLoadFile: (() -> Unit)? = null,
    val onStyle: (() -> Unit)? = null,
    val onSync: (() -> Unit)? = null,
    val sideLoadedLabel: String? = null,
    val onClearSideLoaded: (() -> Unit)? = null,
)

/** Player settings: Quality / Speed / Audio / Subtitles / Sleep timer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    ui: PlayerUiState,
    screen: PlayerScreenState,
    holder: PlayerStateHolder,
    onDismiss: () -> Unit,
    extras: SubtitleExtras = SubtitleExtras(),
) {
    var page by remember { mutableStateOf(SettingsPage.MAIN) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Color(0xF0101010),
        contentColor = Color.White,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            when (page) {
                SettingsPage.MAIN -> {
                    val subLabel = extras.sideLoadedLabel
                        ?: ui.tracks.text.firstOrNull { it.selected }?.label
                        ?: "Off"
                    SheetRow(Icons.Rounded.HighQuality, "Quality", qualityLabel(ui, screen)) { page = SettingsPage.QUALITY }
                    SheetRow(Icons.Rounded.Speed, "Playback speed", speedLabel(ui.speed)) { page = SettingsPage.SPEED }
                    SheetRow(Icons.Rounded.Audiotrack, "Audio", ui.tracks.audio.firstOrNull { it.selected }?.label ?: "Default") {
                        page = SettingsPage.AUDIO
                    }
                    SheetRow(Icons.Rounded.ClosedCaption, "Subtitles", subLabel) { page = SettingsPage.SUBTITLES }
                    SheetRow(Icons.Rounded.Bedtime, "Sleep timer", screen.sleep.label) { page = SettingsPage.SLEEP }
                }
                SettingsPage.QUALITY -> SubPage("Quality", { page = SettingsPage.MAIN }) {
                    val t = ui.tracks
                    // Provider sources (gateway resolutions): switching keeps the position.
                    if (screen.sources.size > 1) {
                        item { Text("Source", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
                        items(screen.sources.size, key = { "src$it" }) { i ->
                            val src = screen.sources[i]
                            val size = src.sizeBytes?.let { b -> " · " + "%.1f GB".format(b / 1_073_741_824.0).replace(" 0.", " 0.") }.orEmpty()
                            Choice((src.label ?: "Source ${i + 1}") + size, screen.sourceIndex == i) {
                                holder.selectSource(i); page = SettingsPage.MAIN
                            }
                        }
                        if (t.video.isNotEmpty()) item { Text("Stream tracks", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
                    }
                    val auto = "Auto" + (t.playingHeight?.takeIf { it > 0 }?.let { " (${it}p)" } ?: "")
                    item { Choice(auto, t.autoQuality) { holder.selectQuality(null); page = SettingsPage.MAIN } }
                    items(t.video, key = { it.height }) { q ->
                        Choice(q.label, t.forcedHeight == q.height) { holder.selectQuality(q.height); page = SettingsPage.MAIN }
                    }
                }
                SettingsPage.SPEED -> SubPage("Playback speed", { page = SettingsPage.MAIN }) {
                    items(PLAYBACK_SPEEDS, key = { it }) { sp ->
                        Choice(speedLabel(sp), ui.speed == sp) { holder.setSpeed(sp); page = SettingsPage.MAIN }
                    }
                }
                SettingsPage.AUDIO -> SubPage("Audio", { page = SettingsPage.MAIN }) {
                    if (ui.tracks.audio.isEmpty()) item { Choice("Default", true) { page = SettingsPage.MAIN } }
                    items(ui.tracks.audio, key = { it.id }) { a ->
                        Choice(a.label, a.selected) { holder.selectAudio(a.id); page = SettingsPage.MAIN }
                    }
                }
                SettingsPage.SUBTITLES -> SubPage("Subtitles", { page = SettingsPage.MAIN }) {
                    val anySelected = extras.sideLoadedLabel != null || ui.tracks.text.any { it.selected }
                    item {
                        Choice("Off", !anySelected) {
                            extras.onClearSideLoaded?.invoke()
                            holder.selectText(null)
                            page = SettingsPage.MAIN
                        }
                    }
                    extras.sideLoadedLabel?.let { label -> item { Choice(label, true) { page = SettingsPage.MAIN } } }
                    items(ui.tracks.text, key = { it.id }) { t ->
                        Choice(t.label, t.selected && extras.sideLoadedLabel == null) {
                            extras.onClearSideLoaded?.invoke()
                            holder.selectText(t.id)
                            page = SettingsPage.MAIN
                        }
                    }
                    extras.onLoadFile?.let { f -> item { Action("Load subtitle file (SRT/VTT)…") { f(); onDismiss() } } }
                    extras.onSync?.let { f -> item { Action("Sync offset…") { f(); onDismiss() } } }
                    extras.onStyle?.let { f -> item { Action("Subtitle style…") { f(); onDismiss() } } }
                }
                SettingsPage.SLEEP -> SubPage("Sleep timer", { page = SettingsPage.MAIN }) {
                    items(SleepOption.entries, key = { it.name }) { o ->
                        Choice(o.label, screen.sleep == o) { holder.setSleep(o); page = SettingsPage.MAIN }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { contentDescription = title }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Back", tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), content = content)
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { contentDescription = label }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.size(24.dp).let { it })
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) Cinetor.tokens.accent else Color.White,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Cinetor.tokens.accent)
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White.copy(alpha = 0.9f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 58.dp, vertical = 13.dp),
    )
}
