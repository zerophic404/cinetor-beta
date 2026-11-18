package com.mythbrix.cinetor.feature.player

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MiniPlayerMathTest {

    @Test
    fun freeDrag_clampsOnlyToSafeMargins_withoutSnappingToCorners() {
        val areaW = 1080f
        val areaH = 2200f
        val boxW = 420f
        val boxH = MiniPlayerMath.heightFor(boxW)
        val m = 12f
        val top = 120f
        val bottom = 180f

        // Dropped in the middle of the screen -> stays at exact (x, y) without corner-snapping!
        val mid = MiniPlayerMath.clampOffset(310f, 860f, boxW, boxH, areaW, areaH, m, top, bottom)
        assertEquals(310f, mid.x, 1e-3f)
        assertEquals(860f, mid.y, 1e-3f)

        // Dropped slightly into margins -> clamped strictly to safe bounds, not forced to opposite corner.
        val nearTopCenter = MiniPlayerMath.clampOffset(340f, 10f, boxW, boxH, areaW, areaH, m, top, bottom)
        assertEquals(340f, nearTopCenter.x, 1e-3f)
        assertEquals(top, nearTopCenter.y, 1e-3f)

        val nearBottomMid = MiniPlayerMath.clampOffset(290f, 2150f, boxW, boxH, areaW, areaH, m, top, bottom)
        assertEquals(290f, nearBottomMid.x, 1e-3f)
        assertEquals(areaH - boxH - bottom, nearBottomMid.y, 1e-3f)

        // Swipe past halfway off left/right edge still dismisses.
        assertTrue(MiniPlayerMath.shouldDismiss(-boxW * 0.6f, boxW, areaW))
        assertTrue(MiniPlayerMath.shouldDismiss(areaW - boxW * 0.4f, boxW, areaW))
        assertFalse(MiniPlayerMath.shouldDismiss(310f, boxW, areaW))
    }

    @Test
    fun positionFraction_roundTripsAndPersistsPerProfile() = runTest {
        val areaW = 1080f
        val areaH = 2200f
        val boxW = 450f
        val boxH = MiniPlayerMath.heightFor(boxW)
        val m = 12f
        val top = 100f
        val bottom = 200f

        val chosen = Offset(275f, 940f)
        val (xf, yf) = MiniPlayerMath.toPositionFraction(chosen, boxW, boxH, areaW, areaH, m, top, bottom)
        val restored = MiniPlayerMath.fromPositionFraction(xf, yf, boxW, boxH, areaW, areaH, m, top, bottom)
        assertEquals(chosen.x, restored.x, 0.5f)
        assertEquals(chosen.y, restored.y, 0.5f)

        val sizes = InMemoryMiniSizeStore()
        var profile = "default"
        val pre = com.mythbrix.cinetor.core.player.MediaPlaybackPreloader(
            scope = backgroundScope,
            playerProvider = { com.mythbrix.cinetor.core.player.FakeCinetorPlayer() },
            clock = { testScheduler.currentTime },
        )
        val holder = PlayerStateHolder(
            scope = backgroundScope,
            preloader = pre,
            profileId = { profile },
            clock = { testScheduler.currentTime },
            miniSizes = sizes,
        )
        holder.setMiniPosition(xf, yf)
        assertEquals(xf, holder.miniPosition.value!!.first, 1e-4f)
        assertEquals(yf, holder.miniPosition.value!!.second, 1e-4f)

        profile = "kids"
        holder.reloadMiniFraction()
        assertEquals(null, holder.miniPosition.value)

        profile = "default"
        holder.reloadMiniFraction()
        assertEquals(xf, holder.miniPosition.value!!.first, 1e-4f)
        assertEquals(yf, holder.miniPosition.value!!.second, 1e-4f)
    }
}
