package com.mythbrix.cinetor.core.player

import androidx.compose.runtime.Immutable

/** Player lifecycle phase (mirrors Player.STATE_* without the Media3 dependency). */
enum class PlaybackPhase { IDLE, BUFFERING, READY, ENDED }

/** Track type of a [RawTrack]. */
enum class TrackKind { VIDEO, AUDIO, TEXT }

/** Stable address of one track inside the current `Tracks` (group index, track index). */
@Immutable
data class TrackRef(val group: Int, val index: Int) {
    val id: String get() = "$group:$index"

    companion object {
        fun parse(id: String): TrackRef? {
            val parts = id.split(':')
            if (parts.size != 2) return null
            val g = parts[0].toIntOrNull() ?: return null
            val i = parts[1].toIntOrNull() ?: return null
            return TrackRef(g, i)
        }
    }
}

/** Flattened, Media3-free view of one track (built from `Tracks` by the Exo wrapper). */
data class RawTrack(
    val kind: TrackKind,
    val ref: TrackRef,
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Int = 0,
    val language: String? = null,
    val label: String? = null,
    val selected: Boolean = false,
    val supported: Boolean = true,
)

@Immutable
data class VideoQuality(val height: Int, val width: Int, val bitrate: Int, val ref: TrackRef) {
    val label: String get() = "${height}p"
}

@Immutable
data class TrackOption(val id: String, val label: String, val language: String?, val selected: Boolean)

@Immutable
data class PlayerTracks(
    /** One entry per height, highest first. */
    val video: List<VideoQuality> = emptyList(),
    val audio: List<TrackOption> = emptyList(),
    val text: List<TrackOption> = emptyList(),
    /** Null = Auto (adaptive); else the forced height. */
    val forcedHeight: Int? = null,
    /** Height of the format currently rendering (shown as "Auto (720p)"). */
    val playingHeight: Int? = null,
    val textEnabled: Boolean = true,
) {
    val autoQuality: Boolean get() = forcedHeight == null
}

/** Everything the UI may know about playback. UI never touches ExoPlayer directly. */
@Immutable
data class PlayerUiState(
    /** Key of the loaded request (`itemId` or `itemId#episodeId`); null = nothing loaded. */
    val key: String? = null,
    val positionMs: Long = 0,
    val bufferedMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val tracks: PlayerTracks = PlayerTracks(),
    val speed: Float = 1f,
    val error: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** Live stream (Media3 `isCurrentMediaItemLive`). */
    val isLive: Boolean = false,
    /** Seekable (VOD, or live with a DVR window). */
    val seekable: Boolean = true,
    /** Distance behind the live edge; −1 = unknown / not live. */
    val liveOffsetMs: Long = -1,
) {
    val buffering: Boolean get() = phase == PlaybackPhase.BUFFERING
    val ended: Boolean get() = phase == PlaybackPhase.ENDED
    val isVertical: Boolean get() = videoHeight > videoWidth && videoWidth > 0
}

/** Pure: raw tracks → UI track lists (qualities deduped by height, best bitrate kept). */
fun buildTracks(raw: List<RawTrack>, forcedHeight: Int?, playingHeight: Int?, textEnabled: Boolean = true): PlayerTracks {
    val video = raw.asSequence()
        .filter { it.kind == TrackKind.VIDEO && it.supported && it.height > 0 }
        .groupBy { it.height }
        .map { (h, list) ->
            val best = list.maxBy { it.bitrate }
            VideoQuality(h, best.width, best.bitrate, best.ref)
        }
        .sortedByDescending { it.height }
    var audioN = 0
    val audio = raw.filter { it.kind == TrackKind.AUDIO && it.supported }.map {
        audioN++
        TrackOption(it.ref.id, trackLabel(it, "Audio $audioN"), it.language, it.selected)
    }
    var textN = 0
    val text = raw.filter { it.kind == TrackKind.TEXT && it.supported }.map {
        textN++
        TrackOption(it.ref.id, trackLabel(it, "Subtitle $textN"), it.language, it.selected && textEnabled)
    }
    return PlayerTracks(video, audio, text, forcedHeight, playingHeight, textEnabled)
}

private fun trackLabel(t: RawTrack, fallback: String): String {
    val lang = t.language?.takeIf { it.isNotBlank() && it != "und" }
    return when {
        !t.label.isNullOrBlank() && lang != null && !t.label.contains(lang, ignoreCase = true) -> "${t.label} ($lang)"
        !t.label.isNullOrBlank() -> t.label
        lang != null -> lang.uppercase()
        else -> fallback
    }
}

/** Pure: which track to force for [height]; null height (Auto) or unknown height → null (clear override). */
fun qualityOverrideFor(tracks: PlayerTracks, height: Int?): TrackRef? =
    height?.let { h -> tracks.video.firstOrNull { it.height == h }?.ref }

/** Playback speeds offered in settings: 0.25…3.0 in 0.25 steps. */
val PLAYBACK_SPEEDS: List<Float> = (1..12).map { it * 0.25f }

fun clampSpeed(speed: Float): Float = speed.coerceIn(PLAYBACK_SPEEDS.first(), PLAYBACK_SPEEDS.last())
