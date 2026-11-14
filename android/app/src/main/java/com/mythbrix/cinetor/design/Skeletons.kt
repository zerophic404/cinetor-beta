package com.mythbrix.cinetor.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val S = CinetorTokens.Spacing
private val Small = RoundedCornerShape(CinetorTokens.Shape.xs)
private val Card = RoundedCornerShape(CinetorTokens.Shape.card)

@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(CinetorTokens.Shape.sm)) {
    Box(modifier.clip(shape).shimmer())
}

@Composable
fun SkeletonText(
    lines: Int,
    modifier: Modifier = Modifier,
    lineHeight: Dp = 12.dp,
    lastLineFraction: Float = 0.6f,
) {
    ShimmerHost {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(S.s8)) {
            repeat(lines) { i ->
                val fraction = if (i == lines - 1 && lines > 1) lastLineFraction else 1f
                SkeletonBox(Modifier.fillMaxWidth(fraction).height(lineHeight), Small)
            }
        }
    }
}

@Composable
fun SkeletonPosterRow(
    modifier: Modifier = Modifier,
    count: Int = 6,
    posterWidth: Dp = 112.dp,
) {
    ShimmerHost {
        Column(modifier.semantics { contentDescription = "Loading" }) {
            SkeletonBox(Modifier.padding(horizontal = S.s16).width(140.dp).height(16.dp), Small)
            Spacer(Modifier.height(S.s12))
            Row(
                Modifier.padding(horizontal = S.s16),
                horizontalArrangement = Arrangement.spacedBy(S.s12),
            ) {
                repeat(count) {
                    SkeletonBox(Modifier.width(posterWidth).aspectRatio(2f / 3f), Card)
                }
            }
        }
    }
}

@Composable
fun SkeletonHero(modifier: Modifier = Modifier) {
    ShimmerHost {
        Box(modifier.fillMaxWidth().aspectRatio(4f / 5f).padding(S.s16)) {
            SkeletonBox(Modifier.matchParentSize(), RoundedCornerShape(CinetorTokens.Shape.lg))
            Column(
                Modifier.align(Alignment.BottomStart).padding(S.s20),
                verticalArrangement = Arrangement.spacedBy(S.s12),
            ) {
                SkeletonBox(Modifier.width(200.dp).height(28.dp), Small)
                SkeletonBox(Modifier.width(150.dp).height(12.dp), Small)
                Row(horizontalArrangement = Arrangement.spacedBy(S.s12)) {
                    SkeletonBox(Modifier.width(110.dp).height(44.dp), RoundedCornerShape(CinetorTokens.Shape.pill))
                    SkeletonBox(Modifier.width(110.dp).height(44.dp), RoundedCornerShape(CinetorTokens.Shape.pill))
                }
            }
        }
    }
}

@Composable
fun SkeletonDetails(modifier: Modifier = Modifier) {
    ShimmerHost {
        Column(modifier.fillMaxWidth()) {
            SkeletonBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f), RoundedCornerShape(0.dp))
            Column(Modifier.padding(S.s16), verticalArrangement = Arrangement.spacedBy(S.s12)) {
                SkeletonBox(Modifier.fillMaxWidth(0.7f).height(28.dp), Small)
                Row(horizontalArrangement = Arrangement.spacedBy(S.s8)) {
                    repeat(4) { SkeletonBox(Modifier.width(56.dp).height(22.dp), RoundedCornerShape(CinetorTokens.Shape.pill)) }
                }
                SkeletonBox(Modifier.fillMaxWidth().height(48.dp), RoundedCornerShape(CinetorTokens.Shape.md))
                SkeletonText(lines = 4)
            }
        }
    }
}

@Composable
fun SkeletonEpisodeList(modifier: Modifier = Modifier, count: Int = 5) {
    ShimmerHost {
        Column(modifier.padding(horizontal = S.s16), verticalArrangement = Arrangement.spacedBy(S.s16)) {
            repeat(count) {
                Row(horizontalArrangement = Arrangement.spacedBy(S.s12)) {
                    SkeletonBox(Modifier.width(144.dp).aspectRatio(16f / 9f), Card)
                    SkeletonText(lines = 3, modifier = Modifier.weight(1f).padding(top = S.s4))
                }
            }
        }
    }
}

/** TV guide "skeleton chart": time ruler + channel column + programme blocks of varied widths. */
@Composable
fun SkeletonEpgGrid(modifier: Modifier = Modifier, channels: Int = 7) {
    val widths = listOf(
        listOf(1.4f, 0.8f, 1.2f), listOf(0.9f, 1.6f, 0.9f), listOf(2f, 1.1f),
        listOf(1f, 1f, 1.3f), listOf(0.7f, 1.5f, 1f), listOf(1.8f, 1.2f), listOf(1.1f, 0.9f, 1.4f),
    )
    ShimmerHost {
        Column(modifier.padding(S.s16), verticalArrangement = Arrangement.spacedBy(S.s8)) {
            Row(horizontalArrangement = Arrangement.spacedBy(S.s8)) {
                Spacer(Modifier.width(72.dp))
                repeat(4) { SkeletonBox(Modifier.weight(1f).height(12.dp), Small) }
            }
            repeat(channels) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(S.s8), verticalAlignment = Alignment.CenterVertically) {
                    SkeletonBox(Modifier.size(width = 72.dp, height = 48.dp), RoundedCornerShape(CinetorTokens.Shape.sm))
                    widths[row % widths.size].forEach { wgt ->
                        SkeletonBox(Modifier.weight(wgt).height(48.dp), RoundedCornerShape(CinetorTokens.Shape.sm))
                    }
                }
            }
        }
    }
}

@Composable
fun SkeletonChapterList(modifier: Modifier = Modifier, count: Int = 8) {
    ShimmerHost {
        Column(modifier.padding(horizontal = S.s16), verticalArrangement = Arrangement.spacedBy(S.s12)) {
            repeat(count) {
                Row(horizontalArrangement = Arrangement.spacedBy(S.s12), verticalAlignment = Alignment.CenterVertically) {
                    SkeletonBox(Modifier.size(48.dp, 64.dp), RoundedCornerShape(CinetorTokens.Shape.sm))
                    SkeletonText(lines = 2, modifier = Modifier.weight(1f))
                    SkeletonBox(Modifier.size(24.dp), CircleShape)
                }
            }
        }
    }
}
