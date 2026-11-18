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
    fun tint_themeStrengthKeepsTheThemeIdentity() {
        val source = Color(0xFF1030E0)
        fun dist(a: Color, b: Color) =
            kotlin.math.abs(a.red - b.red) + kotlin.math.abs(a.green - b.green) + kotlin.math.abs(a.blue - b.blue)
        for (theme in AppTheme.entries) {
            val t = CinetorTokens.of(theme)
            assertTrue("$theme tint strength <= 0.5", t.tintStrength <= 0.5f)
            val c = blendTint(source, t.accent, t.tintStrength)
            assertTrue("$theme stays closer to its accent than to the poster colour", dist(c, t.accent) <= dist(c, source))
        }
    }

    @Test
    fun glass_lowTier_hasNoBlurEffect() {
        GlassLevel.entries.forEach { assertFalse(glassUsesLiveBlur(PerformanceTier.LOW, it)) }
        assertEquals(0.88f, fallbackFillAlpha(PerformanceTier.LOW, GlassLevel.SURFACE), 0f)
    }

    @Test
    fun glass_midBarsOnly_highAllOverlays() {
        assertFalse(glassUsesLiveBlur(PerformanceTier.MID, GlassLevel.BAR)) // lag fix: no per-frame bar blur on MID
        assertTrue(glassUsesLiveBlur(PerformanceTier.MID, GlassLevel.SHEET))
        assertFalse(glassUsesLiveBlur(PerformanceTier.MID, GlassLevel.FLOATING))
        assertFalse(glassUsesLiveBlur(PerformanceTier.MID, GlassLevel.SURFACE))
        assertTrue(glassUsesLiveBlur(PerformanceTier.HIGH, GlassLevel.FLOATING))
        assertFalse(glassUsesLiveBlur(PerformanceTier.HIGH, GlassLevel.SURFACE))
    }
}
