package com.mythbrix.cinetor.feature.player

import androidx.compose.runtime.Immutable
import com.mythbrix.cinetor.core.data.DubOption
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
import com.mythbrix.cinetor.feature.player.subs.fullSubtitleLanguageName
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

private val NON_AUDIO_DUB_TOKEN = Regex(
    """(?i)^(?:\d{3,4}p(?:\s*(?:hd|fhd|qhd|uhd))?|4k(?:\s*uhd)?|2k(?:\s*qhd)?|auto|hd|sd|fhd|qhd|uhd|high[ -]?speed|bdix|mp4|mkv|m4v|avi|webm|mov|ts|hls|dash|h\.?264|h\.?265|x264|x265|hevc|av1|aac|flac|mp3|youtube|direct|proxy|gateway(?:\s*proxy)?|server\s*\d*|source\s*\d*|[\d.]+\s*[kmgt]?b)$""",
)

/** Clean human audio/dub language name for a [Source] (`"Hindi"`, `"English"`, `"Bangla"`, `"Original"`, etc.). */
fun audioDubOf(source: Source): String? {
    source.audioLang?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        return if (raw.equals("Dual Audio", ignoreCase = true)) "Dual Audio" else fullSubtitleLanguageName(raw)
    }
    val label = source.label?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = label.split("·", "|", "/").map { it.trim().trim('[', ']', '(', ')').trim() }
    for (part in parts) {
        if (part.isEmpty() || NON_AUDIO_DUB_TOKEN.matches(part)) continue
        if (part.equals("Dual Audio", ignoreCase = true) || part.equals("Multi Audio", ignoreCase = true)) return "Dual Audio"
        return fullSubtitleLanguageName(part)
    }
    return dubOfSource(source)?.takeIf { !NON_AUDIO_DUB_TOKEN.matches(it) }?.let(::fullSubtitleLanguageName)
}

/** Resolves `(season, episode)` numbers for a loaded item/episode (`(0, 0)` for movies). */
fun seasonEpisodeNumbersOf(item: Item?, episodeId: String?): Pair<Int, Int> {
    if (item == null) return 0 to 0
    if (episodeId != null) {
        Regex("""(?:^|[:_-])s0*(\d+)[:_-]?e0*(\d+)$""", RegexOption.IGNORE_CASE).find(episodeId)?.let { m ->
            val se = m.groupValues[1].toIntOrNull()
            val ep = m.groupValues[2].toIntOrNull()
            if (se != null && ep != null) return se to ep
        }
        for (season in item.seasons) {
            val hit = season.episodes.firstOrNull { it.id == episodeId }
            if (hit != null) return season.n to hit.n
        }
    }
    if (item.seasons.isNotEmpty()) {
        val firstSeason = item.seasons.first()
        val firstEp = firstSeason.episodes.firstOrNull()
        return firstSeason.n to (firstEp?.n ?: 1)
    }
    return if (item.type == ItemType.SERIES) 1 to 1 else 0 to 0
}

private fun resolveEpisodeId(item: Item, season: Int?, episode: Int?): String? {
    if (season == null && episode == null) return null
    val seasons = item.seasons
    if (seasons.isNotEmpty()) {
        val targetSeason = if (season != null) seasons.firstOrNull { it.n == season } ?: seasons.firstOrNull() else seasons.firstOrNull()
        if (targetSeason != null) {
            val targetEp = if (episode != null) {
                targetSeason.episodes.firstOrNull { it.n == episode } ?: targetSeason.episodes.firstOrNull()
            } else {
                targetSeason.episodes.firstOrNull()
            }
            if (targetEp != null) return targetEp.id
        }
        if (season != null && episode != null) {
            val suffix = Regex("""(?:^|[:_-])s0*${season}[:_-]?e0*${episode}$""", RegexOption.IGNORE_CASE)
            SourceResolver.episodes(item).firstOrNull { suffix.containsMatchIn(it.id) }?.let { return it.id }
        }
    }
    return null
}

/** Where the player is drawn (`INLINE` = embedded 16:9 slot in `DetailsScreen`; `FULL`/`MINI` = `PlayerHost`). */
enum class HostMode { HIDDEN, INLINE, FULL, MINI }

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
    /** Provider sources of the loaded title/episode (gateway resolutions) for the quality/audio menus. */
    val sources: List<Source> = emptyList(),
    val sourceIndex: Int = 0,
    /** True while the session is anchored to an open inline `DetailsScreen` (so Fullscreen -> Back returns to INLINE). */
    val openedInline: Boolean = false,
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
    /** Mini-player size + free-drag position per profile (docs/requirements-hardening.md §3). */
    private val miniSizes: MiniSizeStore = InMemoryMiniSizeStore(),
    /** Per-series/profile dub & quality choice persistence across episodes. */
    private val playbackPrefs: PlaybackPreferenceStore = (miniSizes as? PlaybackPreferenceStore) ?: InMemoryPlaybackPreferenceStore(),
) {
    private val _miniFraction = MutableStateFlow(
        MiniPlayerMath.clampFraction(miniSizes.get(profileId())),
    )

    /** Mini-player width as a fraction of screen width for the active profile (0.25–0.80). */
    val miniFraction: StateFlow<Float> = _miniFraction.asStateFlow()

    private val _miniPosition = MutableStateFlow(miniSizes.getPosition(profileId()))

    /** Normalized free-drag mini-player position `(xFrac, yFrac)` for the active profile, or `null` when unset. */
    val miniPosition: StateFlow<Pair<Float, Float>?> = _miniPosition.asStateFlow()

    /** Shared chromeless YouTube WebView holder across `INLINE <-> FULL <-> MINI <-> PiP`. */
    internal var youtubeWebViewHolder: YouTubeWebViewHolder? = null

    /** Persists the mini-player size for the active profile (clamped, 16:9 is kept by the UI). */
    fun setMiniFraction(fraction: Float) {
        val f = MiniPlayerMath.clampFraction(fraction)
        if (f == _miniFraction.value) return
        _miniFraction.value = f
        miniSizes.set(profileId(), f)
    }

    /** Persists the user's chosen free-drag `(xFrac, yFrac)` position for the active profile. */
    fun setMiniPosition(xFrac: Float, yFrac: Float) {
        val clamped = xFrac.coerceIn(0f, 1f) to yFrac.coerceIn(0f, 1f)
        if (clamped == _miniPosition.value) return
        _miniPosition.value = clamped
        miniSizes.setPosition(profileId(), clamped.first, clamped.second)
    }

    /** Re-reads the stored size and free-drag position (call after a profile switch). */
    fun reloadMiniFraction() {
        val prof = profileId()
        _miniFraction.value = MiniPlayerMath.clampFraction(miniSizes.get(prof))
        _miniPosition.value = miniSizes.getPosition(prof)
    }

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
    private var dubJob: Job? = null
    private var autoplayCancelledFor: String? = null
    private var lineupOverride: List<Item>? = null
    private var digitJob: Job? = null
    private var recoverJob: Job? = null
    private var recoverAttempts = 0

    /** Error recoveries done for the current load (diagnostics/tests). */
    var recoveries: Int = 0
        private set

    val playback: PlayerUiState get() = _player.value?.state?.value ?: PlayerUiState()

    /**
     * Open [item] at optional `(season, episode)` either inline inside `DetailsScreen` (`inline = true` →
     * `HostMode.INLINE`) or fullscreen (`inline = false` → `HostMode.FULL`).
     */
    fun open(item: Item, season: Int? = null, episode: Int? = null, inline: Boolean = false) {
        val targetMode = if (inline) HostMode.INLINE else HostMode.FULL
        val epId = resolveEpisodeId(item, season, episode)
        launchPlay(item, epId, targetMode, openedInline = inline, pendingSeason = season, pendingEpisode = episode)
    }

    /** Play [item] (optionally [episodeId]) full screen. Same title already active → just expand. */
    fun play(item: Item, episodeId: String? = null) {
        launchPlay(item, episodeId, HostMode.FULL, openedInline = false)
    }

    private fun launchPlay(
        item: Item,
        episodeId: String?,
        targetMode: HostMode,
        openedInline: Boolean,
        pendingSeason: Int? = null,
        pendingEpisode: Int? = null,
    ) {
        if (audioHandler(item)) return
        val s = _state.value
        if (s.active && s.item?.id == item.id && (episodeId == null || episodeId == s.episodeId)) {
            _state.update {
                it.copy(
                    mode = targetMode,
                    openedInline = if (openedInline) true else it.openedInline,
                    locked = false,
                )
            }
            return
        }
        resolveJob?.cancel()
        dubJob?.cancel()
        beforePlay()
        if (needsFull(item)) {
            _state.update {
                it.copy(
                    mode = targetMode,
                    item = item,
                    title = item.title,
                    subtitle = null,
                    openedInline = openedInline,
                    notice = null,
                )
            }
            resolveJob = scope.launch {
                val full = try {
                    resolveFull(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    item
                }
                val finalEpId = episodeId ?: resolveEpisodeId(full, pendingSeason, pendingEpisode)
                start(full, finalEpId, targetMode, openedInline)
            }
        } else {
            start(item, episodeId, targetMode, openedInline)
        }
    }

    private fun needsFull(item: Item): Boolean =
        (item.type == ItemType.SERIES && item.seasons.isEmpty()) || (item.sources.isEmpty() && item.seasons.isEmpty())

    private fun start(item: Item, episodeId: String?, targetMode: HostMode = HostMode.FULL, openedInline: Boolean = _state.value.openedInline) {
        if (!needsPrepare(item)) return startNow(item, episodeId, targetMode, openedInline)
        val prev = _state.value
        if (prev.item != null && _player.value != null) {
            saveNow()
            _player.value?.pause()
        }
        _state.update {
            it.copy(mode = targetMode, item = item, title = item.title, notice = null, openedInline = openedInline)
        }
        resolveJob = scope.launch {
            val prepared = try {
                prepare(item, episodeId, false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                item
            }
            startNow(prepared, episodeId, targetMode, openedInline)
        }
    }

    private fun sourcesOf(item: Item, episodeId: String?): List<Source> {
        val ep = SourceResolver.episodes(item).let { eps -> eps.firstOrNull { it.id == episodeId } ?: if (episodeId == null) eps.firstOrNull() else null }
        val list = ep?.sources?.ifEmpty { null } ?: item.sources
        return if (
            list.any { it.height != null || !it.audioLang.isNullOrBlank() || isYouTubeUrl(it.url) } ||
            (list.size > 1 && list.any { it.label != null })
        ) list else emptyList()
    }

    private fun preferredIndexFor(item: Item, sources: List<Source>): Int {
        if (isLiveItem(item) || sources.size < 2) return 0
        val prof = profileId()
        val titlePref = playbackPrefs.getTitlePref(prof, item.id)
        val globalPref = playbackPrefs.getGlobalPref(prof)
        val targetDub = titlePref?.dub?.takeIf { it.isNotBlank() } ?: globalPref?.dub?.takeIf { it.isNotBlank() }
        if (targetDub != null) {
            val normTarget = fullSubtitleLanguageName(targetDub)
            val targetH = titlePref?.height ?: globalPref?.height
            val dubIndices = sources.indices.filter { i ->
                val d = audioDubOf(sources[i])
                d != null && (d.equals(normTarget, ignoreCase = true) || d.equals(targetDub, ignoreCase = true))
            }
            if (dubIndices.isNotEmpty()) {
                if (targetH != null) {
                    dubIndices.firstOrNull { sources[it].height == targetH }?.let { return it }
                    dubIndices.filter { (sources[it].height ?: 0) > 0 }
                        .minByOrNull { kotlin.math.abs((sources[it].height ?: 0) - targetH) }
                        ?.let { return it }
                }
                return dubIndices.first()
            }
        }
        return preferredSourceIndex(
            sources = sources,
            titlePref = titlePref,
            globalPref = globalPref,
        )
    }

    private fun startNow(
        item: Item,
        episodeId: String?,
        targetMode: HostMode = HostMode.FULL,
        openedInline: Boolean = _state.value.openedInline,
    ) {
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
                mode = targetMode,
                item = item,
                title = item.title,
                aspect = prev.aspect,
                generation = prev.generation + 1,
                notice = "No source available",
                openedInline = openedInline,
            )
            return
        }
        val p = preloader.open(item, episodeId) ?: run {
            _state.update { it.copy(mode = HostMode.HIDDEN, openedInline = false) }
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
                mode = targetMode,
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
                openedInline = openedInline,
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
        dubJob?.cancel()
        dubJob = null
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
            val failedUrls = mutableSetOf<String>()
            p.state.collect { st ->
                val key = st.key ?: return@collect
                if (!keyBelongs(key, item.id)) return@collect
                if (key != lastKey) {
                    lastKey = key
                    endedHandled = false
                    recoverAttempts = 0
                    failedUrls.clear()
                    val epId = key.substringAfter('#', "").ifEmpty { null }
                    val current = _state.value.item?.takeIf { it.id == item.id } ?: item
                    val req = SourceResolver.request(current, epId)
                    val sources = sourcesOf(current, epId)
                    val prefIdx = preferredIndexFor(current, sources)
                    _state.update {
                        it.copy(
                            episodeId = epId,
                            subtitle = req?.subtitle,
                            introStartMs = req?.introStartSec?.let { s -> s * 1000L },
                            introEndMs = req?.introEndSec?.let { s -> s * 1000L },
                            nextEpisode = SourceResolver.nextEpisode(current, epId),
                            subs = req?.plan?.subtitles.orEmpty(),
                            sources = sources,
                            sourceIndex = prefIdx,
                        )
                    }
                    if (prefIdx > 0 && req != null) {
                        sources.getOrNull(prefIdx)?.let { chosen ->
                            if (chosen.url != req.plan.url) {
                                val plan = SourceResolver.plan(chosen, emptyList()).copy(subtitles = req.plan.subtitles)
                                p.load(req.copy(plan = plan), st.positionMs, playWhenReady = st.playWhenReady)
                            }
                        }
                    }
                    // Resume picked another episode than the one prepared: fetch its sources/subs for the menus.
                    if (needsPrepare(current) && epId != null && sources.isEmpty()) {
                        scope.launch {
                            val fresh = runCatching { prepare(current, epId, false) }.getOrNull() ?: return@launch
                            val r = SourceResolver.request(fresh, epId)
                            val freshSources = sourcesOf(fresh, epId)
                            val freshIdx = preferredIndexFor(fresh, freshSources)
                            _state.update { s ->
                                if (s.item?.id != fresh.id || s.episodeId != epId) s
                                else s.copy(item = fresh, subs = r?.plan?.subtitles.orEmpty(), sources = freshSources, sourceIndex = freshIdx)
                            }
                            if (freshIdx > 0 && r != null && _player.value === p && _state.value.item?.id == fresh.id && _state.value.episodeId == epId) {
                                freshSources.getOrNull(freshIdx)?.let { chosen ->
                                    val plan = SourceResolver.plan(chosen, emptyList()).copy(subtitles = r.plan.subtitles)
                                    val curSt = p.state.value
                                    p.load(r.copy(plan = plan), curSt.positionMs, playWhenReady = curSt.playWhenReady)
                                }
                            }
                        }
                    }
                }
                if (st.phase == PlaybackPhase.READY) recoverAttempts = 0
                if (st.error != null && recoverJob?.isActive != true) {
                    val curState = _state.value
                    val curSrc = curState.sources.getOrNull(curState.sourceIndex)
                    val tryLocalAlt = st.youtubeVideoId != null || isYouTubeUrl(curSrc?.url) || !needsPrepare(item)
                    val switchedLocal = if (tryLocalAlt && curState.sources.size > 1) {
                        curSrc?.url?.let { failedUrls += it }
                        switchAlternateSource(p, curState, st.positionMs, failedUrls)
                    } else {
                        false
                    }
                    if (!switchedLocal && recoverAttempts < 2 && needsPrepare(item)) {
                        curSrc?.url?.let { failedUrls += it }
                        recover(p, key, st.positionMs, failedUrls)
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

    private fun switchAlternateSource(
        p: CinetorPlayer,
        curState: PlayerScreenState,
        positionMs: Long,
        failedUrls: Set<String>,
    ): Boolean {
        val item = curState.item ?: return false
        val altIdx = curState.sources.indexOfFirst { it.url !in failedUrls }
        if (altIdx < 0) return false
        val chosen = curState.sources[altIdx]
        val req = SourceResolver.request(item, curState.episodeId) ?: return false
        val plan = SourceResolver.plan(chosen, emptyList()).copy(subtitles = req.plan.subtitles)
        recoveries++
        p.load(req.copy(plan = plan), positionMs, playWhenReady = true)
        _state.update { it.copy(sourceIndex = altIdx) }
        return true
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
        val keepMode = _state.value.mode.takeIf { it != HostMode.HIDDEN } ?: HostMode.FULL
        start(target, null, keepMode, _state.value.openedInline)
        lineupOverride = null
    }

    /** Live: jump back to the live edge. */
    fun goLive() = _player.value?.goLive()

    fun playEpisode(episodeId: String) {
        val s = _state.value
        val item = s.item ?: return
        if (episodeId == s.episodeId) return
        countdownJob?.cancel()
        _state.update { it.copy(nextCountdown = null) }
        val keepMode = s.mode.takeIf { it != HostMode.HIDDEN } ?: HostMode.FULL
        start(item, episodeId, keepMode, s.openedInline)
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
    fun selectQuality(height: Int?) {
        _player.value?.selectQuality(height)
        val item = _state.value.item
        if (item != null && !isLiveItem(item)) {
            val prof = profileId()
            val prevTitle = playbackPrefs.getTitlePref(prof, item.id) ?: PlaybackPref()
            val prevGlobal = playbackPrefs.getGlobalPref(prof) ?: PlaybackPref()
            playbackPrefs.setTitlePref(prof, item.id, prevTitle.copy(height = height))
            playbackPrefs.setGlobalPref(prof, prevGlobal.copy(height = height))
        }
    }

    fun selectAudio(trackId: String) = _player.value?.selectAudio(trackId)

    /** Audio menu: switch dubbed source stream while keeping preferred resolution & playback position. */
    fun selectDub(dub: String) {
        val s = _state.value
        val targetNorm = fullSubtitleLanguageName(dub)
        val curHeight = s.sources.getOrNull(s.sourceIndex)?.height
        val candidates = s.sources.indices.filter { i ->
            val d = audioDubOf(s.sources[i])
            d != null && (d.equals(targetNorm, ignoreCase = true) || d.equals(dub, ignoreCase = true))
        }
        if (candidates.isNotEmpty()) {
            val best = if (curHeight != null) {
                candidates.firstOrNull { s.sources[it].height == curHeight }
                    ?: candidates.filter { (s.sources[it].height ?: 0) > 0 }
                        .minByOrNull { kotlin.math.abs((s.sources[it].height ?: 0) - curHeight) }
                    ?: candidates.first()
            } else {
                candidates.first()
            }
            selectSource(best)
            return
        }
        val idx = preferredSourceIndex(
            sources = s.sources,
            titlePref = PlaybackPref(dub = dub, height = curHeight),
            globalPref = null,
        )
        if (s.sources.getOrNull(idx)?.let(::dubOfSource)?.equals(dub, ignoreCase = true) == true) {
            selectSource(idx)
            return
        }
        // Check sibling Item.dubs if not yet in s.sources
        val sib = s.item?.dubs.orEmpty().firstOrNull { opt ->
            fullSubtitleLanguageName(opt.languageName).equals(targetNorm, ignoreCase = true) ||
                opt.languageCode.takeIf { it.isNotBlank() }?.let(::fullSubtitleLanguageName)?.equals(targetNorm, ignoreCase = true) == true
        }
        if (sib != null) {
            selectDubOption(sib)
        }
    }

    /** Switch to a sibling catalog [DubOption] (`subjectId`, `slug`), preserving playback position & mode. */
    fun selectDubOption(dub: DubOption) {
        val s = _state.value
        val item = s.item ?: return
        val p = _player.value ?: return
        val targetLang = fullSubtitleLanguageName(dub.languageName.ifBlank { dub.languageCode })
        val inSources = s.sources.indexOfFirst { audioDubOf(it)?.equals(targetLang, ignoreCase = true) == true }
        if (inSources >= 0) {
            selectSource(inSources)
            return
        }
        val pos = p.state.value.positionMs
        val curHeight = s.sources.getOrNull(s.sourceIndex)?.height
        val sibId = if (dub.subjectId.startsWith("gw:")) dub.subjectId else "gw:${dub.subjectId}"
        val sibStub = item.copy(id = sibId, slug = dub.slug.ifBlank { item.slug })
        dubJob?.cancel()
        dubJob = scope.launch {
            val prepared = runCatching { prepare(sibStub, s.episodeId, false) }.getOrNull() ?: return@launch
            val rawSources = sourcesOf(prepared, s.episodeId)
            if (rawSources.isEmpty()) return@launch
            val labeled = rawSources.map { src ->
                if (audioDubOf(src) != null) src
                else src.copy(
                    audioLang = targetLang,
                    label = listOfNotNull(src.label, targetLang).joinToString(" · "),
                )
            }
            val merged = (s.sources + labeled).distinctBy { it.url }
            val chosenIdx = merged.indexOfFirst {
                audioDubOf(it)?.equals(targetLang, ignoreCase = true) == true && (curHeight == null || it.height == curHeight)
            }.takeIf { it >= 0 }
                ?: merged.indexOfFirst { audioDubOf(it)?.equals(targetLang, ignoreCase = true) == true }
            if (chosenIdx < 0 || _player.value !== p || _state.value.item?.id != item.id) return@launch
            val chosen = merged[chosenIdx]
            val req = SourceResolver.request(item, s.episodeId) ?: return@launch
            val plan = SourceResolver.plan(chosen, emptyList()).copy(subtitles = req.plan.subtitles)
            p.load(req.copy(plan = plan), pos, playWhenReady = true)
            _state.update { it.copy(sources = merged, sourceIndex = chosenIdx) }
            if (!isLiveItem(item)) {
                val prof = profileId()
                playbackPrefs.setTitlePref(prof, item.id, PlaybackPref(dub = targetLang, height = chosen.height ?: curHeight))
                playbackPrefs.setGlobalPref(prof, PlaybackPref(dub = targetLang, height = chosen.height ?: curHeight))
            }
        }
    }

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
        if (!isLiveItem(item)) {
            val prof = profileId()
            val prevTitle = playbackPrefs.getTitlePref(prof, item.id) ?: PlaybackPref()
            val prevGlobal = playbackPrefs.getGlobalPref(prof) ?: PlaybackPref()
            val pickedDub = audioDubOf(src) ?: dubOfSource(src) ?: prevTitle.dub
            val pickedHeight = src.height ?: prevTitle.height
            playbackPrefs.setTitlePref(prof, item.id, PlaybackPref(dub = pickedDub, height = pickedHeight))
            playbackPrefs.setGlobalPref(prof, PlaybackPref(dub = pickedDub ?: prevGlobal.dub, height = pickedHeight ?: prevGlobal.height))
        }
    }

    /**
     * Playback error (expired link / 403 …) on a resolved title: 1st → re-resolve fresh and reload at the
     * same position (same resolution when offered), 2nd → gateway proxy (`direct_play_url`), then give up.
     */
    private fun recover(p: CinetorPlayer, key: String, positionMs: Long, failedUrls: Set<String> = emptySet()) {
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
            val idx = sources.indexOfFirst { it.url !in failedUrls && wantHeight != null && it.height == wantHeight }
                .takeIf { it >= 0 }
                ?: sources.indexOfFirst { it.url !in failedUrls }.coerceAtLeast(0)
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

    /** Dock active playback into `HostMode.INLINE` on `DetailsScreen`. */
    fun enterInline() {
        if (_state.value.item == null) return
        _state.update { it.copy(mode = HostMode.INLINE, openedInline = true, locked = false) }
    }

    /** Expand inline (or mini) playback into `HostMode.FULL`. */
    fun expandFullscreen() {
        if (_state.value.item == null) return
        _state.update { it.copy(mode = HostMode.FULL) }
    }

    /** Collapse from `HostMode.FULL`: returns to `HostMode.INLINE` if `DetailsScreen` opened it inline, else `HostMode.MINI`. */
    fun collapseFullscreen() {
        val s = _state.value
        if (!s.active) return
        if (s.openedInline) {
            _state.update { it.copy(mode = HostMode.INLINE, locked = false) }
        } else {
            minimize()
        }
    }

    fun minimize() {
        if (!_state.value.active) return
        _state.update { it.copy(mode = HostMode.MINI, openedInline = false, locked = false) }
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
