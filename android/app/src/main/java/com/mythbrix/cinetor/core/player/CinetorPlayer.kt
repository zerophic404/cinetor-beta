package com.mythbrix.cinetor.core.player

import androidx.media3.exoplayer.source.MediaSource
import com.mythbrix.cinetor.core.download.DownloadCacheProvider
import com.mythbrix.cinetor.core.download.OfflineSource
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The app's single player as the rest of the app sees it. UI + preloader only talk to this;
 * the Media3 [Player] is exposed solely for attaching a video surface ([media3Player]).
 */
interface CinetorPlayer {
    val state: StateFlow<PlayerUiState>

    /** Emits the request key each time a first video frame renders after a load. */
    val firstFrames: SharedFlow<String>

    /** Key of the loaded request, null when idle/cleared. */
    val currentKey: String?

    /** Replace whatever is loaded: set source, seek to [startPositionMs], prepare (buffers). */
    fun load(request: PlaybackRequest, startPositionMs: Long, playWhenReady: Boolean)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long) {
        val s = state.value
        val max = if (s.durationMs > 0) s.durationMs else Long.MAX_VALUE
        seekTo((s.positionMs + deltaMs).coerceIn(0, max))
    }
    fun setSpeed(speed: Float)

    /** Live: jump to the live edge (default position); VOD: to the end of the seekable window. */
    fun goLive() {
        val s = state.value
        if (s.durationMs > 0) seekTo(s.durationMs)
        play()
    }

    /** Null = Auto (adaptive). */
    fun selectQuality(height: Int?)
    fun selectAudio(trackId: String)

    /** Null = subtitles off. Styling/sync offset arrive with Task 3.5 (SubtitleOverlay hook). */
    fun selectText(trackId: String?)

    /** Stop + clear media items → buffers released, player idle (instance kept). */
    fun stop()

    /** Surface hook for PlayerView / PiP; null for fakes. */
    fun media3Player(): Player? = null

    /** Attaches or detaches the chromeless YouTube WebView JS evaluator (main thread). */
    fun attachYouTubeExecutor(executor: ((String) -> Unit)?) {}

    /** Chromeless YouTube bridge callbacks (main thread). */
    fun onYouTubeReady(durationSec: Double) {}
    fun onYouTubeProgress(currentSec: Double, durationSec: Double, ytState: Int) {}
    fun onYouTubeStateChange(ytState: Int) {}
    fun onYouTubeError(errorCode: Int) {}
}

/** [CinetorPlayer] over the shared [ExoPlayer]. Main thread only. */
@OptIn(UnstableApi::class)
class ExoCinetorPlayer(
    private val context: Context,
    private val exo: ExoPlayer,
    private val cache: SimpleCache?,
    /** Download cache hook (Task 3.7): completed downloads play from disk, even in airplane mode. */
    private val offline: () -> DownloadCacheProvider? = { null },
) : CinetorPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private val _firstFrames = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val firstFrames: SharedFlow<String> = _firstFrames.asSharedFlow()

    override var currentKey: String? = null
        private set

    private var lastTracks: Tracks = Tracks.EMPTY
    private var forcedHeight: Int? = null
    private var textEnabled = true
    private var activeYouTubeId: String? = null
    private var ytSpeed: Float = 1f
    private var ytExecutor: ((String) -> Unit)? = null
    private var ytReadyEmittedForKey: String? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (activeYouTubeId == null) refresh()
        }
        override fun onTracksChanged(tracks: Tracks) {
            if (activeYouTubeId != null) return
            lastTracks = tracks
            refresh()
        }
        override fun onPlayerError(error: PlaybackException) {
            if (activeYouTubeId != null) return
            com.mythbrix.cinetor.core.legal.DebugLog.log(TAG) { "playback error ${error.errorCodeName}: ${error.message}" }
            _state.update { it.copy(error = error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')) }
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (activeYouTubeId == null) refresh()
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            if (activeYouTubeId == null) currentKey?.let { _firstFrames.tryEmit(it) }
        }
    }

    init {
        exo.addListener(listener)
        exo.addAnalyticsListener(analytics)
        scope.launch {
            while (isActive) {
                if (currentKey != null && activeYouTubeId == null) refresh()
                delay(if (exo.isPlaying) 250 else 500)
            }
        }
    }

    private fun refresh() {
        if (activeYouTubeId != null) return
        val key = currentKey
        val vs = exo.videoSize
        val raw = rawTracks(lastTracks)
        _state.update { s ->
            s.copy(
                key = key,
                youtubeVideoId = null,
                positionMs = exo.currentPosition.coerceAtLeast(0),
                bufferedMs = exo.bufferedPosition.coerceAtLeast(0),
                durationMs = exo.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
                isPlaying = exo.isPlaying,
                playWhenReady = exo.playWhenReady,
                phase = when (exo.playbackState) {
                    Player.STATE_BUFFERING -> PlaybackPhase.BUFFERING
                    Player.STATE_READY -> PlaybackPhase.READY
                    Player.STATE_ENDED -> PlaybackPhase.ENDED
                    else -> PlaybackPhase.IDLE
                },
                tracks = buildTracks(raw, forcedHeight, exo.videoFormat?.height, textEnabled),
                speed = exo.playbackParameters.speed,
                videoWidth = vs.width,
                videoHeight = vs.height,
                isLive = exo.isCurrentMediaItemLive,
                seekable = exo.isCurrentMediaItemSeekable,
                liveOffsetMs = exo.currentLiveOffset.takeIf { it != C.TIME_UNSET } ?: -1,
            )
        }
    }

    override fun load(request: PlaybackRequest, startPositionMs: Long, playWhenReady: Boolean) {
        val ytId = extractYouTubeVideoId(request.plan.url)
        if (ytId != null) {
            currentKey = request.key
            activeYouTubeId = ytId
            forcedHeight = null
            lastTracks = Tracks.EMPTY
            ytReadyEmittedForKey = null
            exo.pause()
            exo.stop()
            exo.clearMediaItems()
            val startMs = startPositionMs.coerceAtLeast(0)
            val durMs = (request.durationSec ?: 0).coerceAtLeast(0) * 1000L
            _state.value = PlayerUiState(
                key = request.key,
                youtubeVideoId = ytId,
                positionMs = startMs,
                bufferedMs = startMs,
                durationMs = durMs,
                isPlaying = false,
                playWhenReady = playWhenReady,
                phase = if (playWhenReady) PlaybackPhase.BUFFERING else PlaybackPhase.READY,
                speed = ytSpeed,
                videoWidth = 1920,
                videoHeight = 1080,
                seekable = true,
            )
            return
        }
        activeYouTubeId = null
        ytReadyEmittedForKey = null
        currentKey = request.key
        forcedHeight = null
        lastTracks = Tracks.EMPTY
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !textEnabled)
            .build()
        _state.value = PlayerUiState(key = request.key, speed = exo.playbackParameters.speed, phase = PlaybackPhase.BUFFERING)
        exo.setMediaSource(mediaSourceFor(request), startPositionMs.coerceAtLeast(0))
        exo.playWhenReady = playWhenReady
        exo.prepare()
    }

    /** Downloaded → local file / download cache; else online through the LRU cache. */
    private fun mediaSourceFor(request: PlaybackRequest): MediaSource {
        val provider = runCatching { offline() }.getOrNull()
        val src = provider?.let { runCatching { it.offlineSource(request.itemId, request.episodeId) }.getOrNull() }
        return when (src) {
            is OfflineSource.LocalFile -> {
                com.mythbrix.cinetor.core.legal.DebugLog.log(TAG) { "source offline-file ${request.key}" }
                SourceResolver.toLocalMediaSource(context, request, src)
            }
            is OfflineSource.Cached -> {
                com.mythbrix.cinetor.core.legal.DebugLog.log(TAG) { "source offline-cache ${request.key}" }
                SourceResolver.toCachedMediaSource(context, request, src, provider!!)
            }
            null -> SourceResolver.toMediaSource(context, request, cache)
        }
    }

    override fun play() {
        if (activeYouTubeId != null) {
            val s = _state.value
            val restart = s.phase == PlaybackPhase.ENDED
            if (restart) {
                _state.update { it.copy(positionMs = 0, playWhenReady = true, phase = PlaybackPhase.BUFFERING) }
                ytExecutor?.invoke("if(window.ytPlayer&&ytPlayer.seekTo){ytPlayer.seekTo(0,true);ytPlayer.playVideo();}")
            } else {
                val nextPhase = if (ytReadyEmittedForKey == currentKey) PlaybackPhase.READY else PlaybackPhase.BUFFERING
                _state.update { it.copy(playWhenReady = true, phase = nextPhase) }
                ytExecutor?.invoke("if(window.ytPlayer&&ytPlayer.playVideo)ytPlayer.playVideo();")
            }
            return
        }
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
        if (exo.playbackState == Player.STATE_IDLE && currentKey != null) exo.prepare()
        exo.play()
    }

    override fun pause() {
        if (activeYouTubeId != null) {
            _state.update { it.copy(playWhenReady = false, isPlaying = false) }
            ytExecutor?.invoke("if(window.ytPlayer&&ytPlayer.pauseVideo)ytPlayer.pauseVideo();")
            return
        }
        exo.pause()
    }

    override fun goLive() {
        if (activeYouTubeId != null) {
            super.goLive()
            return
        }
        if (exo.isCurrentMediaItemLive) exo.seekToDefaultPosition() else super.goLive()
        exo.play()
        refresh()
    }

    override fun seekTo(positionMs: Long) {
        val pos = positionMs.coerceAtLeast(0)
        if (activeYouTubeId != null) {
            val max = _state.value.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
            val clamped = pos.coerceAtMost(max)
            _state.update { it.copy(positionMs = clamped, bufferedMs = maxOf(it.bufferedMs, clamped)) }
            val sec = clamped / 1000.0
            ytExecutor?.invoke("if(window.ytPlayer&&ytPlayer.seekTo)ytPlayer.seekTo($sec, true);")
            return
        }
        exo.seekTo(pos)
        refresh()
    }

    override fun setSpeed(speed: Float) {
        val clamped = clampSpeed(speed)
        if (activeYouTubeId != null) {
            ytSpeed = clamped
            _state.update { it.copy(speed = clamped) }
            ytExecutor?.invoke("if(window.ytPlayer&&ytPlayer.setPlaybackRate)ytPlayer.setPlaybackRate($clamped);")
            return
        }
        exo.playbackParameters = PlaybackParameters(clamped)
        refresh()
    }

    override fun selectQuality(height: Int?) {
        if (activeYouTubeId != null) return
        val ref = qualityOverrideFor(_state.value.tracks, height)
        val builder = exo.trackSelectionParameters.buildUpon()
        val group = ref?.let { lastTracks.groups.getOrNull(it.group) }
        if (ref == null || group == null) {
            builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            forcedHeight = null
        } else {
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, ref.index))
            forcedHeight = height
        }
        exo.trackSelectionParameters = builder.build()
        refresh()
    }

    override fun selectAudio(trackId: String) {
        if (activeYouTubeId != null) return
        val ref = TrackRef.parse(trackId) ?: return
        val group = lastTracks.groups.getOrNull(ref.group) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, ref.index))
            .build()
        refresh()
    }

    override fun selectText(trackId: String?) {
        if (activeYouTubeId != null) {
            textEnabled = trackId != null
            return
        }
        val builder = exo.trackSelectionParameters.buildUpon()
        if (trackId == null) {
            textEnabled = false
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val ref = TrackRef.parse(trackId) ?: return
            val group = lastTracks.groups.getOrNull(ref.group) ?: return
            textEnabled = true
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, ref.index))
        }
        exo.trackSelectionParameters = builder.build()
        refresh()
    }

    override fun stop() {
        val wasYt = activeYouTubeId != null
        currentKey = null
        activeYouTubeId = null
        ytReadyEmittedForKey = null
        forcedHeight = null
        lastTracks = Tracks.EMPTY
        if (wasYt) {
            ytExecutor?.invoke("if(window.ytPlayer&&ytPlayer.stopVideo)ytPlayer.stopVideo();")
        }
        exo.stop()
        exo.clearMediaItems()
        _state.value = PlayerUiState(speed = if (wasYt) ytSpeed else exo.playbackParameters.speed)
    }

    override fun attachYouTubeExecutor(executor: ((String) -> Unit)?) {
        ytExecutor = executor
    }

    override fun onYouTubeReady(durationSec: Double) {
        if (activeYouTubeId == null) return
        _state.update { applyYouTubeReady(it, durationSec) }
        val cur = _state.value
        if (ytSpeed != 1f) {
            ytExecutor?.invoke("if(window.ytPlayer&&ytPlayer.setPlaybackRate)ytPlayer.setPlaybackRate($ytSpeed);")
        }
        if (cur.positionMs > 1_000L) {
            val sec = cur.positionMs / 1000.0
            ytExecutor?.invoke("if(window.ytPlayer&&ytPlayer.seekTo)ytPlayer.seekTo($sec, true);")
        }
        if (cur.playWhenReady) {
            ytExecutor?.invoke("if(window.ytPlayer&&ytPlayer.playVideo)ytPlayer.playVideo();")
        } else {
            ytExecutor?.invoke("if(window.ytPlayer&&ytPlayer.pauseVideo)ytPlayer.pauseVideo();")
        }
        currentKey?.let { key ->
            if (ytReadyEmittedForKey != key) {
                ytReadyEmittedForKey = key
                _firstFrames.tryEmit(key)
            }
        }
    }

    override fun onYouTubeProgress(currentSec: Double, durationSec: Double, ytState: Int) {
        if (activeYouTubeId == null) return
        _state.update { applyYouTubeProgress(it, currentSec, durationSec, ytState) }
    }

    override fun onYouTubeStateChange(ytState: Int) {
        if (activeYouTubeId == null) return
        _state.update { applyYouTubeStateChange(it, ytState) }
    }

    override fun onYouTubeError(errorCode: Int) {
        if (activeYouTubeId == null) return
        com.mythbrix.cinetor.core.legal.DebugLog.log(TAG) { "youtube playback error $errorCode" }
        _state.update { it.copy(isPlaying = false, phase = PlaybackPhase.IDLE, error = "stream unavailable ($errorCode)") }
    }

    override fun media3Player(): Player = exo

    private companion object {
        const val TAG = "CinetorPlayer"

        fun rawTracks(tracks: Tracks): List<RawTrack> = buildList {
            tracks.groups.forEachIndexed { gi, g ->
                val kind = when (g.type) {
                    C.TRACK_TYPE_VIDEO -> TrackKind.VIDEO
                    C.TRACK_TYPE_AUDIO -> TrackKind.AUDIO
                    C.TRACK_TYPE_TEXT -> TrackKind.TEXT
                    else -> return@forEachIndexed
                }
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    add(
                        RawTrack(
                            kind = kind,
                            ref = TrackRef(gi, i),
                            width = f.width.coerceAtLeast(0),
                            height = f.height.coerceAtLeast(0),
                            bitrate = f.bitrate.coerceAtLeast(0),
                            language = f.language,
                            label = f.label,
                            selected = g.isTrackSelected(i),
                            supported = g.isTrackSupported(i),
                        ),
                    )
                }
            }
        }
    }
}
