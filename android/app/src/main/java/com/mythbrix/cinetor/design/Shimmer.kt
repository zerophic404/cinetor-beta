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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.mythbrix.cinetor.core.perf.PerformanceTier

/**
 * One animation clock shared by every skeleton inside a [ShimmerHost]. The clock only runs while a
 * host is composed (i.e. while something is loading) — never app-wide.
 * [cheap] (LOW tier) = slow solid pulse instead of a moving gradient.
 */
@Stable
class ShimmerState internal constructor(internal val progress: State<Float>, val cheap: Boolean)

val LocalShimmer = compositionLocalOf<ShimmerState?> { null }

@Composable
fun rememberShimmer(): ShimmerState {
    val cheap = Cinetor.tier == PerformanceTier.LOW
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (cheap) 1600 else 1100, easing = LinearEasing),
            repeatMode = if (cheap) RepeatMode.Reverse else RepeatMode.Restart,
        ),
        label = "shimmerProgress",
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

/** Draw-phase-only shimmer (no recomposition per frame). Clip before applying. */
fun Modifier.shimmer(state: ShimmerState?, base: Color, highlight: Color): Modifier = drawBehind {
    if (state == null) {
        drawRect(base)
        return@drawBehind
    }
    val p = state.progress.value
    if (state.cheap) {
        drawRect(lerp(base, highlight, p))
    } else {
        val w = size.width.coerceAtLeast(1f)
        val x = -w + p * 3f * w
        drawRect(
            Brush.linearGradient(
                colors = listOf(base, highlight, base),
                start = Offset(x - w * 0.6f, 0f),
                end = Offset(x, size.height),
            ),
        )
    }
}

/** Shimmer using theme skeleton colours and the nearest [ShimmerHost] clock. */
@Composable
fun Modifier.shimmer(): Modifier {
    val tokens = Cinetor.tokens
    return shimmer(LocalShimmer.current, tokens.skeletonBase, tokens.skeletonHighlight)
}
