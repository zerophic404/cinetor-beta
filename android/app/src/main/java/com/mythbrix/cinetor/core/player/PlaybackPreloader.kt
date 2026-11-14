package com.mythbrix.cinetor.core.player

import com.mythbrix.cinetor.core.data.Item
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Readiness { RESOLVING, BUFFERING, READY, ERROR }

/**
 * Instant-play warm-up (Task 3.2). Details calls [warm] on open and [cancel] on leave; Play calls
 * [take] (same prepared player → play instantly). Latest warm wins; only ONE player exists.
 * A taken item belongs to the player screen: [cancel] ignores it, [release] frees it.
 */
interface PlaybackPreloader {
    fun warm(item: Item, episodeId: String? = null)
    fun cancel(itemId: String)
    val readiness: StateFlow<Map<String, Readiness>>

    /** Hands over the warmed player for [itemId] (and episode, when given); null if not warmed. */
    fun take(itemId: String, episodeId: String? = null): CinetorPlayer? = null

    /** Player screen: reuse the warmed/taken player or load [item] now; playWhenReady when loaded. */
    fun open(item: Item, episodeId: String? = null): CinetorPlayer? {
        warm(item, episodeId)
        return take(item.id, episodeId)
    }

    /** Player screen closed for [itemId]: stop + free (no-op if someone warmed another item since). */
    fun release(itemId: String) = cancel(itemId)
}

/** Does nothing; readiness stays empty. */
object NoopPlaybackPreloader : PlaybackPreloader {
    private val empty = MutableStateFlow<Map<String, Readiness>>(emptyMap())
    override fun warm(item: Item, episodeId: String?) = Unit
    override fun cancel(itemId: String) = Unit
    override val readiness: StateFlow<Map<String, Readiness>> = empty.asStateFlow()
}

/**
 * Real preloader over the shared [CinetorPlayer]: warm resolves the source (+ resume position),
 * then `load(playWhenReady=false)` buffers the first segments while the user reads Details.
 * Readiness follows the player's state for the warmed key. Data saver: resolve only (load on take).
 *
 * Two slots: [owned] = session taken by the player screen (full / mini / PiP), and the latest warm
 * candidate. While something is owned, warming another title only resolves it (never steals the
 * one player → no audio cut); Play on it ([take]/[open]) replaces the owned item (single audio).
 */
class MediaPlaybackPreloader(
    private val scope: CoroutineScope,
    private val playerProvider: () -> CinetorPlayer,
    private val resume: ResumeStore? = null,
    private val profileId: () -> String = { DEFAULT_PROFILE },
    private val dataSaver: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : PlaybackPreloader {
    private val _readiness = MutableStateFlow<Map<String, Readiness>>(emptyMap())
    override val readiness: StateFlow<Map<String, Readiness>> = _readiness.asStateFlow()

    private val _tapToFirstFrame = MutableStateFlow<Long?>(null)

    /** Last measured Play tap → first rendered frame (ms). */
    val tapToFirstFrameMs: StateFlow<Long?> = _tapToFirstFrame.asStateFlow()

    private class Session(val itemId: String, val requestedEpisode: String?) {
        var taken = false
        var request: PlaybackRequest? = null
        var startMs: Long = 0
        var loaded = false
        var job: Job? = null
        var watch: Job? = null
        var tapAt: Long? = null
    }

    /** Latest warm candidate (may be the owned one). */
    private var session: Session? = null

    /** Taken by the player screen. */
    private var owned: Session? = null
    private var player: CinetorPlayer? = null
    private var frameJob: Job? = null

    /** Item currently warmed or owned, for tests/diagnostics. */
    val currentItemId: String? get() = (session ?: owned)?.itemId
    val currentKey: String? get() = (session ?: owned)?.request?.key
    val ownedItemId: String? get() = owned?.itemId

    private fun player(): CinetorPlayer {
        player?.let { return it }
        val p = playerProvider()
        player = p
        frameJob = scope.launch {
            p.firstFrames.collect { key ->
                val s = owned ?: session ?: return@collect
                val tap = s.tapAt ?: return@collect
                if (s.request?.key != key) return@collect
                s.tapAt = null
                val ms = clock() - tap
                _tapToFirstFrame.value = ms
                log("tap→firstFrame ${ms}ms ($key)")
            }
        }
        return p
    }

    private fun Session.matches(itemId: String, episodeId: String?): Boolean =
        this.itemId == itemId && (episodeId == null || episodeId == requestedEpisode || episodeId == request?.episodeId)

    @Synchronized
    override fun warm(item: Item, episodeId: String?) {
        val o = owned
        if (o != null && o.matches(item.id, episodeId)) {
            // Already playing it (e.g. Details back on screen while mini/PiP plays): keep going.
            session?.let { c -> if (c !== o) dropCandidate(c) }
            session = o
            return
        }
        val s = session
        if (s != null && s !== o && s.matches(item.id, episodeId)) return
        start(item, episodeId, taken = false)
    }

    @Synchronized
    override fun take(itemId: String, episodeId: String?): CinetorPlayer? {
        val o = owned
        if (o != null && o.matches(itemId, episodeId)) return player()
        val s = session ?: return null
        if (!s.matches(itemId, episodeId)) return null
        promote(s)
        s.tapAt = clock()
        if (s.loaded) player().play() else if (s.request != null) load(s)
        return player()
    }

    @Synchronized
    override fun open(item: Item, episodeId: String?): CinetorPlayer {
        val o = owned
        if (o != null && o.matches(item.id, episodeId)) return player() // re-attach (rotation): no reload
        val s = session
        if (s != null && s !== o && s.matches(item.id, episodeId)) return take(item.id, episodeId) ?: player()
        start(item, episodeId, taken = true).tapAt = clock()
        return player()
    }

    @Synchronized
    override fun cancel(itemId: String) {
        val s = session
        if (s != null && s.itemId == itemId && !s.taken) {
            dropCandidate(s)
            session = owned
            return
        }
        if (owned?.itemId == itemId) return // the player screen owns it; release() frees it
        _readiness.update { it - itemId }
    }

    @Synchronized
    override fun release(itemId: String) {
        val o = owned ?: return
        if (o.itemId != itemId) return
        o.job?.cancel()
        o.watch?.cancel()
        owned = null
        if (session === o) session = null
        player?.stop()
        _readiness.update { it - itemId }
        // A title warmed while this one played can buffer now.
        session?.let { c -> if (!c.loaded && c.request != null && !dataSaver()) load(c) }
    }

    private fun dropCandidate(c: Session) {
        c.job?.cancel()
        c.watch?.cancel()
        if (c.loaded) player?.stop()
        if (owned?.itemId != c.itemId) _readiness.update { it - c.itemId }
    }

    /** [s] becomes the owned session; a previously owned different session is dropped. */
    private fun promote(s: Session) {
        val o = owned
        if (o != null && o !== s) {
            o.job?.cancel()
            o.watch?.cancel()
            if (o.itemId != s.itemId) _readiness.update { it - o.itemId }
        }
        s.taken = true
        owned = s
        session = s
    }

    private fun start(item: Item, episodeId: String?, taken: Boolean): Session {
        session?.let { prev ->
            if (prev !== owned) {
                prev.job?.cancel()
                prev.watch?.cancel()
                if (prev.itemId != item.id && owned?.itemId != prev.itemId) _readiness.update { it - prev.itemId }
            }
        }
        val s = Session(item.id, episodeId)
        session = s
        if (taken) promote(s)
        set(item.id, Readiness.RESOLVING)
        s.job = scope.launch {
            val entry = lookup(item.id, episodeId)
            val req = SourceResolver.request(item, episodeId ?: entry?.episodeId)
            if (req == null) {
                set(item.id, Readiness.ERROR)
                return@launch
            }
            s.request = req
            s.startMs = if (entry != null && entry.episodeId == req.episodeId) startPositionFor(entry) else 0
            if (session !== s && owned !== s) return@launch
            // Resolve only: data saver, or the player is busy with an owned title (take() loads).
            if (!s.taken && (dataSaver() || owned != null)) return@launch
            load(s)
        }
        return s
    }

    private suspend fun lookup(itemId: String, episodeId: String?): ResumeEntry? {
        val store = resume ?: return null
        return try {
            if (episodeId != null) store.get(profileId(), itemId, episodeId) else store.latest(profileId(), itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun load(s: Session) {
        val req = s.request ?: return
        if (s.loaded) return
        s.loaded = true
        val p = player()
        p.load(req, s.startMs, playWhenReady = s.taken)
        set(s.itemId, Readiness.BUFFERING)
        s.watch?.cancel()
        s.watch = scope.launch {
            p.state.collect { st ->
                if ((session !== s && owned !== s) || st.key != req.key) return@collect
                val r = when {
                    st.error != null -> Readiness.ERROR
                    st.phase == PlaybackPhase.READY || st.phase == PlaybackPhase.ENDED -> Readiness.READY
                    else -> Readiness.BUFFERING
                }
                set(s.itemId, r)
            }
        }
    }

    private fun set(id: String, r: Readiness) = _readiness.update { it + (id to r) }
}

/**
 * Timer-driven stand-in (RESOLVING → BUFFERING → READY), no real player. Kept for UI tests /
 * previews; production uses [MediaPlaybackPreloader].
 */
class SimulatedPlaybackPreloader(
    private val scope: CoroutineScope,
    private val resolveMs: Long = 350,
    private val bufferMs: Long = 550,
) : PlaybackPreloader {
    private val _readiness = MutableStateFlow<Map<String, Readiness>>(emptyMap())
    override val readiness: StateFlow<Map<String, Readiness>> = _readiness.asStateFlow()

    private var current: String? = null
    private var job: Job? = null

    @Synchronized
    override fun warm(item: Item, episodeId: String?) {
        if (current == item.id && job?.isActive == true) return
        current?.let { if (it != item.id) cancel(it) }
        current = item.id
        job?.cancel()
        set(item.id, Readiness.RESOLVING)
        job = scope.launch {
            delay(resolveMs)
            set(item.id, Readiness.BUFFERING)
            delay(bufferMs)
            set(item.id, Readiness.READY)
        }
    }

    @Synchronized
    override fun cancel(itemId: String) {
        if (current == itemId) {
            job?.cancel()
            job = null
            current = null
        }
        _readiness.update { it - itemId }
    }

    private fun set(id: String, r: Readiness) = _readiness.update { it + (id to r) }
}
