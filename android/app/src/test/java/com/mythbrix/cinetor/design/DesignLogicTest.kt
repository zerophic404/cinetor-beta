package com.mythbrix.cinetor.design

import androidx.compose.ui.graphics.Color
import com.mythbrix.cinetor.core.perf.PerformanceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignLogicTest {
    private val eps = 1.5f / 255f

    @Test
    fun tint_blendsTowardAccentByStrength() {
        val source = Color(0xFF0000FF)
        val accent = Color(0xFFFF0000)
        val mixed = blendTint(source, accent, 0.25f)
        assertEquals(0.75f, mixed.red, eps)
        assertEquals(0f, mixed.green, eps)
        assertEquals(0.25f, mixed.blue, eps)
        assertEquals(1f, mixed.alpha, eps)
        assertEquals(accent, blendTint(source, accent, 0f))
        assertEquals(source, blendTint(source, accent, 1f))
        assertEquals(accent, blendTint(source, accent, -3f))
    }

    @Test
    fun tint_themeStrengthKeepsRedIdentity() {
        val blue = Color(0xFF1030E0)
        for (theme in AppTheme.entries) {
            val t = CinetorTokens.of(theme)
            val c = blendTint(blue, t.accent, t.tintStrength)
            assertTrue("$theme stays red-dominant", c.red > c.blue)
        }
    }

    @Test
    fun glass_lowTier_hasNoBlurEffect() {
        GlassLevel.entries.forEach { assertFalse(glassUsesLiveBlur(PerformanceTier.LOW, it)) }
        assertEquals(0.88f, fallbackFillAlpha(PerformanceTier.LOW, GlassLevel.SURFACE), 0f)
    }

    @Test
    fun glass_midBarsOnly_highAllOverlays() {
        assertTrue(glassUsesLiveBlur(PerformanceTier.MID, GlassLevel.BAR))
        assertTrue(glassUsesLiveBlur(PerformanceTier.MID, GlassLevel.SHEET))
        assertFalse(glassUsesLiveBlur(PerformanceTier.MID, GlassLevel.FLOATING))
        assertFalse(glassUsesLiveBlur(PerformanceTier.MID, GlassLevel.SURFACE))
        assertTrue(glassUsesLiveBlur(PerformanceTier.HIGH, GlassLevel.FLOATING))
        assertFalse(glassUsesLiveBlur(PerformanceTier.HIGH, GlassLevel.SURFACE))
    }
}
