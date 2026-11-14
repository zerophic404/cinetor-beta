package com.mythbrix.cinetor.feature.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AssetUris
import com.mythbrix.cinetor.core.download.DownloadInfo
import com.mythbrix.cinetor.core.download.DownloadSort
import com.mythbrix.cinetor.core.download.DownloadStatus
import com.mythbrix.cinetor.core.download.DownloadsModule
import com.mythbrix.cinetor.core.download.MIB
import com.mythbrix.cinetor.core.download.formatBytes
import com.mythbrix.cinetor.core.download.formatDuration
import com.mythbrix.cinetor.core.download.formatSpeed
import com.mythbrix.cinetor.core.download.isActive
import com.mythbrix.cinetor.core.download.progress
import com.mythbrix.cinetor.core.download.sortDownloads
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class DownloadsTab(val title: String) { ACTIVE("Active"), COMPLETED("Completed"), FAILED("Failed") }

/** Splits downloads into the three tabs (paused counts as active). */
fun tabOf(info: DownloadInfo): DownloadsTab = when (info.record.status) {
    DownloadStatus.COMPLETED -> DownloadsTab.COMPLETED
    DownloadStatus.FAILED -> DownloadsTab.FAILED
    else -> DownloadsTab.ACTIVE
}

/**
 * Downloads (from Me): tabs Active / Completed / Failed, storage bar (Cinetor vs free), sort, long-press
 * multi-select delete, quick settings (Wi-Fi only, charging only, parallel downloads, speed limit, connections),
 * tap → [DownloadDetailsSheet]. Adult downloads only appear while the adult space is unlocked.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(module: DownloadsModule, contentPadding: PaddingValues, onOpenItem: (String) -> Unit) {
    val t = Cinetor.tokens
    val all by module.visible.collectAsStateWithLifecycle()
    val storage by module.storage.collectAsStateWithLifecycle()
    val policy by module.prefs.policy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(DownloadsTab.ACTIVE) }
    var sort by rememberSaveable { mutableStateOf(DownloadSort.NEWEST) }
    var sortMenu by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    val selection = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        while (true) {
            module.refreshStorage()
            delay(5_000)
        }
    }
    val shown = sortDownloads(all.filter { tabOf(it) == tab }, sort)

    LazyColumn(
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 16.dp),
        modifier = Modifier.fillMaxSize().semantics { contentDescription = "Downloads screen" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "head") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloads", style = MaterialTheme.typography.headlineSmall, color = t.textPrimary, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    if (selection.isNotEmpty()) {
                        Text("${selection.size} selected", color = t.textSecondary, style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = {
                            val ids = selection.toList()
                            selection.clear()
                            scope.launch { ids.forEach { module.engine.delete(it) } }
                        }, modifier = Modifier.semantics { contentDescription = "Delete selected" }) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, tint = t.error)
                        }
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }, modifier = Modifier.semantics { contentDescription = "Sort" }) {
                            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, tint = t.textPrimary)
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            DownloadSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() } + if (s == sort) "  ✓" else "") },
                                    onClick = { sort = s; sortMenu = false },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { showSettings = !showSettings }, modifier = Modifier.semantics { contentDescription = "Download settings" }) {
                        Icon(Icons.Rounded.Tune, contentDescription = null, tint = if (showSettings) t.accent else t.textPrimary)
                    }
                }
                StorageBar(storage.usedByCinetor, storage.freeBytes, storage.totalBytes)
                if (showSettings) {
                    Column(
                        Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), GlassLevel.SURFACE).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Toggle("Wi-Fi only", policy.wifiOnly) { v -> scope.launch { module.prefs.update { it.copy(wifiOnly = v) } } }
                        Toggle("Only while charging", policy.chargingOnly) { v -> scope.launch { module.prefs.update { it.copy(chargingOnly = v) } } }
                        Stepper("Parallel downloads", policy.concurrency.toFloat(), 1f..5f, "${policy.concurrency}") { v ->
                            scope.launch { module.prefs.update { it.copy(maxConcurrent = v.toInt()) } }
                        }
                        val limitMb = (policy.speedLimitBps / MIB).toFloat()
                        Stepper("Speed limit", limitMb, 0f..20f, if (policy.speedLimitBps <= 0) "Unlimited" else "${limitMb.toInt()} MB/s") { v ->
                            scope.launch { module.prefs.update { it.copy(speedLimitBps = v.toLong() * MIB) } }
                        }
                        Stepper("Connections per file", policy.connections.toFloat(), 0f..16f,
                            if (policy.connections <= 0) "Auto (${module.defaultConnectionCount()})" else "${policy.connections}") { v ->
                            scope.launch { module.prefs.update { it.copy(connections = v.toInt()) } }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DownloadsTab.entries.forEach { tb ->
                        val count = all.count { tabOf(it) == tb }
                        val sel = tb == tab
                        Text(
                            "${tb.title} $count",
                            color = if (sel) t.onAccent else t.textSecondary,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (sel) t.accent else t.surfaceElevated)
                                .clickable { tab = tb; selection.clear() }
                                .semantics { contentDescription = "Tab ${tb.title}" }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                Text(
                    when (tab) {
                        DownloadsTab.ACTIVE -> "Nothing downloading. Tap Download on any title."
                        DownloadsTab.COMPLETED -> "No downloads yet."
                        DownloadsTab.FAILED -> "No failed downloads."
                    },
                    color = t.textSecondary, modifier = Modifier.padding(24.dp),
                )
            }
        }
        items(shown, key = { it.record.id }) { info ->
            val id = info.record.id
            val selected = id in selection
            DownloadRow(
                info = info,
                selected = selected,
                modifier = Modifier.combinedClickable(
                    onClick = {
                        if (selection.isNotEmpty()) { if (selected) selection.remove(id) else selection.add(id) } else detailsId = id
                    },
                    onLongClick = { if (selected) selection.remove(id) else selection.add(id) },
                ),
                onPrimary = {
                    scope.launch {
                        when (info.record.status) {
                            DownloadStatus.PAUSED -> module.engine.resume(id)
                            DownloadStatus.FAILED -> module.engine.retry(id)
                            DownloadStatus.COMPLETED -> onOpenItem(info.record.itemId)
                            else -> module.engine.pause(id)
                        }
                    }
                },
            )
        }
    }
    detailsId?.let { id ->
        DownloadDetailsSheet(id, module, onDismiss = { detailsId = null }, onOpen = { itemId -> detailsId = null; onOpenItem(itemId) })
    }
}

@Composable
private fun DownloadRow(info: DownloadInfo, selected: Boolean, modifier: Modifier, onPrimary: () -> Unit) {
    val t = Cinetor.tokens
    val r = info.record
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .then(if (selected) Modifier.border(2.dp, t.accent, RoundedCornerShape(14.dp)) else Modifier)
            .glass(RoundedCornerShape(14.dp), GlassLevel.SURFACE)
            .then(modifier)
            .semantics { contentDescription = "Download ${r.title}" }
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AsyncImage(
                model = AssetUris.of(r.poster), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp, 80.dp).clip(RoundedCornerShape(8.dp)).background(t.surface),
            )
            if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = t.accent, modifier = Modifier.align(Alignment.TopEnd).size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(r.title, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            r.subtitle?.let { Text(it, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val line = when (r.status) {
                DownloadStatus.DOWNLOADING -> {
                    val l = info.live
                    "${(r.progress * 100).toInt()}% · ${formatSpeed(l?.currentBps ?: 0)}" + (l?.etaSec?.let { " · ${formatDuration(it)} left" } ?: "")
                }
                DownloadStatus.COMPLETED -> "${formatBytes(maxOf(r.totalBytes, r.downloadedBytes))} · ${r.qualityLabel}"
                DownloadStatus.FAILED -> r.lastError ?: "Failed"
                else -> "${statusText(r.status, r.waitingReason)} · ${formatBytes(r.downloadedBytes)} / ${formatBytes(r.totalBytes)}"
            }
            Text(line, color = statusColor(r.status), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.isActive || r.status == DownloadStatus.PAUSED) {
                LinearProgressIndicator(
                    progress = { r.progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = t.accent, trackColor = t.divider,
                )
            } else if (r.status == DownloadStatus.COMPLETED) {
                DownloadedBadge()
            }
        }
        IconButton(onClick = onPrimary, modifier = Modifier.semantics { contentDescription = "Primary action ${r.title}" }) {
            val icon = when (r.status) {
                DownloadStatus.PAUSED, DownloadStatus.COMPLETED -> Icons.Rounded.PlayArrow
                DownloadStatus.FAILED -> Icons.Rounded.Refresh
                else -> Icons.Rounded.Pause
            }
            Icon(icon, contentDescription = null, tint = t.textPrimary)
        }
    }
}

@Composable
private fun StorageBar(used: Long, free: Long, total: Long) {
    val t = Cinetor.tokens
    val safeTotal = total.coerceAtLeast(1)
    val other = (safeTotal - free - used).coerceAtLeast(0)
    Column(Modifier.semantics { contentDescription = "Storage bar" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(t.surfaceElevated)) {
            if (other > 0) Box(Modifier.weight(other.toFloat()).height(8.dp).background(t.textTertiary))
            if (used > 0) Box(Modifier.weight(used.toFloat()).height(8.dp).background(t.accent))
            if (free > 0) Box(Modifier.weight(free.toFloat()).height(8.dp).background(t.surfaceElevated))
        }
        Row {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(t.accent).align(Alignment.CenterVertically))
            Spacer(Modifier.width(4.dp))
            Text("Cinetor ${formatBytes(used)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text("Free ${formatBytes(free)} of ${formatBytes(total)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val t = Cinetor.tokens
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = t.accent),
            modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun Stepper(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    val t = Cinetor.tokens
    var local by remember(value) { mutableStateOf(value) }
    Column {
        Row {
            Text(label, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, color = t.textSecondary, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) },
            valueRange = range, steps = (range.endInclusive - range.start).toInt() - 1,
            colors = SliderDefaults.colors(thumbColor = t.accent, activeTrackColor = t.accent),
        )
    }
}
