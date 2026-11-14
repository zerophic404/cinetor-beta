package com.mythbrix.cinetor.feature.tv

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.SkeletonHero
import com.mythbrix.cinetor.design.SkeletonEpgGrid
import com.mythbrix.cinetor.design.SkeletonPosterRow
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.design.hexColor
import com.mythbrix.cinetor.feature.home.CategoryTabs
import com.mythbrix.cinetor.feature.home.GenreChips
import com.mythbrix.cinetor.feature.home.TabSpec
import kotlinx.coroutines.delay

private val TvTabs = listOf(
    TabSpec("tv-live", "Live", emptyList()),
    TabSpec("tv-guide", "Guide", emptyList()),
    TabSpec("tv-sports", "Sports", emptyList()),
)

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

/** Live TV & Sports space root: Live grid · Guide (EPG) · Sports. Isolated from global features. */
@Composable
fun LiveTvScreen(container: AppContainer, contentPadding: PaddingValues) {
    val tv = container.tv
    val data by tv.data.collectAsStateWithLifecycle()
    LaunchedEffect(tv) { runCatching { tv.load() } }
    val favorites by container.tvPrefs.favorites.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val nowSec = rememberGuideNow(data?.anchorSec)
    val inner = PaddingValues(bottom = contentPadding.calculateBottomPadding())

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        CategoryTabs(TvTabs, tab, { tab = it })
        Spacer(Modifier.height(10.dp))
        val d = data
        when (tab) {
            1 -> if (d == null) {
                SkeletonEpgGrid(Modifier.fillMaxWidth())
            } else {
                EpgGrid(d, nowSec, favorites, inner, onPlay = { ch -> container.playback.playChannel(ch, d.channels) })
            }
            2 -> if (d == null) {
                ShimmerHost { Column { repeat(3) { SkeletonPosterRow(Modifier.padding(vertical = 8.dp)) } } }
            } else {
                SportsScreen(d, nowSec, container, inner)
            }
            else -> if (d == null) {
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
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var favOnly by rememberSaveable { mutableStateOf(false) }
    val nowGenre: (Item) -> String? = { nowNext(it, nowSec, d.anchorSec).now?.genre }
    val genres = remember(d, nowSec / 600) { d.channels.mapNotNull(nowGenre).distinct().sorted() }
    val list = remember(d, group, genre, favOnly, favorites, nowSec / 60) {
        filterChannels(d.channels, group, genre, favOnly, favorites, nowGenre)
    }
    val cols = if (LocalConfiguration.current.screenWidthDp >= 600) 4 else 2
    val featured = d.featured.firstOrNull()

    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        if (featured != null) {
            item(key = "hero", contentType = "hero") {
                FeaturedChannel(featured, nowNext(featured, nowSec, d.anchorSec), nowSec) { onPlay(featured, d.channels) }
            }
        }
        item(key = "groups", contentType = "groups") {
            GroupTabs(listOf<String?>(null) + d.groups, group) { group = it }
        }
        item(key = "chips", contentType = "chips") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FavoritesPill(favOnly, Modifier.padding(start = 16.dp)) { favOnly = !favOnly }
                GenreChips(genres, genre, { genre = it }, Modifier.weight(1f))
            }
        }
        if (list.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Text(
                    if (favOnly) "No favourite channels yet — tap ☆ on a channel" else "No channels here",
                    color = Cinetor.tokens.textSecondary,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        items(list.chunked(cols), key = { row -> "row-" + row.first().id }, contentType = { "channels" }) { row ->
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

@Composable
fun LiveBadgePill(modifier: Modifier = Modifier, text: String = "LIVE") {
    Row(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Cinetor.tokens.liveBadge)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FeaturedChannel(channel: Item, nn: NowNext, nowSec: Long, onWatch: () -> Unit) {
    val tokens = Cinetor.tokens
    val dominant = remember(channel.dominantColor) { hexColor(channel.dominantColor) }
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(CinetorTokens.Shape.card))
            .background(dominant)
            .clickable(onClick = onWatch),
    ) {
        AsyncImage(
            model = rememberSizedImage(channel.backdrop, 400.dp, 225.dp, large = true),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)))))
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveBadgePill()
            Text("Featured · CH ${channel.channelNumber ?: ""}", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(channel.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            nn.now?.let { p ->
                val left = (p.durationSec * (1f - nn.progress) / 60f).toInt().coerceAtLeast(1)
                Text("Now: ${p.title} · $left min left", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                ProgressLine(nn.progress, Modifier.fillMaxWidth(0.6f))
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(CinetorTokens.Shape.md))
                    .background(tokens.accent)
                    .clickable(onClick = onWatch)
                    .semantics { contentDescription = "Watch live" }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = tokens.onAccent)
                Text("Watch live", color = tokens.onAccent, fontWeight = FontWeight.Bold)
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
private fun GroupTabs(groups: List<String?>, selected: String?, onSelect: (String?) -> Unit) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(groups, key = { it ?: "__all" }) { g ->
            val sel = g == selected
            Text(
                g ?: "All channels",
                style = MaterialTheme.typography.titleSmall,
                color = if (sel) tokens.onAccent else tokens.textSecondary,
                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier
                    .clip(shape)
                    .background(if (sel) tokens.accent else Color.White.copy(alpha = 0.06f))
                    .border(1.dp, if (sel) Color.Transparent else tokens.glassStroke, shape)
                    .clickable(role = Role.Tab) { onSelect(g) }
                    .semantics { this.selected = sel; contentDescription = "Group ${g ?: "All"}" }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
            .background(if (on) tokens.accent.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (on) tokens.accent else tokens.glassStroke, shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Favorites only" }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (on) Icons.Rounded.Star else Icons.Rounded.StarBorder, contentDescription = null, tint = if (on) tokens.accent else tokens.textSecondary, modifier = Modifier.size(18.dp))
    }
}

/** Grid tile: logo on channel colour, LIVE badge, number, favourite star, now playing + progress. */
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(CinetorTokens.Shape.card))
                .background(Brush.linearGradient(listOf(dominant, Color.Black)))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(CinetorTokens.Shape.card))
                .clickable(onClick = onClick)
                .semantics { contentDescription = "Channel ${channel.channelNumber ?: ""} ${channel.title}" },
        ) {
            AsyncImage(
                model = rememberSizedImage(channel.logo ?: channel.poster, 180.dp, 101.dp),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(20.dp),
            )
            LiveBadgePill(Modifier.padding(6.dp))
            Row(Modifier.align(Alignment.TopEnd).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
                channel.channelNumber?.let {
                    Text(
                        it.toString(), style = MaterialTheme.typography.labelSmall, color = Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Box(
                    Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onFavorite)
                        .semantics { contentDescription = if (favorite) "Unfavorite ${channel.title}" else "Favorite ${channel.title}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, contentDescription = null,
                        tint = if (favorite) Color(0xFFFFC233) else Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp),
                    )
                }
            }
            ProgressLine(nn.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth(), height = 3.dp)
        }
        Text(channel.title, style = MaterialTheme.typography.labelLarge, color = tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            nn.now?.title ?: "Off air",
            style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Glass card container shared by sports cards. */
@Composable
fun TvGlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.glass(RoundedCornerShape(CinetorTokens.Shape.card), GlassLevel.SURFACE)) { content() }
}
