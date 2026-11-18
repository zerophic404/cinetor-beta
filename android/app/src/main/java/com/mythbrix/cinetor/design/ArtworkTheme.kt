package com.mythbrix.cinetor.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** RGB (0..1) → HSV (hue 0..360, saturation 0..1, value 0..1). */
internal fun rgbToHsv(c: Color): FloatArray {
    val r = c.red
    val g = c.green
    val b = c.blue
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val d = mx - mn
    val h = when {
        d == 0f -> 0f
        mx == r -> 60f * (((g - b) / d) % 6f)
        mx == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val s = if (mx == 0f) 0f else d / mx
    return floatArrayOf(h, s, mx)
}

/**
 * Target relative luminance. White text on the accent needs L <= ~0.194 for 4.3:1 and the accent on a
 * near-black page needs L >= ~0.172, so the sweet spot is 0.183 (both land just above 4.3:1).
 */
private const val ACCENT_LUMINANCE = 0.183f

/**
 * A title's own accent: its sampled artwork hue, saturation lifted so greys still read as a colour,
 * and brightness solved so white button text and accent text on the dark page both stay legible.
 * Near-colourless artwork (saturation < 0.08) keeps the theme accent.
 */
fun artworkAccent(art: Color, fallback: Color): Color {
    val (h, s0, _) = rgbToHsv(art).let { Triple(it[0], it[1], it[2]) }
    if (s0 < 0.08f) return fallback
    val s = s0.coerceIn(0.55f, 0.90f)
    // Normal case: solve brightness. Dark hues (pure blue, purple) peak below the target even at full
    // brightness, so there it is saturation that is lowered until the target luminance is reached.
    if (Color.hsv(h, s, 1f).luminance() < ACCENT_LUMINANCE) {
        var lo = 0f
        var hi = s
        repeat(20) {
            val mid = (lo + hi) / 2f
            if (Color.hsv(h, mid, 1f).luminance() > ACCENT_LUMINANCE) lo = mid else hi = mid
        }
        return Color.hsv(h, (lo + hi) / 2f, 1f)
    }
    var lo = 0f
    var hi = 1f
    repeat(20) {
        val mid = (lo + hi) / 2f
        if (Color.hsv(h, s, mid).luminance() < ACCENT_LUMINANCE) lo = mid else hi = mid
    }
    return Color.hsv(h, s, (lo + hi) / 2f)
}

/** WCAG contrast ratio between two colours. */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return max(la, lb) / min(la, lb)
}

/**
 * Theme tokens re-keyed to a title's artwork: accent, gradient end, glow and glass edge follow the
 * poster; everything else (surfaces, text, semantic colours, the red LIVE badge) stays the theme's.
 */
class ArtworkTokens(private val base: CinetorThemeTokens, art: Color) : CinetorThemeTokens by base {
    override val accent: Color = artworkAccent(art, base.accent)
    override val accentEnd: Color = Color.hsv(
        rgbToHsv(accent)[0],
        rgbToHsv(accent)[1],
        (rgbToHsv(accent)[2] * 0.72f).coerceAtLeast(0.12f),
    )
    override val accentGlow: Color = accent.copy(alpha = 0.42f)
    override val glassStroke: Color = blendTint(accent, base.glassStroke.copy(alpha = 1f), 0.35f)
        .copy(alpha = (base.glassStroke.alpha * 1.25f).coerceAtMost(0.45f))
    override val name: String = base.name

    /** True when [art] produced its own hue (not the theme fallback). */
    val fromArtwork: Boolean = abs(accent.red - base.accent.red) + abs(accent.green - base.accent.green) +
        abs(accent.blue - base.accent.blue) > 0.001f
}

/**
 * Provides [ArtworkTokens] for [art] to [content] (Details: every accent-driven element follows the
 * poster). No artwork yet, or Dynamic color off in Settings → the plain theme.
 */
@Composable
fun ArtworkTheme(art: Color?, content: @Composable () -> Unit) {
    val base = Cinetor.tokens
    if (art == null || !Cinetor.tint.enabled) {
        content()
        return
    }
    val tokens = remember(base, art) { ArtworkTokens(base, art) }
    CompositionLocalProvider(LocalCinetorTokens provides tokens) { content() }
}
