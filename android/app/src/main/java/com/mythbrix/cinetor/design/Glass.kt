package com.mythbrix.cinetor.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.mythbrix.cinetor.core.perf.PerformanceTier
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

/**
 * Where a glass surface sits. [overlay] levels float above the blurred content layer and can use
 * live blur; in-content surfaces never do (they live inside the haze source — no recursion).
 */
enum class GlassLevel(val overlay: Boolean) {
    /** Cards/panels inside scrolling content. */
    SURFACE(overlay = false),
    /** Top/bottom bars, nav rail. */
    BAR(overlay = true),
    /** Sheets, dialogs, spaces switcher. */
    SHEET(overlay = true),
    /** Floating controls (mini-player, FABs) — live blur on HIGH only. */
    FLOATING(overlay = true),
}

/**
 * Pure rule: LOW → never; MID → sheets only (bars re-blur the scrolling content every frame — the main
 * scroll-jank source on mid devices, so they use the tinted fill); HIGH → every overlay level.
 */
fun glassUsesLiveBlur(tier: PerformanceTier, level: GlassLevel): Boolean = when {
    !tier.liveBlur || !level.overlay -> false
    tier.liveBlurEverywhere -> true
    else -> level == GlassLevel.SHEET
}

/** Opacity of the tinted fallback fill (no live blur). LOW is near-opaque (88%). */
internal fun fallbackFillAlpha(tier: PerformanceTier, level: GlassLevel): Float = when {
    tier == PerformanceTier.LOW || level.overlay -> 0.88f
    else -> 0.62f
}

/**
 * Glassmorphism: live Haze blur when the tier allows (see [glassUsesLiveBlur]) else a tinted
 * fill; always a 1px gradient stroke (brighter top-left).
 */
@Composable
fun Modifier.glass(shape: Shape, level: GlassLevel = GlassLevel.SURFACE): Modifier {
    val tokens = Cinetor.tokens
    val tier = Cinetor.tier
    val live = glassUsesLiveBlur(tier, level)
    val strokeAlpha = tokens.glassStroke.alpha
    val stroke = Brush.linearGradient(
        colors = listOf(
            tokens.glassStroke.copy(alpha = (strokeAlpha * 2.2f).coerceAtMost(1f)),
            tokens.glassStroke.copy(alpha = strokeAlpha * 0.35f),
        ),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
    val clipped = this.clip(shape)
    val filled = if (live) {
        val haze = Cinetor.haze
        clipped.hazeChild(haze) {
            backgroundColor = tokens.bg
            tints = listOf(HazeTint(tokens.surface.copy(alpha = 0.55f)), HazeTint(tokens.glassFill))
            blurRadius = tokens.blur
            noiseFactor = 0.03f
            fallbackTint = HazeTint(tokens.surface.copy(alpha = 0.88f))
        }
    } else {
        clipped
            .background(tokens.surface.copy(alpha = fallbackFillAlpha(tier, level)))
            .background(Brush.verticalGradient(listOf(tokens.glassFill, Color.Transparent)))
    }
    return filled.border(CinetorTokens.Shape.strokeHairline, stroke, shape)
}
