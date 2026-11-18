package com.mythbrix.cinetor.core.ui

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.mythbrix.cinetor.design.Cinetor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val artworkColors = ConcurrentHashMap<String, Int>()

/**
 * Keeps a sampled artwork colour readable as a page wash: saturation at least 0.38 and brightness
 * held in 0.48..0.82, so near-black posters still give a visible hue and neon ones never glare.
 */
internal fun gentleArtworkColor(argb: Int): Int {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(argb, hsv)
    hsv[1] = hsv[1].coerceIn(0.38f, 0.78f)
    hsv[2] = hsv[2].coerceIn(0.48f, 0.82f)
    return AndroidColor.HSVToColor(hsv)
}

private fun pickArtworkColor(palette: Palette): Int? {
    val swatch = palette.vibrantSwatch
        ?: palette.lightVibrantSwatch
        ?: palette.darkVibrantSwatch
        ?: palette.mutedSwatch
        ?: palette.dominantSwatch
        ?: return null
    return gentleArtworkColor(swatch.rgb)
}

/**
 * The title's own colour, sampled from its poster (already in Coil's cache from the card that was
 * tapped, so this is a memory hit). Null until sampled; results are memoised per artwork path.
 */
@Composable
fun rememberArtworkColor(path: String?): Color? {
    val context = LocalContext.current
    val tier = Cinetor.tier
    var color by remember(path) { mutableStateOf(path?.let { artworkColors[it] }?.let(::Color)) }
    LaunchedEffect(path) {
        if (path.isNullOrBlank() || color != null) return@LaunchedEffect
        val url = sizedImageUrl(path, 200, tier)
        if (url.isEmpty()) return@LaunchedEffect
        val argb = try {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(96, 144)
                .allowHardware(false)
                .build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@LaunchedEffect
            val bitmap = result.image.toBitmap()
            withContext(Dispatchers.Default) {
                pickArtworkColor(Palette.from(bitmap).maximumColorCount(12).generate())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        artworkColors[path] = argb
        color = Color(argb)
    }
    return color
}
