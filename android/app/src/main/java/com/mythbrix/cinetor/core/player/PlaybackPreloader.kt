package com.mythbrix.cinetor.core.player

import com.mythbrix.cinetor.core.data.Item
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Readiness { RESOLVING, BUFFERING, READY, ERROR }

/**
 * Instant-play warm-up (Task 3.2 contract). Details calls [warm] as soon as it opens and [cancel]
 * when the user leaves. Latest warm wins. The real Media3 implementation lands in Phase 3.
 */
interface PlaybackPreloader {
    fun warm(item: Item, episodeId: String? = null)
    fun cancel(itemId: String)
    val readiness: StateFlow<Map<String, Readiness>>
}

/** Does nothing; readiness stays empty. */
object NoopPlaybackPreloader : PlaybackPreloader {
    private val empty = MutableStateFlow<Map<String, Readiness>>(emptyMap())
    override fun warm(item: Item, episodeId: String?) = Unit
    override fun cancel(itemId: String) = Unit
    override val readiness: StateFlow<Map<String, Readiness>> = empty.asStateFlow()
}

/**
 * Phase-2 stand-in: walks RESOLVING → BUFFERING → READY on a timer so the UI "Ready" dot works.
 * Keeps only the latest warmed item (previous one is cancelled), like the real preloader will.
 */
class SimulatedPlaybackPreloader(
    private val scope: CoroutineScope,
    private val resolveMs: Long = 350,
    private val bufferMs: Long = 550,
) : PlaybackPreloader {
    private val _readiness = MutableStateFlow<Map<String, Readiness>>(emptyMap())
    override val readiness: StateFlow<Map<String, Readiness>> = _readiness.asStateFlow()

    private var current: String? = null
    private var job: Job? = null

    @Synchronized
    override fun warm(item: Item, episodeId: String?) {
        if (current == item.id && job?.isActive == true) return
        current?.let { if (it != item.id) cancel(it) }
        current = item.id
        job?.cancel()
        set(item.id, Readiness.RESOLVING)
        job = scope.launch {
            delay(resolveMs)
            set(item.id, Readiness.BUFFERING)
            delay(bufferMs)
            set(item.id, Readiness.READY)
        }
    }

    @Synchronized
    override fun cancel(itemId: String) {
        if (current == itemId) {
            job?.cancel()
            job = null
            current = null
        }
        _readiness.update { it - itemId }
    }

    private fun set(id: String, r: Readiness) = _readiness.update { it + (id to r) }
}
