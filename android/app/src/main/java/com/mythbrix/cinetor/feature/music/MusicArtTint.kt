package com.mythbrix.cinetor.feature.music

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.AssetUris
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.hexColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Immutable
data class ArtTintHex(
    val groundHex: String,
    val glowHex: String,
)

/**
 * Pure hue-bucket artwork tint extractor (ported from Cinetor Beta `ArtTint`).
 * Uses pure Kotlin RGB↔HSV math so JVM unit tests verify real pixel extraction without Android stubs.
 */
object MusicArtTint {
    private const val HUE_BUCKETS = 12
    private const val CACHE_MAX = 96

    private val cache = object : LinkedHashMap<String, ArtTintHex?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtTintHex?>?) = size > CACHE_MAX
    }
    private val permits = Semaphore(2)

    @Synchronized
    private fun getCached(key: String): Pair<Boolean, ArtTintHex?> =
        if (cache.containsKey(key)) true to cache[key] else false to null

    @Synchronized
    private fun putCached(key: String, value: ArtTintHex?) {
        cache[key] = value
    }

    fun extractFromArgb(pixels: IntArray): ArtTintHex? {
        if (pixels.isEmpty()) return null
        val weight = DoubleArray(HUE_BUCKETS)
        val rSum = DoubleArray(HUE_BUCKETS)
        val gSum = DoubleArray(HUE_BUCKETS)
        val bSum = DoubleArray(HUE_BUCKETS)
        var total = 0.0

        for (p in pixels) {
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val rf = r / 255f
            val gf = g / 255f
            val bf = b / 255f
            val cMax = max(rf, max(gf, bf))
            val cMin = min(rf, min(gf, bf))
            val delta = cMax - cMin
            val sat = if (cMax <= 0.001f) 0f else delta / cMax
            val w = (sat * sat * cMax).toDouble()
            if (w < 0.02) continue
            val hue = when {
                delta <= 0.0001f -> 0f
                cMax == rf -> 60f * (((gf - bf) / delta).mod(6f))
                cMax == gf -> 60f * (((bf - rf) / delta) + 2f)
                else -> 60f * (((rf - gf) / delta) + 4f)
            }
            val bucket = ((hue / 360f) * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
            weight[bucket] += w
            rSum[bucket] += r * w
            gSum[bucket] += g * w
            bSum[bucket] += b * w
            total += w
        }
        if (total < 0.4) return null
        val top = weight.indices.maxByOrNull { weight[it] } ?: return null
        var tw = 0.0
        var tr = 0.0
        var tg = 0.0
        var tb = 0.0
        for (k in -1..1) {
            val i = (top + k + HUE_BUCKETS) % HUE_BUCKETS
            if (k != 0 && weight[i] < weight[top] * 0.35) continue
            tw += weight[i]
            tr += rSum[i]
            tg += gSum[i]
            tb += bSum[i]
        }
        if (tw <= 0.0) return null
        val avgR = (tr / tw).roundToInt().coerceIn(0, 255)
        val avgG = (tg / tw).roundToInt().coerceIn(0, 255)
        val avgB = (tb / tw).roundToInt().coerceIn(0, 255)
        val (h, s, v) = rgbToHsv(avgR, avgG, avgB)
        if (s < 0.18f) return null

        val groundHex = hsvToHex(h, (s * 1.15f).coerceAtMost(0.85f), v.coerceIn(0.18f, 0.38f))
        val glowHex = hsvToHex(h, (s * 1.2f).coerceIn(0.55f, 0.88f), 0.86f)
        return ArtTintHex(groundHex = groundHex, glowHex = glowHex)
    }

    private fun rgbToHsv(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val cMax = max(rf, max(gf, bf))
        val cMin = min(rf, min(gf, bf))
        val delta = cMax - cMin
        val h = when {
            delta <= 0.0001f -> 0f
            cMax == rf -> 60f * (((gf - bf) / delta).mod(6f))
            cMax == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }
        val s = if (cMax <= 0.001f) 0f else delta / cMax
        return Triple(h, s, cMax)
    }

    private fun hsvToHex(h: Float, s: Float, v: Float): String {
        val c = v.coerceIn(0f, 1f) * s.coerceIn(0f, 1f)
        val hh = (h.mod(360f)) / 60f
        val x = c * (1f - kotlin.math.abs(hh.mod(2f) - 1f))
        val m = v.coerceIn(0f, 1f) - c
        val (r1, g1, b1) = when {
            hh < 1f -> Triple(c, x, 0f)
            hh < 2f -> Triple(x, c, 0f)
            hh < 3f -> Triple(0f, c, x)
            hh < 4f -> Triple(0f, x, c)
            hh < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(r, g, b)
    }

    suspend fun load(context: Context, client: OkHttpClient, artworkPath: String): ArtTintHex? {
        if (artworkPath.isBlank()) return null
        val (hit, cached) = getCached(artworkPath)
        if (hit) return cached
        val computed = withContext(Dispatchers.IO) {
            permits.withPermit {
                runCatching { decodeAndExtract(context, client, artworkPath) }.getOrNull()
            }
        }
        putCached(artworkPath, computed)
        return computed
    }

    private fun decodeAndExtract(context: Context, client: OkHttpClient, path: String): ArtTintHex? {
        val bytes = if (AssetUris.isRemote(path)) {
            client.newCall(Request.Builder().url(path).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.bytes() ?: return null
            }
        } else {
            val assetRel = "mock/" + path.removePrefix("/")
            context.assets.open(assetRel).use { it.readBytes() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 28 && bounds.outHeight / (sample * 2) >= 28) {
            sample *= 2
        }
        val bmp = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return try {
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            extractFromArgb(pixels)
        } finally {
            bmp.recycle()
        }
    }
}

/**
 * Resolves `(groundColor, glowColor)` from [artworkPath] off the main thread, falling back cleanly
 * on `PerformanceTier.LOW` (zero bitmap decode on 2 GB RAM devices).
 */
@Composable
fun rememberMusicArtTint(
    container: AppContainer,
    artworkPath: String,
    fallbackDominantHex: String,
    fallbackAccentHex: String,
): Pair<Color, Color> {
    val tier = Cinetor.tier
    val tokens = Cinetor.tokens
    val context = LocalContext.current
    val baseGround = remember(fallbackDominantHex) {
        runCatching { hexColor(fallbackDominantHex) }.getOrDefault(Color(0xFF121216))
    }
    val baseGlow = remember(fallbackAccentHex, tokens.accent) {
        runCatching { hexColor(fallbackAccentHex) }.getOrDefault(tokens.accent)
    }
    var extracted by remember(artworkPath) { mutableStateOf<ArtTintHex?>(null) }

    LaunchedEffect(artworkPath, tier) {
        if (tier == PerformanceTier.LOW || artworkPath.isBlank()) {
            extracted = null
            return@LaunchedEffect
        }
        extracted = MusicArtTint.load(context.applicationContext, container.httpClient, artworkPath)
    }

    val targetGround = extracted?.let { runCatching { hexColor(it.groundHex) }.getOrNull() } ?: baseGround
    val targetGlow = extracted?.let { runCatching { hexColor(it.glowHex) }.getOrNull() } ?: baseGlow
    val animMs = if (tier == PerformanceTier.LOW || Cinetor.prefs.reduceMotion) 0 else tier.scaled(CinetorTokens.Motion.tintAnimMs)

    val ground by animateColorAsState(targetGround, tween(animMs, easing = CinetorTokens.Motion.standard), label = "artGround")
    val glow by animateColorAsState(targetGlow, tween(animMs, easing = CinetorTokens.Motion.standard), label = "artGlow")
    return ground to glow
}
