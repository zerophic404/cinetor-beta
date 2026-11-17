package com.mythbrix.cinetor.design

import androidx.compose.foundation.border
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
private val SmallBarShape = RoundedCornerShape(CinetorTokens.Shape.xs)
private val MediumBarShape = RoundedCornerShape(CinetorTokens.Shape.sm)

@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = MediumBarShape,
) {
    Box(
        modifier
            .clip(shape)
            .shimmer()
            .border(CinetorTokens.Shape.strokeHairline, Cinetor.tokens.divider, shape),
    )
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
                SkeletonBox(Modifier.fillMaxWidth(fraction).height(lineHeight), SmallBarShape)
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
    val posterShape = Cinetor.posterShape
    ShimmerHost {
        Column(modifier.semantics { contentDescription = "Loading" }) {
            SkeletonBox(Modifier.padding(horizontal = S.s16).width(140.dp).height(16.dp), SmallBarShape)
            Spacer(Modifier.height(S.s12))
            Row(
                Modifier.padding(horizontal = S.s16),
                horizontalArrangement = Arrangement.spacedBy(S.s12),
            ) {
                repeat(count) {
                    SkeletonBox(Modifier.width(posterWidth).aspectRatio(2f / 3f), posterShape)
                }
            }
        }
    }
}

@Composable
fun SkeletonHero(modifier: Modifier = Modifier) {
    val heroShape = Cinetor.cardShape
    val buttonShape = Cinetor.buttonShape
    ShimmerHost {
        Box(modifier.fillMaxWidth().aspectRatio(4f / 5f).padding(S.s16)) {
            SkeletonBox(Modifier.matchParentSize(), heroShape)
            Column(
                Modifier.align(Alignment.BottomStart).padding(S.s20),
                verticalArrangement = Arrangement.spacedBy(S.s12),
            ) {
                SkeletonBox(Modifier.width(200.dp).height(28.dp), SmallBarShape)
                SkeletonBox(Modifier.width(150.dp).height(12.dp), SmallBarShape)
                Row(horizontalArrangement = Arrangement.spacedBy(S.s12)) {
                    SkeletonBox(Modifier.width(116.dp).height(44.dp), buttonShape)
                    SkeletonBox(Modifier.width(116.dp).height(44.dp), buttonShape)
                }
            }
        }
    }
}

@Composable
fun SkeletonDetails(modifier: Modifier = Modifier) {
    val buttonShape = Cinetor.buttonShape
    ShimmerHost {
        Column(modifier.fillMaxWidth()) {
            SkeletonBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f), RoundedCornerShape(0.dp))
            Column(Modifier.padding(S.s16), verticalArrangement = Arrangement.spacedBy(S.s12)) {
                SkeletonBox(Modifier.fillMaxWidth(0.7f).height(28.dp), SmallBarShape)
                Row(horizontalArrangement = Arrangement.spacedBy(S.s8)) {
                    repeat(4) { SkeletonBox(Modifier.width(56.dp).height(22.dp), RoundedCornerShape(CinetorTokens.Shape.pill)) }
                }
                SkeletonBox(Modifier.fillMaxWidth().height(48.dp), buttonShape)
                SkeletonText(lines = 4)
            }
        }
    }
}

@Composable
fun SkeletonEpisodeList(modifier: Modifier = Modifier, count: Int = 5) {
    val thumbShape = Cinetor.posterShape
    ShimmerHost {
        Column(modifier.padding(horizontal = S.s16), verticalArrangement = Arrangement.spacedBy(S.s16)) {
            repeat(count) {
                Row(horizontalArrangement = Arrangement.spacedBy(S.s12)) {
                    SkeletonBox(Modifier.width(144.dp).aspectRatio(16f / 9f), thumbShape)
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
                repeat(4) { SkeletonBox(Modifier.weight(1f).height(12.dp), SmallBarShape) }
            }
            repeat(channels) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(S.s8), verticalAlignment = Alignment.CenterVertically) {
                    SkeletonBox(Modifier.size(width = 72.dp, height = 48.dp), MediumBarShape)
                    widths[row % widths.size].forEach { wgt ->
                        SkeletonBox(Modifier.weight(wgt).height(48.dp), MediumBarShape)
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
                    SkeletonBox(Modifier.size(48.dp, 64.dp), MediumBarShape)
                    SkeletonText(lines = 2, modifier = Modifier.weight(1f))
                    SkeletonBox(Modifier.size(24.dp), CircleShape)
                }
            }
        }
    }
}

/** Three-column poster grid placeholder matching Cinetor Beta's grid skeleton. */
@Composable
fun SkeletonPosterGrid(modifier: Modifier = Modifier, rows: Int = 4) {
    val posterShape = Cinetor.posterShape
    ShimmerHost {
        Column(
            modifier.fillMaxWidth().padding(horizontal = S.s16, vertical = S.s8),
            verticalArrangement = Arrangement.spacedBy(S.s12),
        ) {
            repeat(rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(S.s12)) {
                    repeat(3) {
                        SkeletonBox(Modifier.weight(1f).aspectRatio(2f / 3f), posterShape)
                    }
                }
            }
        }
    }
}

/** Track / search result list placeholder with square artwork and dual text bars. */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, rows: Int = 8) {
    ShimmerHost {
        Column(
            modifier.fillMaxWidth().padding(horizontal = S.s16, vertical = S.s8),
            verticalArrangement = Arrangement.spacedBy(S.s12),
        ) {
            repeat(rows) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkeletonBox(Modifier.size(48.dp), MediumBarShape)
                    Spacer(Modifier.width(S.s12))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(S.s8)) {
                        SkeletonBox(Modifier.fillMaxWidth(0.62f).height(13.dp), SmallBarShape)
                        SkeletonBox(Modifier.fillMaxWidth(0.38f).height(10.dp), SmallBarShape)
                    }
                }
            }
        }
    }
}
