package com.mythbrix.cinetor.core.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.crossfade
import com.mythbrix.cinetor.core.data.AssetUris
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.design.Cinetor
import kotlin.random.Random

private const val TMDB_PREFIX = "tmdb:"
private const val TMDB_BASE = "https://image.tmdb.org/t/p/"
private val TMDB_URL_REGEX = Regex("""^https?://image\.tmdb\.org/t/p/[^/]+/(.+)$""")
private val OSS_HOST_REGEX = Regex("""^https?://[^/]*(?:aoneroom\.com|aliyuncs\.com)/""", RegexOption.IGNORE_CASE)
private val OSS_PARAM_REGEX = Regex("""([?&])x-oss-process=[^&]*""")
private val OSS_BUCKETS = intArrayOf(200, 342, 500, 780, 1280)

/** Brand-locked crimson, ruby, wine, scarlet, and dark graphite fallback palettes (from Cinetor Beta). */
val ArtPalettes: List<Pair<Color, Color>> = listOf(
    Color(0xFF7A0E1A) to Color(0xFF1A0609),
    Color(0xFF8F1422) to Color(0xFF200709),
    Color(0xFF4A0A12) to Color(0xFF12050A),
    Color(0xFFA51A28) to Color(0xFF2A0A0F),
    Color(0xFF3B3B40) to Color(0xFF111113),
    Color(0xFF55555C) to Color(0xFF17171A),
    Color(0xFF2A2A2F) to Color(0xFF0C0C0D),
    Color(0xFF5C0A14) to Color(0xFF16060A),
)

/** Non-negative deterministic palette index safe against `Int.MIN_VALUE` hashCodes. */
fun artPaletteIndex(seedKey: String): Int = (seedKey.hashCode() and 0x7fffffff) % ArtPalettes.size

/** Stable brand-aligned fallback accent for a title. */
fun artTint(seedKey: String): Color = ArtPalettes[artPaletteIndex(seedKey)].first

/** LOW tier decodes bitmaps smaller (75% hero/backdrop, 85% cards) to save heap on 2 GB RAM devices. */
fun decodeScale(tier: PerformanceTier, large: Boolean): Float = when (tier) {
    PerformanceTier.LOW -> if (large) 0.75f else 0.85f
    PerformanceTier.MID, PerformanceTier.HIGH -> 1f
}

/** Width-bucketed TMDB size token (`w185`..`w1280`), capped on LOW tier. */
fun tmdbBucket(widthPx: Int, tier: PerformanceTier, large: Boolean = false): String {
    val capped = if (tier == PerformanceTier.LOW) widthPx.coerceAtMost(if (large) 780 else 342) else widthPx
    return when {
        capped <= 185 -> "w185"
        capped <= 342 -> "w342"
        capped <= 500 -> "w500"
        capped <= 780 -> "w780"
        else -> "w1280"
    }
}

/** Width-bucketed OSS resize width (`200`..`1280`), capped on LOW tier. */
fun ossBucket(widthPx: Int, tier: PerformanceTier, large: Boolean = false): Int {
    val capped = if (tier == PerformanceTier.LOW) widthPx.coerceAtMost(if (large) 780 else 342) else widthPx
    return OSS_BUCKETS.firstOrNull { it >= capped } ?: OSS_BUCKETS.last()
}

/**
 * Resolves [rawPath] into a width-bucketed URI:
 * - Blank → `""`
 * - `tmdb:/...` or `image.tmdb.org/t/p/...` → width-bucketed TMDB URL
 * - OSS / MovieBox CDN (`aoneroom.com`, `aliyuncs.com`) → `?x-oss-process=image/resize,w_N`
 * - Other remote URLs → pass-through
 * - Bundled mock paths → `file:///android_asset/mock/...`
 */
fun sizedImageUrl(
    rawPath: String,
    targetWidthPx: Int,
    tier: PerformanceTier,
    large: Boolean = false,
): String {
    val trimmed = rawPath.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.startsWith(TMDB_PREFIX)) {
        val tail = trimmed.removePrefix(TMDB_PREFIX).trimStart('/')
        return "$TMDB_BASE${tmdbBucket(targetWidthPx, tier, large)}/$tail"
    }
    TMDB_URL_REGEX.matchEntire(trimmed)?.let { match ->
        val tail = match.groupValues[1].trimStart('/')
        return "$TMDB_BASE${tmdbBucket(targetWidthPx, tier, large)}/$tail"
    }
    if (OSS_HOST_REGEX.containsMatchIn(trimmed)) {
        val w = ossBucket(targetWidthPx, tier, large)
        val param = "x-oss-process=image/resize,w_$w"
        return if (OSS_PARAM_REGEX.containsMatchIn(trimmed)) {
            OSS_PARAM_REGEX.replace(trimmed) { "${it.groupValues[1]}$param" }
        } else {
            val sep = if ('?' in trimmed) '&' else '?'
            "$trimmed$sep$param"
        }
    }
    return AssetUris.of(trimmed)
}

/**
 * Coil request decoded at exact on-screen pixel dimensions with width-bucketed CDN URLs;
 * LOW tier skips crossfade (`0ms`) and decodes smaller `RGB_565` software bitmaps.
 */
@Composable
fun rememberSizedImage(path: String, width: Dp, height: Dp, large: Boolean = false): ImageRequest {
    val context = LocalContext.current
    val density = LocalDensity.current
    val tier = Cinetor.tier
    return remember(path, width, height, large, tier, density) {
        val scale = decodeScale(tier, large)
        val w = (with(density) { width.toPx() } * scale).toInt().coerceAtLeast(1)
        val h = (with(density) { height.toPx() } * scale).toInt().coerceAtLeast(1)
        val resolved = sizedImageUrl(path, w, tier, large)
        ImageRequest.Builder(context)
            .data(resolved.ifEmpty { null })
            .size(w, h)
            .crossfade(if (tier == PerformanceTier.LOW) 0 else tier.crossfadeMs)
            .apply {
                if (tier.imageRgb565) {
                    bitmapConfig(Bitmap.Config.RGB_565)
                    allowHardware(false)
                }
            }
            .build()
    }
}

/**
 * Abstract generated cinema artwork (ported from Cinetor Beta): crimson/graphite linear field,
 * soft radial light orbs, and an angled projector beam.
 */
@Composable
fun GeneratedArt(seedKey: String, modifier: Modifier = Modifier) {
    val seed = seedKey.hashCode()
    val (light, dark) = ArtPalettes[artPaletteIndex(seedKey)]
    val lowTier = Cinetor.tier == PerformanceTier.LOW
    Canvas(modifier) {
        val random = Random(seed)
        drawRect(
            Brush.linearGradient(
                colors = listOf(light, dark),
                start = Offset(size.width * random.nextFloat(), 0f),
                end = Offset(size.width * random.nextFloat(), size.height),
            ),
        )
        if (!lowTier) {
            repeat(3) {
                val center = Offset(size.width * random.nextFloat(), size.height * random.nextFloat() * 0.8f)
                val radius = size.maxDimension * (0.2f + random.nextFloat() * 0.35f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.10f + random.nextFloat() * 0.16f), Color.Transparent),
                        center = center,
                        radius = radius.coerceAtLeast(1f),
                    ),
                    radius = radius,
                    center = center,
                    blendMode = BlendMode.Screen,
                )
            }
            val beamStart = size.width * random.nextFloat()
            drawLine(
                brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent)),
                start = Offset(beamStart, 0f),
                end = Offset(beamStart + size.width * (random.nextFloat() - 0.5f), size.height),
                strokeWidth = size.width * 0.12f,
                blendMode = BlendMode.Screen,
            )
        }
    }
}

/**
 * Remote/asset artwork with zero-waste fallback: [GeneratedArt] is composed ONLY when [path] is
 * blank or Coil reports an error, so loaded posters never spend GPU frames painting behind bitmaps.
 */
@Composable
fun CinetorArtwork(
    seedKey: String,
    path: String,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackTitle: String? = null,
    imageModifier: Modifier = Modifier,
) {
    var failed by remember(path) { mutableStateOf(false) }
    val showFallback = path.isBlank() || failed
    Box(modifier) {
        if (showFallback) {
            GeneratedArt(seedKey = seedKey, modifier = Modifier.fillMaxSize())
            if (!fallbackTitle.isNullOrBlank()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0.48f to Color.Transparent, 1f to Color(0xEB08080A))),
                )
                Text(
                    text = fallbackTitle,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                        .clearAndSetSemantics {},
                )
            }
        } else {
            AsyncImage(
                model = rememberSizedImage(path, width, height, large = large),
                contentDescription = contentDescription,
                contentScale = contentScale,
                onError = { failed = true },
                modifier = imageModifier.fillMaxSize(),
            )
        }
    }
}
