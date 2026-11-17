package com.mythbrix.cinetor.feature.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.SkeletonHero
import com.mythbrix.cinetor.design.SkeletonPosterRow
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.design.hexColor
import com.mythbrix.cinetor.feature.home.CategoryTabs
import com.mythbrix.cinetor.feature.home.HeroCarousel
import com.mythbrix.cinetor.feature.home.TabSpec
import com.mythbrix.cinetor.feature.home.rememberTintFeeder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val MusicBrowseTabs = listOf(
    TabSpec("mu-trending", "Discover", emptyList()),
    TabSpec("mu-albums", "Charts & Albums", emptyList()),
    TabSpec("mu-tracks", "Tracks", emptyList()),
    TabSpec("mu-artists", "Artists", emptyList()),
)

/**
 * Live Gateway Music Space root: Liquid Glass search (Songs / Albums / Playlists), dynamic gateway
 * category pills, lossless FLAC / 320kbps toggle, Top Charts & Playlists shelf, and Trending Tracks.
 */
@Composable
fun MusicHome(container: AppContainer, contentPadding: PaddingValues, onOpenAlbum: (String) -> Unit) {
    val repo = container.musicLibrary
    val data by repo.data.collectAsStateWithLifecycle()
    val repoError by repo.error.collectAsStateWithLifecycle()
    val music = container.music
    val ms by music.state.collectAsStateWithLifecycle()
    val myList by container.myList.collectAsStateWithLifecycle()
    val downloads = rememberMusicDownloads(container)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val tokens = Cinetor.tokens
    val feeder = rememberTintFeeder()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedCategory by rememberSaveable { mutableStateOf("Trending") }
    var artistFilter by rememberSaveable { mutableStateOf<String?>(null) }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchType by rememberSaveable { mutableStateOf(MusicSearchType.SONG) }
    var searchItems by remember { mutableStateOf<List<Item>>(emptyList()) }
    var searchPage by remember { mutableIntStateOf(1) }
    var searchHasMore by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repo) { runCatching { repo.load() } }

    LaunchedEffect(feedbackMessage) {
        if (feedbackMessage != null) {
            delay(4_500)
            feedbackMessage = null
        }
    }

    val activeSearchTerm = remember(searchQuery, selectedCategory) {
        when {
            searchQuery.trim().length >= 2 -> searchQuery.trim()
            !selectedCategory.equals("Trending", ignoreCase = true) -> selectedCategory
            else -> ""
        }
    }

    BackHandler(enabled = searchQuery.isNotBlank() || !selectedCategory.equals("Trending", ignoreCase = true) || artistFilter != null) {
        when {
            searchQuery.isNotBlank() -> {
                searchQuery = ""
                searchItems = emptyList()
            }
            !selectedCategory.equals("Trending", ignoreCase = true) -> {
                selectedCategory = "Trending"
                searchItems = emptyList()
            }
            artistFilter != null -> artistFilter = null
        }
    }

    LaunchedEffect(activeSearchTerm, searchType) {
        if (activeSearchTerm.isEmpty()) {
            searchItems = emptyList()
            searchHasMore = false
            isSearching = false
            return@LaunchedEffect
        }
        if (searchQuery.isNotBlank()) delay(260)
        isSearching = true
        searchPage = 1
        val res = repo.search(activeSearchTerm, searchType, page = 1)
        searchItems = res.items
        searchHasMore = res.hasMore
        isSearching = false
    }

    fun loadMoreSearch() {
        if (isSearching || isLoadingMore || !searchHasMore || activeSearchTerm.isEmpty()) return
        val term = activeSearchTerm
        val type = searchType
        val next = searchPage + 1
        scope.launch {
            isLoadingMore = true
            val res = repo.search(term, type, page = next)
            if (term == activeSearchTerm && type == searchType) {
                val existingIds = searchItems.map { it.id }.toHashSet()
                val fresh = res.items.filter { it.id !in existingIds }
                searchPage = next
                searchHasMore = res.hasMore && fresh.isNotEmpty()
                if (fresh.isNotEmpty()) searchItems = searchItems + fresh
            }
            isLoadingMore = false
        }
    }

    val d = data
    if (d == null) {
        if (repoError != null) {
            Box(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(CinetorTokens.Shape.lg), GlassLevel.SURFACE)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(repoError.orEmpty(), color = tokens.textPrimary, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(CinetorTokens.Shape.pill))
                            .background(tokens.accent)
                            .clickable { scope.launch { runCatching { repo.refresh() } } }
                            .semantics { contentDescription = "Retry music catalog" }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, tint = tokens.onAccent, modifier = Modifier.size(18.dp))
                        Text("Retry", color = tokens.onAccent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
            return
        }
        ShimmerHost {
            LazyColumn(contentPadding = contentPadding, userScrollEnabled = false) {
                item { SkeletonHero() }
                items(3) { SkeletonPosterRow(Modifier.padding(vertical = 12.dp)) }
            }
        }
        return
    }

    val categories = remember(d.categories) { d.categories.ifEmpty { GatewayMusicMapper.DEFAULT_CATEGORIES } }
    val allCollections = remember(d, artistFilter) {
        val base = d.charts.ifEmpty { d.albums }
        if (artistFilter == null) base else d.albums.filter { it.artist == artistFilter }
    }
    val filteredTracks = remember(d, artistFilter) {
        if (artistFilter == null) d.tracks else d.tracks.filter { it.artist == artistFilter }
    }
    val cols = if (LocalConfiguration.current.screenWidthDp >= 600) 4 else 2
    val currentId = ms.current?.id

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "search_bar", contentType = "search_bar") {
                MusicSearchAndQualityBar(
                    query = searchQuery,
                    onQueryChange = { q ->
                        searchQuery = q
                        if (q.isNotBlank()) selectedCategory = "Trending"
                    },
                    onClear = {
                        searchQuery = ""
                        searchItems = emptyList()
                    },
                    onSubmit = { focusManager.clearFocus() },
                    quality = ms.preferredQuality,
                    onToggleQuality = { music.toggleQuality() },
                )
            }

            item(key = "category_pills", contentType = "category_pills") {
                MusicCategoryPills(
                    categories = categories,
                    selected = if (searchQuery.isNotBlank()) "" else selectedCategory,
                    onSelect = { cat ->
                        searchQuery = ""
                        selectedCategory = cat
                    },
                )
            }

            if (d.isOfflineFallback) {
                item(key = "offline_notice", contentType = "offline_notice") {
                    Row(
                        Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .glass(RoundedCornerShape(CinetorTokens.Shape.md), GlassLevel.SURFACE)
                            .clickable { scope.launch { runCatching { repo.refresh() } } }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = tokens.textSecondary, modifier = Modifier.size(18.dp))
                        Text(
                            "Offline mode · Showing local studio catalog",
                            color = tokens.textSecondary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text("Reconnect", color = tokens.accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (activeSearchTerm.isNotEmpty()) {
                item(key = "search_scopes", contentType = "search_scopes") {
                    SearchScopeRow(
                        queryLabel = activeSearchTerm,
                        selected = searchType,
                        onSelect = { searchType = it },
                    )
                }

                if (isSearching) {
                    items(3, key = { "search_skel_$it" }) {
                        ShimmerHost { SkeletonPosterRow(Modifier.padding(vertical = 6.dp)) }
                    }
                } else if (searchItems.isEmpty()) {
                    item(key = "search_empty", contentType = "empty") {
                        Text(
                            "No ${searchType.label.lowercase()} matched \"$activeSearchTerm\".",
                            color = tokens.textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
                        )
                    }
                } else {
                    when (searchType) {
                        MusicSearchType.SONG -> {
                            itemsIndexed(searchItems, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { i, t ->
                                TrackRow(
                                    track = t,
                                    playing = t.id == currentId,
                                    resolving = ms.resolvingTrackId == t.id,
                                    dl = trackDlOf(downloads[t.id]),
                                    onClick = { music.playQueue(searchItems, i) },
                                    onDownload = {
                                        downloadTrack(container, t, ms.preferredQuality) { msg -> feedbackMessage = msg }
                                    },
                                )
                            }
                        }
                        MusicSearchType.ALBUM, MusicSearchType.PLAYLIST -> {
                            items(
                                searchItems.chunked(cols),
                                key = { row -> "sr-col-" + row.first().id },
                                contentType = { "albums" },
                            ) { row ->
                                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { col ->
                                        AlbumTile(col, col.id == ms.current?.albumId, Modifier.weight(1f)) {
                                            onOpenAlbum(col.id)
                                        }
                                    }
                                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    if (searchHasMore) {
                        item(key = "search_load_more", contentType = "load_more") {
                            LaunchedEffect(searchItems.size) { loadMoreSearch() }
                            Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = tokens.accent)
                            }
                        }
                    }
                }
            } else {
                item(key = "tabs", contentType = "tabs") {
                    CategoryTabs(MusicBrowseTabs, tab, { tab = it })
                }

                if (tab == 0 && d.heroes.isNotEmpty()) {
                    item(key = "hero", contentType = "hero") {
                        HeroCarousel(
                            heroes = d.heroes,
                            myList = myList,
                            visible = true,
                            onOpen = { item ->
                                if (item.type == ItemType.ALBUM) onOpenAlbum(item.id) else music.playItem(item, expand = true)
                            },
                            onPlay = { music.playItem(it) },
                            onToggleMyList = { container.toggleMyList(it) },
                            onColor = feeder::immediate,
                        )
                    }
                }

                artistFilter?.let { a ->
                    item(key = "artist_filter", contentType = "artist") {
                        Row(
                            Modifier
                                .padding(horizontal = 16.dp)
                                .clip(RoundedCornerShape(CinetorTokens.Shape.pill))
                                .background(tokens.accent.copy(alpha = 0.2f))
                                .clickable { artistFilter = null }
                                .semantics { contentDescription = "Clear artist filter" }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Artist: $a", color = tokens.textPrimary, style = MaterialTheme.typography.labelLarge)
                            Icon(Icons.Rounded.Close, contentDescription = null, tint = tokens.textSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                when (tab) {
                    0 -> {
                        if (allCollections.isNotEmpty()) {
                            item(key = "charts_header", contentType = "section_header") {
                                SectionHeader(
                                    title = if (d.charts.isNotEmpty()) "Top Charts & Playlists" else "Featured Albums",
                                    badge = ms.preferredQuality.badgeLabel,
                                )
                            }
                            item(key = "charts_row", contentType = "charts_row") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(allCollections, key = { it.id }, contentType = { "chart_card" }) { chart ->
                                        ChartCard(
                                            chart = chart,
                                            playing = chart.id == ms.current?.albumId,
                                            onClick = { onOpenAlbum(chart.id) },
                                        )
                                    }
                                }
                            }
                        }

                        if (filteredTracks.isNotEmpty()) {
                            item(key = "trending_header", contentType = "section_header") {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Trending Songs", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text("Tap any song to stream · Download in ${ms.preferredQuality.shortLabel}", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        QuickFeedAction("Play All", Icons.Rounded.PlayArrow, primary = true) {
                                            music.playQueue(filteredTracks, 0)
                                        }
                                        QuickFeedAction("Shuffle", Icons.Rounded.Shuffle, primary = false) {
                                            music.playQueue(filteredTracks, filteredTracks.indices.random())
                                            if (music.state.value.queue?.shuffle != true) music.toggleShuffle()
                                        }
                                    }
                                }
                            }
                            itemsIndexed(filteredTracks, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { i, t ->
                                TrackRow(
                                    track = t,
                                    playing = t.id == currentId,
                                    resolving = ms.resolvingTrackId == t.id,
                                    dl = trackDlOf(downloads[t.id]),
                                    number = i + 1,
                                    onClick = { music.playQueue(filteredTracks, i) },
                                    onDownload = {
                                        downloadTrack(container, t, ms.preferredQuality) { msg -> feedbackMessage = msg }
                                    },
                                )
                            }
                        }
                    }

                    1 -> {
                        items(allCollections.chunked(cols), key = { row -> "al-" + row.first().id }, contentType = { "albums" }) { row ->
                            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { al ->
                                    AlbumTile(al, al.id == ms.current?.albumId, Modifier.weight(1f)) { onOpenAlbum(al.id) }
                                }
                                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }

                    2 -> {
                        itemsIndexed(filteredTracks, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { i, t ->
                            TrackRow(
                                track = t,
                                playing = t.id == currentId,
                                resolving = ms.resolvingTrackId == t.id,
                                dl = trackDlOf(downloads[t.id]),
                                onClick = { music.playQueue(filteredTracks, i) },
                                onDownload = {
                                    downloadTrack(container, t, ms.preferredQuality) { msg -> feedbackMessage = msg }
                                },
                            )
                        }
                    }

                    else -> {
                        items(d.artists.chunked(cols + 1), key = { row -> "ar-" + row.first().name }, contentType = { "artists" }) { row ->
                            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { a ->
                                    ArtistTile(a, Modifier.weight(1f)) {
                                        artistFilter = a.name
                                        tab = 2
                                    }
                                }
                                repeat(cols + 1 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                if ((tab == 1 && allCollections.isEmpty()) || (tab == 2 && filteredTracks.isEmpty())) {
                    item(key = "empty", contentType = "empty") {
                        Text("Nothing here yet", color = tokens.textSecondary, modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = feedbackMessage != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = contentPadding.calculateBottomPadding() + 12.dp,
                ),
        ) {
            MusicFeedbackBanner(
                message = feedbackMessage.orEmpty(),
                onDismiss = { feedbackMessage = null },
            )
        }
    }
}

@Composable
private fun MusicSearchAndQualityBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    quality: MusicAudioQuality,
    onToggleQuality: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val barShape = RoundedCornerShape(CinetorTokens.Shape.lg)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(48.dp)
                .glass(barShape, GlassLevel.SURFACE)
                .then(if (focused) Modifier.border(2.dp, tokens.accent, barShape) else Modifier)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = tokens.textSecondary, modifier = Modifier.size(20.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        "Search songs, albums, playlists...",
                        color = tokens.textTertiary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    interactionSource = interaction,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.textPrimary),
                    cursorBrush = SolidColor(tokens.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search music" },
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClear)
                        .semantics { contentDescription = "Clear music search" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = tokens.textSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
        QualityTogglePill(
            quality = quality,
            onToggle = onToggleQuality,
            fullLabel = false,
        )
    }
}

@Composable
private fun MusicCategoryPills(
    categories: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(categories, key = { it }, contentType = { "music_chip" }) { cat ->
            val isSelected = cat.equals(selected, ignoreCase = true)
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            Text(
                cat,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) tokens.onAccent else tokens.textPrimary,
                modifier = Modifier
                    .then(
                        if (isSelected) {
                            Modifier.clip(shape).background(tokens.accent)
                        } else {
                            Modifier.glass(shape, GlassLevel.SURFACE)
                        },
                    )
                    .then(
                        if (focused) Modifier.border(2.dp, tokens.focusRing, shape) else Modifier,
                    )
                    .clickable(interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(cat) }
                    .semantics {
                        this.selected = isSelected
                        contentDescription = "Music category $cat"
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun SearchScopeRow(
    queryLabel: String,
    selected: MusicSearchType,
    onSelect: (MusicSearchType) -> Unit,
) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Results for \"$queryLabel\"",
            color = tokens.textPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MusicSearchType.entries.forEach { scopeType ->
                val isSelected = scopeType == selected
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                Text(
                    scopeType.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) tokens.onAccent else tokens.textSecondary,
                    modifier = Modifier
                        .clip(shape)
                        .background(if (isSelected) tokens.accent else tokens.surfaceElevated)
                        .border(
                            if (focused) 2.dp else 1.dp,
                            if (focused) tokens.focusRing else if (isSelected) tokens.accent else tokens.glassStroke,
                            shape,
                        )
                        .clickable(interactionSource = interaction, indication = null) { onSelect(scopeType) }
                        .semantics { contentDescription = "Search ${scopeType.label}" }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, badge: String) {
    val tokens = Cinetor.tokens
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(badge, color = tokens.accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun QuickFeedAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(CinetorTokens.Shape.pill)
    Row(
        Modifier
            .then(
                if (primary) {
                    Modifier.clip(shape).background(tokens.accent)
                } else {
                    Modifier.glass(shape, GlassLevel.SURFACE)
                },
            )
            .then(if (focused) Modifier.border(2.dp, tokens.focusRing, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (primary) tokens.onAccent else tokens.textPrimary, modifier = Modifier.size(15.dp))
        Text(label, color = if (primary) tokens.onAccent else tokens.textPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ChartCard(chart: Item, playing: Boolean, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "chartScale")
    val cardShape = RoundedCornerShape(CinetorTokens.Shape.card)
    val accent = remember(chart.accentColor) { hexColor(chart.accentColor) }

    Column(
        Modifier
            .width(156.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "Album ${chart.title}" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(156.dp)
                .clip(cardShape)
                .background(hexColor(chart.dominantColor))
                .border(
                    when {
                        focused -> 2.5.dp
                        playing -> 2.dp
                        else -> 1.dp
                    },
                    when {
                        focused -> tokens.focusRing
                        playing -> accent
                        else -> tokens.glassStroke
                    },
                    cardShape,
                ),
        ) {
            AsyncImage(
                model = rememberSizedImage(chart.poster, 156.dp, 156.dp),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    chart.altTitle ?: "HQ Lossless",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
        Text(
            chart.title,
            color = if (focused) tokens.accent else tokens.textPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            chart.artist.orEmpty(),
            color = tokens.textSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AlbumTile(album: Item, playing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "albumTileScale")
    val accent = remember(album.accentColor) { hexColor(album.accentColor) }
    val shape = RoundedCornerShape(CinetorTokens.Shape.card)

    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "Album ${album.title}" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AsyncImage(
            model = rememberSizedImage(album.poster, 170.dp, 170.dp),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(hexColor(album.dominantColor))
                .border(
                    when {
                        focused -> 2.5.dp
                        playing -> 2.dp
                        else -> 1.dp
                    },
                    when {
                        focused -> tokens.focusRing
                        playing -> accent
                        else -> tokens.glassStroke
                    },
                    shape,
                ),
        )
        Text(
            album.title,
            color = if (focused) tokens.accent else tokens.textPrimary,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(album.artist, album.year.takeIf { it > 0 }?.toString()).joinToString(" · "),
            color = tokens.textSecondary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArtistTile(artist: Artist, modifier: Modifier, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "artistScale")

    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "Artist ${artist.name}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AsyncImage(
            model = rememberSizedImage(artist.cover, 96.dp, 96.dp),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(tokens.surfaceElevated)
                .then(if (focused) Modifier.border(2.dp, tokens.focusRing, CircleShape) else Modifier),
        )
        Text(
            artist.name,
            color = if (focused) tokens.accent else tokens.textPrimary,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text("${artist.tracks.size} tracks", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}
