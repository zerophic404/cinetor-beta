package com.mythbrix.cinetor.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val BrandRed = Color(0xFFFF2D3B)
val BrandRedDeep = Color(0xFFB00610)

/** Cinetor Beta 100-unit Aperture iris geometry: 3 blades framing a central play triangle. */
private object AperturePaths {
    val BLADES = listOf(
        "M42.50,62.99L19.72,76.14A40,40 0,0 1,42.50,10.71L42.50,37.01Z",
        "M42.50,37.01L42.50,10.71A40,40 0,0 1,87.78,63.15L65.00,50.00Z",
        "M65.00,50.00L87.78,63.15A40,40 0,0 1,19.72,76.14L42.50,62.99Z",
    )
    const val PLAY = "M65.00,50.00L42.50,62.99L42.50,37.01Z"
    val DEFAULT_BLADE_COLORS = listOf(
        Color(0xFFE11D2E),
        Color(0xFFC8102E),
        Color(0xFFFF2D3F),
    )
    const val SEAM = 3f
}

/**
 * Cinetor Beta 3-blade camera iris Aperture mark framing a central white play opening.
 * [turnProvider] and [litProvider] are evaluated inside `graphicsLayer` and `Canvas` draw scopes
 * so animated intros run with zero per-frame recomposition.
 */
@Composable
fun ApertureMark(
    modifier: Modifier = Modifier,
    tint: Color = BrandRed,
    turnProvider: () -> Float = { 1f },
    litProvider: () -> Float = { 1f },
) {
    val blades = remember { AperturePaths.BLADES.map { PathParser().parsePathString(it).toPath() } }
    val play = remember { PathParser().parsePathString(AperturePaths.PLAY).toPath() }
    val bladeColors = remember(tint) {
        if (tint == BrandRed) {
            AperturePaths.DEFAULT_BLADE_COLORS
        } else {
            listOf(
                tint,
                lerp(tint, Color.Black, 0.16f),
                lerp(tint, Color.White, 0.10f),
            )
        }
    }
    val seam = Cinetor.tokens.bg
    Canvas(
        modifier.graphicsLayer {
            val t = turnProvider().coerceIn(0f, 1f)
            rotationZ = -120f * (1f - t)
            val s = 0.72f + 0.28f * t
            scaleX = s
            scaleY = s
        },
    ) {
        val s = size.minDimension
        if (s <= 0f) return@Canvas
        val ox = (size.width - s) / 2f
        val oy = (size.height - s) / 2f
        val k = s / 100f
        val playAlpha = litProvider().coerceIn(0f, 1f)
        translate(left = ox, top = oy) {
            scale(scaleX = k, scaleY = k, pivot = Offset.Zero) {
                blades.forEachIndexed { i, blade ->
                    drawPath(blade, bladeColors[i])
                    drawPath(
                        path = blade,
                        color = seam,
                        style = Stroke(width = AperturePaths.SEAM, join = StrokeJoin.Round),
                    )
                }
                if (playAlpha > 0f) {
                    drawPath(play, Color.White, alpha = playAlpha)
                }
            }
        }
    }
}

/** Credits-style Cinetor wordmark: `CINE` Light + `TOR` Bold tracked at `0.34.em`. */
@Composable
fun CinetorWordmark(
    fontSize: TextUnit = 16.sp,
    modifier: Modifier = Modifier,
    color: Color = Cinetor.tokens.textPrimary,
    letterSpacing: TextUnit = 0.34.em,
) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append("CINE") }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("TOR") }
        },
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        letterSpacing = letterSpacing,
        maxLines = 1,
    )
}

/** Header lockup pairing the 3-blade [ApertureMark] with the `CINE`/`TOR` wordmark. */
@Composable
fun CinetorHeaderBrand(
    modifier: Modifier = Modifier,
    markSize: Dp = 24.dp,
    fontSize: TextUnit = 16.sp,
) {
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = "Cinetor" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ApertureMark(modifier = Modifier.size(markSize))
        CinetorWordmark(fontSize = fontSize)
    }
}

/** Logo "Noir Red": black glass tile, red C-ring open to the right, white play triangle. */
@Composable
fun CinetorMark(modifier: Modifier = Modifier, showTile: Boolean = true) {
    Canvas(modifier) {
        val s = size.minDimension
        val origin = Offset((size.width - s) / 2f, (size.height - s) / 2f)
        if (showTile) {
            val r = CornerRadius(s * CinetorTokens.Shape.tileRadiusRatio)
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(Color(0xFF1A1A1A), Color(0xFF050505)), origin.y, origin.y + s),
                topLeft = origin, size = Size(s, s), cornerRadius = r,
            )
            drawRoundRect(
                brush = Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x08FFFFFF)), origin, origin + Offset(s, s)),
                topLeft = origin, size = Size(s, s), cornerRadius = r, style = Stroke(width = s * 0.012f),
            )
        }
        val ringStroke = s * 0.085f
        val ringSize = s * 0.56f
        val ringTopLeft = origin + Offset((s - ringSize) / 2f, (s - ringSize) / 2f)
        drawArc(
            brush = Brush.linearGradient(listOf(BrandRed, BrandRedDeep), ringTopLeft, ringTopLeft + Offset(ringSize, ringSize)),
            startAngle = 40f, sweepAngle = 280f, useCenter = false,
            topLeft = ringTopLeft, size = Size(ringSize, ringSize),
            style = Stroke(width = ringStroke, cap = StrokeCap.Round),
        )
        val c = origin + Offset(s / 2f, s / 2f)
        val t = s * 0.12f
        val play = Path().apply {
            moveTo(c.x - t * 0.7f, c.y - t)
            lineTo(c.x + t * 1.0f, c.y)
            lineTo(c.x - t * 0.7f, c.y + t)
            close()
        }
        drawPath(play, Color.White)
    }
}
