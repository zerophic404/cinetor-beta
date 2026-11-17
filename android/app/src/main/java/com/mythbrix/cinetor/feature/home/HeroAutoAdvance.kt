package com.mythbrix.cinetor.feature.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Why the hero carousel is not advancing. Any active reason pauses it. */
enum class HeroPause { DRAG, TOUCH, OFFSCREEN, LIFECYCLE, SINGLE_PAGE, SETTING, FOCUS, LOW_TIER }

/**
 * Auto-advance clock for the hero carousel (pure, virtual-time testable).
 * Advances every [intervalMs] (`5s`) while running and unpaused; after user touch or drag,
 * waits [postTouchDelayMs] (`6s`) before resuming the `5s` cadence so cards never jump under a finger.
 */
class HeroAutoAdvance(
    private val scope: CoroutineScope,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val postTouchDelayMs: Long = DEFAULT_POST_TOUCH_DELAY_MS,
    private val onAdvance: suspend () -> Unit,
) {
    private val reasons = HashSet<HeroPause>()
    private var running = false
    private var touchCooldownPending = false
    private var job: Job? = null

    val isPaused: Boolean get() = reasons.isNotEmpty()
    val isTicking: Boolean get() = job?.isActive == true

    fun start() {
        running = true
        restart()
    }

    fun stop() {
        running = false
        touchCooldownPending = false
        job?.cancel()
        job = null
    }

    fun pause(reason: HeroPause) {
        if (reason == HeroPause.DRAG || reason == HeroPause.TOUCH) {
            touchCooldownPending = true
        }
        if (reasons.add(reason)) {
            job?.cancel()
            job = null
        }
    }

    fun resume(reason: HeroPause) {
        if (reasons.remove(reason)) restart()
    }

    fun setPaused(reason: HeroPause, paused: Boolean) = if (paused) pause(reason) else resume(reason)

    /** Marks a user-initiated card tap/swipe and restarts with the post-touch delay. */
    fun notifyUserInteraction() {
        touchCooldownPending = true
        restart()
    }

    private fun restart() {
        job?.cancel()
        job = null
        if (!running || reasons.isNotEmpty()) return
        job = scope.launch {
            var firstTickAfterTouch = touchCooldownPending
            while (isActive) {
                val waitMs = if (firstTickAfterTouch) {
                    touchCooldownPending = false
                    firstTickAfterTouch = false
                    maxOf(intervalMs, postTouchDelayMs)
                } else {
                    intervalMs
                }
                delay(waitMs)
                onAdvance()
            }
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 5_000L
        const val DEFAULT_POST_TOUCH_DELAY_MS = 6_000L
    }
}
