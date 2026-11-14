package com.mythbrix.cinetor.feature.player.gestures

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/** STREAM_MUSIC volume as 0..1. */
class VolumeController(context: Context) {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    fun get(): Float = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max

    fun set(value: Float) {
        val idx = (value.coerceIn(0f, 1f) * max).roundToInt()
        if (idx != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0) }
        }
    }
}
