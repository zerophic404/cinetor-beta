package com.mythbrix.cinetor.feature.player.gestures

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.feature.player.AspectMode
import com.mythbrix.cinetor.feature.player.PlayerScreenState
import com.mythbrix.cinetor.feature.player.PlayerStateHolder
import com.mythbrix.cinetor.feature.player.findActivity
import com.mythbrix.cinetor.feature.player.formatDelta
import com.mythbrix.cinetor.feature.player.formatTime
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Gesture layer over the video (Task 3.4) + its feedback (double-tap ripple, 2× pill, level bar,
 * seek preview). Brightness override is restored when the layer leaves composition.
 */
@Composable
fun PlayerGestures(
    holder: PlayerStateHolder,
    screen: PlayerScreenState,
    ui: PlayerUiState,
    modifier: Modifier,
    onToggleControls: () -> Unit,
    seekStepMs: Long = 10_000,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val brightness = remember(activity) { BrightnessController(activity) }
    val volume = remember(context) { VolumeController(context) }
    DisposableEffect(brightness) { onDispose { brightness.restore() } }

    val screenRef = rememberUpdatedState(screen)
    val uiRef = rememberUpdatedState(ui)
    val toggleRef = rememberUpdatedState(onToggleControls)
    val stepRef = rememberUpdatedState(seekStepMs)
    val controller = remember(holder) {
        GestureController(
            target = object : GestureTarget {
                override val positionMs: Long get() = uiRef.value.positionMs
                override val durationMs: Long get() = uiRef.value.durationMs
                override val speed: Float get() = uiRef.value.speed
                override val locked: Boolean get() = screenRef.value.locked
                override fun seekBy(deltaMs: Long) { holder.seekBy(deltaMs) }
                override fun seekTo(positionMs: Long) { holder.seekTo(positionMs) }
                override fun setSpeed(speed: Float) { holder.setSpeed(speed) }
                override fun togglePlay() = holder.togglePlay()
                override fun toggleControls() = toggleRef.value()
                override fun brightness(): Float = brightness.get()
                override fun setBrightness(value: Float) = brightness.set(value)
                override fun volume(): Float = volume.get()
                override fun setVolume(value: Float) = volume.set(value)
                override fun setFill(fill: Boolean) = holder.setAspect(if (fill) AspectMode.FILL else AspectMode.FIT)
            },
            stepMs = { stepRef.value },
            windowMs = 1_000,
        )
    }
    val g by controller.ui.collectAsStateWithLifecycle()
    LaunchedEffect(g.doubleTap?.stamp) {
        if (g.doubleTap != null) {
            delay(1_050)
            controller.expireDoubleTap()
        }
    }

    Box(
        modifier
            .pointerInput(controller) {
                detectTapGestures(
                    onDoubleTap = { controller.onDoubleTap(it.x, size.width.toFloat()) },
                    onTap = { controller.onTap(it.x, size.width.toFloat()) },
                    onLongPress = { controller.onHoldStart() },
                    onPress = {
                        tryAwaitRelease()
                        controller.onPressEnd()
                    },
                )
            }
            .pointerInput(controller) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    controller.onDragStart(down.position.x, size.width.toFloat(), size.height.toFloat())
                    var total = Offset.Zero
                    var zoom = 1f
                    var multi = false
                    var consumed = false
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2) {
                            multi = true
                            zoom *= event.calculateZoom()
                            event.changes.forEach { it.consume() }
                        } else if (!multi) {
                            val ch = event.changes.first { it.pressed }
                            total += ch.positionChange()
                            if (controller.onDrag(total.x, total.y, slop)) {
                                consumed = true
                                ch.consume()
                            }
                        }
                    }
                    if (multi) controller.onPinchEnd(zoom)
                    if (consumed || multi) Unit
                    controller.onDragEnd()
                }
            },
    ) {
        GestureFeedback(g)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.GestureFeedback(g: GestureUi) {
    val dt = g.doubleTap
    if (dt != null && dt.side != TapSide.CENTER) {
        val left = dt.side == TapSide.LEFT
        Box(
            Modifier
                .align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.36f)
                .clip(
                    if (left) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                    else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50),
                )
                .background(Color.White.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (left) "« ${dt.totalSec}s" else "${dt.totalSec}s »",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            )
        }
    }
    AnimatedVisibility(g.holding, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)) {
        Text(
            "2× ▸▸",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
    g.level?.let { lv ->
        Column(
            Modifier
                .align(if (lv.kind == LevelKind.BRIGHTNESS) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 40.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (lv.kind == LevelKind.BRIGHTNESS) Icons.Rounded.BrightnessMedium else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp),
            )
            Box(Modifier.width(6.dp).height(110.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.25f))) {
                Box(
                    Modifier.align(Alignment.BottomCenter).width(6.dp).fillMaxHeight(lv.value.coerceIn(0f, 1f))
                        .background(Cinetor.tokens.accent),
                )
            }
            Text("${(lv.value * 100).roundToInt()}%", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
    g.seekPreview?.let { sp ->
        Column(
            Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(formatTime(sp.targetMs), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(formatDelta(sp.deltaMs), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
        }
    }
}
