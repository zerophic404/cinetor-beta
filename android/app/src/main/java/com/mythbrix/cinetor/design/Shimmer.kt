package com.mythbrix.cinetor.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.mythbrix.cinetor.core.perf.PerformanceTier

internal const val SHIMMER_SWEEP_MS = 1300
internal const val SHIMMER_LOW_PULSE_MS = 1600
internal const val SHIMMER_BAND_FRACTION = 0.7f

/**
 * Shared animation clock for skeletons inside a [ShimmerHost].
 * - `progress == null` when `prefs.reduceMotion` is enabled (zero animation, static skeleton base).
 * - `cheap == true` on [PerformanceTier.LOW] (draw-only solid pulse with zero shader allocations).
 * - Otherwise runs Cinetor Beta's `1300ms` linear sweep read exclusively inside `drawBehind`.
 */
@Stable
class ShimmerState internal constructor(
    internal val progress: State<Float>?,
    val cheap: Boolean,
)

val LocalShimmer = compositionLocalOf<ShimmerState?> { null }

@Composable
fun rememberShimmer(): ShimmerState {
    val reduceMotion = Cinetor.prefs.reduceMotion
    val cheap = Cinetor.tier == PerformanceTier.LOW
    if (reduceMotion) {
        return remember { ShimmerState(progress = null, cheap = false) }
    }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (cheap) SHIMMER_LOW_PULSE_MS else SHIMMER_SWEEP_MS,
                easing = LinearEasing,
            ),
            repeatMode = if (cheap) RepeatMode.Reverse else RepeatMode.Restart,
        ),
        label = "shimmer_phase",
    )
    return remember(progress, cheap) { ShimmerState(progress, cheap) }
}

/** Provides a single shared shimmer clock to all skeletons below (no-op if one already exists). */
@Composable
fun ShimmerHost(content: @Composable () -> Unit) {
    if (LocalShimmer.current != null) {
        content()
    } else {
        CompositionLocalProvider(LocalShimmer provides rememberShimmer(), content = content)
    }
}

/**
 * Zero-recomposition draw-phase shimmer: [ShimmerState.progress] is read ONLY inside `drawBehind`,
 * sweeping a `0.7f` highlight band smoothly across [base] toward [highlight] on every repaint.
 * Clip before applying.
 */
fun Modifier.shimmer(state: ShimmerState?, base: Color, highlight: Color): Modifier = drawBehind {
    val phase = state?.progress?.value
    if (phase == null) {
        drawRect(base)
        return@drawBehind
    }
    if (state.cheap) {
        drawRect(lerp(base, highlight, phase * 0.75f))
    } else {
        val width = size.width.coerceAtLeast(1f)
        val band = width * SHIMMER_BAND_FRACTION
        val x = -band + (width + 2f * band) * phase
        val edge = highlight.copy(alpha = 0f)
        drawRect(base)
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(edge, highlight, edge),
                startX = x,
                endX = x + band,
            ),
        )
    }
}

/** Shimmer using theme skeleton colours and the nearest [ShimmerHost] (or a local clock if standalone). */
@Composable
fun Modifier.shimmer(): Modifier {
    val tokens = Cinetor.tokens
    val state = LocalShimmer.current ?: rememberShimmer()
    return shimmer(state, tokens.skeletonBase, tokens.skeletonHighlight)
}
