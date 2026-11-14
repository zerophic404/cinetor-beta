package com.mythbrix.cinetor.feature.player.subs

import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.delay

/** Base subtitle size at 100 % (sp) for the Cinetor overlay. */
private const val BASE_SP = 18f

fun SubtitleStyle.fontFamily(): FontFamily = when (font) {
    SubFont.SERIF -> FontFamily.Serif
    SubFont.MONO -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

/**
 * Renders side-loaded cues with the sync offset. [positionMs] is polled every 50 ms so cue
 * changes don't wait for the player's 250 ms state tick.
 */
@Composable
fun SubtitleOverlay(subs: SideLoadedSubs?, offsetMs: Long, style: SubtitleStyle, positionMs: () -> Long, modifier: Modifier = Modifier) {
    if (subs == null) return
    val pos by rememberUpdatedState(positionMs)
    var text by remember(subs) { mutableStateOf("") }
    LaunchedEffect(subs, offsetMs) {
        while (true) {
            text = SubtitleSync.active(subs.cues, pos(), offsetMs).joinToString("\n") { it.text }
            delay(50)
        }
    }
    if (text.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val bottom = maxHeight * (style.bottomPercent / 100f)
        val sizeSp = (BASE_SP * style.sizePercent / 100f * (if (maxWidth > 600.dp) 1.25f else 1f)).sp
        val weight = if (style.font == SubFont.BOLD) FontWeight.Bold else FontWeight.Medium
        val color = Color(style.textColor.toInt())
        val base = TextStyle(
            fontSize = sizeSp,
            fontFamily = style.fontFamily(),
            fontWeight = weight,
            textAlign = TextAlign.Center,
            lineHeight = sizeSp * 1.2f,
            shadow = if (style.edge == SubEdge.SHADOW) Shadow(Color.Black, Offset(2f, 2f), 6f) else null,
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottom, start = 24.dp, end = 24.dp)
                .background(Color(style.bgArgb.toInt()), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            if (style.edge == SubEdge.OUTLINE) {
                Text(text, style = base.copy(color = Color.Black, drawStyle = Stroke(width = 4f)))
            }
            Text(text, style = base.copy(color = color))
        }
    }
}

/** Applies [style] to Media3's own SubtitleView (embedded / Media3-rendered tracks). */
@OptIn(UnstableApi::class)
fun applySubtitleStyle(view: PlayerView, style: SubtitleStyle, hidden: Boolean) {
    val sv: SubtitleView = view.subtitleView ?: return
    sv.visibility = if (hidden) android.view.View.GONE else android.view.View.VISIBLE
    val edge = when (style.edge) {
        SubEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
        SubEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubEdge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
    }
    val typeface = when (style.font) {
        SubFont.DEFAULT -> Typeface.DEFAULT
        SubFont.SERIF -> Typeface.SERIF
        SubFont.MONO -> Typeface.MONOSPACE
        SubFont.BOLD -> Typeface.DEFAULT_BOLD
    }
    sv.setApplyEmbeddedStyles(false)
    sv.setApplyEmbeddedFontSizes(false)
    sv.setStyle(
        CaptionStyleCompat(
            style.textColor.toInt(),
            style.bgArgb.toInt(),
            android.graphics.Color.TRANSPARENT,
            edge,
            android.graphics.Color.BLACK,
            typeface,
        ),
    )
    sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.sizePercent / 100f)
    sv.setBottomPaddingFraction(style.bottomPercent / 100f)
}
