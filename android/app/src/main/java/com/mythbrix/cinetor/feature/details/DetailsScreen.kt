package com.mythbrix.cinetor.feature.details

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.core.player.Readiness
import com.mythbrix.cinetor.core.ui.posterKey
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.core.ui.sharedPoster
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.SkeletonBox
import com.mythbrix.cinetor.design.SkeletonDetails
import com.mythbrix.cinetor.design.SkeletonEpisodeList
import com.mythbrix.cinetor.design.SkeletonText
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.design.hexColor
import com.mythbrix.cinetor.feature.home.PosterCard

private val HeaderHeight = 280.dp
private val PosterW = 116.dp
private val PosterH = 174.dp

/**
 * Details: skeleton shimmer while the item resolves (card summary header shown when known),
 * playback warm-up started on open and cancelled on leave, Play/Resume with "Ready" dot,
 * Download (placeholder sheet), My List, Share, seasons/episodes, More Like This (same space).
 */
@Composable
fun DetailsScreen(
    id: String,
    origin: String,
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenItem: (Item, String?) -> Unit,
) {
    val vm: DetailsViewModel = viewModel(
        key = "details-$id",
        factory = viewModelFactory {
            initializer { DetailsViewModel(id, container.catalog, container.preloader, container.peek::get) }
        },
    )
    val holder = vm.holder
    val state by holder.state.collectAsStateWithLifecycle()
    val readiness by holder.readiness.collectAsStateWithLifecycle()
    val myList by container.myList.collectAsStateWithLifecycle()

    // Hidden behind another screen (e.g. More Like This → another Details) → free the warm-up.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, holder) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> holder.pauseWarm()
                Lifecycle.Event.ON_START -> holder.resumeWarm()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val tint = Cinetor.tint
    val headerItem = when (val s = state) {
        is DetailsUiState.Ready -> s.ui.item
        is DetailsUiState.Loading -> s.peek
        is DetailsUiState.Error -> null
    }
    LaunchedEffect(headerItem?.dominantColor) { headerItem?.let { tint.setSource(hexColor(it.dominantColor)) } }

    var showDownloads by rememberSaveable { mutableStateOf(false) }
    var season by rememberSaveable(id) { mutableStateOf(0) }
    val context = LocalContext.current

    LazyColumn(
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        modifier = Modifier.fillMaxSize(),
    ) {
        when (val s = state) {
            is DetailsUiState.Error -> item(key = "error") {
                Text(
                    "Title not found (${s.id})",
                    color = Cinetor.tokens.textSecondary,
                    modifier = Modifier.padding(top = contentPadding.calculateTopPadding()).padding(24.dp),
                )
            }
            is DetailsUiState.Loading -> {
                val peek = s.peek
                if (peek == null) {
                    item(key = "skeleton", contentType = "skeleton") {
                        ShimmerHost {
                            Column(
                                Modifier.padding(top = contentPadding.calculateTopPadding()),
                                verticalArrangement = Arrangement.spacedBy(24.dp),
                            ) {
                                SkeletonDetails()
                                SkeletonEpisodeList()
                            }
                        }
                    }
                } else {
                    item(key = "header", contentType = "header") { DetailsHeader(peek, origin) }
                    item(key = "skeleton", contentType = "skeleton") { BodySkeleton() }
                }
            }
            is DetailsUiState.Ready -> {
                val ui = s.ui
                val item = ui.item
                item(key = "header", contentType = "header") { DetailsHeader(item, origin) }
                item(key = "actions", contentType = "actions") {
                    DetailsActions(
                        item = item,
                        readiness = readiness[item.id],
                        inMyList = item.id in myList,
                        onPlay = {
                            Toast.makeText(context, "Player arrives in Phase 3", Toast.LENGTH_SHORT).show()
                        },
                        onDownload = { showDownloads = true },
                        onToggleMyList = { container.toggleMyList(item.id) },
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Watch \"${item.title}\" on Cinetor")
                            }
                            context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                    )
                }
                item(key = "synopsis", contentType = "synopsis") { Synopsis(item) }
                val seasons = item.seasons
                if (seasons.isNotEmpty()) {
                    val selected = season.coerceIn(0, seasons.lastIndex)
                    item(key = "seasons", contentType = "seasons") {
                        Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                            SeasonTabs(seasons, selected) { season = it }
                        }
                    }
                    items(seasons[selected].episodes, key = { "ep-${it.id}" }, contentType = { "episode" }) { ep ->
                        EpisodeRow(ep, showProgress = selected == 0 && ep.n == 1) {
                            Toast.makeText(context, "Player arrives in Phase 3", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                item(key = "more", contentType = "more") {
                    MoreLikeThis(ui, onOpenItem)
                }
            }
        }
    }

    if (showDownloads) DownloadsSheet(onDismiss = { showDownloads = false })
}

@Composable
private fun DetailsHeader(item: Item, origin: String) {
    val tokens = Cinetor.tokens
    val tier = Cinetor.tier
    val dominant = remember(item.dominantColor) { hexColor(item.dominantColor) }
    Box(Modifier.fillMaxWidth().height(HeaderHeight + PosterH / 2)) {
        Box(Modifier.fillMaxWidth().height(HeaderHeight).background(dominant)) {
            AsyncImage(
                model = rememberSizedImage(item.backdrop, 400.dp, HeaderHeight, large = true),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (tier != PerformanceTier.LOW) Modifier.blur(6.dp) else Modifier),
            )
            Box(Modifier.fillMaxSize().background(dominant.copy(alpha = 0.30f)))
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to tokens.bg.copy(alpha = 0.35f),
                        0.5f to Color.Transparent,
                        1f to tokens.bg,
                    ),
                ),
            )
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier
                    .sharedPoster(origin.ifEmpty { null })
                    .size(PosterW, PosterH)
                    .clip(RoundedCornerShape(CinetorTokens.Shape.card))
                    .background(dominant)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(CinetorTokens.Shape.card)),
            ) {
                AsyncImage(
                    model = rememberSizedImage(item.poster, PosterW, PosterH),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.weight(1f).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.logo != null) {
                    AsyncImage(
                        model = rememberSizedImage(item.logo, 200.dp, 64.dp),
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier.width(200.dp).heightIn(max = 64.dp),
                    )
                } else {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = tokens.textPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MetaChips(item)
            }
        }
    }
}

private fun runtimeLabel(item: Item): String? = when {
    item.seasons.isNotEmpty() -> "${item.seasons.size} Season" + if (item.seasons.size > 1) "s" else ""
    item.durationSec != null -> {
        val m = item.durationSec / 60
        if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaChips(item: Item) {
    val tokens = Cinetor.tokens
    val chips = buildList {
        if (item.year > 0) add(item.year.toString())
        if (item.rating > 0) add("★ ${"%.1f".format(item.rating)}")
        if (item.maturity.isNotBlank()) add(item.maturity)
        runtimeLabel(item)?.let { add(it) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            chips.forEach { c ->
                Text(
                    c,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
        if (item.genres.isNotEmpty()) {
            Text(
                item.genres.take(3).joinToString("  •  "),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Play/Resume (with Ready dot) + Download side by side, then My List / Share. */
@Composable
private fun DetailsActions(
    item: Item,
    readiness: Readiness?,
    inMyList: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onToggleMyList: () -> Unit,
    onShare: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(CinetorTokens.Shape.md)
    val firstEp = item.seasons.firstOrNull()?.episodes?.firstOrNull()
    val playLabel = when {
        item.type == ItemType.SERIES && firstEp != null -> "Resume S1:E${firstEp.n}"
        else -> "Play"
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .height(50.dp)
                    .clip(shape)
                    .background(Brush.horizontalGradient(listOf(tokens.accent, tokens.accentEnd)))
                    .clickable(onClick = onPlay)
                    .semantics { contentDescription = "Play" },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = tokens.onAccent)
                Spacer(Modifier.width(6.dp))
                Text(playLabel, color = tokens.onAccent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(10.dp))
                ReadyDot(readiness)
            }
            Row(
                Modifier
                    .height(50.dp)
                    .glass(shape, GlassLevel.SURFACE)
                    .clickable(onClick = onDownload)
                    .semantics { contentDescription = "Download" }
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, tint = tokens.textPrimary)
                Spacer(Modifier.width(6.dp))
                Text("Download", color = tokens.textPrimary, style = MaterialTheme.typography.labelLarge)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            ActionIcon(if (inMyList) Icons.Rounded.Check else Icons.Rounded.Add, "My List", onToggleMyList)
            ActionIcon(Icons.Rounded.Share, "Share", onShare)
        }
    }
}

@Composable
private fun ReadyDot(readiness: Readiness?) {
    val tokens = Cinetor.tokens
    if (readiness == null) return
    val color = when (readiness) {
        Readiness.READY -> tokens.success
        Readiness.ERROR -> tokens.error
        else -> tokens.warning
    }
    val pulse = if (readiness == Readiness.READY || readiness == Readiness.ERROR) {
        null
    } else {
        rememberInfiniteTransition(label = "readyPulse").animateFloat(
            0.35f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "readyAlpha",
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = if (readiness == Readiness.READY) "Ready" else "Preparing" },
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = pulse?.value ?: 1f }
                .clip(CircleShape)
                .background(color),
        )
        if (readiness == Readiness.READY) {
            Spacer(Modifier.width(4.dp))
            Text("Ready", style = MaterialTheme.typography.labelSmall, color = tokens.onAccent.copy(alpha = 0.9f))
        }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    Column(
        Modifier
            .clip(RoundedCornerShape(CinetorTokens.Shape.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tokens.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary)
    }
}

@Composable
private fun Synopsis(item: Item) {
    val tokens = Cinetor.tokens
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            item.synopsis,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.textPrimary,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (expanded) "Less" else "More",
            style = MaterialTheme.typography.labelMedium,
            color = tokens.accent,
            fontWeight = FontWeight.SemiBold,
        )
        if (expanded && item.cast.isNotEmpty()) {
            Text(
                "Cast: " + item.cast.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }
    }
}

@Composable
private fun MoreLikeThis(ui: DetailsUi, onOpenItem: (Item, String?) -> Unit) {
    val tokens = Cinetor.tokens
    val tint = Cinetor.tint
    if (!ui.moreLoading && ui.moreLikeThis.isEmpty()) return
    Column(Modifier.padding(top = 24.dp)) {
        Text(
            "More Like This",
            style = MaterialTheme.typography.titleMedium,
            color = tokens.textPrimary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(6.dp))
        if (ui.moreLoading) {
            ShimmerHost {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) {
                        SkeletonBox(Modifier.size(104.dp, 156.dp), RoundedCornerShape(CinetorTokens.Shape.card))
                    }
                }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ui.moreLikeThis, key = { it.id }, contentType = { "poster" }) { m ->
                    val key = posterKey("mlt-${ui.item.id}", m.id)
                    PosterCard(
                        m,
                        onClick = { onOpenItem(m, key) },
                        onActive = { tint.setSource(it) },
                        width = 104.dp,
                        sharedKey = key,
                    )
                }
            }
        }
    }
}

@Composable
private fun BodySkeleton() {
    ShimmerHost {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonBox(Modifier.weight(1f).height(50.dp), RoundedCornerShape(CinetorTokens.Shape.md))
                SkeletonBox(Modifier.width(120.dp).height(50.dp), RoundedCornerShape(CinetorTokens.Shape.md))
            }
            SkeletonText(lines = 3)
            Spacer(Modifier.height(8.dp))
            SkeletonEpisodeList(Modifier.padding(horizontal = 0.dp), count = 4)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsSheet(onDismiss: () -> Unit) {
    val tokens = Cinetor.tokens
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tokens.surfaceElevated,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(40.dp))
            Text("Downloads coming in Phase 3", style = MaterialTheme.typography.titleMedium, color = tokens.textPrimary)
            Text(
                "Pick quality, audio and subtitles, then watch offline.",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textSecondary,
            )
            Button(onClick = onDismiss) { Text("OK") }
        }
    }
}
