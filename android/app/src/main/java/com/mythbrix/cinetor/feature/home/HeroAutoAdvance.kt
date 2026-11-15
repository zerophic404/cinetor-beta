package com.mythbrix.cinetor.feature.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Why the hero carousel is not advancing. Any active reason pauses it. */
enum class HeroPause { DRAG, TOUCH, OFFSCREEN, LIFECYCLE, SINGLE_PAGE, SETTING, FOCUS }

/**
 * Auto-advance clock for the hero carousel (pure, virtual-time testable).
 * Every [intervalMs] while running and not paused → [onAdvance]. Resuming restarts the full
 * interval so the banner never jumps right after the user lets go.
 */
class HeroAutoAdvance(
    private val scope: CoroutineScope,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val onAdvance: suspend () -> Unit,
) {
    private val reasons = HashSet<HeroPause>()
    private var running = false
    private var job: Job? = null

    val isPaused: Boolean get() = reasons.isNotEmpty()
    val isTicking: Boolean get() = job?.isActive == true

    fun start() {
        running = true
        restart()
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
    }

    fun pause(reason: HeroPause) {
        if (reasons.add(reason)) {
            job?.cancel()
            job = null
        }
    }

    fun resume(reason: HeroPause) {
        if (reasons.remove(reason)) restart()
    }

    fun setPaused(reason: HeroPause, paused: Boolean) = if (paused) pause(reason) else resume(reason)

    private fun restart() {
        job?.cancel()
        job = null
        if (!running || reasons.isNotEmpty()) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                onAdvance()
            }
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 5_000L
    }
}
