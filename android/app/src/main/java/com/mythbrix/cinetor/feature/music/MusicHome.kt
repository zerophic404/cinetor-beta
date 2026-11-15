package com.mythbrix.cinetor.feature.music

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.ShimmerHost
import com.mythbrix.cinetor.design.SkeletonHero
import com.mythbrix.cinetor.design.SkeletonPosterRow
import com.mythbrix.cinetor.design.hexColor
import com.mythbrix.cinetor.feature.home.CategoryTabs
import com.mythbrix.cinetor.feature.home.GenreChips
import com.mythbrix.cinetor.feature.home.HeroCarousel
import com.mythbrix.cinetor.feature.home.TabSpec
import com.mythbrix.cinetor.feature.home.rememberTintFeeder

private val MusicTabs = listOf(
    TabSpec("mu-albums", "Albums", emptyList()),
    TabSpec("mu-tracks", "Tracks", emptyList()),
    TabSpec("mu-artists", "Artists", emptyList()),
)

/** Music space root: Albums · Tracks · Artists, hero carousel, genre chips. Isolated space. */
@Composable
fun MusicHome(container: AppContainer, contentPadding: PaddingValues, onOpenAlbum: (String) -> Unit) {
    val repo = container.musicLibrary
    val data by repo.data.collectAsStateWithLifecycle()
    LaunchedEffect(repo) { runCatching { repo.load() } }
    val music = container.music
    val ms by music.state.collectAsStateWithLifecycle()
    val myList by container.myList.collectAsStateWithLifecycle()
    val downloads = rememberMusicDownloads(container)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var artistFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val feeder = rememberTintFeeder()
    val d = data
    if (d == null) {
        ShimmerHost {
            LazyColumn(contentPadding = contentPadding, userScrollEnabled = false) {
                item { SkeletonHero() }
                items(3) { SkeletonPosterRow(Modifier.padding(vertical = 12.dp)) }
            }
        }
        return
    }
    fun Item.matches(): Boolean =
        (genre == null || genres.any { it.equals(genre, true) }) && (artistFilter == null || this.artist == artistFilter)
    val albums = remember(d, genre, artistFilter) { d.albums.filter { it.matches() } }
    val tracks = remember(d, genre, artistFilter) { d.tracks.filter { it.matches() } }
    val artists = remember(d, genre) { d.artists.filter { a -> genre == null || a.albums.any { al -> al.genres.any { it.equals(genre, true) } } } }
    val cols = if (LocalConfiguration.current.screenWidthDp >= 600) 4 else 2
    val currentId = ms.current?.id

    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "tabs", contentType = "tabs") { CategoryTabs(MusicTabs, tab, { tab = it }) }
        item(key = "hero", contentType = "hero") {
            HeroCarousel(
                heroes = d.heroes,
                myList = myList,
                visible = true,
                onOpen = { onOpenAlbum(it.id) },
                onPlay = { music.playItem(it) },
                onToggleMyList = { container.toggleMyList(it) },
                onColor = feeder::immediate,
            )
        }
        item(key = "genres", contentType = "genres") { GenreChips(d.genres, genre, { genre = it }) }
        artistFilter?.let { a ->
            item(key = "artist", contentType = "artist") {
                Row(
                    Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(CinetorTokens.Shape.pill))
                        .background(Cinetor.tokens.accent.copy(alpha = 0.2f))
                        .clickable { artistFilter = null }
                        .semantics { contentDescription = "Clear artist filter" }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Artist: $a", color = Cinetor.tokens.textPrimary, style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = Cinetor.tokens.textSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
        when (tab) {
            1 -> itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { i, t ->
                TrackRow(
                    track = t, playing = t.id == currentId, dl = trackDlOf(downloads[t.id]),
                    onClick = { music.playQueue(tracks, i) },
                    onDownload = { downloadTrack(container, t) },
                )
            }
            2 -> items(artists.chunked(cols + 1), key = { row -> "ar-" + row.first().name }, contentType = { "artists" }) { row ->
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { a ->
                        ArtistTile(a, Modifier.weight(1f)) {
                            artistFilter = a.name
                            tab = 0
                        }
                    }
                    repeat(cols + 1 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            else -> items(albums.chunked(cols), key = { row -> "al-" + row.first().id }, contentType = { "albums" }) { row ->
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { al -> AlbumTile(al, al.id == ms.current?.albumId, Modifier.weight(1f)) { onOpenAlbum(al.id) } }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if ((tab == 0 && albums.isEmpty()) || (tab == 1 && tracks.isEmpty())) {
            item(key = "empty", contentType = "empty") {
                Text("Nothing here yet", color = Cinetor.tokens.textSecondary, modifier = Modifier.padding(24.dp))
            }
        }
    }
}

@Composable
private fun AlbumTile(album: Item, playing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    val accent = remember(album.accentColor) { hexColor(album.accentColor) }
    Column(
        modifier.clickable(onClick = onClick).semantics { contentDescription = "Album ${album.title}" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AsyncImage(
            model = rememberSizedImage(album.poster, 170.dp, 170.dp),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(CinetorTokens.Shape.card))
                .background(hexColor(album.dominantColor))
                .border(if (playing) 2.dp else 1.dp, if (playing) accent else tokens.glassStroke, RoundedCornerShape(CinetorTokens.Shape.card)),
        )
        Text(album.title, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(album.artist, album.year.takeIf { it > 0 }?.toString()).joinToString(" · "),
            color = tokens.textSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArtistTile(artist: Artist, modifier: Modifier, onClick: () -> Unit) {
    val tokens = Cinetor.tokens
    Column(
        modifier.clickable(onClick = onClick).semantics { contentDescription = "Artist ${artist.name}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AsyncImage(
            model = rememberSizedImage(artist.cover, 96.dp, 96.dp),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(tokens.surfaceElevated),
        )
        Text(artist.name, color = tokens.textPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text("${artist.tracks.size} tracks", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}
