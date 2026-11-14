package com.mythbrix.cinetor.core.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import com.mythbrix.cinetor.core.data.AssetUris
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.design.Cinetor

/** LOW tier decodes big images (hero/backdrop) at 75% of their on-screen size. */
fun decodeScale(tier: PerformanceTier, large: Boolean): Float =
    if (tier == PerformanceTier.LOW && large) 0.75f else 1f

/**
 * Coil request decoded at the exact on-screen size (no full-res bitmaps in lists);
 * LOW tier → RGB_565 software bitmaps (half the memory).
 */
@Composable
fun rememberSizedImage(path: String, width: Dp, height: Dp, large: Boolean = false): ImageRequest {
    val context = LocalContext.current
    val density = LocalDensity.current
    val tier = Cinetor.tier
    return remember(path, width, height, tier, density) {
        val scale = decodeScale(tier, large)
        val w = (with(density) { width.toPx() } * scale).toInt().coerceAtLeast(1)
        val h = (with(density) { height.toPx() } * scale).toInt().coerceAtLeast(1)
        ImageRequest.Builder(context)
            .data(AssetUris.of(path))
            .size(w, h)
            .apply {
                if (tier.imageRgb565) {
                    bitmapConfig(Bitmap.Config.RGB_565)
                    allowHardware(false)
                }
            }
            .build()
    }
}
