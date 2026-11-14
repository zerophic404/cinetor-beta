package com.mythbrix.cinetor.feature.downloads

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AssetUris
import com.mythbrix.cinetor.core.download.DownloadEvent
import com.mythbrix.cinetor.core.download.DownloadKind
import com.mythbrix.cinetor.core.download.DownloadStatus
import com.mythbrix.cinetor.core.download.DownloadsModule
import com.mythbrix.cinetor.core.download.formatBytes
import com.mythbrix.cinetor.core.download.formatDuration
import com.mythbrix.cinetor.core.download.formatSpeed
import com.mythbrix.cinetor.core.download.progress
import com.mythbrix.cinetor.core.download.sourceHost
import com.mythbrix.cinetor.core.download.subtitleList
import com.mythbrix.cinetor.design.Cinetor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything about one download: status, % + bytes, current/average speed + 60 s graph, ETA, elapsed,
 * per-connection chunk bars (progressive) or segments (HLS/DASH), quality/codec/bitrate/container, tracks,
 * path, host, timestamps, retries, last error, event log, and actions.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadDetailsSheet(id: String, module: DownloadsModule, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val t = Cinetor.tokens
    val all by module.visible.collectAsStateWithLifecycle()
    val info = all.firstOrNull { it.record.id == id }
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<DownloadEvent>>(emptyList()) }
    LaunchedEffect(id) {
        while (true) {
            events = module.engine.events(id)
            delay(2_000)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.surfaceElevated,
    ) {
        if (info == null) {
            Text("Download removed", color = t.textSecondary, modifier = Modifier.padding(24.dp))
            return@ModalBottomSheet
        }
        val r = info.record
        val live = info.live
        val engine = module.engine
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)
                .semantics { contentDescription = "Download details" },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AsyncImage(
                    model = AssetUris.of(r.poster), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp, 104.dp).clip(RoundedCornerShape(10.dp)).background(t.surface),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(r.title, style = MaterialTheme.typography.titleMedium, color = t.textPrimary, fontWeight = FontWeight.Bold)
                    r.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.textSecondary) }
                    Text(statusText(r.status, r.waitingReason), color = statusColor(r.status), style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${(r.progress * 100).toInt()}% · ${formatBytes(r.downloadedBytes)} / ${formatBytes(r.totalBytes)}",
                        color = t.textPrimary, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { r.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = t.accent, trackColor = t.divider,
            )

            Header("Speed")
            Grid(
                "Current" to formatSpeed(live?.currentBps ?: 0),
                "Average" to formatSpeed(live?.averageBps ?: 0),
                "ETA" to (live?.etaSec?.let { formatDuration(it) } ?: "—"),
                "Elapsed" to (live?.elapsedMs?.let { formatDuration(it / 1000) }
                    ?: r.startedAt?.let { s -> formatDuration(((r.finishedAt ?: s) - s) / 1000) } ?: "—"),
            )
            SpeedGraph(live?.speedHistory.orEmpty())

            if (r.kind == DownloadKind.PROGRESSIVE) {
                Header("Connections (${live?.activeConnections ?: 0} active / ${r.connections} max)")
                val chunks = live?.chunks.orEmpty()
                if (chunks.isEmpty()) {
                    Text(if (r.acceptRanges == false) "Server has no range support → single connection" else "Chunk map appears while downloading",
                        color = t.textTertiary, style = MaterialTheme.typography.bodySmall)
                }
                chunks.forEach { c ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("#${c.index}", color = if (c.active) t.accent else t.textTertiary, style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.width(30.dp))
                        LinearProgressIndicator(
                            progress = { c.fraction }, modifier = Modifier.weight(1f).height(5.dp).padding(top = 5.dp).clip(RoundedCornerShape(3.dp)),
                            color = if (c.active) t.accent else t.success, trackColor = t.divider,
                        )
                        Text(formatBytes(c.downloaded), color = t.textTertiary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(64.dp))
                    }
                }
            } else {
                Header("Segments")
                Text(
                    if (r.segmentsTotal > 0) "${r.segmentsDone} / ${r.segmentsTotal} (≈)" else "—",
                    color = t.textPrimary, style = MaterialTheme.typography.bodyMedium,
                )
            }

            Header("Media")
            Grid(
                "Quality" to r.qualityLabel,
                "Resolution" to (if (r.width != null && r.height != null) "${r.width}×${r.height}" else r.height?.let { "${it}p" } ?: "—"),
                "Codec" to (r.codecs ?: r.mimeType ?: "—"),
                "Bitrate" to (r.bitrate?.let { "${it / 1000} kbps" } ?: "—"),
                "Container" to r.container.uppercase().ifBlank { "—" },
                "Audio" to (r.audioTrack ?: "Default"),
                "Subtitles" to r.subtitleList.joinToString().ifBlank { "None" },
                "Engine" to if (r.kind == DownloadKind.PROGRESSIVE) "Chunked HTTP" else "Media3 ${r.kind}",
            )
            Header("Source")
            Mono("Host", r.sourceHost)
            Mono("Path", r.filePath)
            Grid(
                "Started" to fmtTime(r.startedAt),
                "Finished" to fmtTime(r.finishedAt),
                "Added" to fmtTime(r.createdAt),
                "Retries" to r.retryCount.toString(),
            )
            r.lastError?.let { Mono("Last error", it) }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (r.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.WAITING ->
                        Action("Pause") { scope.launch { engine.pause(r.id) } }
                    DownloadStatus.PAUSED -> Action("Resume") { scope.launch { engine.resume(r.id) } }
                    DownloadStatus.FAILED -> Action("Retry") { scope.launch { engine.retry(r.id) } }
                    DownloadStatus.COMPLETED -> Action("Play") { onOpen(r.itemId) }
                }
                if (r.status != DownloadStatus.COMPLETED) {
                    Action("Cancel") { scope.launch { engine.cancel(r.id); onDismiss() } }
                    Action("Move up") { scope.launch { engine.move(r.id, -1) } }
                }
                Action("Open") { onOpen(r.itemId) }
                Action("Delete") { scope.launch { engine.delete(r.id); onDismiss() } }
            }

            if (events.isNotEmpty()) {
                Header("Log")
                events.take(12).forEach { e ->
                    Text("${fmtClock(e.time)}  ${e.type}  ${e.message}", color = t.textTertiary, style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun SpeedGraph(samples: List<Long>) {
    val t = Cinetor.tokens
    Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(10.dp)).background(t.surface).semantics { contentDescription = "Speed graph" }) {
        Canvas(Modifier.fillMaxWidth().height(72.dp).padding(6.dp)) {
            if (samples.size < 2) return@Canvas
            val max = (samples.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
            val stepX = size.width / 59f
            val startX = size.width - stepX * (samples.size - 1)
            val line = Path()
            samples.forEachIndexed { i, v ->
                val x = startX + i * stepX
                val y = size.height - (v / max) * size.height
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(startX, size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(t.accent.copy(alpha = 0.35f), t.accent.copy(alpha = 0f))))
            drawPath(line, t.accent, style = Stroke(width = 2.dp.toPx()))
            drawLine(t.divider, Offset(0f, size.height), Offset(size.width, size.height))
        }
        Text("60 s", color = t.textTertiary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(6.dp))
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Cinetor.tokens.textPrimary, modifier = Modifier.padding(top = 8.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Grid(vararg cells: Pair<String, String>) {
    val t = Cinetor.tokens
    FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 2) {
        cells.forEach { (k, v) ->
            Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                Text(k, color = t.textTertiary, style = MaterialTheme.typography.labelSmall)
                Text(v, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
        }
    }
}

@Composable
private fun Mono(label: String, value: String) {
    val t = Cinetor.tokens
    Column {
        Text(label, color = t.textTertiary, style = MaterialTheme.typography.labelSmall)
        Text(value.ifBlank { "—" }, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        Text(label, color = Cinetor.tokens.textPrimary)
    }
}

fun statusText(s: DownloadStatus, reason: String?): String = when (s) {
    DownloadStatus.QUEUED -> "Queued"
    DownloadStatus.DOWNLOADING -> "Downloading"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.WAITING -> reason ?: "Waiting"
    DownloadStatus.COMPLETED -> "Downloaded"
    DownloadStatus.FAILED -> "Failed"
}

@Composable
fun statusColor(s: DownloadStatus) = when (s) {
    DownloadStatus.COMPLETED -> Cinetor.tokens.success
    DownloadStatus.FAILED -> Cinetor.tokens.error
    DownloadStatus.WAITING -> Cinetor.tokens.warning
    DownloadStatus.DOWNLOADING -> Cinetor.tokens.accent
    else -> Cinetor.tokens.textSecondary
}

private fun fmtTime(ms: Long?): String =
    if (ms == null || ms <= 0) "—" else SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault()).format(Date(ms))

private fun fmtClock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
