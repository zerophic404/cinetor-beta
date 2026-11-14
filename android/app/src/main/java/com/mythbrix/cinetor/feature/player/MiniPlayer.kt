package com.mythbrix.cinetor.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.design.Cinetor
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Corner { TOP_START, TOP_END, BOTTOM_START, BOTTOM_END }

/** Pure mini-window maths (px). */
object MiniPlayerMath {
    /** Corner nearest to the window centre. */
    fun nearestCorner(x: Float, y: Float, boxW: Float, boxH: Float, areaW: Float, areaH: Float): Corner {
        val left = x + boxW / 2 < areaW / 2
        val top = y + boxH / 2 < areaH / 2
        return when {
            top && left -> Corner.TOP_START
            top -> Corner.TOP_END
            left -> Corner.BOTTOM_START
            else -> Corner.BOTTOM_END
        }
    }

    /** Top-left offset for [corner] inside the area (margins: [m] sides/top, [bottom] above the nav bar). */
    fun offsetFor(corner: Corner, boxW: Float, boxH: Float, areaW: Float, areaH: Float, m: Float, top: Float, bottom: Float): Offset {
        val x = if (corner == Corner.TOP_START || corner == Corner.BOTTOM_START) m else areaW - boxW - m
        val y = if (corner == Corner.TOP_START || corner == Corner.TOP_END) top else areaH - boxH - bottom
        return Offset(x, y)
    }

    /** Swipe off: more than half of the window pushed past the left/right edge. */
    fun shouldDismiss(x: Float, boxW: Float, areaW: Float): Boolean = x < -boxW / 2 || x > areaW - boxW / 2
}

/**
 * In-app floating player: draggable 16:9 window (~40 % width), snaps to the nearest corner,
 * tap = expand, swipe off a side = stop. Rest of the screen stays interactive.
 */
@Composable
fun MiniPlayer(
    screen: PlayerScreenState,
    ui: PlayerUiState,
    holder: PlayerStateHolder,
    video: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    var corner by rememberSaveable { mutableStateOf(Corner.BOTTOM_END) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val areaW = constraints.maxWidth.toFloat()
        val areaH = constraints.maxHeight.toFloat()
        val boxWdp = (maxWidth * 0.42f).coerceIn(160.dp, 320.dp)
        val boxW = with(density) { boxWdp.toPx() }
        val boxH = boxW * 9f / 16f
        val m = with(density) { 12.dp.toPx() }
        val top = with(density) { (insets.calculateTopPadding() + 64.dp).toPx() }
        val bottom = with(density) { (insets.calculateBottomPadding() + 96.dp).toPx() }
        val scope = rememberCoroutineScope()
        val target = MiniPlayerMath.offsetFor(corner, boxW, boxH, areaW, areaH, m, top, bottom)
        val pos = remember { Animatable(target, Offset.VectorConverter) }
        var dragging by remember { mutableStateOf(false) }
        LaunchedEffect(target, dragging) {
            if (!dragging) pos.animateTo(target, spring(dampingRatio = 0.8f, stiffness = 500f))
        }
        val fade = if (dragging) (1f - abs(pos.value.x - target.x) / (areaW / 2)).coerceIn(0.35f, 1f) else 1f
        Box(
            Modifier
                .offset { IntOffset(pos.value.x.roundToInt(), pos.value.y.roundToInt()) }
                .size(boxWdp, boxWdp * 9f / 16f)
                .graphicsLayer { alpha = fade }
                .shadow(12.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                .semantics { contentDescription = "Mini player" }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { holder.expand() })
                }
                .pointerInput(boxW, areaW, areaH) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            val p = pos.value
                            if (MiniPlayerMath.shouldDismiss(p.x, boxW, areaW)) {
                                dragging = false
                                holder.close()
                            } else {
                                corner = MiniPlayerMath.nearestCorner(p.x, p.y, boxW, boxH, areaW, areaH)
                                dragging = false
                            }
                        },
                        onDragCancel = { dragging = false },
                        onDrag = { change, amount ->
                            change.consume()
                            scope.launch { pos.snapTo(pos.value + amount) }
                        },
                    )
                },
        ) {
            video(Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f)),
            ) {
                val f = if (ui.durationMs > 0) (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth(f).height(3.dp).background(Cinetor.tokens.accent))
            }
            PlayerIconButton(
                if (ui.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (ui.playWhenReady) "Pause" else "Play",
                { holder.togglePlay() },
                modifier = Modifier.align(Alignment.Center),
                size = 38.dp, iconSize = 22.dp,
            )
            PlayerIconButton(
                Icons.Rounded.Close, "Close mini player", { holder.close() },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                size = 28.dp, iconSize = 16.dp,
            )
        }
    }
}
