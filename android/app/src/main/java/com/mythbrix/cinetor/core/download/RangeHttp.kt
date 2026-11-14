package com.mythbrix.cinetor.core.download

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.io.File

/** Result of a HEAD / `Range: bytes=0-0` probe. contentLength -1 = unknown. */
data class ProbeResult(
    val contentLength: Long,
    val acceptRanges: Boolean,
    val contentType: String? = null,
    val finalUrl: String? = null,
)

/** Open response body. [code] 200 = full body, 206 = partial. */
interface HttpBody : Closeable {
    val code: Int
    val stream: InputStream
}

/** Minimal blocking HTTP surface so the downloader can be unit tested with a fake. */
interface RangeHttp {
    @Throws(IOException::class)
    fun probe(url: String, headers: Map<String, String>): ProbeResult

    /** [range] null = whole body (no Range header); end < 0 = open ended `bytes=start-`. */
    @Throws(IOException::class)
    fun open(url: String, headers: Map<String, String>, range: ByteRange?): HttpBody
}

class HttpStatusException(val code: Int, msg: String) : IOException(msg)

/** Production [RangeHttp] over OkHttp. */
class OkHttpRangeHttp(private val client: OkHttpClient) : RangeHttp {

    override fun probe(url: String, headers: Map<String, String>): ProbeResult {
        val head = runCatching {
            client.newCall(Request.Builder().url(url).head().addAll(headers).build()).execute().use { r ->
                if (!r.isSuccessful) throw HttpStatusException(r.code, "HEAD ${r.code}")
                ProbeResult(
                    contentLength = r.header("Content-Length")?.toLongOrNull() ?: -1,
                    acceptRanges = r.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true,
                    contentType = r.header("Content-Type"),
                    finalUrl = r.request.url.toString(),
                )
            }
        }.getOrNull()
        if (head != null && head.contentLength > 0 && head.acceptRanges) return head
        // Range probe: 206 + Content-Range proves range support even when HEAD hides it.
        val req = Request.Builder().url(url).addAll(headers).header("Range", "bytes=0-0").build()
        val r = client.newCall(req).execute()
        try {
            if (!r.isSuccessful) {
                if (head != null) return head
                throw HttpStatusException(r.code, "HTTP ${r.code}")
            }
            if (r.code == 206) {
                val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: head?.contentLength ?: -1L
                return ProbeResult(total, total > 0, r.header("Content-Type") ?: head?.contentType, r.request.url.toString())
            }
            return ProbeResult(
                r.header("Content-Length")?.toLongOrNull() ?: head?.contentLength ?: -1L,
                false,
                r.header("Content-Type") ?: head?.contentType,
                r.request.url.toString(),
            )
        } finally {
            r.close()
        }
    }

    override fun open(url: String, headers: Map<String, String>, range: ByteRange?): HttpBody {
        val b = Request.Builder().url(url).addAll(headers)
        if (range != null) b.header("Range", if (range.end < 0) "bytes=${range.start}-" else "bytes=${range.start}-${range.end}")
        val r = client.newCall(b.build()).execute()
        if (!r.isSuccessful) {
            r.close(); throw HttpStatusException(r.code, "HTTP ${r.code}")
        }
        val body = r.body ?: run { r.close(); throw IOException("Empty body") }
        return object : HttpBody {
            override val code = r.code
            override val stream: InputStream = body.byteStream()
            override fun close() = r.close()
        }
    }

    private fun Request.Builder.addAll(h: Map<String, String>): Request.Builder = apply {
        h.forEach { (k, v) -> header(k, v) }
    }
}

/** Positional writes from several connections at once. */
interface ChunkSink : Closeable {
    fun write(position: Long, buf: ByteArray, off: Int, len: Int)
    fun length(): Long
    fun truncate(length: Long)
}

/** File-backed sink; FileChannel positional writes are thread safe. */
class FileChunkSink(file: File) : ChunkSink {
    private val raf: RandomAccessFile
    private val channel: FileChannel

    init {
        file.parentFile?.mkdirs()
        raf = RandomAccessFile(file, "rw")
        channel = raf.channel
    }

    override fun write(position: Long, buf: ByteArray, off: Int, len: Int) {
        val bb = ByteBuffer.wrap(buf, off, len)
        var p = position
        while (bb.hasRemaining()) p += channel.write(bb, p)
    }

    override fun length(): Long = channel.size()
    override fun truncate(length: Long) { channel.truncate(length) }
    override fun close() { runCatching { channel.force(false) }; raf.close() }
}

/** In-memory sink for tests. */
class MemoryChunkSink(initial: ByteArray = ByteArray(0)) : ChunkSink {
    @Volatile var bytes: ByteArray = initial.copyOf()
        private set

    @Synchronized
    override fun write(position: Long, buf: ByteArray, off: Int, len: Int) {
        val end = (position + len).toInt()
        if (end > bytes.size) bytes = bytes.copyOf(end)
        System.arraycopy(buf, off, bytes, position.toInt(), len)
    }

    override fun length(): Long = bytes.size.toLong()

    @Synchronized
    override fun truncate(length: Long) { bytes = bytes.copyOf(length.toInt()) }

    override fun close() = Unit
}
