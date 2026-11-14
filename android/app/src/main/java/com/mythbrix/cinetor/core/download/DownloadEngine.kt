package com.mythbrix.cinetor.core.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Fetches the bytes of one download kind. Cancellation = pause (keep partial data). */
interface DownloadRunner {
    /** Runs to completion; throws on failure. */
    suspend fun run(record: DownloadRecord, reporter: ProgressReporter)

    /** Deletes files / cached segments of [record]. */
    suspend fun remove(record: DownloadRecord)
}

/** Callbacks from a running [DownloadRunner] (non-suspending; any thread). */
interface ProgressReporter {
    fun probed(total: Long, acceptRanges: Boolean?, mimeType: String?)
    fun progress(
        downloaded: Long,
        total: Long,
        chunks: List<ChunkProgress> = emptyList(),
        activeConnections: Int = 0,
        segmentsDone: Int = 0,
        segmentsTotal: Int = 0,
    )
    fun retry(count: Int, error: String)
}

/** Aggregate state for the foreground service / resume worker. */
data class EngineSummary(val active: Int = 0, val pending: Int = 0, val blockedReason: String? = null)

/**
 * Download facade + queue. Owns scheduling ([decideQueue]: priority, max concurrent, Wi-Fi/charging/storage
 * constraints), state transitions, live speed stats and the event log. Bytes are fetched by [runners]
 * (progressive → [ChunkedHttpDownloader], HLS/DASH → Media3). Pure Kotlin: Android wiring lives in AppContainer.
 */
class DownloadEngine(
    private val store: DownloadStore,
    private val scope: CoroutineScope,
    private val conditions: StateFlow<DeviceConditions>,
    private val policy: StateFlow<QueuePolicy>,
    private val runners: (DownloadKind) -> DownloadRunner,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 1_000,
) {
    private data class Snapshot(
        val downloaded: Long,
        val total: Long,
        val chunks: List<ChunkProgress>,
        val active: Int,
        val segDone: Int,
        val segTotal: Int,
    )

    private val sched = Mutex()
    private val recMutex = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    private val meters = ConcurrentHashMap<String, SpeedMeter>()
    private val live = MutableStateFlow<Map<String, LiveStats>>(emptyMap())
    private val _summary = MutableStateFlow(EngineSummary())
    private var ticker: Job? = null

    /** Every download (newest state) with live stats for running ones. */
    val downloads: StateFlow<List<DownloadInfo>> = combine(store.observe(), live) { recs, l ->
        recs.map { DownloadInfo(it, l[it.id]) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val summary: StateFlow<EngineSummary> = _summary.asStateFlow()

    init {
        scope.launch {
            // Process death mid-download → back to the queue (chunk offsets are already persisted).
            recMutex.withLock {
                store.all().filter { it.status == DownloadStatus.DOWNLOADING }.forEach {
                    store.upsert(it.copy(status = DownloadStatus.QUEUED))
                }
            }
            combine(conditions, policy) { c, p -> c to p }.collect { schedule() }
        }
    }

    // ---- public API ---------------------------------------------------------------------------

    /** Adds (or re-queues) a download. Completed ones are left alone. */
    suspend fun enqueue(record: DownloadRecord) {
        recMutex.withLock {
            val existing = store.get(record.id)
            if (existing != null) {
                if (existing.status == DownloadStatus.PAUSED || existing.status == DownloadStatus.FAILED) {
                    store.upsert(existing.copy(status = DownloadStatus.QUEUED))
                }
                return@withLock
            }
            val maxPriority = store.all().maxOfOrNull { it.priority } ?: -1
            store.upsert(record.copy(status = DownloadStatus.QUEUED, priority = maxPriority + 1, createdAt = nowMs()))
        }
        event(record.id, "queued", record.qualityLabel)
        schedule()
    }

    suspend fun pause(id: String) {
        sched.withLock {
            jobs.remove(id)?.cancelAndJoin()
            edit(id) { if (it.isActive) it.copy(status = DownloadStatus.PAUSED, waitingReason = null) else it }
            dropLive(id)
            refreshSummary()
        }
        event(id, "paused")
        schedule()
    }

    suspend fun resume(id: String) {
        edit(id) {
            if (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED) it.copy(status = DownloadStatus.QUEUED) else it
        }
        schedule()
    }

    /** Re-queues a failed download; progressive chunks resume from their offsets. */
    suspend fun retry(id: String) {
        event(id, "retry", "manual")
        resume(id)
    }

    /** Stops and removes the download with its files (cancel = delete). */
    suspend fun delete(id: String) {
        sched.withLock {
            jobs.remove(id)?.cancelAndJoin()
            val rec = store.get(id)
            if (rec != null) runCatching { runners(rec.kind).remove(rec) }
            store.delete(id)
            dropLive(id)
            refreshSummary()
        }
        schedule()
    }

    suspend fun cancel(id: String) = delete(id)

    suspend fun pauseAll() = store.all().filter { it.isActive }.forEach { pause(it.id) }

    suspend fun resumeAll() {
        store.all().filter { it.status == DownloadStatus.PAUSED }.forEach { r -> edit(r.id) { it.copy(status = DownloadStatus.QUEUED) } }
        schedule()
    }

    /** Moves an unfinished download up (delta < 0) or down in the queue. */
    suspend fun move(id: String, delta: Int) {
        recMutex.withLock {
            val queue = store.all().filter { it.isActive || it.status == DownloadStatus.PAUSED }
                .sortedWith(compareBy({ it.priority }, { it.createdAt }))
            val i = queue.indexOfFirst { it.id == id }
            if (i < 0) return@withLock
            val j = (i + delta).coerceIn(0, queue.lastIndex)
            if (i == j) return@withLock
            val reordered = queue.toMutableList().apply { add(j, removeAt(i)) }
            reordered.forEachIndexed { p, r -> if (r.priority != p) store.upsert(r.copy(priority = p)) }
        }
        schedule()
    }

    suspend fun events(id: String): List<DownloadEvent> = store.events(id)

    fun isRunning(id: String): Boolean = jobs.containsKey(id)

    // ---- scheduling ---------------------------------------------------------------------------

    /** Re-evaluates the queue against the current policy + device conditions. */
    suspend fun schedule() {
        sched.withLock {
            val records = store.all()
            val d = decideQueue(records, jobs.keys.toSet(), policy.value, conditions.value)
            for (id in d.toStop) {
                jobs.remove(id)?.cancelAndJoin()
                dropLive(id)
                edit(id) { if (it.isActive) it.copy(status = d.stopStatus, waitingReason = d.waitingReason) else it }
                event(id, "stopped", d.waitingReason ?: "queue")
            }
            for (r in records) {
                if (r.id in d.toStart || jobs.containsKey(r.id)) continue
                if (d.waitingReason != null && (r.status == DownloadStatus.QUEUED || r.status == DownloadStatus.DOWNLOADING)) {
                    edit(r.id) { if (it.isActive) it.copy(status = DownloadStatus.WAITING, waitingReason = d.waitingReason) else it }
                } else if (d.waitingReason == null && (r.status == DownloadStatus.WAITING || r.status == DownloadStatus.DOWNLOADING)) {
                    edit(r.id) { if (it.isActive) it.copy(status = DownloadStatus.QUEUED, waitingReason = null) else it }
                }
            }
            d.toStart.forEach { start(it) }
            refreshSummary(d.waitingReason)
        }
    }

    private suspend fun refreshSummary(reason: String? = _summary.value.blockedReason) {
        val pending = store.all().count { it.isActive } - jobs.size
        _summary.value = EngineSummary(jobs.size, pending.coerceAtLeast(0), if (pending > 0 || jobs.isNotEmpty()) reason else null)
    }

    private suspend fun start(id: String) {
        val now = nowMs()
        val rec = edit(id) {
            it.copy(status = DownloadStatus.DOWNLOADING, startedAt = it.startedAt ?: now, waitingReason = null)
        } ?: return
        meters[id] = SpeedMeter()
        snapshots.remove(id)
        event(id, "started", "${rec.connections} connection(s)")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runners(rec.kind).run(rec, reporterFor(id))
                flush(id)
                edit(id) {
                    it.copy(
                        status = DownloadStatus.COMPLETED,
                        finishedAt = nowMs(),
                        downloadedBytes = maxOf(it.downloadedBytes, it.totalBytes),
                        lastError = null,
                    )
                }
                event(id, "completed")
            } catch (e: CancellationException) {
                withContext(NonCancellable) { flush(id) }
                throw e
            } catch (e: Throwable) {
                flush(id)
                val msg = e.message ?: e.javaClass.simpleName
                edit(id) { it.copy(status = DownloadStatus.FAILED, lastError = msg) }
                event(id, "error", msg)
            } finally {
                val self = coroutineContext[Job]
                if (self != null) jobs.remove(id, self)
                scope.launch { schedule() }
            }
        }
        jobs[id] = job
        job.start()
        ensureTicker()
    }

    private fun reporterFor(id: String) = object : ProgressReporter {
        override fun probed(total: Long, acceptRanges: Boolean?, mimeType: String?) {
            scope.launch {
                edit(id) { it.copy(totalBytes = if (total > 0) total else it.totalBytes, acceptRanges = acceptRanges, mimeType = mimeType ?: it.mimeType) }
                event(id, "probe", "size=${formatBytes(total)} ranges=$acceptRanges")
            }
        }

        override fun progress(downloaded: Long, total: Long, chunks: List<ChunkProgress>, activeConnections: Int, segmentsDone: Int, segmentsTotal: Int) {
            snapshots[id] = Snapshot(downloaded, total, chunks, activeConnections, segmentsDone, segmentsTotal)
        }

        override fun retry(count: Int, error: String) {
            scope.launch {
                edit(id) { it.copy(retryCount = count, lastError = error) }
                event(id, "retry", "#$count $error")
            }
        }
    }

    /** Writes the latest progress snapshot into the record. */
    private suspend fun flush(id: String) {
        val s = snapshots[id] ?: return
        edit(id) {
            it.copy(
                downloadedBytes = s.downloaded,
                totalBytes = if (s.total > 0) s.total else it.totalBytes,
                segmentsDone = s.segDone,
                segmentsTotal = if (s.segTotal > 0) s.segTotal else it.segmentsTotal,
            )
        }
    }

    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (jobs.isNotEmpty()) {
                delay(tickMs)
                tick()
            }
        }
    }

    private suspend fun tick() {
        for (id in jobs.keys.toList()) {
            val s = snapshots[id] ?: continue
            val meter = meters.getOrPut(id) { SpeedMeter() }
            meter.sample(s.downloaded, tickMs)
            val cur = meter.current()
            val rec = store.get(id) ?: continue
            val total = if (s.total > 0) s.total else rec.totalBytes
            val stats = LiveStats(
                currentBps = cur,
                averageBps = meter.average(s.downloaded),
                speedHistory = meter.history(),
                etaSec = if (total > 0) etaSeconds(total - s.downloaded, cur) else null,
                elapsedMs = rec.startedAt?.let { nowMs() - it } ?: 0,
                activeConnections = s.active,
                chunks = s.chunks,
            )
            live.update { it + (id to stats) }
            flush(id)
        }
    }

    private fun dropLive(id: String) {
        live.update { it - id }
        meters.remove(id)
    }

    private suspend fun edit(id: String, f: (DownloadRecord) -> DownloadRecord): DownloadRecord? = recMutex.withLock {
        val cur = store.get(id) ?: return@withLock null
        val next = f(cur)
        if (next != cur) store.upsert(next)
        next
    }

    private suspend fun event(id: String, type: String, message: String = "") {
        runCatching { store.addEvent(DownloadEvent(downloadId = id, time = nowMs(), type = type, message = message)) }
    }
}
