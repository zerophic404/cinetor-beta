package com.mythbrix.cinetor

import android.app.Application
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.bitmapConfig
import coil3.request.crossfade
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.perf.detectTier

class CinetorApplication : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
    }

    /** Image pipeline sized by the auto-detected tier (RGB_565 + smaller cache on LOW). */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val tier = detectTier(container.deviceProfile)
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, tier.memCachePercent).build()
            }
            .crossfade(tier.crossfadeMs)
            .apply { if (tier.imageRgb565) bitmapConfig(Bitmap.Config.RGB_565) }
            .build()
    }
}
