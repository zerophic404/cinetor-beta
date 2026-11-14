package com.mythbrix.cinetor.feature.music

import androidx.compose.runtime.Immutable
import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.player.CinetorPlayer
import com.mythbrix.cinetor.core.player.PlaybackPhase
import com.mythbrix.cinetor.core.player.PlaybackPreloader
import com.mythbrix.cinetor.core.player.PlayerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val random: Random = Random.Default,
    private val restartThresholdMs: Long = 3_000,
) {
    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()

    private val _player = MutableStateFlow<CinetorPlayer?>(null)
    val player: StateFlow<CinetorPlayer?> = _player.asStateFlow()

    private var watchJob: Job? = null

    val playback: PlayerUiState get() = _player.value?.state?.value ?: PlayerUiState()

    /** Play [tracks] from [startIndex] (album, list, search result context). */
    fun playQueue(tracks: List<Item>, startIndex: Int = 0, expand: Boolean = false) {
        val list = tracks.filter { it.type == ItemType.TRACK }
        if (list.isEmpty()) return
        val start = list.indexOfFirst { it.id == tracks.getOrNull(startIndex)?.id }.coerceAtLeast(0)
        val prev = _state.value
        val queue = MusicQueue(list, start, repeat = prev.queue?.repeat ?: RepeatMode.OFF)
        val q = if (prev.queue?.shuffle == true) queue.toggleShuffle(random) else queue
        _state.value = MusicState(q, expanded = expand || prev.expanded, generation = prev.generation + 1)
        load(q.current ?: return)
    }

    /** Music item tapped anywhere (track → its album as queue; album → whole album). */
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

    /** Audio items belong here (PlayerStateHolder hook). */
    fun handles(item: Item): Boolean {
        if (item.space != Space.MUSIC || (item.type != ItemType.TRACK && item.type != ItemType.ALBUM)) return false
        playItem(item)
        return true
    }

    private fun load(track: Item) {
        takeOver()
        watchJob?.cancel()
        val p = preloader.open(track) ?: return
        _player.value = p
        watch(p, track)
    }

    private fun watch(p: CinetorPlayer, track: Item) {
        watchJob = scope.launch {
            var endedHandled = false
            p.state.collect { st ->
                if (st.key != track.id) return@collect
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
        _state.update { it.copy(queue = next, generation = if (sameTrack) it.generation else it.generation + 1) }
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
        _state.update { it.copy(queue = prev, generation = it.generation + 1) }
        prev.current?.let(::load)
    }

    fun playAt(i: Int) {
        val q = _state.value.queue ?: return
        if (i == q.index) return
        val n = q.playAt(i)
        _state.update { it.copy(queue = n, generation = it.generation + 1) }
        n.current?.let(::load)
    }

    fun move(from: Int, to: Int) = _state.update { s -> s.copy(queue = s.queue?.move(from, to)) }
    fun removeAt(i: Int) = _state.update { s -> s.copy(queue = s.queue?.removeAt(i)) }
    fun toggleShuffle() = _state.update { s -> s.copy(queue = s.queue?.toggleShuffle(random)) }
    fun cycleRepeat() = _state.update { s -> s.copy(queue = s.queue?.cycleRepeat()) }

    fun togglePlay() {
        val p = _player.value ?: return
        if (p.state.value.playWhenReady) p.pause() else p.play()
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
        watchJob?.cancel()
        watchJob = null
        _player.value = null
        _state.update { MusicState(generation = it.generation + 1) }
        cur?.let { preloader.release(it.id) }
    }

    /** A video title is about to take the one player: drop the queue without touching the player. */
    fun yieldToVideo() {
        if (!_state.value.active) return
        watchJob?.cancel()
        watchJob = null
        _player.value = null
        _state.update { MusicState(generation = it.generation + 1) }
    }
}

/** Music library over the catalog (isolated space). */
data class Artist(val name: String, val cover: String, val albums: List<Item>, val tracks: List<Item>)

@Immutable
data class MusicData(
    val albums: List<Item>,
    val tracks: List<Item>,
    val genres: List<String>,
    val heroes: List<Item>,
) {
    private val tracksById = tracks.associateBy { it.id }
    val artists: List<Artist> by lazy {
        albums.groupBy { it.artist ?: "Unknown" }.map { (name, al) ->
            Artist(name, al.first().poster, al, tracks.filter { (it.artist ?: "Unknown") == name })
        }.sortedBy { it.name }
    }

    fun tracksOf(album: Item): List<Item> =
        album.trackIds.mapNotNull { tracksById[it] }.ifEmpty { tracks.filter { it.albumId == album.id }.sortedBy { it.trackNumber ?: 0 } }

    fun albumOf(track: Item): Item? = albums.firstOrNull { it.id == track.albumId }
}

class MusicRepository(private val catalog: CatalogRepository) {
    private val mutex = Mutex()
    private val _data = MutableStateFlow<MusicData?>(null)
    val data: StateFlow<MusicData?> = _data.asStateFlow()

    suspend fun load(): MusicData {
        _data.value?.let { return it }
        return mutex.withLock {
            _data.value ?: run {
                val all = catalog.allItems(Space.MUSIC)
                val albums = all.filter { it.type == ItemType.ALBUM }
                val heroIds = catalog.categories(Space.MUSIC).flatMap { it.heroIds }.distinct()
                MusicData(
                    albums = albums,
                    tracks = all.filter { it.type == ItemType.TRACK },
                    genres = catalog.genres(Space.MUSIC),
                    heroes = heroIds.mapNotNull { id -> albums.firstOrNull { it.id == id } }.ifEmpty { albums.take(3) },
                ).also { _data.value = it }
            }
        }
    }

    /** Track → its album's tracks (queue context) + index. */
    suspend fun contextFor(track: Item): Pair<List<Item>, Int> {
        val d = load()
        val album = d.albumOf(track) ?: return listOf(track) to 0
        val list = d.tracksOf(album)
        return list to list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
    }

    suspend fun tracksOf(album: Item): List<Item> = load().tracksOf(album)
}
