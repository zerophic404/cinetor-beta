package com.mythbrix.cinetor.feature.comics

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Keeps the zoomed content covering the viewport (no empty borders). */
internal fun clampOffset(offset: Offset, scale: Float, size: IntSize, panY: Boolean): Offset {
    val maxX = (size.width * (scale - 1f)) / 2f
    val maxY = if (panY) (size.height * (scale - 1f)) / 2f else 0f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/**
 * Pinch + double-tap zoom with single taps reported (tap zones). Paged pages ([singleFingerPan] = true) pan with
 * one finger while zoomed; the webtoon list keeps one-finger vertical scrolling (only horizontal pan is applied).
 * At 1× nothing is consumed, so the pager / list scroll normally.
 */
@Composable
fun ZoomableBox(
    resetKey: Any,
    modifier: Modifier = Modifier,
    singleFingerPan: Boolean = true,
    onTap: (x: Float, width: Float) -> Unit,
    onZoomChanged: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    var scale by remember(resetKey) { mutableFloatStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(resetKey) {
                detectTapGestures(
                    onDoubleTap = { p ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                            onZoomChanged(false)
                        } else {
                            scale = DOUBLE_TAP_ZOOM
                            val c = Offset(size.width / 2f, size.height / 2f)
                            offset = clampOffset((c - p) * (DOUBLE_TAP_ZOOM - 1f), scale, size, singleFingerPan)
                            onZoomChanged(true)
                        }
                    },
                    onTap = { p -> onTap(p.x, size.width.toFloat()) },
                )
            }
            .pointerInput(resetKey, singleFingerPan) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        val pinch = fingers >= 2
                        if (pinch || (scale > 1.01f && singleFingerPan)) {
                            val zoom = if (pinch) event.calculateZoom() else 1f
                            val pan = event.calculatePan()
                            val was = scale > 1.01f
                            scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            offset = if (scale <= 1.01f) Offset.Zero else clampOffset(offset + pan, scale, size, singleFingerPan)
                            if (was != scale > 1.01f) onZoomChanged(scale > 1.01f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (scale > 1.01f) {
                            // Webtoon: horizontal pan only, vertical stays with the list (not consumed).
                            val pan = event.calculatePan()
                            offset = clampOffset(offset + Offset(pan.x, 0f), scale, size, false)
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    ) { content() }
}
