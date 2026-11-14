package com.mythbrix.cinetor.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.design.Cinetor
import kotlin.math.roundToInt

/** "1:05" / "1:02:03". Negative → 0. */
fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Signed delta label: "+0:45" / "-1:10". */
fun formatDelta(ms: Long): String = (if (ms < 0) "-" else "+") + formatTime(kotlin.math.abs(ms))

/** Pure: x on a bar of [width] px → position (clamped to 0..duration). */
fun fractionToMs(x: Float, width: Float, durationMs: Long): Long {
    if (width <= 0f || durationMs <= 0) return 0
    return ((x / width).coerceIn(0f, 1f) * durationMs).toLong()
}

/**
 * Seek bar: buffered layer, played layer, intro/chapter marks, thumb; drag shows a time bubble
 * and seeks on release, tap seeks immediately.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    marks: List<LongRange>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onScrubbing: (Long?) -> Unit = {},
) {
    val accent = Cinetor.tokens.accent
    var scrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableFloatStateOf(0f) }
    val seek by rememberUpdatedState(onSeek)
    val scrubCb by rememberUpdatedState(onScrubbing)
    val duration by rememberUpdatedState(durationMs)
    val shown = if (scrubbing) scrubMs.toLong() else positionMs
    BoxWithConstraints(modifier.fillMaxWidth().semantics { contentDescription = "Seek bar" }) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        if (scrubbing && durationMs > 0) {
            val bubbleW = with(LocalDensity.current) { 64.dp.toPx() }
            val x = (widthPx * shown / durationMs - bubbleW / 2).coerceIn(0f, (widthPx - bubbleW).coerceAtLeast(0f))
            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt(), 0) }
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(formatTime(shown), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
        Canvas(
            Modifier
                .padding(top = if (scrubbing) 30.dp else 0.dp)
                .fillMaxWidth()
                .height(28.dp)
                .align(Alignment.BottomStart)
                .pointerInput(Unit) {
                    detectTapGestures { o -> seek(fractionToMs(o.x, size.width.toFloat(), duration)) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o ->
                            scrubbing = true
                            scrubMs = fractionToMs(o.x, size.width.toFloat(), duration).toFloat()
                            scrubCb(scrubMs.toLong())
                        },
                        onDragEnd = {
                            seek(scrubMs.toLong())
                            scrubbing = false
                            scrubCb(null)
                        },
                        onDragCancel = {
                            scrubbing = false
                            scrubCb(null)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            scrubMs = fractionToMs(change.position.x, size.width.toFloat(), duration).toFloat()
                            scrubCb(scrubMs.toLong())
                        },
                    )
                },
        ) {
            val h = if (scrubbing) 6.dp.toPx() else 4.dp.toPx()
            val y = size.height / 2 - h / 2
            val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, y), Size(size.width, h), r)
            if (durationMs > 0) {
                val bw = size.width * (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
                drawRoundRect(Color.White.copy(alpha = 0.42f), Offset(0f, y), Size(bw, h), r)
                marks.forEach { m ->
                    val sx = size.width * (m.first.toFloat() / durationMs).coerceIn(0f, 1f)
                    val ex = size.width * (m.last.toFloat() / durationMs).coerceIn(0f, 1f)
                    drawRect(Color(0xFFF5A524).copy(alpha = 0.85f), Offset(sx, y), Size((ex - sx).coerceAtLeast(2f), h))
                }
                val px = size.width * (shown.toFloat() / durationMs).coerceIn(0f, 1f)
                drawRoundRect(accent, Offset(0f, y), Size(px, h), r)
                drawCircle(accent, radius = if (scrubbing) 9.dp.toPx() else 6.dp.toPx(), center = Offset(px, size.height / 2))
            }
        }
    }
}
