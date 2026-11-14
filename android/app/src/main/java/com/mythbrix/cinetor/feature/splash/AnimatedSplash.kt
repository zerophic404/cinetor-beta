package com.mythbrix.cinetor.feature.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.design.BrandRed
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.CinetorMark
import com.mythbrix.cinetor.design.CinetorTokens
import com.mythbrix.cinetor.design.WordmarkStyle

private const val WORD = "CINETOR"

/** 0..1 progress of [p] inside the window [start, start+len]. */
internal fun segment(p: Float, start: Float, len: Float): Float = ((p - start) / len).coerceIn(0f, 1f)

private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

/**
 * Compose intro after the system splash: glass tile pops, a red light sweeps across it, then
 * "CINETOR" reveals letter by letter (600 ms). LOW tier: a plain 300 ms fade.
 * All animated values are read in graphicsLayer/draw lambdas → no per-frame recomposition.
 */
@Composable
fun AnimatedSplash(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val low = Cinetor.tier == PerformanceTier.LOW
    val progress = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(if (low) 300 else 600, easing = LinearEasing))
        exit.animateTo(0f, tween(if (low) 120 else 220, easing = CinetorTokens.Motion.standard))
        finished()
    }
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = exit.value }
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .semantics { contentDescription = "Cinetor" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
            val tileShape = RoundedCornerShape(28.dp)
            Box(
                Modifier
                    .size(120.dp)
                    .graphicsLayer {
                        val p = progress.value
                        if (low) {
                            alpha = p
                        } else {
                            val t = easeOut(segment(p, 0f, 0.45f))
                            alpha = segment(p, 0f, 0.25f)
                            scaleX = 0.82f + 0.18f * t
                            scaleY = scaleX
                        }
                    }
                    .clip(tileShape)
                    .drawWithContent {
                        drawContent()
                        if (!low) {
                            val s = segment(progress.value, 0.25f, 0.5f)
                            if (s > 0f && s < 1f) {
                                val w = size.width
                                val x = -w + s * 2.5f * w
                                drawRect(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            BrandRed.copy(alpha = 0.55f),
                                            Color.White.copy(alpha = 0.35f),
                                            Color.Transparent,
                                        ),
                                        start = Offset(x, 0f),
                                        end = Offset(x + w * 0.7f, size.height),
                                    ),
                                )
                            }
                        }
                    },
            ) {
                CinetorMark(Modifier.fillMaxSize())
            }
            Row {
                WORD.forEachIndexed { i, ch ->
                    Text(
                        ch.toString(),
                        style = WordmarkStyle,
                        color = Color.White,
                        modifier = Modifier.graphicsLayer {
                            val p = progress.value
                            if (low) {
                                alpha = p
                            } else {
                                val t = easeOut(segment(p, 0.35f + i * 0.06f, 0.25f))
                                alpha = t
                                translationY = (1f - t) * 14.dp.toPx()
                            }
                        },
                    )
                }
            }
        }
    }
}
