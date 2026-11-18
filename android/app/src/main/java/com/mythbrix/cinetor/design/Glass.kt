package com.mythbrix.cinetor.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.perf.PerformanceTier
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlin.math.min

/**
 * True inside any subtree recorded by `Modifier.haze(state)`. Calling `Modifier.hazeChild(state)` on
 * a descendant of the same `HazeState` causes a fatal `RenderNode cannot be drawn into itself` crash
 * on Android 12–15 (`API 31–35`), so [Modifier.glass] unconditionally uses its cached drawn-glass path
 * whenever [LocalInsideHazeSource] is `true`.
 */
val LocalInsideHazeSource = compositionLocalOf { false }

/**
 * Where a glass surface sits. [overlay] levels float above the blurred content layer and can use
 * live blur when outside [LocalInsideHazeSource]; in-content surfaces never do.
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

/** Live blur also shuts off when a theme specifies `0.dp` blur radius (e.g. `MATTE`). */
fun glassUsesLiveBlur(tier: PerformanceTier, level: GlassLevel, blurRadius: Dp): Boolean =
    blurRadius > 0.dp && glassUsesLiveBlur(tier, level)

/** Theme-aware overload checking specular shine and theme blur radius (`MATTE` = false). */
fun glassUsesLiveBlur(tier: PerformanceTier, level: GlassLevel, theme: AppTheme): Boolean =
    themeUsesSpecularShine(theme) && glassUsesLiveBlur(tier, level, CinetorTokens.of(theme).blur)

/** Pure JVM-testable gate combining [LocalInsideHazeSource] cyclic-RenderNode immunity with tier/level/theme rules. */
fun glassAllowsLiveBlur(
    insideHazeSource: Boolean,
    tier: PerformanceTier,
    level: GlassLevel,
    theme: AppTheme,
): Boolean = !insideHazeSource && glassUsesLiveBlur(tier, level, theme)

/** Opacity of the tinted fallback fill (no live blur). LOW is near-opaque (88%) for locked 60 fps. */
internal fun fallbackFillAlpha(tier: PerformanceTier, level: GlassLevel): Float = when {
    tier == PerformanceTier.LOW || level.overlay -> 0.88f
    else -> 0.62f
}

/**
 * Liquid Glass optics:
 * - Live `Haze` backdrop blur (`hazeChild`, Haze 1.1.1) ONLY when `!LocalInsideHazeSource.current`,
 *   `specular`, and `glassUsesLiveBlur(tier, level, Cinetor.theme)` (preventing `RenderNode` self-blur
 *   crashes on Android 12–15 while keeping top/bottom bars live-blurred outside `AppBackground`).
 * - Tinted translucent fallback fill (88% alpha on `PerformanceTier.LOW` with zero live blur so 2 GB RAM
 *   phones stay at locked 60 fps).
 * - Cached single-pass drawn optics (`drawWithCache`): soft top inner highlight band (disabled in `MATTE`)
 *   behind content, and a top-left-to-bottom-right refractive specular border fading from a bright top-left
 *   rim into a subtle accent/aura edge gleam.
 */
@Composable
fun Modifier.glass(
    shape: Shape = Cinetor.cardShape,
    level: GlassLevel = GlassLevel.SURFACE,
): Modifier {
    val tokens = Cinetor.tokens
    val tier = Cinetor.tier
    val specular = Cinetor.specularShine
    val isLight = tokens.isLight
    val live = !LocalInsideHazeSource.current && specular && glassUsesLiveBlur(tier, level, Cinetor.theme)

    val tintSource = Cinetor.tint.let { if (it.enabled) it.source else null }
    val gleamHue = if (tintSource != null && tokens.tintStrength > 0f) {
        blendTint(tintSource, tokens.accent, tokens.tintStrength)
    } else {
        tokens.accent
    }

    val clipped = this.clip(shape)
    val backdrop = if (live) {
        val haze = Cinetor.haze
        val surfaceAlpha = when {
            isLight -> 0.72f
            else -> 0.56f
        }
        clipped.hazeChild(haze) {
            backgroundColor = tokens.bg
            tints = listOf(HazeTint(tokens.surface.copy(alpha = surfaceAlpha)), HazeTint(tokens.glassFill))
            blurRadius = tokens.blur
            noiseFactor = if (isLight) 0.015f else 0.025f
            fallbackTint = HazeTint(tokens.surface.copy(alpha = 0.88f))
        }
    } else {
        clipped
    }

    return backdrop.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val strokeStyle = Stroke(width = CinetorTokens.Shape.strokeHairline.toPx())
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)

        val baseFillColor = if (!live) {
            val alpha = when {
                tier == PerformanceTier.LOW -> fallbackFillAlpha(tier, level)
                !specular -> if (level.overlay) 0.96f else 0.92f
                isLight && !level.overlay -> 0.78f
                else -> fallbackFillAlpha(tier, level)
            }
            tokens.surface.copy(alpha = alpha)
        } else {
            Color.Unspecified
        }

        val bodySheenBrush: Brush? = if (!live && specular && tier != PerformanceTier.LOW) {
            val topFill = tokens.glassFill
            val midFill = tokens.glassFill.copy(alpha = tokens.glassFill.alpha * 0.28f)
            val cornerTint = gleamHue.copy(alpha = if (isLight) 0.025f else 0.038f)
            Brush.linearGradient(
                colors = listOf(topFill, midFill, cornerTint),
                start = Offset.Zero,
                end = Offset(w, h),
            )
        } else {
            null
        }

        val highlightBandHeight = min(h * 0.42f, 32.dp.toPx()).coerceAtLeast(1f)
        val innerHighlightBrush: Brush? = if (specular) {
            val bandAlpha = when {
                tier == PerformanceTier.LOW -> if (isLight) 0.34f else 0.045f
                isLight -> 0.62f
                else -> (tokens.glassFill.alpha * 1.15f).coerceIn(0.065f, 0.15f)
            }
            Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = bandAlpha), Color.Transparent),
                startY = 0f,
                endY = highlightBandHeight,
            )
        } else {
            null
        }

        val borderBrush: Brush = if (!specular) {
            SolidColor(tokens.divider)
        } else {
            val strokeAlpha = tokens.glassStroke.alpha
            val topLeftRim = if (isLight) {
                tokens.glassStroke.copy(alpha = (strokeAlpha * 1.45f).coerceIn(0.14f, 0.30f))
            } else {
                tokens.glassStroke.copy(alpha = (strokeAlpha * 2.35f).coerceIn(0.24f, 0.78f))
            }
            val midRim = tokens.glassStroke.copy(alpha = (strokeAlpha * 0.42f).coerceAtLeast(0.06f))
            val gleamAlpha = when {
                tier == PerformanceTier.LOW -> (strokeAlpha * 0.50f).coerceAtLeast(0.08f)
                isLight -> 0.20f
                else -> 0.24f
            }
            val bottomRightGleam = blendTint(
                source = gleamHue,
                accent = tokens.glassStroke,
                strength = 0.42f,
            ).copy(alpha = gleamAlpha)
            Brush.linearGradient(
                colorStops = arrayOf(
                    0f to topLeftRim,
                    0.52f to midRim,
                    1f to bottomRightGleam,
                ),
                start = Offset.Zero,
                end = Offset(w, h),
            )
        }

        onDrawWithContent {
            if (baseFillColor != Color.Unspecified) {
                drawOutline(outline = outline, color = baseFillColor)
            }
            if (bodySheenBrush != null) {
                drawOutline(outline = outline, brush = bodySheenBrush)
            }
            if (innerHighlightBrush != null) {
                drawRect(brush = innerHighlightBrush, size = Size(size.width, highlightBandHeight))
            }
            drawContent()
            drawOutline(outline = outline, brush = borderBrush, style = strokeStyle)
        }
    }
}

/**
 * Liquid Glass control (icon buttons, pills): [glass] plus a curved lens highlight across the top,
 * a soft refracted rim glow along the bottom edge, a Cinema Red wash + rim when [active], and a
 * springy press-in driven by [interaction]. LOW tier keeps the plain glass and the press scale only.
 */
@Composable
fun Modifier.liquidGlassButton(
    shape: Shape = CircleShape,
    active: Boolean = false,
    interaction: MutableInteractionSource? = null,
): Modifier {
    val tokens = Cinetor.tokens
    val tier = Cinetor.tier
    val pressed = interaction?.collectIsPressedAsState()?.value ?: false
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 650f),
        label = "liquidGlassPress",
    )
    val optics = tier != PerformanceTier.LOW
    val lensAlpha = if (tokens.isLight) 0.42f else 0.20f
    val rimColor = if (active) tokens.accent else Color.White
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .glass(shape, GlassLevel.SURFACE)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val w = size.width.coerceAtLeast(1f)
            val h = size.height.coerceAtLeast(1f)
            val lens = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = lensAlpha), Color.Transparent),
                center = Offset(w * 0.5f, -h * 0.18f),
                radius = maxOf(w, h) * 0.78f,
            )
            val rimGlow = Brush.verticalGradient(
                colors = listOf(Color.Transparent, rimColor.copy(alpha = if (active) 0.26f else 0.09f)),
                startY = h * 0.55f,
                endY = h,
            )
            val activeWash = tokens.accent.copy(alpha = 0.16f)
            val activeStroke = Stroke(width = 1.dp.toPx())
            onDrawWithContent {
                if (active) drawOutline(outline = outline, color = activeWash)
                if (optics) {
                    drawOutline(outline = outline, brush = lens)
                    drawOutline(outline = outline, brush = rimGlow)
                }
                drawContent()
                if (active) drawOutline(outline = outline, color = tokens.accent.copy(alpha = 0.62f), style = activeStroke)
            }
        }
}

/**
 * Floating navigation dock: noticeably more see-through than panels. Live blur with light tints
 * where the device allows it (HIGH tier, outside a haze source); otherwise a 58% translucent fill.
 * Both get a curved lens highlight, a top-left → bottom-right rim, and a soft accent-tinted shadow.
 */
@Composable
fun Modifier.liquidGlassDock(shape: Shape): Modifier {
    val tokens = Cinetor.tokens
    val tier = Cinetor.tier
    val live = !LocalInsideHazeSource.current && glassUsesLiveBlur(tier, GlassLevel.FLOATING, Cinetor.theme)
    val shadowed = if (tier == PerformanceTier.LOW) {
        this
    } else {
        this.shadow(18.dp, shape, ambientColor = tokens.accentGlow, spotColor = Color.Black.copy(alpha = 0.5f))
    }
    val clipped = shadowed.clip(shape)
    val backdrop = if (live) {
        clipped.hazeChild(Cinetor.haze) {
            backgroundColor = tokens.bg
            tints = listOf(HazeTint(tokens.surface.copy(alpha = if (tokens.isLight) 0.46f else 0.34f)))
            blurRadius = tokens.blur
            noiseFactor = 0.02f
            fallbackTint = HazeTint(tokens.surface.copy(alpha = 0.62f))
        }
    } else {
        clipped
    }
    return backdrop.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)
        val fill = if (live) {
            null
        } else {
            Brush.verticalGradient(
                listOf(
                    tokens.surfaceElevated.copy(alpha = if (tokens.isLight) 0.70f else 0.58f),
                    tokens.surface.copy(alpha = if (tokens.isLight) 0.78f else 0.66f),
                ),
            )
        }
        val lens = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = if (tokens.isLight) 0.50f else 0.16f), Color.Transparent),
            center = Offset(w * 0.5f, -h * 0.35f),
            radius = w * 0.55f,
        )
        val rim = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = if (tokens.isLight) 0.70f else 0.34f),
                0.5f to tokens.glassStroke,
                1f to tokens.accent.copy(alpha = 0.30f),
            ),
            start = Offset.Zero,
            end = Offset(w, h),
        )
        val stroke = Stroke(width = 1.dp.toPx())
        onDrawWithContent {
            fill?.let { drawOutline(outline = outline, brush = it) }
            if (tier != PerformanceTier.LOW) drawOutline(outline = outline, brush = lens)
            drawContent()
            drawOutline(outline = outline, brush = rim, style = stroke)
        }
    }
}
