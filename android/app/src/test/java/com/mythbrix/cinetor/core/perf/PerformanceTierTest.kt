package com.mythbrix.cinetor.core.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceTierTest {
    private val gb = 1024L * 1024L * 1024L

    private fun profile(ramGb: Double, sdk: Int, cores: Int = 8, lowRam: Boolean = false, tv: Boolean = false) =
        DeviceProfile((ramGb * gb).toLong(), sdk, cores, lowRam, tv)

    @Test
    fun tier_lowRam_disablesLiveBlur() {
        val tier = detectTier(profile(2.0, 30))
        assertEquals(PerformanceTier.LOW, tier)
        assertFalse(tier.liveBlur)
        assertTrue(tier.imageRgb565)
        assertEquals(0, tier.crossfadeMs)
        assertEquals(PerformanceTier.LOW, detectTier(profile(8.0, 34, lowRam = true)))
    }

    @Test
    fun tier_8gbApi34_isHigh() {
        val tier = detectTier(profile(7.6, 34))
        assertEquals(PerformanceTier.HIGH, tier)
        assertTrue(tier.liveBlurEverywhere && tier.kenBurns && tier.parallax)
    }

    @Test
    fun tier_tvBox1gb_isLow() {
        assertEquals(PerformanceTier.LOW, detectTier(profile(1.0, 28, cores = 4, tv = true)))
    }

    @Test
    fun tier_userOverride_wins() {
        assertEquals(PerformanceTier.HIGH, detectTier(profile(1.0, 24, cores = 4), PerformanceTier.HIGH))
        assertEquals(PerformanceTier.LOW, detectTier(profile(12.0, 35), EffectsQuality.LOW.toTierOrNull()))
        assertEquals(PerformanceTier.HIGH, detectTier(profile(12.0, 35), EffectsQuality.AUTO.toTierOrNull()))
    }

    @Test
    fun tier_midRules() {
        assertEquals(PerformanceTier.MID, detectTier(profile(4.0, 33)))
        assertEquals(PerformanceTier.MID, detectTier(profile(8.0, 30)))
        assertEquals(PerformanceTier.LOW, detectTier(profile(4.0, 25)))
        assertEquals(PerformanceTier.LOW, detectTier(profile(6.0, 34, cores = 4)))
    }
}
