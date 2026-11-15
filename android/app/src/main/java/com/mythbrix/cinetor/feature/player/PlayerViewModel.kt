package com.mythbrix.cinetor.feature.player

import androidx.compose.runtime.Immutable
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Source
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
    /** Live mode (channel / live event): LIVE badge, go-to-live, zapping; no resume. */
    val live: Boolean = false,
    /** Zapping lineup (channels in guide order) when [live]. */
    val channels: List<Item> = emptyList(),
    /** Digits typed on a TV remote (OSD) before jumping to that channel number. */
    val channelInput: String? = null,
    /** User-facing notice instead of a stream (e.g. "No source available"). */
    val notice: String? = null,
    /** Provider sources of the loaded title/episode (gateway resolutions) for the quality menu. */
    val sources: List<Source> = emptyList(),
    val sourceIndex: Int = 0,
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
    /** Zapping lineup for a live item played without an explicit one (Details / search). */
    private val lineupFor: (Item) -> List<Item> = { emptyList() },
    /** Audio items (tracks/albums) go to the music player instead; true = handled. */
    private val audioHandler: (Item) -> Boolean = { false },
    /** Called before a video title takes the one player (music yields → never two audios). */
    private val beforePlay: () -> Unit = {},
    private val channelEntryMs: Long = 1_500,
    /** Settings → Playback → Autoplay next episode. */
    private val autoplayNext: () -> Boolean = { true },
    /** Items whose sources are resolved at play time (gateway / TMDB-origin). */
    private val needsPrepare: (Item) -> Boolean = { false },
    /** Attach real sources (fresh = bypass the stream cache, used after a playback error). */
    private val prepare: suspend (Item, String?, Boolean) -> Item = { item, _, _ -> item },
    /** Last resort after a failed re-resolve: the gateway proxy source. */
    private val fallbackFor: (Item, String?) -> Item? = { _, _ -> null },
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
    private var lineupOverride: List<Item>? = null
    private var digitJob: Job? = null
    private var recoverJob: Job? = null
    private var recoverAttempts = 0

    /** Error recoveries done for the current load (diagnostics/tests). */
    var recoveries: Int = 0
        private set

    val playback: PlayerUiState get() = _player.value?.state?.value ?: PlayerUiState()

    /** Play [item] (optionally [episodeId]) full screen. Same title already active → just expand. */
    fun play(item: Item, episodeId: String? = null) {
        if (audioHandler(item)) return
        val s = _state.value
        if (s.active && s.item?.id == item.id && (episodeId == null || episodeId == s.episodeId)) {
            _state.update { it.copy(mode = HostMode.FULL) }
            return
        }
        resolveJob?.cancel()
        beforePlay()
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
        if (!needsPrepare(item)) return startNow(item, episodeId)
        val prev = _state.value
        if (prev.item != null && _player.value != null) {
            saveNow()
            _player.value?.pause()
        }
        _state.update { it.copy(mode = HostMode.FULL, item = item, title = item.title, notice = null) }
        resolveJob = scope.launch {
            val prepared = try {
                prepare(item, episodeId, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                item
            }
            startNow(prepared, episodeId)
        }
    }

    private fun sourcesOf(item: Item, episodeId: String?): List<Source> {
        val ep = SourceResolver.episodes(item).let { eps -> eps.firstOrNull { it.id == episodeId } ?: if (episodeId == null) eps.firstOrNull() else null }
        val list = ep?.sources?.ifEmpty { null } ?: item.sources
        return if (list.any { it.height != null }) list else emptyList()
    }

    private fun startNow(item: Item, episodeId: String?) {
        val prev = _state.value
        if (prev.item != null && _player.value != null) {
            saveNow() // previous title keeps its position
            _player.value?.pause() // never two audios / no blip of the old title while the new one resolves
        }
        cancelJobs()
        autoplayCancelledFor = null
        recoverAttempts = 0
        if (needsPrepare(item) && SourceResolver.request(item, episodeId) == null) {
            // Gateway / resolver found nothing playable: tell the user instead of spinning forever.
            _player.value?.let { preloader.release(prev.item?.id ?: item.id) }
            _player.value = null
            _state.value = PlayerScreenState(
                mode = HostMode.FULL, item = item, title = item.title, aspect = prev.aspect,
                generation = prev.generation + 1, notice = "No source available",
            )
            return
        }
        val p = preloader.open(item, episodeId) ?: run {
            _state.update { it.copy(mode = HostMode.HIDDEN) }
            return
        }
        opens++
        _player.value = p
        val req = SourceResolver.request(item, episodeId)
        val live = isLiveItem(item)
        val lineup = if (live) (lineupOverride ?: lineupFor(item)).ifEmpty { listOf(item) } else emptyList()
        lineupOverride = null
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
                live = live,
                channels = lineup,
                sources = sourcesOf(item, req?.episodeId),
            )
        }
        watch(p, item)
    }

    private fun cancelJobs() {
        watchJob?.cancel()
        saveJob?.cancel()
        countdownJob?.cancel()
        sleepJob?.cancel()
        recoverJob?.cancel()
        recoverJob = null
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
                    recoverAttempts = 0
                    val epId = key.substringAfter('#', "").ifEmpty { null }
                    val current = _state.value.item?.takeIf { it.id == item.id } ?: item
                    val req = SourceResolver.request(current, epId)
                    _state.update {
                        it.copy(
                            episodeId = epId,
                            subtitle = req?.subtitle,
                            introStartMs = req?.introStartSec?.let { s -> s * 1000L },
                            introEndMs = req?.introEndSec?.let { s -> s * 1000L },
                            nextEpisode = SourceResolver.nextEpisode(current, epId),
                            subs = req?.plan?.subtitles.orEmpty(),
                            sources = sourcesOf(current, epId),
                            sourceIndex = 0,
                        )
                    }
                    // Resume picked another episode than the one prepared: fetch its sources/subs for the menus.
                    if (needsPrepare(current) && epId != null && sourcesOf(current, epId).isEmpty()) {
                        scope.launch {
                            val fresh = runCatching { prepare(current, epId, false) }.getOrNull() ?: return@launch
                            val r = SourceResolver.request(fresh, epId)
                            _state.update { s ->
                                if (s.item?.id != fresh.id || s.episodeId != epId) s
                                else s.copy(item = fresh, subs = r?.plan?.subtitles.orEmpty(), sources = sourcesOf(fresh, epId))
                            }
                        }
                    }
                }
                if (st.phase == PlaybackPhase.READY) recoverAttempts = 0
                if (st.error != null && recoverJob?.isActive != true && recoverAttempts < 2 && needsPrepare(item)) {
                    recover(p, key, st.positionMs)
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
        if (autoplayCancelledFor == key || !autoplayNext()) return
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

    /** Live channel / event with its zapping [lineup] (guide order). */
    fun playChannel(item: Item, lineup: List<Item>) {
        lineupOverride = lineup
        play(item)
        lineupOverride = null
    }

    /** Channel up (+1) / down (−1): same player instance, next stream replaces the current one. */
    fun zap(delta: Int) {
        val s = _state.value
        if (!s.live) return
        val target = zapTarget(s.channels, s.item?.id, delta) ?: return
        switchChannel(target, s.channels)
    }

    /** Jump to channel [number] (number keys / OSD). False when no such channel. */
    fun zapToNumber(number: Int): Boolean {
        val s = _state.value
        val target = s.channels.firstOrNull { it.channelNumber == number } ?: return false
        if (target.id != s.item?.id) switchChannel(target, s.channels)
        return true
    }

    /** TV remote digit: accumulates for [channelEntryMs], then jumps. */
    fun channelDigit(digit: Int) {
        if (!_state.value.live) return
        val buffer = appendChannelDigit(_state.value.channelInput, digit)
        _state.update { it.copy(channelInput = buffer) }
        digitJob?.cancel()
        digitJob = scope.launch {
            delay(channelEntryMs)
            _state.update { it.copy(channelInput = null) }
            buffer.toIntOrNull()?.let { zapToNumber(it) }
        }
    }

    private fun switchChannel(target: Item, lineup: List<Item>) {
        lineupOverride = lineup
        start(target, null)
        lineupOverride = null
    }

    /** Live: jump back to the live edge. */
    fun goLive() = _player.value?.goLive()

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

    /** Quality menu: switch to provider source [index] (gateway resolution), keeping the position. */
    fun selectSource(index: Int) {
        val s = _state.value
        val item = s.item ?: return
        val src = s.sources.getOrNull(index) ?: return
        val p = _player.value ?: return
        val req = SourceResolver.request(item, s.episodeId) ?: return
        val plan = SourceResolver.plan(src, emptyList()).copy(subtitles = req.plan.subtitles)
        val pos = p.state.value.positionMs
        p.load(req.copy(plan = plan), pos, playWhenReady = true)
        _state.update { it.copy(sourceIndex = index) }
    }

    /**
     * Playback error (expired link / 403 …) on a resolved title: 1st → re-resolve fresh and reload at the
     * same position (same resolution when offered), 2nd → gateway proxy (`direct_play_url`), then give up.
     */
    private fun recover(p: CinetorPlayer, key: String, positionMs: Long) {
        val s = _state.value
        val item = s.item ?: return
        val epId = key.substringAfter('#', "").ifEmpty { null }
        val wantHeight = s.sources.getOrNull(s.sourceIndex)?.height
        recoverAttempts++
        recoveries++
        val attempt = recoverAttempts
        recoverJob = scope.launch {
            val next = if (attempt == 1) {
                runCatching { prepare(item, epId, true) }.getOrNull()
            } else {
                fallbackFor(item, epId)
            } ?: if (attempt == 1) fallbackFor(item, epId) else null
            next ?: return@launch
            val req = SourceResolver.request(next, epId) ?: return@launch
            val sources = sourcesOf(next, epId)
            val idx = sources.indexOfFirst { wantHeight != null && it.height == wantHeight }.coerceAtLeast(0)
            val chosen = sources.getOrNull(idx)
            val plan = chosen?.let { SourceResolver.plan(it, emptyList()).copy(subtitles = req.plan.subtitles) } ?: req.plan
            if (_player.value !== p || _state.value.item?.id != item.id) return@launch
            p.load(req.copy(plan = plan), positionMs, playWhenReady = true)
            _state.update { it.copy(item = next, sources = sources, sourceIndex = idx) }
        }
    }
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
        if (_state.value.live) return // live TV never feeds resume / continue watching
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
