package com.mythbrix.cinetor.design

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

val BrandRed = Color(0xFFFF2D3B)
val BrandRedDeep = Color(0xFFB00610)

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
