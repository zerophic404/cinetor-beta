package com.mythbrix.cinetor.feature.splash

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.design.ApertureMark
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.CinetorWordmark
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_SPLASH_MS = 1_350L

/** 0..1 progress of [p] inside the window [start, start+len]. */
internal fun segment(p: Float, start: Float, len: Float): Float = ((p - start) / len).coerceIn(0f, 1f)

/**
 * Cinetor Beta Aperture intro: 3 iris blades twist into place, the center play opening illuminates,
 * `CINETOR` (`CINE` Light + `TOR` Bold) rises into focus, and a thin cinema-red timeline fills while
 * `HOME` pre-warms. Exits smoothly as soon as [ready] is true or at the hard [MAX_SPLASH_MS] cap so
 * offline devices and CI emulators never block. All per-frame values are read in `graphicsLayer` /
 * `Canvas` draw lambdas -> zero per-frame recomposition.
 */
@Composable
fun AnimatedSplash(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    ready: Boolean = true,
) {
    val tokens = Cinetor.tokens
    val low = Cinetor.tier == PerformanceTier.LOW || Cinetor.prefs.reduceMotion
    val drawn = remember { Animatable(if (low) 1f else 0f) }
    val lit = remember { Animatable(if (low) 1f else 0f) }
    val word = remember { Animatable(if (low) 1f else 0f) }
    val timeline = remember { Animatable(if (low) 0.35f else 0f) }
    val exit = remember { Animatable(1f) }
    val isReady by rememberUpdatedState(ready)
    val finished by rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        val startMs = SystemClock.elapsedRealtime()
        if (!low) {
            launch { drawn.animateTo(1f, tween(480, easing = FastOutSlowInEasing)) }
            launch {
                delay(280)
                lit.animateTo(1f, tween(200))
            }
            launch {
                delay(340)
                word.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
            }
        }
        val creep = launch {
            if (!low) delay(320)
            timeline.animateTo(0.84f, tween(if (low) 650 else 850, easing = LinearOutSlowInEasing))
        }

        val minHoldMs = if (low) 220L else 560L
        delay(minHoldMs)

        val exitFadeMs = if (low) 100 else 150
        val finishBarMs = if (low) 0 else 80
        val remainingForReady = (MAX_SPLASH_MS - (SystemClock.elapsedRealtime() - startMs) - exitFadeMs - finishBarMs)
            .coerceAtLeast(0L)
        if (!isReady && remainingForReady > 0L) {
            withTimeoutOrNull(remainingForReady) {
                snapshotFlow { isReady }.first { it }
            }
        }

        creep.cancel()
        if (finishBarMs > 0) {
            timeline.animateTo(1f, tween(finishBarMs))
        } else {
            timeline.snapTo(1f)
        }
        exit.animateTo(0f, tween(exitFadeMs, easing = CinetorTokens.Motion.standard))
        finished()
    }

    val spillAlpha = if (tokens.isLight) 0.10f else 0.18f
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = exit.value }
            .background(tokens.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .semantics { contentDescription = "Cinetor" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = lit.value },
        ) {
            val center = Offset(size.width * 0.5f, size.height * 0.46f)
            val radius = (size.minDimension * 0.62f).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tokens.accent.copy(alpha = spillAlpha), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ApertureMark(
                modifier = Modifier.size(116.dp),
                turnProvider = { drawn.value },
                litProvider = { lit.value },
            )
            Spacer(Modifier.height(22.dp))
            CinetorWordmark(
                fontSize = 26.sp,
                color = tokens.textPrimary,
                modifier = Modifier.graphicsLayer {
                    val w = word.value
                    alpha = w
                    translationY = (1f - w) * 10.dp.toPx()
                },
            )
            Spacer(Modifier.height(28.dp))
            Canvas(
                Modifier
                    .width(120.dp)
                    .height(6.dp)
                    .graphicsLayer { alpha = word.value },
            ) {
                val y = size.height * 0.5f
                val strokePx = 3.dp.toPx()
                drawLine(
                    color = tokens.divider,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round,
                )
                val endX = size.width * timeline.value.coerceIn(0f, 1f)
                if (endX > 0f) {
                    drawLine(
                        color = tokens.accent,
                        start = Offset(0f, y),
                        end = Offset(endX, y),
                        strokeWidth = strokePx,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
