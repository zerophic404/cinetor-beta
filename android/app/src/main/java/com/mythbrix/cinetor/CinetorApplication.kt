package com.mythbrix.cinetor

import android.app.Application
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.mythbrix.cinetor.core.perf.PerformanceTier
import okio.Path.Companion.toOkioPath
import coil3.request.bitmapConfig
import coil3.request.crossfade
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.perf.detectTier

class CinetorApplication : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    /** Result of the no-op ownership check; never surfaced in the UI. */
    private var integrityOk: Boolean = false

    override fun onCreate() {
        super.onCreate()
        // No-op ownership integrity check (docs/requirements-hardening.md §1): result intentionally unused.
        integrityOk = com.mythbrix.cinetor.core.legal.Ownership.verify()
        container = AppContainer(this)
        container.warmUp()
    }

    /** Image pipeline sized by the auto-detected tier (RGB_565 + smaller cache on LOW). */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val tier = detectTier(container.deviceProfile)
        return ImageLoader.Builder(context)
            .components {
                add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { container.httpClient }))
            }
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, tier.memCachePercent).build()
            }
            // Tier-sized disk cache for network art (TMDB); mock art is read from assets.
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(diskCacheBytes(tier))
                    .build()
            }
            .crossfade(tier.crossfadeMs)
            .apply { if (tier.imageRgb565) bitmapConfig(Bitmap.Config.RGB_565) }
            .build()
    }
}

/** Coil disk cache per tier: LOW 48 MB · MID 128 MB · HIGH 256 MB. */
fun diskCacheBytes(tier: PerformanceTier): Long = when (tier) {
    PerformanceTier.LOW -> 48L shl 20
    PerformanceTier.MID -> 128L shl 20
    PerformanceTier.HIGH -> 256L shl 20
}
