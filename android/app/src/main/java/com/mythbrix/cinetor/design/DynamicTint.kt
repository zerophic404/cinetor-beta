package com.mythbrix.cinetor.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.mythbrix.cinetor.core.data.parseHexColor
import com.mythbrix.cinetor.core.perf.PerformanceTier
import kotlin.math.max

/**
 * Blends a content colour toward the theme accent so the red/black identity always holds:
 * result = source·strength + accent·(1 − strength) (per sRGB channel, alpha = 1).
 */
fun blendTint(source: Color, accent: Color, strength: Float): Color {
    val s = strength.coerceIn(0f, 1f)
    fun mix(a: Float, b: Float) = a * s + b * (1f - s)
    return Color(
        red = mix(source.red, accent.red),
        green = mix(source.green, accent.green),
        blue = mix(source.blue, accent.blue),
        alpha = 1f,
    )
}

/** `#RRGGBB` → Color (model colours are hex strings). */
fun hexColor(hex: String): Color = Color(parseHexColor(hex))

/** Holds the colour the app background tints toward (current hero / focused card). */
@Stable
class DynamicTintState {
    var source: Color? by mutableStateOf(null)
        private set

    /** Settings → Appearance → Dynamic color; off = plain theme accent glow. */
    var enabled: Boolean by mutableStateOf(true)

    fun setSource(color: Color) {
        source = color
    }

    fun clear() {
        source = null
    }
}

/**
 * App background: theme bg + radial tint glow from the top, animated over 600 ms (tier-scaled).
 * Colour is read only in the draw phase → no recomposition while animating.
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val tokens = Cinetor.tokens
    val tier = Cinetor.tier
    val tint = Cinetor.tint
    val target = blendTint(tint.source?.takeIf { tint.enabled } ?: tokens.accent, tokens.accent, tokens.tintStrength)
    val animated by animateColorAsState(
        targetValue = target,
        animationSpec = tween(tier.scaled(CinetorTokens.Motion.tintAnimMs), easing = CinetorTokens.Motion.standard),
        label = "appTint",
    )
    val threeStop = tier != PerformanceTier.LOW
    Box(
        modifier
            .fillMaxSize()
            .background(tokens.bg)
            .drawBehind {
                val c = animated
                val stops = if (threeStop) {
                    arrayOf(0f to c.copy(alpha = 0.34f), 0.45f to c.copy(alpha = 0.10f), 1f to Color.Transparent)
                } else {
                    arrayOf(0f to c.copy(alpha = 0.26f), 1f to Color.Transparent)
                }
                drawRect(
                    Brush.radialGradient(
                        colorStops = *stops,
                        center = Offset(size.width * 0.5f, 0f),
                        radius = max(size.width, size.height) * 0.85f,
                    ),
                )
            },
        content = content,
    )
}
