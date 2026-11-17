package com.mythbrix.cinetor.feature.downloads

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.download.DownloadChoice
import com.mythbrix.cinetor.core.download.DownloadKind
import com.mythbrix.cinetor.core.download.DownloadsModule
import com.mythbrix.cinetor.core.download.dedupeQualityOptions
import com.mythbrix.cinetor.core.download.MAX_CONNECTIONS
import com.mythbrix.cinetor.core.download.QualityOption
import com.mythbrix.cinetor.core.download.StreamTrackInfo
import com.mythbrix.cinetor.core.download.downloadKindOf
import com.mythbrix.cinetor.core.download.formatBytes
import com.mythbrix.cinetor.core.download.qualityOptions
import com.mythbrix.cinetor.design.Cinetor
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * Downloadable sources; gateway lists (resolution-tagged) keep MP4 files only when any exist — DASH
 * manifests there need signed cookies the Media3 downloader cannot probe.
 */
fun downloadableSources(sources: List<Source>): List<Source> {
    val ok = sources.filter { downloadKindOf(it.kind) != null && !isNonDownloadableUrl(it.url) }
    val tagged = ok.any { it.height != null }
    val files = ok.filter { it.kind == com.mythbrix.cinetor.core.data.SourceKind.MP4 }
    return if (tagged && files.isNotEmpty()) files.distinctBy { it.height } else ok
}

/** What the sheet downloads: one title/episode, or several episodes (season / next 3). */
data class DownloadTarget(val item: Item, val episodes: List<Episode> = emptyList(), val seasonNumber: Int? = null, val label: String? = null) {
    val episode: Episode? get() = episodes.singleOrNull()
}

/**
 * Options before a download starts: quality list with estimated size (bitrate × duration for HLS/DASH,
 * Content-Length HEAD for files), audio + subtitle tracks, storage location, connections (progressive).
 * Requests POST_NOTIFICATIONS (Android 13+) on Start.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadOptionsSheet(
    target: DownloadTarget,
    module: DownloadsModule,
    onDismiss: () -> Unit,
    onStarted: (Int) -> Unit,
    /** Gateway titles: resolve real sources (same URL + headers as playback) for an episode / the movie. */
    resolve: (suspend (Item, Episode?) -> List<Source>)? = null,
) {
    val t = Cinetor.tokens
    val scope = rememberCoroutineScope()
    val first = target.episodes.firstOrNull()
    var sources by remember(target) { mutableStateOf(downloadableSources(first?.sources ?: target.item.sources)) }
    val duration = (first?.durationSec ?: target.item.durationSec ?: 0).toLong()
    val options = remember(target) { mutableStateListOf<QualityOption>() }
    var tracks by remember(target) { mutableStateOf<StreamTrackInfo?>(null) }
    var loading by remember(target) { mutableStateOf(true) }
    var selected by remember(target) { mutableStateOf<QualityOption?>(null) }
    var audio by remember(target) { mutableStateOf<String?>(null) }
    val subs = remember(target) { mutableStateListOf<String>() }
    var connections by remember { mutableFloatStateOf(module.defaultConnectionCount().toFloat()) }

    LaunchedEffect(target) {
        if (resolve != null) {
            sources = downloadableSources(runCatching { resolve(target.item, first) }.getOrDefault(emptyList()))
        }
        // Cheap placeholders first (sizes unknown), then probe each source.
        options += dedupeQualityOptions(sources.flatMap { qualityOptions(it, null, duration) })
        selected = options.firstOrNull()
        val probed = mutableListOf<QualityOption>()
        for (s in sources) {
            val (opts, tr) = module.options(s, duration)
            probed += opts
            if (tr != null && tracks == null) tracks = tr
        }
        if (probed.isNotEmpty()) {
            // Hardening §4: identical resolution + format + size is listed ONCE.
            val unique = dedupeQualityOptions(probed)
            options.clear(); options += unique
            selected = unique.first()
        }
        loading = false
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val subtitleChoices = remember(target, tracks) {
        ((first?.subtitles ?: target.item.subtitles).map { it.label } + tracks?.texts.orEmpty()).distinct()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.surfaceElevated,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Download", style = MaterialTheme.typography.titleLarge, color = t.textPrimary, fontWeight = FontWeight.Bold)
            Text(
                target.label ?: (target.episode?.let { "${target.item.title} · E${it.n} ${it.title}" } ?: target.item.title),
                style = MaterialTheme.typography.bodyMedium, color = t.textSecondary,
            )
            Section("Quality")
            if (options.isEmpty()) {
                Text(if (loading) "Loading…" else "No downloadable source", color = t.textSecondary)
            }
            options.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { selected = q }
                        .semantics { contentDescription = "Quality ${q.label}" }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == q, onClick = { selected = q }, colors = RadioButtonDefaults.colors(selectedColor = t.accent))
                    Column(Modifier.weight(1f)) {
                        Text(q.label, color = t.textPrimary, style = MaterialTheme.typography.bodyLarge)
                        val meta = listOfNotNull(q.codecs, q.bitrate?.let { "${it / 1000} kbps" }).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, color = t.textTertiary, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        if (q.estimatedBytes > 0) "≈ ${formatBytes(q.estimatedBytes)}" else if (loading) "…" else "size ?",
                        color = t.textSecondary, style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = t.accent, strokeWidth = 2.dp)

            val audios = tracks?.audios.orEmpty()
            if (audios.size > 1 && selected?.kind != DownloadKind.PROGRESSIVE) {
                Section("Audio")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    audios.forEach { a -> Chip(a, audio == a) { audio = if (audio == a) null else a } }
                }
            }
            if (subtitleChoices.isNotEmpty()) {
                Section("Subtitles")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    subtitleChoices.forEach { s -> Chip(s, s in subs) { if (s in subs) subs.remove(s) else subs.add(s) } }
                }
            }
            Section("Location")
            val adultTarget = target.item.space.name == "ADULT"
            val location by module.location.collectAsStateWithLifecycle()
            val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) scope.launch { module.setLocation(uri) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    com.mythbrix.cinetor.core.download.DownloadLocations.label(location, adultTarget),
                    color = t.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                if (!adultTarget) {
                    androidx.compose.material3.TextButton(
                        onClick = { runCatching { pickFolder.launch(null) } },
                        modifier = Modifier.semantics { contentDescription = "Choose download folder" },
                    ) {
                        Text(if (location.chosen) "Change" else "Choose folder", color = t.accent)
                    }
                }
            }
            if (selected?.kind == DownloadKind.PROGRESSIVE) {
                Section("Connections: ${connections.toInt()}")
                Slider(
                    value = connections, onValueChange = { connections = it }, valueRange = 1f..MAX_CONNECTIONS.toFloat(),
                    steps = MAX_CONNECTIONS - 2,
                    colors = SliderDefaults.colors(thumbColor = t.accent, activeTrackColor = t.accent),
                )
            }
            Button(
                enabled = selected != null,
                onClick = {
                    val q = selected ?: return@Button
                    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    scope.launch {
                        val choice = DownloadChoice(q, audio, subs.toList(), connections.toInt())
                        val eps = target.episodes
                        if (eps.isEmpty()) {
                            module.start(target.item, null, null, choice)
                        } else {
                            val srcIndex = sources.indexOf(q.source).coerceAtLeast(0)
                            eps.forEach { ep ->
                                val epSources = if (ep != first && resolve != null) {
                                    downloadableSources(runCatching { resolve(target.item, ep) }.getOrDefault(emptyList()))
                                } else {
                                    downloadableSources(ep.sources)
                                }
                                val epSrc = epSources.firstOrNull { q.height != null && it.height == q.height }
                                    ?: epSources.getOrNull(srcIndex) ?: q.source
                                val epChoice = if (ep == first) choice else choice.copy(quality = q.copy(source = epSrc, estimatedBytes = -1))
                                module.start(target.item, ep, target.seasonNumber, epChoice)
                            }
                        }
                        onStarted(maxOf(1, eps.size))
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { contentDescription = "Start download" },
                colors = ButtonDefaults.buttonColors(containerColor = t.accent, contentColor = t.onAccent),
            ) {
                Text(if (target.episodes.size > 1) "Download ${target.episodes.size} episodes" else "Start download")
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Cinetor.tokens.textPrimary, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = Cinetor.tokens
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = t.accent.copy(alpha = 0.25f),
            selectedLabelColor = t.textPrimary,
            labelColor = t.textSecondary,
        ),
    )
}
