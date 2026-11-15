package com.mythbrix.cinetor.core.data

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Artificial latency for the mock catalog so skeletons are visible.
 * Configurable (Settings → Developer later); [None] in tests.
 */
class MockLatency(
    val minMs: Long = 300,
    val maxMs: Long = 1200,
    private val random: Random = Random.Default,
) {
    init {
        require(minMs >= 0 && maxMs >= minMs) { "Invalid latency range $minMs..$maxMs" }
    }

    /** Settings → Developer → Mock latency. */
    @Volatile
    var enabled: Boolean = true

    fun nextMs(): Long = if (!enabled || maxMs == 0L) 0L else random.nextLong(minMs, maxMs + 1)

    suspend fun await() {
        val ms = nextMs()
        if (ms > 0) delay(ms)
    }

    companion object {
        val None = MockLatency(0, 0)
        val Default = MockLatency(300, 1200)
    }
}
