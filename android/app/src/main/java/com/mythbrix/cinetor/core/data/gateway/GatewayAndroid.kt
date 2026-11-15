package com.mythbrix.cinetor.core.data.gateway

import android.content.Context
import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Gateway id → slug in SharedPreferences (history / downloads reopen gateway titles after a restart). */
class SharedPrefsSlugStore(context: Context) : SlugStore {
    private val prefs = context.applicationContext.getSharedPreferences("gateway_slugs", Context.MODE_PRIVATE)
    private val memory = ConcurrentHashMap<String, String>()

    override fun get(id: String): String? = memory[id] ?: prefs.getString(id, null)?.also { memory[id] = it }

    override fun put(id: String, slug: String) {
        if (memory.put(id, slug) == slug) return
        prefs.edit().putString(id, slug).apply()
    }
}

/**
 * Poster dominant color via Palette — always off the main thread (IO, max 2 at a time, tiny 48 px
 * decode), cached in memory + SharedPreferences, so mapping a row never blocks and repeat visits are free.
 * The first sighting of a poster returns null (fallback tint); the next mapping gets the real color.
 */
class PaletteDominantColors(
    context: Context,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) : DominantColors {
    private val prefs = context.applicationContext.getSharedPreferences("gateway_colors", Context.MODE_PRIVATE)
    private val memory = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val permits = Semaphore(2)

    override fun colorFor(posterUrl: String): String? {
        memory[posterUrl]?.let { return it }
        prefs.getString(posterUrl, null)?.let { memory[posterUrl] = it; return it }
        if (inFlight.size < 64 && inFlight.add(posterUrl)) {
            scope.launch(Dispatchers.IO) {
                try {
                    permits.withPermit { compute(posterUrl) }?.let { c ->
                        memory[posterUrl] = c
                        prefs.edit().putString(posterUrl, c).apply()
                    }
                } catch (_: Exception) {
                } finally {
                    inFlight.remove(posterUrl)
                }
            }
        }
        return null
    }

    private fun compute(url: String): String? {
        val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.bytes() ?: return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 48) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val palette = Palette.from(bmp).maximumColorCount(8).generate()
        bmp.recycle()
        val rgb = palette.getDarkMutedColor(palette.getDarkVibrantColor(palette.getDominantColor(0x1A1A1A)))
        return darken(rgb)
    }

    /** Backgrounds stay dark (red/black brand): cap brightness. */
    private fun darken(rgb: Int): String {
        var r = (rgb shr 16) and 0xFF
        var g = (rgb shr 8) and 0xFF
        var b = rgb and 0xFF
        val max = maxOf(r, g, b)
        if (max > 90) {
            val f = 90.0 / max
            r = (r * f).toInt(); g = (g * f).toInt(); b = (b * f).toInt()
        }
        return "#%02X%02X%02X".format(r, g, b)
    }
}
