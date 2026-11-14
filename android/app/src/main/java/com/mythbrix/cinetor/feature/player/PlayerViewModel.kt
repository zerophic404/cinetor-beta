package com.mythbrix.cinetor.feature.player

import androidx.compose.runtime.Immutable
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.player.CinetorPlayer
import com.mythbrix.cinetor.core.player.DEFAULT_PROFILE
import com.mythbrix.cinetor.core.player.PlaybackPhase
import com.mythbrix.cinetor.core.player.PlaybackPreloader
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.core.player.ResumeEntry
import com.mythbrix.cinetor.core.player.ResumeStore
import com.mythbrix.cinetor.core.player.SourceResolver
import com.mythbrix.cinetor.core.player.SubPlan
import com.mythbrix.cinetor.core.player.TrackOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Player key (`itemId` or `itemId#episodeId`) belongs to [itemId]. */
fun keyBelongs(key: String, itemId: String): Boolean = key == itemId || key.startsWith("$itemId#")

/** Catalog subtitle file behind the Media3 text track [selected] (label first, then language). */
fun knownSubFor(selected: TrackOption?, subs: List<SubPlan>): SubPlan? {
    if (selected == null) return null
    return subs.firstOrNull { it.label.equals(selected.label, ignoreCase = true) }
        ?: subs.firstOrNull { selected.label.startsWith(it.label, ignoreCase = true) }
        ?: selected.language?.let { lang -> subs.firstOrNull { it.language.equals(lang, ignoreCase = true) } }
}

/** Where the player is drawn (PlayerHost above the NavHost). */
enum class HostMode { FULL, MINI, HIDDEN }

/** Video scaling (PlayerView resize mode + optional zoom). */
enum class AspectMode(val label: String) { FIT("Fit"), FILL("Fill"), ZOOM("Zoom"), STRETCH("Stretch") }

/** Sleep timer choice; [minutes] null = end of episode. */
enum class SleepOption(val label: String, val minutes: Int?) {
    OFF("Off", null), M15("15 min", 15), M30("30 min", 30), M60("60 min", 60), END("End of episode", null),
}

@Immutable
data class PlayerScreenState(
    val mode: HostMode = HostMode.HIDDEN,
    val item: Item? = null,
    /** Episode currently loaded (from the player key); null for movies. */
    val episodeId: String? = null,
    val title: String = "",
    val subtitle: String? = null,
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val locked: Boolean = false,
    val aspect: AspectMode = AspectMode.FIT,
    /** Seconds left on the "Next episode" card; null = no countdown. */
    val nextCountdown: Int? = null,
    val nextEpisode: Episode? = null,
    val sleep: SleepOption = SleepOption.OFF,
    /** Wall-clock ms when the sleep timer fires (minutes options). */
    val sleepAtMs: Long? = null,
    val inPip: Boolean = false,
    /** Catalog subtitle files of the loaded request (sync converts these to side-loaded). */
    val subs: List<SubPlan> = emptyList(),
    /** Bumped on every play() of a new title (UI resets transient state). */
    val generation: Int = 0,
) {
    val active: Boolean get() = mode != HostMode.HIDDEN && item != null
    val episodes: List<Episode> get() = item?.let { SourceResolver.episodes(it) }.orEmpty()

    fun showSkipIntro(positionMs: Long): Boolean {
        val s = introStartMs ?: return false
        val e = introEndMs ?: return false
        return e > s && positionMs >= s && positionMs < e - 500
    }
}

/**
 * App-scoped player logic (Android-free, TestScope-drivable). Lives in AppContainer, so rotation,
 * recomposition and navigation never recreate the player. Play goes through [PlaybackPreloader]
 * (takes the warmed instance; one player, latest wins).
 */
class PlayerStateHolder(
    private val scope: CoroutineScope,
    private val preloader: PlaybackPreloader,
    private val resume: ResumeStore? = null,
    private val profileId: () -> String = { DEFAULT_PROFILE },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Loads the full item (seasons/sources) when a card summary is played (hero Play). */
    private val resolveFull: suspend (Item) -> Item = { it },
    private val saveIntervalMs: Long = 5_000,
    private val countdownSec: Int = 10,
) {
    private val _state = MutableStateFlow(PlayerScreenState())
    val state: StateFlow<PlayerScreenState> = _state.asStateFlow()

    private val _player = MutableStateFlow<CinetorPlayer?>(null)

    /** The attached player (null when nothing is playing). UI collects `player.state`. */
    val player: StateFlow<CinetorPlayer?> = _player.asStateFlow()

    /** Times the holder obtained a player from the preloader for a *new* load (diagnostics/tests). */
    var opens: Int = 0
        private set

    private var watchJob: Job? = null
    private var saveJob: Job? = null
    private var countdownJob: Job? = null
    private var sleepJob: Job? = null
    private var resolveJob: Job? = null
    private var autoplayCancelledFor: String? = null

    val playback: PlayerUiState get() = _player.value?.state?.value ?: PlayerUiState()

    /** Play [item] (optionally [episodeId]) full screen. Same title already active → just expand. */
    fun play(item: Item, episodeId: String? = null) {
        val s = _state.value
        if (s.active && s.item?.id == item.id && (episodeId == null || episodeId == s.episodeId)) {
            _state.update { it.copy(mode = HostMode.FULL) }
            return
        }
        resolveJob?.cancel()
        if (needsFull(item)) {
            _state.update { it.copy(mode = HostMode.FULL, item = item, title = item.title, subtitle = null) }
            resolveJob = scope.launch {
                val full = try {
                    resolveFull(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    item
                }
                start(full, episodeId)
            }
        } else {
            start(item, episodeId)
        }
    }

    private fun needsFull(item: Item): Boolean =
        (item.type == ItemType.SERIES && item.seasons.isEmpty()) || (item.sources.isEmpty() && item.seasons.isEmpty())

    private fun start(item: Item, episodeId: String?) {
        val prev = _state.value
        if (prev.item != null && _player.value != null) {
            saveNow() // previous title keeps its position
            _player.value?.pause() // never two audios / no blip of the old title while the new one resolves
        }
        cancelJobs()
        autoplayCancelledFor = null
        val p = preloader.open(item, episodeId) ?: run {
            _state.update { it.copy(mode = HostMode.HIDDEN) }
            return
        }
        opens++
        _player.value = p
        val req = SourceResolver.request(item, episodeId)
        _state.update {
            PlayerScreenState(
                mode = HostMode.FULL,
                item = item,
                episodeId = req?.episodeId,
                title = item.title,
                subtitle = req?.subtitle,
                introStartMs = req?.introStartSec?.let { s -> s * 1000L },
                introEndMs = req?.introEndSec?.let { s -> s * 1000L },
                aspect = prev.aspect,
                inPip = prev.inPip,
                subs = req?.plan?.subtitles.orEmpty(),
                generation = prev.generation + 1,
            )
        }
        watch(p, item)
    }

    private fun cancelJobs() {
        watchJob?.cancel()
        saveJob?.cancel()
        countdownJob?.cancel()
        sleepJob?.cancel()
        watchJob = null
        saveJob = null
        countdownJob = null
        sleepJob = null
    }

    /** Follow the player: current episode (resume may pick another), END → autoplay / sleep. */
    private fun watch(p: CinetorPlayer, item: Item) {
        watchJob = scope.launch {
            var lastKey: String? = null
            var endedHandled = false
            p.state.collect { st ->
                val key = st.key ?: return@collect
                if (!keyBelongs(key, item.id)) return@collect
                if (key != lastKey) {
                    lastKey = key
                    endedHandled = false
                    val epId = key.substringAfter('#', "").ifEmpty { null }
                    val req = SourceResolver.request(item, epId)
                    _state.update {
                        it.copy(
                            episodeId = epId,
                            subtitle = req?.subtitle,
                            introStartMs = req?.introStartSec?.let { s -> s * 1000L },
                            introEndMs = req?.introEndSec?.let { s -> s * 1000L },
                            nextEpisode = SourceResolver.nextEpisode(item, epId),
                            subs = req?.plan?.subtitles.orEmpty(),
                        )
                    }
                }
                if (st.phase == PlaybackPhase.ENDED && !endedHandled) {
                    endedHandled = true
                    onEnded(key)
                } else if (st.phase != PlaybackPhase.ENDED) {
                    endedHandled = false
                }
            }
        }
        saveJob = scope.launch {
            while (isActive) {
                delay(saveIntervalMs)
                if (p.state.value.isPlaying) saveNow()
            }
        }
    }

    private fun onEnded(key: String) {
        saveNow()
        val s = _state.value
        if (s.sleep == SleepOption.END) {
            _state.update { it.copy(sleep = SleepOption.OFF, sleepAtMs = null) }
            return
        }
        val next = s.nextEpisode ?: return
        if (autoplayCancelledFor == key) return
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (left in countdownSec downTo 1) {
                _state.update { it.copy(nextCountdown = left) }
                delay(1_000)
            }
            _state.update { it.copy(nextCountdown = null) }
            playEpisode(next.id)
        }
    }

    /** Dismiss the autoplay card for the current episode. */
    fun cancelAutoplay() {
        countdownJob?.cancel()
        autoplayCancelledFor = _player.value?.state?.value?.key
        _state.update { it.copy(nextCountdown = null) }
    }

    fun playEpisode(episodeId: String) {
        val item = _state.value.item ?: return
        if (episodeId == _state.value.episodeId) return
        countdownJob?.cancel()
        _state.update { it.copy(nextCountdown = null) }
        start(item, episodeId)
    }

    fun next() {
        val next = _state.value.nextEpisode ?: return
        playEpisode(next.id)
    }

    fun togglePlay() {
        val p = _player.value ?: return
        if (p.state.value.playWhenReady) {
            p.pause()
            saveNow()
        } else {
            p.play()
        }
    }

    fun pause() {
        val p = _player.value ?: return
        p.pause()
        saveNow()
    }

    fun resumePlay() = _player.value?.play()

    fun seekBy(deltaMs: Long) = _player.value?.seekBy(deltaMs)
    fun seekTo(positionMs: Long) = _player.value?.seekTo(positionMs)
    fun setSpeed(speed: Float) = _player.value?.setSpeed(speed)
    fun selectQuality(height: Int?) = _player.value?.selectQuality(height)
    fun selectAudio(trackId: String) = _player.value?.selectAudio(trackId)
    fun selectText(trackId: String?) = _player.value?.selectText(trackId)

    fun skipIntro() {
        val end = _state.value.introEndMs ?: return
        seekTo(end)
    }

    fun setAspect(mode: AspectMode) = _state.update { it.copy(aspect = mode) }

    fun cycleAspect() = _state.update {
        val all = AspectMode.entries
        it.copy(aspect = all[(all.indexOf(it.aspect) + 1) % all.size])
    }

    fun toggleLock() = _state.update { it.copy(locked = !it.locked) }

    fun setSleep(option: SleepOption) {
        sleepJob?.cancel()
        sleepJob = null
        val minutes = option.minutes
        val at = minutes?.let { clock() + it * 60_000L }
        _state.update { it.copy(sleep = option, sleepAtMs = at) }
        if (minutes != null) {
            sleepJob = scope.launch {
                delay(minutes * 60_000L)
                pause()
                _state.update { it.copy(sleep = SleepOption.OFF, sleepAtMs = null) }
            }
        }
    }

    fun minimize() {
        if (!_state.value.active) return
        _state.update { it.copy(mode = HostMode.MINI, locked = false) }
    }

    fun expand() {
        if (_state.value.item == null) return
        _state.update { it.copy(mode = HostMode.FULL) }
    }

    fun setPip(inPip: Boolean) = _state.update { it.copy(inPip = inPip) }

    /** App went to background / screen off: persist position now. */
    fun onBackground() {
        if (_player.value != null && _state.value.item != null) saveNow()
    }

    /** Stop playback and hide the player. */
    fun close() {
        val s = _state.value
        val item = s.item ?: run {
            _state.value = PlayerScreenState()
            return
        }
        saveNow()
        cancelJobs()
        resolveJob?.cancel()
        preloader.release(item.id)
        _player.value = null
        _state.value = PlayerScreenState(aspect = s.aspect, generation = s.generation)
    }

    /** Persist the current position (no-op without a duration or before 1 s). */
    fun saveNow() {
        val store = resume ?: return
        val item = _state.value.item ?: return
        val st = _player.value?.state?.value ?: return
        val key = st.key ?: return
        if (!keyBelongs(key, item.id)) return
        if (st.durationMs <= 0 || st.positionMs < 1_000) return
        val epId = key.substringAfter('#', "").ifEmpty { null }
        val epLabel = epId?.let { id ->
            item.seasons.firstNotNullOfOrNull { season ->
                season.episodes.firstOrNull { it.id == id }?.let { "S${season.n}:E${it.n}" }
            }
        }
        val entry = ResumeEntry(
            profileId = profileId(),
            itemId = item.id,
            episodeId = epId,
            positionMs = st.positionMs,
            durationMs = st.durationMs,
            updatedAt = clock(),
            space = item.space,
            title = item.title,
            backdrop = item.backdrop,
            poster = item.poster,
            dominantColor = item.dominantColor,
            accentColor = item.accentColor,
            episodeLabel = epLabel,
        )
        scope.launch { runCatching { store.save(entry) } }
    }
}
