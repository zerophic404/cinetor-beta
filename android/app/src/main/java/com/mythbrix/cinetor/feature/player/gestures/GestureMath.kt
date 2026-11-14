package com.mythbrix.cinetor.feature.player.gestures

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

/** Which third of the player a tap landed in. */
enum class TapSide { LEFT, CENTER, RIGHT }

/** Axis a drag locked onto after the touch slop. */
enum class DragAxis { NONE, HORIZONTAL, VERTICAL }

/** What a vertical drag controls. */
enum class LevelKind { BRIGHTNESS, VOLUME }

/** Pure gesture maths (no Android). */
object GestureMath {
    /** Allowed double-tap seek steps (settings), seconds. */
    val SEEK_STEPS_SEC = listOf(5, 10, 15, 30)

    fun sideOf(x: Float, width: Float): TapSide = when {
        width <= 0f -> TapSide.CENTER
        x < width / 3f -> TapSide.LEFT
        x > width * 2f / 3f -> TapSide.RIGHT
        else -> TapSide.CENTER
    }

    /** Vertical drags: left half = brightness, right half = volume. */
    fun levelKindOf(x: Float, width: Float): LevelKind = if (x < width / 2f) LevelKind.BRIGHTNESS else LevelKind.VOLUME

    fun axisOf(dx: Float, dy: Float, slop: Float): DragAxis = when {
        abs(dx) < slop && abs(dy) < slop -> DragAxis.NONE
        abs(dx) > abs(dy) -> DragAxis.HORIZONTAL
        else -> DragAxis.VERTICAL
    }

    /** Drag up raises the level; a full-height drag spans 0..1. [dy] is screen-space (down = +). */
    fun levelAfterDrag(start: Float, dy: Float, height: Float): Float {
        if (height <= 0f) return start.coerceIn(0f, 1f)
        return (start - dy / height).coerceIn(0f, 1f)
    }

    /** Full-width horizontal drag = 90 s (or the whole video if shorter). */
    fun seekSpanMs(durationMs: Long): Long = if (durationMs in 1 until 90_000) durationMs else 90_000

    /** Horizontal drag → target position, clamped to [0, duration]. */
    fun seekAfterDrag(startMs: Long, dx: Float, width: Float, durationMs: Long): Long {
        if (width <= 0f) return startMs
        val delta = (dx / width * seekSpanMs(durationMs)).toLong()
        val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return (startMs + delta).coerceIn(0, max)
    }

    /** Pinch result: >1.15 → fill, <0.87 → fit, else unchanged (null). */
    fun pinchToFill(zoom: Float): Boolean? = when {
        zoom > 1.15f -> true
        zoom < 0.87f -> false
        else -> null
    }
}

/** Gesture settings (Settings → Playback): double-tap step, hold speed, gestures on/off. */
data class GesturePrefs(val stepSec: Int = 10, val holdSpeed: Float = 2f, val enabled: Boolean = true)

data class DoubleTapUi(val side: TapSide, val totalSec: Int, val stamp: Long)
data class LevelUi(val kind: LevelKind, val value: Float)
data class SeekPreviewUi(val targetMs: Long, val deltaMs: Long)

data class GestureUi(
    val doubleTap: DoubleTapUi? = null,
    val holding: Boolean = false,
    val level: LevelUi? = null,
    val seekPreview: SeekPreviewUi? = null,
)

/** Everything gestures may do to the player/system (implemented by the UI layer; fakes in tests). */
interface GestureTarget {
    val positionMs: Long
    val durationMs: Long
    val speed: Float
    val locked: Boolean
    fun seekBy(deltaMs: Long)
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun togglePlay()
    fun toggleControls()
    fun brightness(): Float
    fun setBrightness(value: Float)
    fun volume(): Float
    fun setVolume(value: Float)
    fun setFill(fill: Boolean)
}

/**
 * Gesture state machine (pure): double-tap ±step accumulating (further taps on the same side within
 * [windowMs] add a step), hold = 2× while pressed, vertical drag = brightness/volume, horizontal
 * drag = seek preview (seeks on release), pinch = fill/fit, single tap = toggle controls.
 * Locked: everything ignored except single tap (which reveals the unlock button).
 */
class GestureController(
    private val target: GestureTarget,
    private val clock: () -> Long = System::currentTimeMillis,
    private val stepMs: () -> Long = { 10_000 },
    private val windowMs: Long = 700,
    /** Settings → Playback → hold speed (default 2×). */
    private val holdSpeed: () -> Float = { 2f },
    /** Settings → Playback → gestures; off = only single tap (toggle controls) works. */
    private val enabled: () -> Boolean = { true },
) {
    private val _ui = MutableStateFlow(GestureUi())
    val ui: StateFlow<GestureUi> = _ui.asStateFlow()

    private var speedBeforeHold: Float? = null
    private var dragAxis = DragAxis.NONE
    private var dragStartX = 0f
    private var dragW = 0f
    private var dragH = 0f
    private var dragKind = LevelKind.VOLUME
    private var levelStart = 0f
    private var seekStart = 0L

    /** Double tap on [x] of a [width]-px wide surface. */
    fun onDoubleTap(x: Float, width: Float) {
        if (target.locked) return
        if (!enabled()) {
            target.toggleControls()
            return
        }
        val side = GestureMath.sideOf(x, width)
        if (side == TapSide.CENTER) {
            target.togglePlay()
            return
        }
        step(side)
    }

    /** Single tap: continues a double-tap streak on the same side, else toggles the controls. */
    fun onTap(x: Float, width: Float) {
        val streak = _ui.value.doubleTap
        if (!target.locked && enabled() && streak != null && clock() - streak.stamp <= windowMs) {
            val side = GestureMath.sideOf(x, width)
            if (side == streak.side) {
                step(side)
                return
            }
        }
        target.toggleControls()
    }

    private fun step(side: TapSide) {
        val now = clock()
        val step = stepMs()
        val prev = _ui.value.doubleTap
        val total = if (prev != null && prev.side == side && now - prev.stamp <= windowMs) prev.totalSec + (step / 1000).toInt() else (step / 1000).toInt()
        target.seekBy(if (side == TapSide.LEFT) -step else step)
        _ui.update { it.copy(doubleTap = DoubleTapUi(side, total, now)) }
    }

    /** UI calls after the ripple has shown long enough. */
    fun expireDoubleTap() {
        val dt = _ui.value.doubleTap ?: return
        if (clock() - dt.stamp >= windowMs) _ui.update { it.copy(doubleTap = null) }
    }

    fun onHoldStart() {
        if (target.locked || !enabled() || speedBeforeHold != null || dragAxis != DragAxis.NONE) return
        speedBeforeHold = target.speed
        target.setSpeed(holdSpeed())
        _ui.update { it.copy(holding = true) }
    }

    /** Finger lifted (any press). Restores speed after a hold. */
    fun onPressEnd() {
        val prev = speedBeforeHold ?: return
        speedBeforeHold = null
        target.setSpeed(prev)
        _ui.update { it.copy(holding = false) }
    }

    fun onDragStart(x: Float, width: Float, height: Float) {
        dragAxis = DragAxis.NONE
        dragStartX = x
        dragW = width
        dragH = height
    }

    /** Total drag since start; returns true when the gesture is consumed (axis locked). */
    fun onDrag(totalDx: Float, totalDy: Float, slop: Float): Boolean {
        if (target.locked || !enabled() || speedBeforeHold != null) return false
        if (dragAxis == DragAxis.NONE) {
            dragAxis = GestureMath.axisOf(totalDx, totalDy, slop)
            when (dragAxis) {
                DragAxis.NONE -> return false
                DragAxis.VERTICAL -> {
                    dragKind = GestureMath.levelKindOf(dragStartX, dragW)
                    levelStart = if (dragKind == LevelKind.BRIGHTNESS) target.brightness() else target.volume()
                }
                DragAxis.HORIZONTAL -> seekStart = target.positionMs
            }
        }
        when (dragAxis) {
            DragAxis.VERTICAL -> {
                val v = GestureMath.levelAfterDrag(levelStart, totalDy, dragH)
                if (dragKind == LevelKind.BRIGHTNESS) target.setBrightness(v) else target.setVolume(v)
                _ui.update { it.copy(level = LevelUi(dragKind, v)) }
            }
            DragAxis.HORIZONTAL -> {
                val t = GestureMath.seekAfterDrag(seekStart, totalDx, dragW, target.durationMs)
                _ui.update { it.copy(seekPreview = SeekPreviewUi(t, t - seekStart)) }
            }
            DragAxis.NONE -> Unit
        }
        return true
    }

    fun onDragEnd() {
        if (dragAxis == DragAxis.HORIZONTAL) {
            _ui.value.seekPreview?.let { target.seekTo(it.targetMs) }
        }
        dragAxis = DragAxis.NONE
        _ui.update { it.copy(level = null, seekPreview = null) }
    }

    fun onPinchEnd(zoom: Float) {
        if (target.locked || !enabled()) return
        GestureMath.pinchToFill(zoom)?.let { target.setFill(it) }
    }
}
