package com.mythbrix.cinetor.core.download

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import kotlin.random.Random

/** In-memory HTTP server: honours Range when [acceptRanges]; can fail the first N opens. */
class FakeRangeHttp(
    private val data: ByteArray,
    private val acceptRanges: Boolean = true,
    var failFirstOpens: Int = 0,
) : RangeHttp {
    val requests: MutableList<ByteRange?> = Collections.synchronizedList(mutableListOf())

    override fun probe(url: String, headers: Map<String, String>) = ProbeResult(data.size.toLong(), acceptRanges)

    override fun open(url: String, headers: Map<String, String>, range: ByteRange?): HttpBody {
        requests += range
        if (failFirstOpens > 0) {
            failFirstOpens--
            throw IOException("connection reset")
        }
        val (code, bytes) = if (range != null && acceptRanges) {
            val end = if (range.end < 0) data.size - 1 else range.end.toInt()
            206 to data.copyOfRange(range.start.toInt(), end + 1)
        } else {
            200 to data
        }
        return object : HttpBody {
            override val code: Int = code
            override val stream: InputStream = ByteArrayInputStream(bytes)
            override fun close() = Unit
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChunkedDownloaderTest {

    private fun record(id: String = "d1", connections: Int = 4) =
        DownloadRecord(id = id, itemId = id, title = id, kind = DownloadKind.PROGRESSIVE, url = "https://example.test/$id.mp4", connections = connections)

    private fun data(size: Int) = Random(42).nextBytes(size)

    /** No stealing, small chunks allowed → request pattern is deterministic. */
    private val noSteal = ChunkedHttpDownloader.Config(minChunk = 1, minSteal = Long.MAX_VALUE / 4, bufferSize = 333)

    @Test
    fun chunked_splitsRangesEvenly() = runTest {
        val r = splitRanges(10_000_003, 4, minChunk = 1)
        assertEquals(4, r.size)
        assertEquals(0L, r.first().start)
        assertEquals(10_000_002L, r.last().end)
        r.zipWithNext().forEach { (a, b) -> assertEquals(a.end + 1, b.start) }
        val sizes = r.map { it.length }
        assertTrue(sizes.max() - sizes.min() <= 1)
        assertEquals(10_000_003L, sizes.sum())
        // Small file never gets sub-minChunk pieces; connections clamped to 1..16.
        assertEquals(1, splitRanges(MIB + MIB / 2, 8).size)
        assertEquals(16, splitRanges(100 * MIB, 64).size)
        assertTrue(splitRanges(0, 4).isEmpty())

        // End to end: 4 ranged requests exactly matching the split, file intact.
        val bytes = data(40_000)
        val http = FakeRangeHttp(bytes)
        val dl = ChunkedHttpDownloader(http, InMemoryDownloadStore(), config = noSteal, io = StandardTestDispatcher(testScheduler))
        val sink = MemoryChunkSink()
        val size = dl.download(record(connections = 4), sink)
        assertEquals(40_000L, size)
        assertEquals(splitRanges(40_000, 4, 1).toSet(), http.requests.toSet())
        assertArrayEquals(bytes, sink.bytes)
    }

    @Test
    fun chunked_resumesFromPersistedOffsets() = runTest {
        val bytes = data(4_000)
        // 4 chunks of 1000: #0 done, #1 half, #2 untouched, #3 missing its last byte.
        val store = InMemoryDownloadStore()
        val done = longArrayOf(1_000, 500, 0, 999)
        store.saveChunks("d1", (0 until 4).map { ChunkState("d1", it, it * 1_000L, it * 1_000L + 999, done[it]) })
        val partial = ByteArray(4_000)
        for (i in 0 until 4) System.arraycopy(bytes, i * 1_000, partial, i * 1_000, done[i].toInt())
        val sink = MemoryChunkSink(partial)
        val http = FakeRangeHttp(bytes)
        val dl = ChunkedHttpDownloader(http, store, config = noSteal, io = StandardTestDispatcher(testScheduler))

        dl.download(record(connections = 4), sink)

        assertEquals(setOf(ByteRange(1_500, 1_999), ByteRange(2_000, 2_999), ByteRange(3_999, 3_999)), http.requests.toSet())
        assertArrayEquals(bytes, sink.bytes)
        // Final offsets persisted: every chunk complete.
        assertTrue(store.chunks("d1").all { it.isDone })
    }

    @Test
    fun chunked_noAcceptRanges_singleConnection() = runTest {
        val bytes = data(25_000)
        val http = FakeRangeHttp(bytes, acceptRanges = false)
        val store = InMemoryDownloadStore()
        val dl = ChunkedHttpDownloader(http, store, config = noSteal, io = StandardTestDispatcher(testScheduler))
        val sink = MemoryChunkSink()

        dl.download(record(connections = 8), sink)

        assertEquals(listOf<ByteRange?>(null), http.requests.toList())
        assertEquals(1, store.chunks("d1").size)
        assertArrayEquals(bytes, sink.bytes)
    }

    @Test
    fun chunked_retriesWithBackoff_thenCompletes() = runTest {
        val bytes = data(10_000)
        val http = FakeRangeHttp(bytes, failFirstOpens = 2)
        var retries = 0
        val dl = ChunkedHttpDownloader(http, InMemoryDownloadStore(), config = noSteal, io = StandardTestDispatcher(testScheduler))
        val sink = MemoryChunkSink()
        dl.download(record(connections = 1), sink, object : ChunkedHttpDownloader.Listener {
            override fun onRetry(retryCount: Int, error: String) { retries = retryCount }
        })
        assertEquals(2, retries)
        assertArrayEquals(bytes, sink.bytes)
        assertEquals(1_000L, backoffMs(1))
        assertEquals(4_000L, backoffMs(3))
        assertEquals(30_000L, backoffMs(12))
    }

    @Test
    fun workStealing_splitsSlowChunk() {
        val chunks = listOf(
            ChunkView(index = 0, position = 1_000_000, endInclusive = 999_999, bytesPerSec = 0), // finished
            ChunkView(index = 1, position = 1_000_000, endInclusive = 9_999_999, bytesPerSec = 100_000), // slow, 9 MB left
            ChunkView(index = 2, position = 10_000_000, endInclusive = 12_999_999, bytesPerSec = 3_000_000), // fast
        )
        val plan = planSteal(chunks, minSplit = 512 * 1024L, thiefBps = 3_000_000)
        assertNotNull(plan)
        plan!!
        assertEquals(1, plan.victimIndex)
        assertEquals(9_999_999L, plan.newRange.end)
        assertEquals(plan.victimNewEnd + 1, plan.newRange.start)
        // Slow victim keeps a small share; the fast thief takes most of the remaining bytes.
        assertTrue(plan.newRange.length > 9_000_000L / 2)
        assertTrue(plan.victimNewEnd - 1_000_000 + 1 >= 512 * 1024L)
        // Nothing worth splitting → null.
        assertNull(planSteal(listOf(ChunkView(0, 0, 900_000, 1)), minSplit = 512 * 1024L))
    }

    @Test
    fun workStealing_endToEnd_coversWholeFile() = runTest {
        val bytes = data(64_000)
        val http = FakeRangeHttp(bytes)
        // 2 initial chunks but 4 connections → idle connections steal halves; file must stay intact.
        val store = InMemoryDownloadStore()
        store.saveChunks("d1", listOf(ChunkState("d1", 0, 0, 31_999, 0), ChunkState("d1", 1, 32_000, 63_999, 0)))
        val dl = ChunkedHttpDownloader(
            http, store,
            config = ChunkedHttpDownloader.Config(minChunk = 1, minSteal = 4_000, bufferSize = 1_000),
            io = StandardTestDispatcher(testScheduler),
        )
        val sink = MemoryChunkSink()
        dl.download(record(connections = 4), sink)
        assertArrayEquals(bytes, sink.bytes)
        assertTrue(store.chunks("d1").size > 2)
    }

    @Test
    fun sizeEstimate_fromBitrateAndDuration() {
        // 5 Mbps × 2 h = 4.5 GB
        assertEquals(4_500_000_000L, estimateSizeBytes(5_000_000, 7_200))
        assertEquals(30_000_000L, estimateSizeBytes(800_000, 300))
        assertEquals(-1L, estimateSizeBytes(0, 300))
        assertEquals(-1L, estimateSizeBytes(800_000, 0))
        assertEquals("4.19 GB", formatBytes(4_500_000_000L))
    }
}
