package com.mythbrix.cinetor.feature.tv

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.SkeletonEpgGrid
import com.mythbrix.cinetor.design.SkeletonHero
import com.mythbrix.cinetor.design.SkeletonPosterRow
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.design.hexColor
import kotlinx.coroutines.delay

private enum class TvMainSection { LIVE_TV, SPORTS }
private enum class LiveSubMode { CHANNELS, GUIDE }

private val PreferredCategories = listOf("News", "Entertainment", "Movies", "Kids", "Music", "Sports")

/** Mock guide "now" (epoch s), ticking every 30 s. Real clock until the anchor is known. */
@Composable
fun rememberGuideNow(anchorSec: Long?): Long {
    var real by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            real = System.currentTimeMillis() / 1000
        }
    }
    return if (anchorSec == null) real else EpgClock.nowSec(real, anchorSec)
}

/** Device UTC offset (s) for guide clock labels. */
fun localOffsetSec(): Int = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000

/**
 * Primary `TV` tab destination:
 * - Prominent 2-segment header switching between **`Live TV`** (`Tab Live`) and **`Sports`** (`Tab Sports` — PlayzTV).
 * - Inside `Live TV`: `Channels` / `Guide` (`Tab Guide`) sub-pills, `All · News · Entertainment · Movies · Kids · Music`
 *   category chips, live preview banner (`Watch live`), and responsive channel cards with visible channel name + airing
 *   program title underneath every thumbnail.
 */
@Composable
fun LiveTvScreen(container: AppContainer, contentPadding: PaddingValues) {
    val tv = container.tv
    val data by tv.data.collectAsStateWithLifecycle()
    LaunchedEffect(tv) { runCatching { tv.load() } }
    val favorites by container.tvPrefs.favorites.collectAsStateWithLifecycle()
    var sectionName by rememberSaveable { mutableStateOf(TvMainSection.LIVE_TV.name) }
    var subModeName by rememberSaveable { mutableStateOf(LiveSubMode.CHANNELS.name) }

    val section = runCatching { TvMainSection.valueOf(sectionName) }.getOrDefault(TvMainSection.LIVE_TV)
    val subMode = runCatching { LiveSubMode.valueOf(subModeName) }.getOrDefault(LiveSubMode.CHANNELS)
    val nowSec = rememberGuideNow(data?.anchorSec)
    val inner = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 12.dp)

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        TvPrimarySegmentedBar(
            section = section,
            liveEventCount = data?.liveEvents?.size ?: 0,
            onSelectLive = {
                sectionName = TvMainSection.LIVE_TV.name
                subModeName = LiveSubMode.CHANNELS.name
            },
            onSelectSports = {
                sectionName = TvMainSection.SPORTS.name
            },
        )
        Spacer(Modifier.height(10.dp))

        val d = data
        when (section) {
            TvMainSection.SPORTS -> {
                if (d == null) {
                    ShimmerHost { Column { repeat(3) { SkeletonPosterRow(Modifier.padding(vertical = 8.dp)) } } }
                } else {
                    SportsScreen(d, nowSec, container, inner)
                }
            }
            TvMainSection.LIVE_TV -> {
                LiveSubModeRow(
                    subMode = subMode,
                    onSelectMode = { subModeName = it.name },
                )
                Spacer(Modifier.height(8.dp))
                when (subMode) {
                    LiveSubMode.GUIDE -> {
                        if (d == null) {
                            SkeletonEpgGrid(Modifier.fillMaxWidth())
                        } else {
                            EpgGrid(
                                d = d,
                                nowSec = nowSec,
                                favorites = favorites,
                                padding = inner,
                                onPlay = { ch -> container.playback.playChannel(ch, d.channels) },
                            )
                        }
                    }
                    LiveSubMode.CHANNELS -> {
                        if (d == null) {
                            ShimmerHost {
                                Column {
                                    SkeletonHero()
                                    repeat(2) { SkeletonPosterRow(Modifier.padding(vertical = 8.dp)) }
                                }
                            }
                        } else {
                            LiveChannels(
                                d = d,
                                nowSec = nowSec,
                                favorites = favorites,
                                padding = inner,
                                onPlay = { ch, lineup -> container.playback.playChannel(ch, lineup) },
                                onFavorite = container.tvPrefs::toggleFavorite,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvPrimarySegmentedBar(
    section: TvMainSection,
    liveEventCount: Int,
    onSelectLive: () -> Unit,
    onSelectSports: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val outerShape = RoundedCornerShape(16.dp)
    val pillShape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .glass(outerShape, GlassLevel.SURFACE)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val liveSelected = section == TvMainSection.LIVE_TV
        Row(
            Modifier
                .weight(1f)
                .clip(pillShape)
                .background(if (liveSelected) tokens.accent else Color.Transparent)
                .clickable(role = Role.Tab, onClick = onSelectLive)
                .semantics {
                    selected = liveSelected
                    contentDescription = "Tab Live"
                }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.LiveTv,
                contentDescription = null,
                tint = if (liveSelected) tokens.onAccent else tokens.textSecondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Live TV",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (liveSelected) tokens.onAccent else tokens.textPrimary,
            )
        }

        val sportsSelected = section == TvMainSection.SPORTS
        Row(
            Modifier
                .weight(1f)
                .clip(pillShape)
                .background(if (sportsSelected) tokens.accent else Color.Transparent)
                .clickable(role = Role.Tab, onClick = onSelectSports)
                .semantics {
                    selected = sportsSelected
                    contentDescription = "Tab Sports"
                }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Sports",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (sportsSelected) tokens.onAccent else tokens.textPrimary,
            )
            if (liveEventCount > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "$liveEventCount LIVE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (sportsSelected) tokens.accent else Color.White,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (sportsSelected) tokens.onAccent else tokens.liveBadge)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun LiveSubModeRow(
    subMode: LiveSubMode,
    onSelectMode: (LiveSubMode) -> Unit,
) {
    val tokens = Cinetor.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val channelsSelected = subMode == LiveSubMode.CHANNELS
        Row(
            Modifier
                .clip(CircleShape)
                .background(if (channelsSelected) tokens.accent.copy(alpha = 0.18f) else tokens.glassFill)
                .border(1.dp, if (channelsSelected) tokens.accent else tokens.glassStroke, CircleShape)
                .clickable(role = Role.Tab) { onSelectMode(LiveSubMode.CHANNELS) }
                .semantics {
                    selected = channelsSelected
                    contentDescription = "Tab Channels"
                }
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                Icons.Rounded.LiveTv,
                contentDescription = null,
                tint = if (channelsSelected) tokens.accent else tokens.textSecondary,
                modifier = Modifier.size(15.dp),
            )
            Text(
                "Channels",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (channelsSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (channelsSelected) tokens.textPrimary else tokens.textSecondary,
            )
        }

        val guideSelected = subMode == LiveSubMode.GUIDE
        Row(
            Modifier
                .clip(CircleShape)
                .background(if (guideSelected) tokens.accent.copy(alpha = 0.18f) else tokens.glassFill)
                .border(1.dp, if (guideSelected) tokens.accent else tokens.glassStroke, CircleShape)
                .clickable(role = Role.Tab) { onSelectMode(LiveSubMode.GUIDE) }
                .semantics {
                    selected = guideSelected
                    contentDescription = "Tab Guide"
                }
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                Icons.Rounded.Schedule,
                contentDescription = null,
                tint = if (guideSelected) tokens.accent else tokens.textSecondary,
                modifier = Modifier.size(15.dp),
            )
            Text(
                "Guide",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (guideSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (guideSelected) tokens.textPrimary else tokens.textSecondary,
            )
        }
    }
}

@Composable
private fun LiveChannels(
    d: TvData,
    nowSec: Long,
    favorites: Set<String>,
    padding: PaddingValues,
    onPlay: (Item, List<Item>) -> Unit,
    onFavorite: (String) -> Unit,
) {
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var favOnly by rememberSaveable { mutableStateOf(false) }
    val nowGenre: (Item) -> String? = { nowNext(it, nowSec, d.anchorSec).now?.genre }

    val orderedGroups = remember(d.groups) {
        val available = d.groups
        val prioritized = PreferredCategories.filter { pref -> available.any { it.equals(pref, ignoreCase = true) } }
        val extras = available.filter { g -> prioritized.none { it.equals(g, ignoreCase = true) } }
        prioritized + extras
    }

    val list = remember(d, group, favOnly, favorites, nowSec / 60) {
        filterChannels(d.channels, group, null, favOnly, favorites, nowGenre)
    }
    val cols = if (LocalConfiguration.current.screenWidthDp >= 600) 4 else 2
    val featured = remember(d.featured, group, list) {
        if (group == null) d.featured.firstOrNull() else list.firstOrNull() ?: d.featured.firstOrNull()
    }

    LazyColumn(
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (featured != null) {
            item(key = "hero", contentType = "hero") {
                FeaturedChannel(featured, nowNext(featured, nowSec, d.anchorSec)) { onPlay(featured, d.channels) }
            }
        }
        item(key = "groups", contentType = "groups") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FavoritesPill(favOnly, Modifier.padding(start = 16.dp, end = 4.dp)) { favOnly = !favOnly }
                GroupTabs(
                    groups = listOf<String?>(null) + orderedGroups,
                    selected = group,
                    onSelect = { group = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (list.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Text(
                    if (favOnly) "No saved channels yet — tap ★ on any channel to pin it here." else "No channels in this category.",
                    color = Cinetor.tokens.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
        }
        items(list.chunked(cols), key = { row -> "row-" + row.first().id }, contentType = { "channels" }) { row ->
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { ch ->
                    ChannelTile(
                        channel = ch,
                        nn = nowNext(ch, nowSec, d.anchorSec),
                        favorite = ch.id in favorites,
                        onClick = { onPlay(ch, list) },
                        onFavorite = { onFavorite(ch.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Pulsing broadcast LIVE pill (`graphicsLayer { alpha }` only — zero layout/re-measure cost). */
@Composable
fun LiveBadgePill(modifier: Modifier = Modifier, text: String = "LIVE", pulse: Boolean = true) {
    val shouldPulse = pulse && Cinetor.tier != PerformanceTier.LOW && !Cinetor.prefs.reduceMotion
    val dotAlpha = if (shouldPulse) {
        val transition = rememberInfiniteTransition(label = "liveDotPulse")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.28f,
            animationSpec = infiniteRepeatable(tween(780), RepeatMode.Reverse),
            label = "liveDotAlpha",
        )
        a
    } else {
        1f
    }
    Row(
        modifier
            .clip(CircleShape)
            .background(Cinetor.tokens.liveBadge)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .graphicsLayer { alpha = dotAlpha }
                .clip(CircleShape)
                .background(Color.White),
        )
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FeaturedChannel(channel: Item, nn: NowNext, onWatch: () -> Unit) {
    val tokens = Cinetor.tokens
    val dominant = remember(channel.dominantColor) { hexColor(channel.dominantColor) }
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(dominant)
            .border(1.dp, tokens.glassStroke, shape)
            .clickable(onClick = onWatch),
    ) {
        AsyncImage(
            model = rememberSizedImage(channel.backdrop.ifBlank { channel.poster }, 400.dp, 225.dp, large = true),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.2f),
                        0.45f to Color.Black.copy(alpha = 0.35f),
                        1f to Color.Black.copy(alpha = 0.92f),
                    ),
                ),
        )
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveBadgePill()
            channel.channelNumber?.let { chNum ->
                Text(
                    "CH $chNum · ${channel.channelGroup ?: "Live"}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.52f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                channel.title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            nn.now?.let { p ->
                val left = (p.durationSec * (1f - nn.progress) / 60f).toInt().coerceAtLeast(1)
                Text(
                    "On Now: ${p.title} · $left min left",
                    color = Color.White.copy(alpha = 0.86f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ProgressLine(nn.progress, Modifier.fillMaxWidth(0.62f))
            }
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(tokens.accent)
                    .clickable(onClick = onWatch)
                    .semantics { contentDescription = "Watch live" }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = tokens.onAccent, modifier = Modifier.size(18.dp))
                Text("Watch live", color = tokens.onAccent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 3.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.22f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(height).background(Cinetor.tokens.accent))
    }
}

@Composable
private fun GroupTabs(
    groups: List<String?>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(start = 6.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(groups, key = { it ?: "__all" }) { g ->
            val sel = g == selected
            Text(
                g ?: "All",
                style = MaterialTheme.typography.labelLarge,
                color = if (sel) tokens.onAccent else tokens.textSecondary,
                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier
                    .clip(shape)
                    .background(if (sel) tokens.accent else tokens.glassFill)
                    .border(1.dp, if (sel) Color.Transparent else tokens.glassStroke, shape)
                    .clickable(role = Role.Tab) { onSelect(g) }
                    .semantics { this.selected = sel; contentDescription = "Group ${g ?: "All"}" }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun FavoritesPill(on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    Row(
        modifier
            .clip(shape)
            .background(if (on) tokens.accent.copy(alpha = 0.22f) else tokens.glassFill)
            .border(1.dp, if (on) tokens.accent else tokens.glassStroke, shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Favorites only" }
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (on) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            contentDescription = null,
            tint = if (on) tokens.accent else tokens.textSecondary,
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * Channel card: 16:9 thumbnail with channel logo, LIVE pill, channel number, favourite star,
 * live progress bar, and visible Channel Name + Current Program Title underneath.
 */
@Composable
private fun ChannelTile(
    channel: Item,
    nn: NowNext,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    val dominant = remember(channel.dominantColor) { hexColor(channel.dominantColor) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Channel ${channel.channelNumber ?: ""} ${channel.title}" },
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(Brush.linearGradient(listOf(dominant, Color(0xFF0E1015))))
                .border(1.dp, tokens.glassStroke, shape),
        ) {
            AsyncImage(
                model = rememberSizedImage(channel.logo ?: channel.poster, 180.dp, 101.dp),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(18.dp),
            )
            LiveBadgePill(Modifier.padding(6.dp), pulse = false)
            Row(Modifier.align(Alignment.TopEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                channel.channelNumber?.let {
                    Text(
                        "CH $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.Black.copy(alpha = 0.58f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onFavorite)
                        .semantics { contentDescription = if (favorite) "Unfavorite ${channel.title}" else "Favorite ${channel.title}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = null,
                        tint = if (favorite) Color(0xFFFFC233) else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            ProgressLine(nn.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth(), height = 3.dp)
        }
        Text(
            channel.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            nn.now?.title ?: "Live broadcast",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Glass card container shared by TV & sports cards. */
@Composable
fun TvGlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.glass(RoundedCornerShape(CinetorTokens.Shape.card), GlassLevel.SURFACE)) { content() }
}
