package com.mythbrix.cinetor.core.download

import android.content.Context
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Android wiring of the download stack (owned by AppContainer as `downloads`): Room store, device monitor,
 * DataStore policy, chunked + Media3 runners, [DownloadEngine], foreground service / WorkManager hand-off,
 * and the [cacheProvider] used by the player for offline playback.
 */
class DownloadsModule(
    context: Context,
    private val scope: CoroutineScope,
    baseClient: OkHttpClient,
    private val lowTier: Boolean,
    adultEnabled: Flow<Boolean>,
) {
    private val app = context.applicationContext
    val paths = DownloadPaths(app)
    val prefs = DownloadPrefs(app, scope)
    val monitor = DeviceConditionsMonitor(app, scope)
    private val store: DownloadStore = VaultedDownloadStore(
        RoomDownloadStore(CinetorDb.get(app).downloads()),
        UrlVault.forApp(app),
    )

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val limiter = SpeedLimiter()
    private val downloader = ChunkedHttpDownloader(OkHttpRangeHttp(client), store, limiter)
    private val completed = MutableStateFlow<Map<String, DownloadRecord>>(emptyMap())
    val media3 = Media3Downloads(app, paths, completed)
    private val progressive = ProgressiveRunner(downloader, client)
    private val segmented = Media3Runner(media3)

    val engine = DownloadEngine(
        store = store,
        scope = scope,
        conditions = monitor.state,
        policy = prefs.policy,
        runners = { kind -> if (kind == DownloadKind.PROGRESSIVE) progressive else segmented },
    )

    /** Offline playback hook for the player. */
    val cacheProvider: DownloadCacheProvider get() = media3

    /**
     * Adult downloads are listed only while the adult space is unlocked. Until the Task 4.4 PIN session exists this
     * follows Settings → "Show 18+ section"; 4.4 should call [setAdultSessionUnlocked].
     */
    private val adultSession = MutableStateFlow<Boolean?>(null)
    val adultUnlocked: StateFlow<Boolean> = combine(adultEnabled, adultSession) { enabled, session -> enabled && (session ?: true) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun setAdultSessionUnlocked(unlocked: Boolean) { adultSession.value = unlocked }

    /** Downloads the UI may show. */
    val visible: StateFlow<List<DownloadInfo>> = combine(engine.downloads, adultUnlocked) { all, unlocked -> visibleDownloads(all, unlocked) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Ids of completed downloads (→ "Downloaded" badges). */
    val downloadedIds: StateFlow<Set<String>> = engine.downloads
        .map { l -> l.filter { it.record.status == DownloadStatus.COMPLETED }.map { it.record.id }.toSet() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val _storage = MutableStateFlow(StorageInfo())
    val storage: StateFlow<StorageInfo> = _storage.asStateFlow()

    init {
        scope.launch { prefs.policy.collect { limiter.bytesPerSec = it.speedLimitBps } }
        scope.launch {
            engine.downloads.collect { l ->
                completed.value = l.filter { it.record.status == DownloadStatus.COMPLETED }.associate { it.record.id to it.record }
                // Finished files are copied into the user-chosen folder (hardening §4).
                l.map { it.record }.forEach { r -> runCatching { exportIfNeeded(r) } }
            }
        }
        // Running work → foreground service; queued but blocked → WorkManager waits for the constraint.
        scope.launch {
            engine.summary.collect { s ->
                if (s.active > 0) {
                    if (!CinetorDownloadService.start(app)) scheduleResumeWork()
                } else if (s.pending > 0) {
                    scheduleResumeWork()
                }
            }
        }
    }

    fun scheduleResumeWork() = runCatching { ResumeDownloadsWorker.enqueue(app, prefs.policy.value) }

    // ----------------------------------------------- user-chosen location + delete (hardening §4)

    private val exporter = DownloadExporter(app)

    /** Chosen download folder (blank = app storage). */
    val location: StateFlow<DownloadLocation> get() = prefs.location

    /** Remembers the SAF tree the user picked as the new default. */
    suspend fun setLocation(treeUri: android.net.Uri) {
        exporter.remember(treeUri)
        val s = treeUri.toString()
        prefs.setLocation(s, DownloadLocations.folderName(s))
    }

    suspend fun clearLocation() = prefs.setLocation("", "")

    fun locationLabel(adult: Boolean): String = DownloadLocations.label(location.value, adult)

    /** Removes the entry only; files stay where they are. */
    suspend fun removeEntry(id: String) = engine.delete(id)

    /** Removes the entry AND deletes the file from the chosen location + app storage. */
    suspend fun deleteWithFile(id: String): Boolean {
        val record = engine.record(id)
        val deleted = record?.let { exporter.deleteFiles(it) } ?: false
        engine.delete(id)
        return deleted
    }

    /** Copies finished files into the chosen folder; failures are silent (the app copy still plays). */
    private suspend fun exportIfNeeded(record: DownloadRecord) {
        val loc = location.value
        if (!DownloadLocations.needsExport(record, loc)) return
        val uri = exporter.export(loc.treeUri, java.io.File(record.filePath), DownloadLocations.fileName(record), record.mimeType)
            ?: return
        engine.setExportUri(record.id, uri)
    }

    suspend fun refreshStorage() {
        _storage.value = withContext(Dispatchers.IO) {
            StorageInfo(
                usedByCinetor = paths.usedBytes(),
                freeBytes = DeviceConditionsMonitor.freeBytes(app.filesDir),
                totalBytes = DeviceConditionsMonitor.totalBytes(app.filesDir),
            )
        }
    }

    /** Connections for new downloads: user setting or 4 Wi-Fi / 2 mobile / 2 LOW tier. */
    fun defaultConnectionCount(): Int =
        prefs.policy.value.connections.takeIf { it > 0 } ?: defaultConnections(monitor.state.value.network, lowTier)

    /** Probes a source for the options sheet: HEAD size (progressive) or Media3 tracks (HLS/DASH). */
    suspend fun options(source: Source, durationSec: Long): Pair<List<QualityOption>, StreamTrackInfo?> {
        val kind = downloadKindOf(source.kind) ?: return emptyList<QualityOption>() to null
        return if (kind == DownloadKind.PROGRESSIVE && (source.sizeBytes ?: 0) > 0) {
            qualityOptions(source, null, durationSec) to null
        } else if (kind == DownloadKind.PROGRESSIVE) {
            val len = withContext(Dispatchers.IO) {
                runCatching { OkHttpRangeHttp(client).probe(source.url, source.headers).contentLength }.getOrDefault(-1L)
            }
            qualityOptions(source, null, durationSec, len) to null
        } else {
            val tracks = runCatching { media3.probeTracks(kind, source.url) }.getOrNull()
            qualityOptions(source, tracks, durationSec) to tracks
        }
    }

    suspend fun start(item: Item, episode: Episode?, seasonNumber: Int?, choice: DownloadChoice) {
        val record = buildRecord(
            item, episode, seasonNumber, choice,
            fileFor = { id, adult, ext -> paths.fileFor(id, adult, ext).absolutePath },
            mediaCacheDir = paths.mediaCacheDir.absolutePath,
        )
        engine.enqueue(record)
    }
}

/** Storage bar numbers. */
data class StorageInfo(val usedByCinetor: Long = 0, val freeBytes: Long = 0, val totalBytes: Long = 0)
