package com.mythbrix.cinetor.core.download

import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Global token-bucket limiter shared by every connection of every download.
 * [bytesPerSec] ≤ 0 = unlimited. [nanoClock] injectable for tests.
 */
class SpeedLimiter(bytesPerSec: Long = 0, private val nanoClock: () -> Long = System::nanoTime) {
    @Volatile var bytesPerSec: Long = bytesPerSec

    private var nextFree = 0L

    /** Suspends long enough to keep the aggregate rate under the limit. */
    suspend fun acquire(bytes: Int) {
        val rate = bytesPerSec
        if (rate <= 0 || bytes <= 0) return
        val waitNs: Long
        synchronized(this) {
            val now = nanoClock()
            // Allow at most ~0.5 s of burst credit.
            val start = max(nextFree, now - 500_000_000L)
            nextFree = start + bytes * 1_000_000_000L / rate
            waitNs = nextFree - now
        }
        if (waitNs > 1_000_000) delay(waitNs / 1_000_000)
    }
}

/** Rolling 1 s speed samples (last [capacity]) + average since [start]. */
class SpeedMeter(private val capacity: Int = 60) {
    private val samples = ArrayDeque<Long>()
    private var lastBytes = -1L
    private var activeMs = 0L
    private var bytesAtStart = -1L

    /** Call once per second with the cumulative byte count. */
    fun sample(totalDownloaded: Long, intervalMs: Long = 1_000) {
        if (bytesAtStart < 0) bytesAtStart = totalDownloaded
        val delta = if (lastBytes < 0) 0 else max(0L, totalDownloaded - lastBytes)
        lastBytes = totalDownloaded
        activeMs += intervalMs
        samples.addLast(delta * 1_000 / max(1L, intervalMs))
        while (samples.size > capacity) samples.removeFirst()
    }

    fun history(): List<Long> = samples.toList()

    /** Smoothed current speed (mean of the last 3 samples). */
    fun current(): Long = samples.takeLast(3).let { if (it.isEmpty()) 0 else it.sum() / it.size }

    fun average(totalDownloaded: Long): Long =
        if (activeMs <= 0 || bytesAtStart < 0) 0 else max(0L, totalDownloaded - bytesAtStart) * 1_000 / activeMs

    fun reset() {
        samples.clear(); lastBytes = -1; activeMs = 0; bytesAtStart = -1
    }
}

/** ETA in seconds from remaining bytes and speed; null when unknown. */
fun etaSeconds(remainingBytes: Long, bps: Long): Long? =
    if (remainingBytes < 0 || bps <= 0) null else (remainingBytes + bps - 1) / bps
