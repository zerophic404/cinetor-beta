package com.mythbrix.cinetor.feature.player.gestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTarget : GestureTarget {
    override var positionMs: Long = 50_000
    override var durationMs: Long = 600_000
    var spd: Float = 1f
    override val speed: Float get() = spd
    override var locked: Boolean = false
    val seeksBy = mutableListOf<Long>()
    val seeksTo = mutableListOf<Long>()
    var toggles = 0
    var playToggles = 0
    var bright = 0.5f
    var vol = 0.5f
    var fill: Boolean? = null
    override fun seekBy(deltaMs: Long) { seeksBy += deltaMs }
    override fun seekTo(positionMs: Long) { seeksTo += positionMs }
    override fun setSpeed(speed: Float) { spd = speed }
    override fun togglePlay() { playToggles++ }
    override fun toggleControls() { toggles++ }
    override fun brightness(): Float = bright
    override fun setBrightness(value: Float) { bright = value }
    override fun volume(): Float = vol
    override fun setVolume(value: Float) { vol = value }
    override fun setFill(fill: Boolean) { this.fill = fill }
}

class GestureControllerTest {
    private var now = 0L
    private val t = FakeTarget()
    private val c = GestureController(t, clock = { now }, windowMs = 700)
    private val w = 900f
    private val h = 500f

    @Test
    fun doubleTap_accumulates() {
        c.onDoubleTap(850f, w) // right third
        assertEquals(10, c.ui.value.doubleTap?.totalSec)
        now = 300
        c.onTap(800f, w) // streak continues
        assertEquals(20, c.ui.value.doubleTap?.totalSec)
        now = 600
        c.onTap(820f, w)
        assertEquals(30, c.ui.value.doubleTap?.totalSec)
        assertEquals(listOf(10_000L, 10_000L, 10_000L), t.seeksBy)
        assertEquals(0, t.toggles)
        // Left side resets the streak and seeks back.
        now = 700
        c.onDoubleTap(50f, w)
        assertEquals(10, c.ui.value.doubleTap?.totalSec)
        assertEquals(-10_000L, t.seeksBy.last())
        // Window expired → a tap toggles controls again.
        now = 2_000
        c.onTap(50f, w)
        assertEquals(1, t.toggles)
        c.expireDoubleTap()
        assertNull(c.ui.value.doubleTap)
        // Center double tap = play/pause.
        c.onDoubleTap(450f, w)
        assertEquals(1, t.playToggles)
    }

    @Test
    fun hold_sets2x_releaseRestores() {
        t.spd = 1.25f
        c.onHoldStart()
        assertEquals(2f, t.speed)
        assertTrue(c.ui.value.holding)
        c.onPressEnd()
        assertEquals(1.25f, t.speed)
        assertFalse(c.ui.value.holding)
        // Plain press release without hold leaves speed alone.
        c.onPressEnd()
        assertEquals(1.25f, t.speed)
    }

    @Test
    fun verticalDrag_leftIsBrightness_rightIsVolume() {
        c.onDragStart(100f, w, h)
        assertTrue(c.onDrag(0f, -250f, 10f)) // half height up
        assertEquals(1f, t.bright, 0.001f)
        assertEquals(0.5f, t.vol, 0.001f)
        assertEquals(LevelKind.BRIGHTNESS, c.ui.value.level?.kind)
        c.onDragEnd()
        assertNull(c.ui.value.level)

        c.onDragStart(800f, w, h)
        c.onDrag(0f, 125f, 10f) // quarter height down
        assertEquals(0.25f, t.vol, 0.001f)
        assertEquals(1f, t.bright, 0.001f)
        assertEquals(LevelKind.VOLUME, c.ui.value.level?.kind)
        c.onDragEnd()
    }

    @Test
    fun horizontalDrag_clampsToDuration() {
        t.durationMs = 60_000
        t.positionMs = 50_000
        c.onDragStart(100f, w, h)
        assertFalse(c.onDrag(3f, 2f, 10f)) // under slop: not consumed
        c.onDrag(w, 0f, 10f) // full width = whole (short) video
        assertEquals(60_000L, c.ui.value.seekPreview?.targetMs)
        assertEquals(10_000L, c.ui.value.seekPreview?.deltaMs)
        c.onDragEnd()
        assertEquals(listOf(60_000L), t.seeksTo)
        // Backwards clamps at 0.
        c.onDragStart(800f, w, h)
        c.onDrag(-w * 3, 0f, 10f)
        c.onDragEnd()
        assertEquals(0L, t.seeksTo.last())
        assertEquals(90_000L, GestureMath.seekSpanMs(3_600_000))
    }

    @Test
    fun locked_ignoresGestures() {
        t.locked = true
        c.onDoubleTap(850f, w)
        c.onHoldStart()
        c.onDragStart(100f, w, h)
        assertFalse(c.onDrag(0f, -250f, 10f))
        c.onDragEnd()
        c.onPinchEnd(1.5f)
        assertTrue(t.seeksBy.isEmpty())
        assertTrue(t.seeksTo.isEmpty())
        assertEquals(1f, t.speed)
        assertEquals(0.5f, t.bright, 0.001f)
        assertNull(t.fill)
        // Single tap still reveals the unlock button.
        c.onTap(450f, w)
        assertEquals(1, t.toggles)
    }

    @Test
    fun pinch_togglesFill() {
        c.onPinchEnd(1.4f)
        assertEquals(true, t.fill)
        c.onPinchEnd(0.7f)
        assertEquals(false, t.fill)
    }
}
