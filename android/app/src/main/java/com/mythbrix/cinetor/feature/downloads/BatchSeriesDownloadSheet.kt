package com.mythbrix.cinetor.feature.downloads

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.download.DownloadInfo
import com.mythbrix.cinetor.core.download.DownloadLocations
import com.mythbrix.cinetor.core.download.DownloadStatus
import com.mythbrix.cinetor.core.download.DownloadsModule
import com.mythbrix.cinetor.core.download.downloadIdFor
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Multi-Season & Multi-Episode Batch Series Download Sheet (Liquid Glass).
 * Preserves checked/unchecked episodes across all seasons, supports Select Season / Select All Seasons /
 * Clear All, Preferred Quality (`1080p`, `720p`, `480p`, `360p`), SAF destination picker, TV D-pad focus,
 * and sequential background resolution with mid-resolve cancellation.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BatchSeriesDownloadSheet(
    item: Item,
    initialSeasonNumber: Int,
    module: DownloadsModule,
    downloadsById: Map<String, DownloadInfo>,
    onDismiss: () -> Unit,
    onQueued: (Int) -> Unit,
    onNoStreamsFound: () -> Unit = {},
    preselectedEpisodeIds: Set<String>? = null,
    resolve: (suspend (Item, Int, Episode) -> List<Source>)? = null,
) {
    val tokens = Cinetor.tokens
    val scope = rememberCoroutineScope()

    val completedKeys = remember(item.id, item.seasons, downloadsById) {
        buildSet {
            item.seasons.forEach { s ->
                s.episodes.forEach { ep ->
                    if (downloadsById[downloadIdFor(item.id, ep.id)]?.record?.status == DownloadStatus.COMPLETED) {
                        add(BatchEpisodeKey(s.n, ep.id))
                    }
                }
            }
        }
    }

    val selection = remember(item.id, initialSeasonNumber, preselectedEpisodeIds) {
        BatchSelection(
            seasons = item.seasons,
            initialSeasonNumber = initialSeasonNumber,
            startEmpty = preselectedEpisodeIds != null,
            completedEpisodeKeys = completedKeys,
        ).also { holder ->
            if (preselectedEpisodeIds != null) {
                item.seasons.forEach { s ->
                    s.episodes.forEach { ep ->
                        if (ep.id in preselectedEpisodeIds) {
                            holder.toggleEpisode(s.n, ep.id)
                        }
                    }
                }
            }
        }
    }

    val selState by selection.state.collectAsStateWithLifecycle()
    val location by module.location.collectAsStateWithLifecycle()
    val adultTarget = item.space.name == "ADULT"

    var resolveJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<BatchResolveProgress?>(null) }
    var liveQueuedCount by remember { mutableIntStateOf(0) }
    val isResolving = resolveJob?.isActive == true

    DisposableEffect(Unit) {
        onDispose {
            resolveJob?.cancel()
        }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch { module.setLocation(uri) }
    }

    ModalBottomSheet(
        onDismissRequest = {
            resolveJob?.cancel()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tokens.surfaceElevated,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .semantics { contentDescription = "Batch episodes download sheet" },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Sheet Header
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Download Episodes",
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.textPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${item.title} · ${selState.totalSelectedCount} of ${selState.totalEpisodeCount} selected · ${selState.preferredQuality.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val closeInteraction = remember { MutableInteractionSource() }
                val closeFocused by closeInteraction.collectIsFocusedAsState()
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(CinetorTokens.Shape.pill))
                        .glass(RoundedCornerShape(CinetorTokens.Shape.pill), GlassLevel.SURFACE)
                        .then(
                            if (closeFocused) Modifier.border(2.dp, tokens.focusRing, RoundedCornerShape(CinetorTokens.Shape.pill))
                            else Modifier,
                        )
                        .clickable(interactionSource = closeInteraction, indication = null) {
                            resolveJob?.cancel()
                            onDismiss()
                        }
                        .semantics { contentDescription = "Close download sheet" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = tokens.textPrimary, modifier = Modifier.size(18.dp))
                }
            }

            // Multi-Season Selector Pills with S1 (4/8) check badges
            if (selState.seasons.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(selState.seasons, key = { "batch-season-${it.n}" }) { s ->
                        val isCurrentTab = s.n == selState.activeSeasonNumber
                        val selInSeason = selState.selectedCountInSeason(s.n)
                        val totInSeason = selState.totalInSeason(s.n)
                        val fullSeason = selState.isSeasonFullySelected(s.n)
                        val pillShape = RoundedCornerShape(CinetorTokens.Shape.pill)
                        val interaction = remember { MutableInteractionSource() }
                        val focused by interaction.collectIsFocusedAsState()

                        Row(
                            Modifier
                                .clip(pillShape)
                                .then(
                                    if (isCurrentTab) Modifier.background(tokens.accent)
                                    else Modifier.glass(pillShape, GlassLevel.SURFACE),
                                )
                                .border(
                                    width = if (focused) 2.dp else 1.dp,
                                    color = when {
                                        focused -> tokens.focusRing
                                        isCurrentTab -> Color.Transparent
                                        selInSeason > 0 -> tokens.accent.copy(alpha = 0.55f)
                                        else -> tokens.glassStroke
                                    },
                                    shape = pillShape,
                                )
                                .clickable(
                                    enabled = !isResolving,
                                    interactionSource = interaction,
                                    indication = null,
                                    role = Role.Tab,
                                ) { selection.selectSeasonTab(s.n) }
                                .semantics { contentDescription = "Season ${s.n} (${selInSeason}/${totInSeason})" }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (fullSeason) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = if (isCurrentTab) tokens.onAccent else tokens.accent,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                            Text(
                                s.title ?: "Season ${s.n}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isCurrentTab) FontWeight.Bold else FontWeight.Medium,
                                color = if (isCurrentTab) tokens.onAccent else tokens.textPrimary,
                            )
                            Text(
                                selState.seasonBadgeLabel(s.n),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isCurrentTab) tokens.onAccent.copy(alpha = 0.9f)
                                else if (selInSeason > 0) tokens.accent
                                else tokens.textSecondary,
                            )
                        }
                    }
                }
            }

            // Selection Controls: Select/Unselect Season + Select All Seasons + Clear All
            val activeSeason = selState.activeSeason
            if (activeSeason != null) {
                val seasonFull = selState.isSeasonFullySelected(activeSeason.n)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BatchControlChip(
                        label = if (seasonFull) "Unselect Season ${activeSeason.n}" else "Select Season ${activeSeason.n}",
                        highlighted = seasonFull,
                        enabled = !isResolving,
                        onClick = { selection.toggleSeasonAll(activeSeason.n) },
                    )
                    if (selState.seasons.size > 1) {
                        BatchControlChip(
                            label = if (selState.isAllSeasonsSelected) "All Seasons Selected" else "Select All Seasons",
                            highlighted = selState.isAllSeasonsSelected,
                            enabled = !isResolving && !selState.isAllSeasonsSelected,
                            onClick = { selection.selectAllSeasons() },
                        )
                    }
                    if (selState.totalSelectedCount > 0) {
                        BatchControlChip(
                            label = "Clear All",
                            highlighted = false,
                            enabled = !isResolving,
                            onClick = { selection.clearAll() },
                        )
                    }
                }
            }

            // Preferred Quality Row (1080p, 720p, 480p, 360p)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Preferred Quality",
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BatchQuality.entries.forEach { q ->
                        val active = selState.preferredQuality == q
                        val qShape = RoundedCornerShape(CinetorTokens.Shape.sm)
                        val qInteraction = remember { MutableInteractionSource() }
                        val qFocused by qInteraction.collectIsFocusedAsState()
                        Box(
                            Modifier
                                .clip(qShape)
                                .then(
                                    if (active) Modifier.background(tokens.accent)
                                    else Modifier.glass(qShape, GlassLevel.SURFACE),
                                )
                                .border(
                                    width = if (qFocused) 2.dp else 1.dp,
                                    color = if (qFocused) tokens.focusRing else if (active) Color.Transparent else tokens.glassStroke,
                                    shape = qShape,
                                )
                                .clickable(
                                    enabled = !isResolving,
                                    interactionSource = qInteraction,
                                    indication = null,
                                    role = Role.RadioButton,
                                ) { selection.setQuality(q) }
                                .semantics { contentDescription = "Preferred quality ${q.label}" }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                q.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) tokens.onAccent else tokens.textPrimary,
                            )
                        }
                    }
                }
            }

            // Episodes List with D-pad/TV focusable Checkboxes
            if (activeSeason != null) {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(CinetorTokens.Shape.md))
                        .glass(RoundedCornerShape(CinetorTokens.Shape.md), GlassLevel.SURFACE),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(activeSeason.episodes, key = { "${activeSeason.n}-${it.id}" }) { ep ->
                        val checked = selState.isSelected(activeSeason.n, ep.id)
                        val dlInfo = downloadsById[downloadIdFor(item.id, ep.id)]
                        val rowShape = RoundedCornerShape(CinetorTokens.Shape.sm)
                        val rowInteraction = remember { MutableInteractionSource() }
                        val rowFocused by rowInteraction.collectIsFocusedAsState()

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(rowShape)
                                .background(
                                    when {
                                        rowFocused -> tokens.accent.copy(alpha = 0.16f)
                                        checked -> tokens.accent.copy(alpha = 0.08f)
                                        else -> Color.Transparent
                                    },
                                )
                                .then(
                                    if (rowFocused) Modifier.border(2.dp, tokens.focusRing, rowShape)
                                    else Modifier,
                                )
                                .toggleable(
                                    value = checked,
                                    enabled = !isResolving,
                                    role = Role.Checkbox,
                                    interactionSource = rowInteraction,
                                    indication = null,
                                    onValueChange = { selection.toggleEpisode(activeSeason.n, ep.id) },
                                )
                                .semantics { contentDescription = "Select S${activeSeason.n}E${ep.n} ${ep.title}" }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = tokens.accent,
                                    uncheckedColor = tokens.textTertiary,
                                    checkmarkColor = tokens.onAccent,
                                ),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "E${ep.n} · ${ep.title}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (checked) tokens.textPrimary else tokens.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val subMeta = buildList {
                                    if (ep.durationSec > 0) add("${(ep.durationSec / 60).coerceAtLeast(1)}m")
                                    when (dlInfo?.record?.status) {
                                        DownloadStatus.COMPLETED -> add("Downloaded")
                                        DownloadStatus.DOWNLOADING -> add("Downloading")
                                        DownloadStatus.QUEUED, DownloadStatus.WAITING -> add("In queue")
                                        else -> Unit
                                    }
                                    ep.synopsis?.takeIf { it.isNotBlank() }?.let { add(it) }
                                }.joinToString(" · ")
                                if (subMeta.isNotEmpty()) {
                                    Text(
                                        subMeta,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (dlInfo?.record?.status == DownloadStatus.COMPLETED) tokens.success else tokens.textTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Destination Folder Summary (SAF folder if configured)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(CinetorTokens.Shape.sm))
                    .glass(RoundedCornerShape(CinetorTokens.Shape.sm), GlassLevel.SURFACE)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = tokens.textSecondary, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text("Destination", style = MaterialTheme.typography.labelSmall, color = tokens.textTertiary)
                    Text(
                        DownloadLocations.label(location, adultTarget),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!adultTarget) {
                    TextButton(
                        enabled = !isResolving,
                        onClick = { runCatching { pickFolder.launch(null) } },
                        modifier = Modifier.semantics { contentDescription = "Choose download folder" },
                    ) {
                        Text(if (location.chosen) "Change" else "Choose folder", color = tokens.accent)
                    }
                }
            }

            // Background Resolution Progress Banner (when resolving)
            val currentProgress = progress
            if (isResolving && currentProgress != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(CinetorTokens.Shape.md))
                        .glass(RoundedCornerShape(CinetorTokens.Shape.md), GlassLevel.SURFACE)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Resolving ${currentProgress.currentIndex}/${currentProgress.totalCount} · ${currentProgress.currentEpisodeLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Queued $liveQueuedCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    LinearProgressIndicator(
                        progress = {
                            (currentProgress.currentIndex.toFloat() / currentProgress.totalCount.coerceAtLeast(1)).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = tokens.accent,
                        trackColor = tokens.divider,
                    )
                }
            }

            // Action Buttons: Download N Episodes + Cancel (or Cancel Resolve mid-flight)
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isResolving) {
                    OutlinedButton(
                        onClick = {
                            val queuedBeforeCancel = liveQueuedCount
                            resolveJob?.cancel()
                            resolveJob = null
                            progress = null
                            if (queuedBeforeCancel > 0) {
                                onQueued(queuedBeforeCancel)
                                onDismiss()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .semantics { contentDescription = "Cancel batch resolve" },
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = tokens.accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Cancel Resolve", color = tokens.textPrimary, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .height(48.dp)
                            .semantics { contentDescription = "Cancel" },
                    ) {
                        Text("Cancel", color = tokens.textSecondary)
                    }
                    val count = selState.totalSelectedCount
                    Button(
                        enabled = count > 0,
                        onClick = {
                            val toQueue = selState.selectedEpisodes()
                            if (toQueue.isEmpty()) return@Button
                            if (Build.VERSION.SDK_INT >= 33) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            val targetHeight = selState.preferredQuality.height
                            val connCount = module.defaultConnectionCount()
                            liveQueuedCount = 0
                            resolveJob = scope.launch {
                                try {
                                    val result = enqueueBatchEpisodes(
                                        item = item,
                                        episodes = toQueue,
                                        targetHeight = targetHeight,
                                        connections = connCount,
                                        resolveSources = { seriesItem, se, ep ->
                                            if (resolve != null) {
                                                withContext(Dispatchers.IO) { resolve(seriesItem, se, ep) }
                                            } else {
                                                ep.sources.ifEmpty { seriesItem.sources }
                                            }
                                        },
                                        enqueue = { seriesItem, ep, se, choice ->
                                            module.start(seriesItem, ep, se, choice)
                                            liveQueuedCount++
                                        },
                                        onProgress = { p -> progress = p },
                                    )
                                    progress = null
                                    resolveJob = null
                                    if (result.queuedCount > 0) {
                                        onQueued(result.queuedCount)
                                    } else {
                                        onNoStreamsFound()
                                    }
                                    onDismiss()
                                } catch (e: CancellationException) {
                                    progress = null
                                    throw e
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.accent,
                            contentColor = tokens.onAccent,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .semantics { contentDescription = "Start batch download" },
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (count == 1) "Download 1 Episode" else "Download $count Episodes",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchControlChip(
    label: String,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Box(
        Modifier
            .clip(shape)
            .then(
                if (highlighted) Modifier.background(tokens.accent.copy(alpha = 0.18f))
                else Modifier.glass(shape, GlassLevel.SURFACE),
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    focused -> tokens.focusRing
                    highlighted -> tokens.accent.copy(alpha = 0.65f)
                    else -> tokens.glassStroke
                },
                shape = shape,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (!enabled) tokens.textTertiary else if (highlighted) tokens.accent else tokens.textPrimary,
        )
    }
}
