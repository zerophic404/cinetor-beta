package com.mythbrix.cinetor.core.download

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the options sheet can offer for an HLS/DASH stream (from Media3 [DownloadHelper]). */
data class StreamTrackInfo(
    val videos: List<VideoOption> = emptyList(),
    val audios: List<String> = emptyList(),
    val texts: List<String> = emptyList(),
    val durationSec: Long = -1,
)

/** One selectable video quality. */
data class VideoOption(val height: Int, val width: Int, val bitrate: Long, val codecs: String?) {
    val label: String get() = if (height > 0) "${height}p" else "Auto"
}

/**
 * Offline playback hook for the player (core/player).
 *
 * Usage in the player's source resolver:
 * ```
 * when (val o = container.downloads.cacheProvider.offlineSource(itemId, episodeId)) {
 *   is OfflineSource.LocalFile -> MediaItem.fromUri(Uri.fromFile(o.file))         // progressive, airplane-mode safe
 *   is OfflineSource.Cached -> DefaultMediaSourceFactory(provider.cacheDataSourceFactory(upstream))
 *                                .createMediaSource(o.request.toMediaItem())        // HLS/DASH segments from download cache
 *   null -> normal online path
 * }
 * ```
 */
interface DownloadCacheProvider {
    /** Media3 download cache (HLS/DASH segments). Separate from the player's LRU SimpleCache (a dir can host one SimpleCache). */
    val cache: Cache

    /** Completed download for [itemId]/[episodeId] or null (→ stream online). */
    fun offlineSource(itemId: String, episodeId: String?): OfflineSource?

    /** Read-through-cache factory: cached segments first, [upstream] for anything missing (never writes). */
    fun cacheDataSourceFactory(upstream: DataSource.Factory): DataSource.Factory
}

sealed interface OfflineSource {
    data class LocalFile(val file: File, val mimeType: String?) : OfflineSource
    data class Cached(val request: DownloadRequest) : OfflineSource
}

/**
 * Media3 offline stack: one [SimpleCache] (no eviction) + [DownloadManager]. The [DownloadEngine] stays the queue
 * authority: [Media3Runner] adds a request when the engine starts it, sets a stop reason when the engine pauses it.
 * DownloadManager must be touched from the main thread.
 */
@OptIn(UnstableApi::class)
class Media3Downloads(
    context: Context,
    private val paths: DownloadPaths,
    private val completed: StateFlow<Map<String, DownloadRecord>>,
) : DownloadCacheProvider {
    private val app = context.applicationContext
    private val db by lazy { StandaloneDatabaseProvider(app) }

    override val cache: Cache by lazy { SimpleCache(paths.mediaCacheDir, NoOpCacheEvictor(), db) }

    val httpFactory: DefaultHttpDataSource.Factory by lazy {
        DefaultHttpDataSource.Factory().setUserAgent("Cinetor/1.0").setAllowCrossProtocolRedirects(true)
    }

    /** Created lazily on the main thread (see [manager]). */
    @Volatile private var dm: DownloadManager? = null

    suspend fun manager(): DownloadManager = withContext(Dispatchers.Main.immediate) {
        dm ?: DownloadManager(app, db, cache, httpFactory, Executors.newFixedThreadPool(4)).also {
            it.maxParallelDownloads = 5
            it.requirements = Requirements(Requirements.NETWORK)
            it.resumeDownloads()
            dm = it
        }
    }

    override fun offlineSource(itemId: String, episodeId: String?): OfflineSource? {
        val r = completed.value[downloadIdFor(itemId, episodeId)] ?: return null
        return when (r.kind) {
            DownloadKind.PROGRESSIVE -> File(r.filePath).takeIf { it.exists() }?.let { OfflineSource.LocalFile(it, r.mimeType) }
            // Index request carries the exact stream keys that were downloaded.
            else -> OfflineSource.Cached(peek(r.id)?.request ?: requestFor(r))
        }
    }

    override fun cacheDataSourceFactory(upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun requestFor(r: DownloadRecord): DownloadRequest =
        DownloadRequest.Builder(r.id, Uri.parse(r.url))
            .setMimeType(if (r.kind == DownloadKind.HLS) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MPD)
            .setStreamKeys(r.streamKeyList.map { StreamKey(it.first, it.second, it.third) })
            .build()

    private fun mediaItem(kind: DownloadKind, url: String): MediaItem =
        MediaItem.Builder().setUri(url)
            .setMimeType(if (kind == DownloadKind.HLS) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MPD)
            .build()

    /** Prepares a [DownloadHelper] on the main thread. Caller must [DownloadHelper.release]. */
    private suspend fun prepare(kind: DownloadKind, url: String): DownloadHelper = withContext(Dispatchers.Main.immediate) {
        val helper = DownloadHelper.forMediaItem(app, mediaItem(kind, url), DefaultRenderersFactory(app), DefaultDataSource.Factory(app, httpFactory))
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { helper.release() }
            helper.prepare(object : DownloadHelper.Callback {
                override fun onPrepared(h: DownloadHelper) { if (cont.isActive) cont.resume(h) }
                override fun onPrepareError(h: DownloadHelper, e: IOException) {
                    h.release()
                    if (cont.isActive) cont.resumeWithException(e)
                }
            })
        }
    }

    /** Lists qualities / audio / subtitle tracks for the options sheet. */
    suspend fun probeTracks(kind: DownloadKind, url: String): StreamTrackInfo {
        val helper = prepare(kind, url)
        return withContext(Dispatchers.Main.immediate) {
            try {
                val videos = mutableListOf<VideoOption>()
                val audios = linkedSetOf<String>()
                val texts = linkedSetOf<String>()
                if (helper.periodCount > 0) {
                    for (g in helper.getTracks(0).groups) {
                        for (i in 0 until g.length) {
                            val f = g.getTrackFormat(i)
                            when (g.type) {
                                C.TRACK_TYPE_VIDEO -> videos += VideoOption(f.height, f.width, maxOf(f.bitrate, f.averageBitrate, f.peakBitrate).toLong(), f.codecs)
                                C.TRACK_TYPE_AUDIO -> audios += (f.label ?: f.language ?: "Audio ${audios.size + 1}")
                                C.TRACK_TYPE_TEXT -> texts += (f.label ?: f.language ?: "Sub ${texts.size + 1}")
                            }
                        }
                    }
                }
                val dur = (helper.manifest as? HlsManifest)?.mediaPlaylist?.durationUs?.let { it / 1_000_000 } ?: -1
                StreamTrackInfo(videos.distinctBy { it.height to it.bitrate }.sortedByDescending { it.height }, audios.toList(), texts.toList(), dur)
            } finally {
                helper.release()
            }
        }
    }

    /** Builds the final request honouring the chosen height / audio / subtitles (stream keys). */
    suspend fun buildRequest(r: DownloadRecord): DownloadRequest {
        if (r.streamKeys.isNotBlank()) return requestFor(r)
        val helper = prepare(r.kind, r.url)
        return withContext(Dispatchers.Main.immediate) {
            try {
                val b = TrackSelectionParameters.Builder(app)
                r.height?.let { h -> b.setMaxVideoSize(Int.MAX_VALUE, h).setMinVideoSize(0, h).setForceHighestSupportedBitrate(true) }
                r.audioTrack?.let { b.setPreferredAudioLanguage(it) }
                val subs = r.subtitleList
                if (subs.isNotEmpty()) b.setPreferredTextLanguages(*subs.toTypedArray())
                for (p in 0 until helper.periodCount) {
                    helper.clearTrackSelections(p)
                    helper.addTrackSelection(p, b.build())
                }
                helper.getDownloadRequest(r.id, null)
            } finally {
                helper.release()
            }
        }
    }

    suspend fun stop(id: String) {
        val m = dm ?: return
        withContext(Dispatchers.Main.immediate) { m.setStopReason(id, STOP_PAUSED) }
    }

    suspend fun remove(id: String) {
        val m = manager()
        withContext(Dispatchers.Main.immediate) { m.removeDownload(id) }
    }

    /** Current Media3 download state (any thread; reads the index). */
    fun peek(id: String): Download? = runCatching { dm?.downloadIndex?.getDownload(id) }.getOrNull()

    companion object {
        const val STOP_PAUSED = 1
    }
}

/** HLS/DASH runner: Media3 segment download into the shared download cache. */
@OptIn(UnstableApi::class)
class Media3Runner(private val media: Media3Downloads) : DownloadRunner {

    override suspend fun run(record: DownloadRecord, reporter: ProgressReporter) {
        val request = media.buildRequest(record)
        val dm = media.manager()
        val segTotal = estimateSegments(record)
        coroutineScope {
            val poller = launch {
                while (true) {
                    delay(1_000)
                    val d = withContext(Dispatchers.Main.immediate) { dm.currentDownloads.firstOrNull { it.request.id == record.id } }
                        ?: media.peek(record.id) ?: continue
                    val pct = d.percentDownloaded.takeIf { it >= 0 } ?: 0f
                    val total = if (d.contentLength > 0) d.contentLength else if (pct > 0) (d.bytesDownloaded * 100 / pct).toLong() else -1
                    val tot = if (segTotal > 0) segTotal else 100
                    reporter.progress(d.bytesDownloaded, total, emptyList(), 1, (pct / 100f * tot).toInt(), tot)
                }
            }
            try {
                awaitTerminal(dm, request)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { media.stop(record.id) }
                throw e
            } finally {
                poller.cancel()
            }
        }
        media.peek(record.id)?.let { d ->
            val tot = if (segTotal > 0) segTotal else 100
            reporter.progress(d.bytesDownloaded, d.bytesDownloaded, emptyList(), 0, tot, tot)
        }
    }

    private suspend fun awaitTerminal(dm: DownloadManager, request: DownloadRequest) = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine<Unit> { cont ->
            val listener = object : DownloadManager.Listener {
                override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                    if (download.request.id != request.id || !cont.isActive) return
                    when (download.state) {
                        Download.STATE_COMPLETED -> { manager.removeListener(this); cont.resume(Unit) }
                        Download.STATE_FAILED -> {
                            manager.removeListener(this)
                            cont.resumeWithException(IOException(finalException?.message ?: "Segment download failed", finalException))
                        }
                    }
                }

                override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                    if (download.request.id == request.id && cont.isActive) {
                        manager.removeListener(this); cont.resumeWithException(IOException("Removed"))
                    }
                }
            }
            dm.addListener(listener)
            cont.invokeOnCancellation { dm.removeListener(listener) }
            val existing = dm.currentDownloads.firstOrNull { it.request.id == request.id }
            if (existing?.state == Download.STATE_COMPLETED) {
                dm.removeListener(listener); cont.resume(Unit)
            } else {
                dm.addDownload(request, Download.STOP_REASON_NONE)
            }
        }
    }

    /** HLS: duration / ~6 s segments (rough; Media3 exposes only %). */
    private fun estimateSegments(r: DownloadRecord): Int = r.durationSec?.let { (it + 5) / 6 } ?: 0

    override suspend fun remove(record: DownloadRecord) = media.remove(record.id)
}
