package com.mythbrix.cinetor.core.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** In-memory [CinetorPlayer] for logic tests: records calls, state driven by the test. */
class FakeCinetorPlayer : CinetorPlayer {
    val mutableState = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = mutableState
    val frames = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val firstFrames: SharedFlow<String> = frames

    override var currentKey: String? = null
        private set

    val loads = mutableListOf<Pair<String, Long>>()
    var stops = 0
    var lastPlayWhenReady = false
    var forcedHeight: Int? = null
    var overriddenRef: TrackRef? = null
    var audioId: String? = null
    var textId: String? = "unset"
    var seeks = mutableListOf<Long>()

    override fun load(request: PlaybackRequest, startPositionMs: Long, playWhenReady: Boolean) {
        currentKey = request.key
        loads += request.key to startPositionMs
        lastPlayWhenReady = playWhenReady
        mutableState.value = PlayerUiState(
            key = request.key, positionMs = startPositionMs, phase = PlaybackPhase.BUFFERING,
            playWhenReady = playWhenReady, speed = mutableState.value.speed,
            durationMs = (request.durationSec ?: 0) * 1000L,
        )
    }

    override fun play() {
        lastPlayWhenReady = true
        mutableState.update { it.copy(playWhenReady = true, isPlaying = it.phase == PlaybackPhase.READY) }
    }

    override fun pause() {
        lastPlayWhenReady = false
        mutableState.update { it.copy(playWhenReady = false, isPlaying = false) }
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        mutableState.update { it.copy(positionMs = positionMs) }
    }

    override fun setSpeed(speed: Float) = mutableState.update { it.copy(speed = clampSpeed(speed)) }

    override fun selectQuality(height: Int?) {
        overriddenRef = qualityOverrideFor(mutableState.value.tracks, height)
        forcedHeight = if (overriddenRef != null) height else null
        mutableState.update { it.copy(tracks = it.tracks.copy(forcedHeight = forcedHeight)) }
    }

    override fun selectAudio(trackId: String) {
        audioId = trackId
        mutableState.update { s ->
            s.copy(tracks = s.tracks.copy(audio = s.tracks.audio.map { it.copy(selected = it.id == trackId) }))
        }
    }

    override fun selectText(trackId: String?) {
        textId = trackId
    }

    override fun stop() {
        stops++
        currentKey = null
        mutableState.value = PlayerUiState(speed = mutableState.value.speed)
    }

    /** Test helper: move the loaded key to [phase]. */
    fun setPhase(phase: PlaybackPhase, error: String? = null) =
        mutableState.update { it.copy(phase = phase, error = error, isPlaying = phase == PlaybackPhase.READY && it.playWhenReady) }
}
