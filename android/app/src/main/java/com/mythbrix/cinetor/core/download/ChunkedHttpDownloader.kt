package com.mythbrix.cinetor.core.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Collections
import kotlin.math.min

/**
 * Multi-connection HTTP downloader for progressive files (MP4/MKV/WebM/MP3).
 *
 * - Probe (HEAD, then `Range: bytes=0-0`) → length + range support.
 * - Splits into 1–16 ranges ([splitRanges]); extra idle connections and finished ones steal the back
 *   half of the slowest chunk ([planSteal]).
 * - Per-chunk offsets persisted every [Config.persistEveryMs] and on stop → resume after kill/reboot.
 * - Per-connection retry with exponential backoff; 4xx (except 408/429) fail fast.
 * - Server without `Accept-Ranges` (or ignoring Range) → single connection, restart from 0.
 * - Final size verified against Content-Length. Global [SpeedLimiter]. Cancel the coroutine = pause.
 */
class ChunkedHttpDownloader(
    private val http: RangeHttp,
    private val store: DownloadStore,
    private val limiter: SpeedLimiter = SpeedLimiter(),
    private val config: Config = Config(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class Config(
        val minChunk: Long = MIB,
        val minSteal: Long = 512 * 1024L,
        val maxRetries: Int = 6,
        val baseBackoffMs: Long = 1_000,
        val maxBackoffMs: Long = 30_000,
        val bufferSize: Int = 64 * 1024,
        val persistEveryMs: Long = 1_000,
    )

    interface Listener {
        fun onProbe(total: Long, acceptRanges: Boolean, contentType: String?) {}
        fun onProgress(downloaded: Long, total: Long, chunks: List<ChunkProgress>, activeConnections: Int) {}
        fun onRetry(retryCount: Int, error: String) {}
    }

    class RangeIgnoredException : IOException("Server ignored the Range header")

    private class Slot(val index: Int, val start: Long, @Volatile var end: Long, @Volatile var downloaded: Long) {
        @Volatile var active = false
        @Volatile var finishedUnknown = false
        @Volatile var bps = 0L
        var lastDownloaded = downloaded
        val position: Long get() = start + downloaded
        val done: Boolean get() = finishedUnknown || (end >= 0 && position > end)
    }

    /** Downloads [record] into [sink]; returns the final size. Throws on failure. */
    suspend fun download(record: DownloadRecord, sink: ChunkSink, listener: Listener = object : Listener {}): Long {
        val headers = record.headerMap
        val probe = withContext(io) { http.probe(record.url, headers) }
        listener.onProbe(probe.contentLength, probe.acceptRanges, probe.contentType)
        val total = probe.contentLength
        val ranges = probe.acceptRanges && total > 0
        var chunks = store.chunks(record.id)
        val resumable = ranges && chunks.isNotEmpty() && chunks.maxOf { it.endInclusive } == total - 1
        if (!resumable) {
            chunks = initialChunks(record.id, total, if (ranges) record.connections else 1, ranges, config.minChunk)
            withContext(io) { sink.truncate(0) }
            store.saveChunks(record.id, chunks)
        }
        return try {
            runAll(record, headers, sink, chunks, ranges, total, listener)
        } catch (e: RangeIgnoredException) {
            chunks = initialChunks(record.id, total, 1, false)
            withContext(io) { sink.truncate(0) }
            store.saveChunks(record.id, chunks)
            runAll(record, headers, sink, chunks, false, total, listener)
        }
    }

    private suspend fun runAll(
        record: DownloadRecord,
        headers: Map<String, String>,
        sink: ChunkSink,
        chunks: List<ChunkState>,
        ranges: Boolean,
        total: Long,
        listener: Listener,
    ): Long {
        val lock = Any()
        val slots = chunks.sortedBy { it.idx }.map { Slot(it.idx, it.start, it.endInclusive, it.downloaded) }.toMutableList()
        var retries = record.retryCount
        // Blocking socket reads ignore coroutine cancellation → a watcher closes open bodies on pause/cancel.
        val openBodies: MutableSet<HttpBody> = Collections.synchronizedSet(LinkedHashSet())

        fun snapshot(): List<Slot> = synchronized(lock) { slots.toList() }

        suspend fun persist() {
            val snap = snapshot()
            store.saveChunks(record.id, snap.map { ChunkState(record.id, it.index, it.start, it.end, it.downloaded) })
            listener.onProgress(
                snap.sumOf { it.downloaded },
                total,
                snap.map { ChunkProgress(it.index, it.start, it.end, it.downloaded, it.active) },
                snap.count { it.active },
            )
        }

        fun steal(thiefBps: Long): Slot? {
            if (!ranges) return null
            synchronized(lock) {
                val views = slots.filter { !it.done && it.end >= 0 }.map { ChunkView(it.index, it.position, it.end, it.bps) }
                val plan = planSteal(views, config.minSteal, thiefBps) ?: return null
                val victim = slots.first { it.index == plan.victimIndex }
                if (victim.position > plan.victimNewEnd) return null
                victim.end = plan.victimNewEnd
                val s = Slot(slots.size, plan.newRange.start, plan.newRange.end, 0)
                slots += s
                return s
            }
        }

        suspend fun runChunk(slot: Slot) {
            var attempts = 0
            while (!slot.done) {
                currentCoroutineContext().ensureActive()
                if (!ranges && slot.downloaded > 0) {
                    synchronized(lock) { slot.downloaded = 0 }
                }
                val range = if (ranges) ByteRange(slot.position, slot.end) else null
                try {
                    val body = http.open(record.url, headers, range)
                    openBodies += body
                    val reserved = LongArray(2)
                    try {
                        if (range != null && body.code == 200 && range.start > 0) throw RangeIgnoredException()
                        slot.active = true
                        val buf = ByteArray(config.bufferSize)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = body.stream.read(buf)
                            if (n < 0) break
                            if (n == 0) continue
                            limiter.acquire(n)
                            synchronized(lock) {
                                val p = slot.position
                                val a = if (slot.end < 0) n.toLong() else min(n.toLong(), slot.end - p + 1).coerceAtLeast(0L)
                                if (a > 0) slot.downloaded += a
                                reserved[0] = p
                                reserved[1] = a
                            }
                            val pos = reserved[0]
                            val allowed = reserved[1].toInt()
                            if (allowed > 0) {
                                sink.write(pos, buf, 0, allowed)
                                attempts = 0
                            }
                            if (slot.end >= 0 && slot.position > slot.end) break
                        }
                        if (slot.end < 0) {
                            slot.finishedUnknown = true
                        } else if (!slot.done) {
                            throw IOException("Connection closed early at ${slot.position}")
                        }
                    } finally {
                        slot.active = false
                        openBodies -= body
                        runCatching { body.close() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RangeIgnoredException) {
                    throw e
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    if (e is HttpStatusException && e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                    attempts++
                    retries++
                    listener.onRetry(retries, e.message ?: e.javaClass.simpleName)
                    if (attempts > config.maxRetries) throw e
                    delay(backoffMs(attempts, config.baseBackoffMs, config.maxBackoffMs))
                }
            }
        }

        suspend fun worker(first: Slot?) {
            var current: Slot? = first ?: steal(0)
            while (current != null) {
                runChunk(current)
                current = steal(current.bps)
            }
        }

        try {
            coroutineScope {
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        synchronized(openBodies) { openBodies.toList() }.forEach { runCatching { it.close() } }
                    }
                }
                val ticker = launch {
                    while (isActive) {
                        delay(config.persistEveryMs)
                        for (s in snapshot()) {
                            val d = s.downloaded
                            s.bps = (d - s.lastDownloaded).coerceAtLeast(0) * 1_000 / config.persistEveryMs
                            s.lastDownloaded = d
                        }
                        persist()
                    }
                }
                val pending = snapshot().filter { !it.done }
                val workerCount = if (ranges) record.connections.coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS) else 1
                val jobs = (0 until maxOf(workerCount, pending.size)).map { i ->
                    launch(io) { worker(pending.getOrNull(i)) }
                }
                jobs.joinAll()
                ticker.cancel()
                watcher.cancel()
            }
        } finally {
            withContext(NonCancellable) { persist() }
        }

        val got = snapshot().sumOf { it.downloaded }
        if (total > 0) {
            if (got != total) throw IOException("Size mismatch: got $got of $total bytes")
            withContext(io) {
                if (sink.length() > total) sink.truncate(total)
                if (sink.length() != total) throw IOException("File size ${sink.length()} != $total")
            }
            return total
        }
        return got
    }
}
