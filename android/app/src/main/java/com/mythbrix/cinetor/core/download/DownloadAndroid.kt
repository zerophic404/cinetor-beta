package com.mythbrix.cinetor.core.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.StatFs
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** Network type, charging and free space as a StateFlow (ConnectivityManager callback + battery broadcast + StatFs poll). */
class DeviceConditionsMonitor(context: Context, scope: CoroutineScope) {
    private val app = context.applicationContext
    private val _state = MutableStateFlow(DeviceConditions())
    val state: StateFlow<DeviceConditions> = _state.asStateFlow()

    init {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        _state.update { it.copy(network = currentNetwork(cm), charging = readCharging(), freeBytes = freeBytes(app.filesDir)) }
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val type = when {
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkType.NONE
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetworkType.UNMETERED
                        else -> NetworkType.METERED
                    }
                    _state.update { it.copy(network = type) }
                }

                override fun onLost(network: Network) {
                    _state.update { it.copy(network = NetworkType.NONE) }
                }
            })
        }
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    _state.update { it.copy(charging = i?.action == Intent.ACTION_POWER_CONNECTED) }
                }
            }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(15_000)
                val free = freeBytes(app.filesDir)
                _state.update { it.copy(freeBytes = free) }
            }
        }
    }

    private fun currentNetwork(cm: ConnectivityManager?): NetworkType {
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkType.NONE
        return when {
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkType.NONE
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetworkType.UNMETERED
            else -> NetworkType.METERED
        }
    }

    private fun readCharging(): Boolean {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    companion object {
        fun freeBytes(dir: File): Long = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
        fun totalBytes(dir: File): Long = runCatching { StatFs(dir.absolutePath).totalBytes }.getOrDefault(0L)
    }
}

private val Context.downloadPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "download_prefs")

/** Download settings persisted in DataStore → [QueuePolicy]. */
class DownloadPrefs(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.downloadPrefsStore

    val policy: StateFlow<QueuePolicy> = store.data.map { p ->
        QueuePolicy(
            maxConcurrent = p[MAX] ?: 2,
            wifiOnly = p[WIFI] ?: false,
            chargingOnly = p[CHARGING] ?: false,
            speedLimitBps = p[LIMIT] ?: 0L,
            connections = p[CONN] ?: 0,
        )
    }.stateIn(scope, SharingStarted.Eagerly, QueuePolicy())

    /** User-chosen download folder (SAF tree), remembered as the default. */
    val location: StateFlow<DownloadLocation> = store.data.map { p ->
        DownloadLocation(p[TREE].orEmpty(), p[TREE_LABEL].orEmpty())
    }.stateIn(scope, SharingStarted.Eagerly, DownloadLocation())

    suspend fun setLocation(treeUri: String, label: String) {
        store.edit {
            it[TREE] = treeUri
            it[TREE_LABEL] = label
        }
    }

    suspend fun update(f: (QueuePolicy) -> QueuePolicy) {
        val n = f(policy.value)
        store.edit {
            it[MAX] = n.maxConcurrent.coerceIn(1, 5)
            it[WIFI] = n.wifiOnly
            it[CHARGING] = n.chargingOnly
            it[LIMIT] = n.speedLimitBps.coerceAtLeast(0)
            it[CONN] = n.connections.coerceIn(0, MAX_CONNECTIONS)
        }
    }

    private companion object {
        val MAX = intPreferencesKey("max_concurrent")
        val WIFI = booleanPreferencesKey("wifi_only")
        val CHARGING = booleanPreferencesKey("charging_only")
        val LIMIT = longPreferencesKey("speed_limit_bps")
        val CONN = intPreferencesKey("connections")
        val TREE = stringPreferencesKey("location_tree_uri")
        val TREE_LABEL = stringPreferencesKey("location_label")
    }
}

/** App-private storage layout. Adult downloads go to a separate hidden folder. */
class DownloadPaths(context: Context) {
    private val files = context.applicationContext.filesDir
    val publicDir: File get() = File(files, "downloads").apply { mkdirs() }
    val adultDir: File get() = File(files, "downloads_private").apply {
        mkdirs()
        runCatching { File(this, ".nomedia").createNewFile() }
    }
    /** Media3 segment cache (HLS/DASH) shared with the player. */
    val mediaCacheDir: File get() = File(files, "media3_downloads")

    fun fileFor(id: String, adult: Boolean, ext: String): File {
        val safe = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(if (adult) adultDir else publicDir, "$safe.$ext")
    }

    /** Bytes used by Cinetor downloads (files + media cache). */
    fun usedBytes(): Long = listOf(File(files, "downloads"), File(files, "downloads_private"), mediaCacheDir).sumOf { dirSize(it) }

    private fun dirSize(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0
}

/** Progressive files via [ChunkedHttpDownloader] + sidecar subtitles. */
class ProgressiveRunner(
    private val downloader: ChunkedHttpDownloader,
    private val client: OkHttpClient,
) : DownloadRunner {

    override suspend fun run(record: DownloadRecord, reporter: ProgressReporter) {
        val file = File(record.filePath)
        val sink = withContext(Dispatchers.IO) { FileChunkSink(file) }
        try {
            downloader.download(record, sink, object : ChunkedHttpDownloader.Listener {
                override fun onProbe(total: Long, acceptRanges: Boolean, contentType: String?) =
                    reporter.probed(total, acceptRanges, contentType)

                override fun onProgress(downloaded: Long, total: Long, chunks: List<ChunkProgress>, activeConnections: Int) =
                    reporter.progress(downloaded, total, chunks, activeConnections)

                override fun onRetry(retryCount: Int, error: String) = reporter.retry(retryCount, error)
            })
        } finally {
            withContext(Dispatchers.IO) { runCatching { sink.close() } }
        }
        downloadSidecars(record, file)
    }

    private suspend fun downloadSidecars(record: DownloadRecord, file: File) = withContext(Dispatchers.IO) {
        record.sidecarSubs.lines().filter { it.contains('|') }.forEach { line ->
            val lang = line.substringBefore('|')
            val url = line.substringAfter('|')
            if (!url.startsWith("http")) return@forEach
            val ext = url.substringAfterLast('.', "vtt").take(4)
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) File(file.parentFile, "${file.nameWithoutExtension}.$lang.$ext").writeBytes(r.body!!.bytes())
                }
            }
        }
    }

    override suspend fun remove(record: DownloadRecord) = withContext(Dispatchers.IO) {
        val f = File(record.filePath)
        f.parentFile?.listFiles()?.filter { it.name.startsWith(f.nameWithoutExtension + ".") }?.forEach { it.delete() }
        f.delete()
        Unit
    }
}
