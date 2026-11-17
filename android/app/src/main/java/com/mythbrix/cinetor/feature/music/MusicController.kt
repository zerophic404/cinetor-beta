package com.mythbrix.cinetor.feature.music

import androidx.compose.runtime.Immutable
import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.gateway.DominantColors
import com.mythbrix.cinetor.core.data.gateway.NoDominantColors
import com.mythbrix.cinetor.core.legal.UserErrors
import com.mythbrix.cinetor.core.player.CinetorPlayer
import com.mythbrix.cinetor.core.player.PlaybackPhase
import com.mythbrix.cinetor.core.player.PlaybackPreloader
import com.mythbrix.cinetor.core.player.PlayerUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Activity stopped (Home / screen off): pause video unless PiP or the Background-play setting keeps it;
 * music always continues (PlaybackService keeps the media notification + session alive).
 */
fun shouldPauseOnBackground(videoActive: Boolean, musicActive: Boolean, backgroundPlay: Boolean, inPip: Boolean): Boolean =
    videoActive && !musicActive && !inPip && !backgroundPlay

@Immutable
data class MusicState(
    val queue: MusicQueue? = null,
    /** Now Playing full screen open (else the glass mini bar). */
    val expanded: Boolean = false,
    val generation: Int = 0,
    val preferredQuality: MusicAudioQuality = MusicAudioQuality.FLAC,
    val activeQualityLabel: String? = null,
    val lyrics: MusicLyrics? = null,
    val lyricsLoading: Boolean = false,
    val resolvingTrackId: String? = null,
    val error: String? = null,
) {
    val current: Item? get() = queue?.current
    val active: Boolean get() = current != null
}

/**
 * App-scoped music player logic (Android-free, TestScope-drivable). Uses the ONE shared player via the
 * preloader (never two audios); it lives in AppContainer, so navigating between screens/spaces or sending
 * the app to background never stops it. Video taking the player calls [yieldToVideo].
 */
class MusicController(
    private val scope: CoroutineScope,
    private val preloader: PlaybackPreloader,
    /** Close the video player before music takes the shared player. */
    private val takeOver: () -> Unit = {},
    /** Queue context for a single track (its album) + start index. */
    private val contextFor: suspend (Item) -> Pair<List<Item>, Int> = { listOf(it) to 0 },
    /** Album → its tracks. */
    private val tracksOf: suspend (Item) -> List<Item> = { emptyList() },
    private val resolve: suspend (String) -> Item? = { null },
    /** Attaches live `/music/stream` URL + headers right before opening on the shared player. */
    private val prepareTrack: (suspend (Item, MusicAudioQuality) -> Item)? = null,
    /** Loads plain + time-synced LRC lyrics for the active track. */
    private val fetchLyrics: (suspend (Item) -> MusicLyrics?)? = null,
    private val random: Random = Random.Default,
    private val restartThresholdMs: Long = 3_000,
) {
    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()

    private val _player = MutableStateFlow<CinetorPlayer?>(null)
    val player: StateFlow<CinetorPlayer?> = _player.asStateFlow()

    private var loadJob: Job? = null
    private var watchJob: Job? = null
    private var lyricsJob: Job? = null

    val playback: PlayerUiState get() = _player.value?.state?.value ?: PlayerUiState()

    /** Play [tracks] from [startIndex] (album, playlist, chart, search result context). */
    fun playQueue(tracks: List<Item>, startIndex: Int = 0, expand: Boolean = false) {
        val list = tracks.filter { it.type == ItemType.TRACK }
        if (list.isEmpty()) return
        val start = list.indexOfFirst { it.id == tracks.getOrNull(startIndex)?.id }.coerceAtLeast(0)
        val prev = _state.value
        val queue = MusicQueue(list, start, repeat = prev.queue?.repeat ?: RepeatMode.OFF)
        val q = if (prev.queue?.shuffle == true) queue.toggleShuffle(random) else queue
        _state.value = prev.copy(
            queue = q,
            expanded = expand || prev.expanded,
            generation = prev.generation + 1,
            error = null,
        )
        load(q.current ?: return)
    }

    /** Music item tapped anywhere (track → its album as queue; album/playlist → whole tracklist). */
    fun playItem(item: Item, expand: Boolean = false) {
        scope.launch {
            when (item.type) {
                ItemType.ALBUM -> playQueue(tracksOf(item), 0, expand)
                ItemType.TRACK -> {
                    val (ctx, i) = contextFor(item)
                    playQueue(ctx.ifEmpty { listOf(item) }, i, expand)
                }
                else -> Unit
            }
        }
    }

    fun playById(id: String) {
        scope.launch { resolve(id)?.let { playItem(it) } }
    }

    /** Audio items belong here (PlayerStateHolder hook, including offline downloaded Music records). */
    fun handles(item: Item): Boolean {
        if (item.space != Space.MUSIC) return false
        return when (item.type) {
            ItemType.ALBUM, ItemType.TRACK -> {
                playItem(item)
                true
            }
            ItemType.MOVIE -> if (item.sources.isNotEmpty()) {
                playItem(item.copy(type = ItemType.TRACK))
                true
            } else {
                false
            }
            else -> false
        }
    }

    /** Switch streaming & download preference (`FLAC` ↔ `320kbps`); reloads active gateway track seamlessly. */
    fun setQuality(quality: MusicAudioQuality) {
        val prev = _state.value
        if (prev.preferredQuality == quality) return
        val cur = prev.current
        _state.update {
            it.copy(
                preferredQuality = quality,
                activeQualityLabel = cur?.let { tr -> GatewayMusicMapper.qualityBadgeFor(tr, quality) },
                error = null,
            )
        }
        if (cur != null && prepareTrack != null) {
            val resumePos = playback.positionMs.coerceAtLeast(0L)
            load(cur, forceReload = true, initialSeekMs = resumePos, keepLyrics = true)
        }
    }

    fun toggleQuality() = setQuality(_state.value.preferredQuality.toggle())

    fun clearError() = _state.update { it.copy(error = null) }

    private fun load(
        track: Item,
        forceReload: Boolean = false,
        initialSeekMs: Long = 0L,
        keepLyrics: Boolean = false,
    ) {
        takeOver()
        loadJob?.cancel()
        watchJob?.cancel()
        if (!keepLyrics) {
            lyricsJob?.cancel()
            val fetcher = fetchLyrics
            if (fetcher != null) {
                _state.update { it.copy(lyrics = null, lyricsLoading = true) }
                lyricsJob = scope.launch {
                    val loaded = try {
                        fetcher(track)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    _state.update { s ->
                        if (s.current?.id == track.id) s.copy(lyrics = loaded, lyricsLoading = false) else s
                    }
                }
            } else {
                _state.update { it.copy(lyrics = null, lyricsLoading = false) }
            }
        }

        val preparer = prepareTrack
        if (preparer == null) {
            _state.update {
                it.copy(
                    resolvingTrackId = null,
                    error = null,
                    activeQualityLabel = GatewayMusicMapper.qualityBadgeFor(track, it.preferredQuality, track.studio),
                )
            }
            if (forceReload) preloader.release(track.id)
            val p = preloader.open(track) ?: return
            _player.value = p
            watch(p, track, initialSeekMs)
            return
        }

        val quality = _state.value.preferredQuality
        _state.update {
            it.copy(
                resolvingTrackId = track.id,
                error = null,
                activeQualityLabel = GatewayMusicMapper.qualityBadgeFor(track, quality),
            )
        }
        loadJob = scope.launch {
            val prepared = try {
                preparer(track, quality)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s ->
                    if (s.current?.id == track.id) {
                        s.copy(resolvingTrackId = null, error = UserErrors.safe(e, UserErrors.PLAYBACK))
                    } else {
                        s
                    }
                }
                return@launch
            }
            if (_state.value.current?.id != track.id) return@launch
            if (prepared.sources.isEmpty()) {
                _state.update { it.copy(resolvingTrackId = null, error = UserErrors.PLAYBACK) }
                return@launch
            }
            val resolvedLabel = GatewayMusicMapper.qualityBadgeFor(
                prepared,
                quality,
                prepared.sources.firstOrNull()?.label ?: prepared.studio,
            )
            _state.update { s ->
                val updatedQueue = s.queue?.let { q ->
                    val updatedItems = q.items.map { if (it.id == prepared.id) prepared else it }
                    val updatedOrig = q.original.map { if (it.id == prepared.id) prepared else it }
                    q.copy(items = updatedItems, original = updatedOrig)
                }
                s.copy(queue = updatedQueue, resolvingTrackId = null, activeQualityLabel = resolvedLabel, error = null)
            }
            if (forceReload) preloader.release(prepared.id)
            val p = preloader.open(prepared) ?: return@launch
            _player.value = p
            watch(p, prepared, initialSeekMs)
        }
    }

    private fun watch(p: CinetorPlayer, track: Item, initialSeekMs: Long = 0L) {
        watchJob = scope.launch {
            var endedHandled = false
            var seekApplied = initialSeekMs <= 0L
            p.state.collect { st ->
                if (st.key != track.id) return@collect
                if (!seekApplied) {
                    seekApplied = true
                    p.seekTo(initialSeekMs)
                }
                if (st.error != null) {
                    _state.update { it.copy(error = UserErrors.PLAYBACK) }
                }
                if (st.phase == PlaybackPhase.ENDED) {
                    if (!endedHandled) {
                        endedHandled = true
                        advance(auto = true)
                    }
                } else {
                    endedHandled = false
                }
            }
        }
    }

    private fun advance(auto: Boolean) {
        val q = _state.value.queue ?: return
        val next = q.next(auto)
        if (next == null) {
            // End of queue: stay on the last track, paused at its end (mini bar keeps showing it).
            _player.value?.pause()
            return
        }
        val sameTrack = next.current?.id == q.current?.id
        _state.update { it.copy(queue = next, generation = if (sameTrack) it.generation else it.generation + 1, error = null) }
        if (sameTrack) {
            _player.value?.seekTo(0)
            _player.value?.play()
        } else {
            next.current?.let(::load)
        }
    }

    fun next() = advance(auto = false)

    /** > 3 s into the track → restart it; else previous track. */
    fun previous() {
        val q = _state.value.queue ?: return
        if (playback.positionMs > restartThresholdMs || q.previous() === q) {
            _player.value?.seekTo(0)
            return
        }
        val prev = q.previous()
        _state.update { it.copy(queue = prev, generation = it.generation + 1, error = null) }
        prev.current?.let(::load)
    }

    fun playAt(i: Int) {
        val q = _state.value.queue ?: return
        if (i == q.index) return
        val n = q.playAt(i)
        _state.update { it.copy(queue = n, generation = it.generation + 1, error = null) }
        n.current?.let(::load)
    }

    fun move(from: Int, to: Int) = _state.update { s -> s.copy(queue = s.queue?.move(from, to)) }
    fun removeAt(i: Int) = _state.update { s -> s.copy(queue = s.queue?.removeAt(i)) }
    fun toggleShuffle() = _state.update { s -> s.copy(queue = s.queue?.toggleShuffle(random)) }
    fun cycleRepeat() = _state.update { s -> s.copy(queue = s.queue?.cycleRepeat()) }

    fun togglePlay() {
        val p = _player.value
        if (p != null) {
            if (p.state.value.playWhenReady) p.pause() else p.play()
        } else {
            _state.value.current?.let { load(it, keepLyrics = _state.value.lyrics != null) }
        }
    }

    fun pause() = _player.value?.pause()
    fun seekTo(positionMs: Long) = _player.value?.seekTo(positionMs)

    fun expand() {
        if (_state.value.active) _state.update { it.copy(expanded = true) }
    }

    fun collapse() = _state.update { it.copy(expanded = false) }

    /** Stop music and hide the bar. */
    fun stop() {
        val cur = _state.value.current
        loadJob?.cancel()
        loadJob = null
        watchJob?.cancel()
        watchJob = null
        lyricsJob?.cancel()
        lyricsJob = null
        val pref = _state.value.preferredQuality
        _player.value = null
        _state.update { MusicState(generation = it.generation + 1, preferredQuality = pref) }
        cur?.let { preloader.release(it.id) }
    }

    /** A video title is about to take the one player: drop the queue without touching the player. */
    fun yieldToVideo() {
        if (!_state.value.active) return
        loadJob?.cancel()
        loadJob = null
        watchJob?.cancel()
        watchJob = null
        lyricsJob?.cancel()
        lyricsJob = null
        val pref = _state.value.preferredQuality
        _player.value = null
        _state.update { MusicState(generation = it.generation + 1, preferredQuality = pref) }
    }
}

/** Music library over the gateway (with bundled mock fallback only when completely offline & uncached). */
@Immutable
data class Artist(val name: String, val cover: String, val albums: List<Item>, val tracks: List<Item>)

@Immutable
data class MusicCollection(
    val header: Item,
    val tracks: List<Item>,
    val isPlaylist: Boolean = false,
)

@Immutable
data class MusicSearchResult(
    val type: MusicSearchType,
    val items: List<Item>,
    val hasMore: Boolean,
)

@Immutable
data class MusicData(
    val albums: List<Item>,
    val tracks: List<Item>,
    val genres: List<String>,
    val heroes: List<Item>,
    val charts: List<Item> = emptyList(),
    val categories: List<String> = emptyList(),
    val isOfflineFallback: Boolean = false,
) {
    private val tracksById = tracks.associateBy { it.id }
    val artists: List<Artist> by lazy {
        val groupedAlbums = albums.groupBy { it.artist ?: "Unknown" }
        if (groupedAlbums.isNotEmpty()) {
            groupedAlbums.map { (name, al) ->
                Artist(name, al.first().poster, al, tracks.filter { (it.artist ?: "Unknown") == name })
            }.sortedBy { it.name }
        } else {
            tracks.groupBy { it.artist ?: "Unknown" }.map { (name, tr) ->
                Artist(name, tr.first().poster, emptyList(), tr)
            }.sortedBy { it.name }
        }
    }

    fun tracksOf(album: Item): List<Item> =
        album.trackIds.mapNotNull { tracksById[it] }.ifEmpty { tracks.filter { it.albumId == album.id }.sortedBy { it.trackNumber ?: 0 } }

    fun albumOf(track: Item): Item? = (albums + charts).firstOrNull { it.id == track.albumId }
}

class MusicRepository(
    private val catalog: CatalogRepository,
    private val api: GatewayMusicApi? = null,
    private val colors: DominantColors = NoDominantColors,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val STREAM_TTL_MS = 10 * 60_000L
    }

    private val mutex = Mutex()
    private val _data = MutableStateFlow<MusicData?>(null)
    val data: StateFlow<MusicData?> = _data.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val knownTracks = ConcurrentHashMap<String, Item>()
    private val knownCollections = ConcurrentHashMap<String, MusicCollection>()
    private val streamCache = ConcurrentHashMap<String, Pair<Long, GwMusicStream>>()

    suspend fun load(forceRefresh: Boolean = false): MusicData {
        if (!forceRefresh) _data.value?.let { return it }
        return mutex.withLock {
            if (!forceRefresh) _data.value?.let { return@withLock it }
            val loaded = fetchMusicData()
            _data.value = loaded
            _error.value = null
            loaded
        }
    }

    suspend fun refresh(): MusicData = load(forceRefresh = true)

    private suspend fun fetchMusicData(): MusicData {
        val gw = api ?: return loadMockCatalog(isOfflineFallback = false)
        return try {
            val feed = gw.trending()
            val charts = feed.charts.map { GatewayMusicMapper.chartToItem(it, colors) }
            val tracks = feed.tracks.mapIndexed { idx, t -> GatewayMusicMapper.trackToItem(t, colors, idx + 1) }
            tracks.forEach { knownTracks[it.id] = it }
            charts.forEach { ch -> knownCollections.putIfAbsent(ch.id, MusicCollection(ch, emptyList(), isPlaylist = true)) }
            val categories = feed.categories.ifEmpty { GatewayMusicMapper.DEFAULT_CATEGORIES }
            MusicData(
                albums = charts,
                tracks = tracks,
                genres = categories.filterNot { it.equals("Trending", ignoreCase = true) },
                heroes = charts.take(5).ifEmpty { tracks.take(3) },
                charts = charts,
                categories = categories,
                isOfflineFallback = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: OfflineNoCacheException) {
            loadMockCatalog(isOfflineFallback = true)
        } catch (e: Exception) {
            _error.value = UserErrors.safe(e, UserErrors.CATALOG)
            throw e
        }
    }

    private suspend fun loadMockCatalog(isOfflineFallback: Boolean): MusicData {
        val all = catalog.allItems(Space.MUSIC)
        val albums = all.filter { it.type == ItemType.ALBUM }
        val tracks = all.filter { it.type == ItemType.TRACK }
        tracks.forEach { knownTracks[it.id] = it }
        val heroIds = catalog.categories(Space.MUSIC).flatMap { it.heroIds }.distinct()
        val genres = catalog.genres(Space.MUSIC)
        return MusicData(
            albums = albums,
            tracks = tracks,
            genres = genres,
            heroes = heroIds.mapNotNull { id -> albums.firstOrNull { it.id == id } }.ifEmpty { albums.take(3) },
            charts = emptyList(),
            categories = listOf("Trending") + genres,
            isOfflineFallback = isOfflineFallback,
        )
    }

    suspend fun search(query: String, type: MusicSearchType = MusicSearchType.SONG, page: Int = 1): MusicSearchResult {
        val q = query.trim()
        if (q.isEmpty()) return MusicSearchResult(type, emptyList(), false)
        val gw = api
        if (gw == null || _data.value?.isOfflineFallback == true) {
            val d = runCatching { load() }.getOrNull() ?: return MusicSearchResult(type, emptyList(), false)
            val pool = when (type) {
                MusicSearchType.SONG -> d.tracks
                MusicSearchType.ALBUM -> d.albums
                MusicSearchType.PLAYLIST -> d.charts.ifEmpty { d.albums }
            }
            val hits = pool.filter {
                it.title.contains(q, ignoreCase = true) ||
                    (it.artist?.contains(q, ignoreCase = true) == true) ||
                    (it.album?.contains(q, ignoreCase = true) == true)
            }
            return MusicSearchResult(type, hits, hasMore = false)
        }
        return try {
            when (type) {
                MusicSearchType.SONG -> {
                    val (list, more) = gw.searchSongs(q, page)
                    val items = list.mapIndexed { idx, t -> GatewayMusicMapper.trackToItem(t, colors, (page - 1) * 20 + idx + 1) }
                    items.forEach { knownTracks[it.id] = it }
                    MusicSearchResult(type, items, more)
                }
                MusicSearchType.ALBUM -> {
                    val (list, more) = gw.searchAlbums(q, page)
                    val items = list.map { GatewayMusicMapper.albumToItem(it, colors) }
                    MusicSearchResult(type, items, more)
                }
                MusicSearchType.PLAYLIST -> {
                    val (list, more) = gw.searchPlaylists(q, page)
                    val items = list.map { GatewayMusicMapper.chartToItem(it, colors) }
                    MusicSearchResult(type, items, more)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            MusicSearchResult(type, emptyList(), false)
        }
    }

    /** Loads an Album or Playlist/Chart detail by its routed [id]. */
    suspend fun loadCollection(id: String): MusicCollection? {
        knownCollections[id]?.takeIf { it.tracks.isNotEmpty() }?.let { return it }
        val gw = api
        if (gw != null && (GatewayMusicMapper.isGatewayPlaylistId(id) || GatewayMusicMapper.isGatewayAlbumId(id))) {
            val loaded = try {
                if (GatewayMusicMapper.isGatewayPlaylistId(id)) {
                    gw.playlist(id)?.let { pl ->
                        val (header, tracks) = GatewayMusicMapper.playlistToItem(pl, colors)
                        MusicCollection(header, tracks, isPlaylist = true)
                    }
                } else {
                    gw.album(id)?.let { al ->
                        val header = GatewayMusicMapper.albumToItem(al, colors)
                        val tracks = al.tracks.mapIndexed { idx, tr ->
                            GatewayMusicMapper.trackToItem(tr, colors, idx + 1, overrideAlbumId = header.id)
                        }
                        MusicCollection(header, tracks, isPlaylist = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (loaded != null) {
                loaded.tracks.forEach { knownTracks[it.id] = it }
                knownCollections[id] = loaded
                return loaded
            }
        }
        val d = runCatching { load() }.getOrNull()
        val mockAlbum = d?.albums?.firstOrNull { it.id == id } ?: d?.charts?.firstOrNull { it.id == id }
        if (d != null && mockAlbum != null) {
            val tracks = d.tracksOf(mockAlbum)
            return MusicCollection(mockAlbum, tracks, isPlaylist = GatewayMusicMapper.isGatewayPlaylistId(id)).also {
                if (tracks.isNotEmpty()) knownCollections[id] = it
            }
        }
        return knownCollections[id]
    }

    suspend fun resolveItem(id: String): Item? {
        knownTracks[id]?.let { return it }
        knownCollections[id]?.let { return it.header }
        if (GatewayMusicMapper.isGatewayPlaylistId(id) || GatewayMusicMapper.isGatewayAlbumId(id)) {
            return loadCollection(id)?.header
        }
        val d = runCatching { load() }.getOrNull() ?: return null
        return d.tracks.firstOrNull { it.id == id } ?: d.albums.firstOrNull { it.id == id } ?: d.charts.firstOrNull { it.id == id }
    }

    /** Track → its album/playlist tracks (queue context) + index. */
    suspend fun contextFor(track: Item): Pair<List<Item>, Int> {
        track.albumId?.let { colId ->
            loadCollection(colId)?.takeIf { it.tracks.isNotEmpty() }?.let { col ->
                val idx = col.tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                return col.tracks to idx
            }
        }
        val d = runCatching { load() }.getOrNull() ?: return listOf(track) to 0
        val album = d.albumOf(track) ?: return listOf(track) to 0
        val list = d.tracksOf(album)
        if (list.isEmpty()) return listOf(track) to 0
        return list to list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
    }

    suspend fun tracksOf(album: Item): List<Item> =
        loadCollection(album.id)?.tracks?.takeIf { it.isNotEmpty() } ?: runCatching { load().tracksOf(album) }.getOrDefault(emptyList())

    private suspend fun resolveStream(trackId: String, quality: MusicAudioQuality): GwMusicStream? {
        val gw = api ?: return null
        val key = "${GatewayMusicMapper.rawIdOf(trackId)}|${quality.queryValue}"
        val now = clock()
        streamCache[key]?.let { (at, stream) -> if (now - at < STREAM_TTL_MS) return stream }
        return gw.stream(trackId, quality)?.also { streamCache[key] = now to it }
    }

    /** Attaches live stream URL + headers for playback, or offline file stub when already downloaded. */
    suspend fun attachStream(
        track: Item,
        quality: MusicAudioQuality,
        offlineAvailable: (String) -> Boolean = { false },
    ): Item {
        if (!GatewayMusicMapper.isGatewayTrackId(track.id)) return track
        val stream = resolveStream(track.id, quality)
        if (stream != null) {
            val src = GatewayMusicMapper.sourceOfStream(stream, quality, forDownload = false)
            return track.copy(
                studio = stream.quality.ifBlank { quality.badgeLabel },
                sources = listOf(src),
            )
        }
        if (offlineAvailable(track.id)) {
            val offlineStub = Source(url = "file:///offline/${track.id}", kind = SourceKind.MP4, label = quality.badgeLabel)
            return track.copy(sources = listOf(offlineStub))
        }
        throw UserErrors.NoRouteException(UserErrors.PLAYBACK)
    }

    /** Attaches live `downloadUrl` + headers for `AppContainer.downloads`. */
    suspend fun resolveDownloadItem(track: Item, quality: MusicAudioQuality): Item? {
        if (!GatewayMusicMapper.isGatewayTrackId(track.id)) return track.takeIf { it.sources.isNotEmpty() }
        val stream = resolveStream(track.id, quality) ?: return null
        val src = GatewayMusicMapper.sourceOfStream(stream, quality, forDownload = true)
        return track.copy(
            studio = stream.quality.ifBlank { quality.badgeLabel },
            sources = listOf(src),
        )
    }

    suspend fun lyricsFor(track: Item): MusicLyrics? {
        val gw = api ?: return null
        return gw.lyrics(track.id, track.title, track.artist.orEmpty())
    }
}
