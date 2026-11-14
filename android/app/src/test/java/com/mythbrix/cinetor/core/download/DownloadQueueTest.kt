package com.mythbrix.cinetor.core.download

import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Source
import com.mythbrix.cinetor.core.data.SourceKind
import com.mythbrix.cinetor.core.data.Space
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Runner that blocks until its gate is completed (cancel = pause). */
class GatedRunner : DownloadRunner {
    val started: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    fun finish(id: String) { gates.getOrPut(id) { CompletableDeferred() }.complete(Unit) }

    override suspend fun run(record: DownloadRecord, reporter: ProgressReporter) {
        started += record.id
        reporter.progress(10, 100)
        gates.getOrPut(record.id) { CompletableDeferred() }.await()
    }

    override suspend fun remove(record: DownloadRecord) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueTest {

    private fun rec(id: String, adult: Boolean = false) =
        DownloadRecord(id = id, itemId = id, title = "T $id", kind = DownloadKind.PROGRESSIVE, url = "https://x.test/$id.mp4", adult = adult)

    private class Harness(
        val store: InMemoryDownloadStore,
        val engine: DownloadEngine,
        val runner: GatedRunner,
        val conditions: MutableStateFlow<DeviceConditions>,
        val policy: MutableStateFlow<QueuePolicy>,
    )

    private fun TestScope.harness(policy: QueuePolicy, conditions: DeviceConditions = DeviceConditions()): Harness {
        val store = InMemoryDownloadStore()
        val runner = GatedRunner()
        val c = MutableStateFlow(conditions)
        val p = MutableStateFlow(policy)
        var clock = 1_000L
        val engine = DownloadEngine(store, backgroundScope, c, p, { runner }, nowMs = { clock++ })
        return Harness(store, engine, runner, c, p)
    }

    private suspend fun Harness.status(id: String) = store.get(id)!!.status

    @Test
    fun queue_respectsMaxConcurrent() = runTest {
        val h = harness(QueuePolicy(maxConcurrent = 2))
        runCurrent()
        listOf("a", "b", "c", "d").forEach { h.engine.enqueue(rec(it)) }
        runCurrent()
        assertEquals(listOf("a", "b"), h.runner.started.toList())
        assertEquals(DownloadStatus.DOWNLOADING, h.status("a"))
        assertEquals(DownloadStatus.QUEUED, h.status("c"))
        assertEquals(2, h.engine.summary.value.active)

        h.runner.finish("a")
        runCurrent()
        advanceTimeBy(10); runCurrent()
        assertEquals(DownloadStatus.COMPLETED, h.status("a"))
        assertEquals(listOf("a", "b", "c"), h.runner.started.toList())
        assertEquals(2, listOf("b", "c", "d").count { h.engine.isRunning(it) })

        // Raising the limit starts the rest; the policy is clamped to 1..5.
        h.policy.value = QueuePolicy(maxConcurrent = 9)
        runCurrent()
        assertTrue(h.engine.isRunning("d"))
        assertEquals(5, QueuePolicy(maxConcurrent = 9).concurrency)
        assertEquals(1, QueuePolicy(maxConcurrent = 0).concurrency)

        // Pure scheduler: priority order, never more than the limit.
        val recs = (1..6).map { rec("r$it").copy(priority = 6 - it, status = DownloadStatus.QUEUED) }
        val d = decideQueue(recs, emptySet(), QueuePolicy(maxConcurrent = 3), DeviceConditions())
        assertEquals(listOf("r6", "r5", "r4"), d.toStart)
    }

    @Test
    fun wifiOnly_pausesOnMobile() = runTest {
        val h = harness(QueuePolicy(wifiOnly = true), DeviceConditions(network = NetworkType.UNMETERED))
        runCurrent()
        h.engine.enqueue(rec("a"))
        runCurrent()
        assertTrue(h.engine.isRunning("a"))

        h.conditions.value = DeviceConditions(network = NetworkType.METERED)
        runCurrent()
        assertFalse(h.engine.isRunning("a"))
        val r = h.store.get("a")!!
        assertEquals(DownloadStatus.WAITING, r.status)
        assertEquals("Waiting for Wi-Fi", r.waitingReason)
        assertEquals("Waiting for Wi-Fi", h.engine.summary.value.blockedReason)

        // Back on Wi-Fi → resumes by itself.
        h.conditions.value = DeviceConditions(network = NetworkType.UNMETERED)
        runCurrent()
        assertTrue(h.engine.isRunning("a"))
        assertEquals(DownloadStatus.DOWNLOADING, h.status("a"))
        assertEquals(2, h.runner.started.size)

        // Other guards.
        assertEquals("Waiting for charging", blockedReason(QueuePolicy(chargingOnly = true), DeviceConditions(charging = false)))
        assertEquals("Low storage", blockedReason(QueuePolicy(), DeviceConditions(freeBytes = 10 * MIB)))
        assertEquals(null, blockedReason(QueuePolicy(wifiOnly = false), DeviceConditions(network = NetworkType.METERED)))
    }

    @Test
    fun pauseResume_keepsQueueOrder() = runTest {
        val h = harness(QueuePolicy(maxConcurrent = 1))
        runCurrent()
        h.engine.enqueue(rec("a")); h.engine.enqueue(rec("b"))
        runCurrent()
        h.engine.pause("a")
        runCurrent()
        assertEquals(DownloadStatus.PAUSED, h.status("a"))
        assertTrue(h.engine.isRunning("b"))
        h.engine.move("a", -1)
        h.engine.resume("a")
        runCurrent()
        // "a" moved to the front → takes the only slot again, "b" goes back to the queue.
        assertTrue(h.engine.isRunning("a"))
        assertEquals(DownloadStatus.QUEUED, h.status("b"))
    }

    @Test
    fun adultDownloads_hiddenWhenLocked() = runTest {
        val all = listOf(DownloadInfo(rec("a")), DownloadInfo(rec("x", adult = true)), DownloadInfo(rec("b")))
        assertEquals(listOf("a", "b"), visibleDownloads(all, adultUnlocked = false).map { it.record.id })
        assertEquals(listOf("a", "x", "b"), visibleDownloads(all, adultUnlocked = true).map { it.record.id })

        // Adult item → adult flag + separate private folder.
        val item = Item(
            id = "ad1", space = Space.ADULT, categoryId = "c", type = ItemType.MOVIE, title = "A", year = 2020, rating = 1.0,
            maturity = "18+", genres = emptyList(), synopsis = "", poster = "p", backdrop = "b", dominantColor = "#000000",
            accentColor = "#000000", durationSec = 100,
        )
        val src = Source("https://x.test/a.mp4", SourceKind.MP4, "MP4")
        val choice = DownloadChoice(qualityOptions(src, null, 100).first(), connections = 99)
        val r = buildRecord(item, null, null, choice, { id, adult, ext -> (if (adult) "/private/" else "/public/") + "$id.$ext" }, "/cache")
        assertTrue(r.adult)
        assertEquals("/private/ad1.mp4", r.filePath)
        assertEquals(MAX_CONNECTIONS, r.connections)
        val home = buildRecord(item.copy(space = Space.HOME), null, null, choice, { id, adult, ext -> (if (adult) "/private/" else "/public/") + "$id.$ext" }, "/cache")
        assertFalse(home.adult)
        assertEquals("/public/ad1.mp4", home.filePath)

        // Live TV is never downloadable.
        assertFalse(isDownloadable(item.copy(space = Space.TV, type = ItemType.CHANNEL, live = true)))
        assertTrue(isDownloadable(item.copy(space = Space.HOME)))
    }

    @Test
    fun qualityOptions_estimateSizePerHeight() {
        val src = Source("https://x.test/master.m3u8", SourceKind.HLS, "HLS")
        val tracks = StreamTrackInfo(
            videos = listOf(VideoOption(1080, 1920, 5_000_000, "avc1"), VideoOption(480, 854, 1_000_000, "avc1")),
            durationSec = 600,
        )
        val opts = qualityOptions(src, tracks, 0)
        assertEquals(listOf("1080p · HLS", "480p · HLS"), opts.map { it.label })
        assertEquals(375_000_000L, opts[0].estimatedBytes)
        assertEquals(75_000_000L, opts[1].estimatedBytes)
        assertEquals(4, defaultConnections(NetworkType.UNMETERED, lowTier = false))
        assertEquals(2, defaultConnections(NetworkType.METERED, lowTier = false))
        assertEquals(2, defaultConnections(NetworkType.UNMETERED, lowTier = true))
    }
}
