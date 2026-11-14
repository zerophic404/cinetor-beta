package com.mythbrix.cinetor.core.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.mythbrix.cinetor.core.perf.PerformanceTier
import java.io.File

/** Pure buffer policy per tier (Task 3.1). */
data class LoadControlConfig(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val prioritizeTimeOverSize: Boolean = true,
) {
    companion object {
        fun forTier(tier: PerformanceTier): LoadControlConfig = when (tier) {
            PerformanceTier.LOW -> LoadControlConfig(15_000, 30_000, 500, 1_500)
            else -> LoadControlConfig(30_000, 50_000, 500, 1_500)
        }
    }
}

/** Shared media cache size: 200 MB, 80 MB on LOW tier. */
fun cacheBytesFor(tier: PerformanceTier): Long = if (tier == PerformanceTier.LOW) 80L shl 20 else 200L shl 20

/**
 * Owns the ONE app-wide [ExoPlayer] (LOW tier never gets a second) and the shared [SimpleCache].
 * Created lazily on the main thread; lives as long as the process (AppContainer scope), so screen
 * rotation / navigation never recreate it.
 */
@OptIn(UnstableApi::class)
class PlayerFactory(context: Context, private val tier: PerformanceTier) {
    private val appContext = context.applicationContext

    /** How many ExoPlayer instances were ever built (must stay 1). */
    var created: Int = 0
        private set

    val cache: SimpleCache by lazy {
        val dir = File(appContext.cacheDir, "media3-cache")
        SimpleCache(dir, LeastRecentlyUsedCacheEvictor(cacheBytesFor(tier)), StandaloneDatabaseProvider(appContext))
    }

    private var exo: ExoPlayer? = null
    private var wrapper: ExoCinetorPlayer? = null

    /** The shared player (wrapper). Main thread only. */
    @Synchronized
    fun player(): ExoCinetorPlayer {
        wrapper?.let { return it }
        val w = ExoCinetorPlayer(appContext, exoPlayer(), cache)
        wrapper = w
        return w
    }

    @Synchronized
    private fun exoPlayer(): ExoPlayer {
        exo?.let { return it }
        val cfg = LoadControlConfig.forTier(tier)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                cfg.minBufferMs,
                cfg.maxBufferMs,
                cfg.bufferForPlaybackMs,
                cfg.bufferForPlaybackAfterRebufferMs,
            )
            .setPrioritizeTimeOverSizeThresholds(cfg.prioritizeTimeOverSize)
            .build()
        val renderers = DefaultRenderersFactory(appContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        val player = ExoPlayer.Builder(appContext, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        created++
        exo = player
        return player
    }
}
