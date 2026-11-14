package com.mythbrix.cinetor.core.perf

/**
 * Rendering budget. Rules (Design system):
 * - LOW if isLowRamDevice OR RAM < 3 GB OR SDK < 26 OR cores <= 4 (most TV boxes).
 * - HIGH if RAM >= 6 GB AND SDK >= 31; else MID.
 * RAM is compared against nominal sizes with headroom (a "6 GB" phone reports ~5.5 GiB).
 */
enum class PerformanceTier(
    /** Live (Haze) blur allowed at all. */
    val liveBlur: Boolean,
    /** Live blur on every glass surface (MID = bars/sheets only). */
    val liveBlurEverywhere: Boolean,
    val parallax: Boolean,
    val kenBurns: Boolean,
    /** Multiplier for animation durations. */
    val durationScale: Float,
    /** Spring overshoot allowed. */
    val springOvershoot: Boolean,
    /** Decode images as RGB_565 (half memory). */
    val imageRgb565: Boolean,
    /** Coil memory-cache share of the app heap. */
    val memCachePercent: Double,
    val crossfadeMs: Int,
) {
    LOW(
        liveBlur = false, liveBlurEverywhere = false, parallax = false, kenBurns = false,
        durationScale = 0.6f, springOvershoot = false, imageRgb565 = true,
        memCachePercent = 0.12, crossfadeMs = 0,
    ),
    MID(
        liveBlur = true, liveBlurEverywhere = false, parallax = true, kenBurns = false,
        durationScale = 1f, springOvershoot = true, imageRgb565 = false,
        memCachePercent = 0.20, crossfadeMs = 150,
    ),
    HIGH(
        liveBlur = true, liveBlurEverywhere = true, parallax = true, kenBurns = true,
        durationScale = 1f, springOvershoot = true, imageRgb565 = false,
        memCachePercent = 0.25, crossfadeMs = 250,
    );

    fun scaled(durationMs: Int): Int = (durationMs * durationScale).toInt()
}

/** User setting: Settings → Appearance → Effects quality. */
enum class EffectsQuality {
    AUTO, LOW, MEDIUM, HIGH;

    fun toTierOrNull(): PerformanceTier? = when (this) {
        AUTO -> null
        LOW -> PerformanceTier.LOW
        MEDIUM -> PerformanceTier.MID
        HIGH -> PerformanceTier.HIGH
    }
}

private const val GIB = 1024L * 1024L * 1024L
private val LOW_RAM_BELOW = (2.75 * GIB).toLong()
private val HIGH_RAM_FROM = (5.5 * GIB).toLong()

/** Pure tier detection; [override] (user choice) always wins. */
fun detectTier(profile: DeviceProfile, override: PerformanceTier? = null): PerformanceTier {
    if (override != null) return override
    val low = profile.isLowRamDevice ||
        profile.totalRamBytes < LOW_RAM_BELOW ||
        profile.sdkInt < 26 ||
        profile.cpuCores <= 4
    if (low) return PerformanceTier.LOW
    return if (profile.totalRamBytes >= HIGH_RAM_FROM && profile.sdkInt >= 31) {
        PerformanceTier.HIGH
    } else {
        PerformanceTier.MID
    }
}
