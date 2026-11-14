package com.mythbrix.cinetor.core.data

import android.content.Context
import com.mythbrix.cinetor.BuildConfig
import com.mythbrix.cinetor.core.data.tmdb.OkHttpTmdbHttp
import com.mythbrix.cinetor.core.data.tmdb.TmdbApi
import com.mythbrix.cinetor.core.data.tmdb.TmdbCatalogSource
import com.mythbrix.cinetor.core.perf.DeviceProfile
import com.mythbrix.cinetor.core.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual DI (no Hilt): one instance per process, owned by the Application. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val assetReader: AssetReader = AssetReader { path ->
        appContext.assets.open(path).bufferedReader().use { it.readText() }
    }

    val latency: MockLatency = MockLatency.Default

    val catalogSource: MockCatalogSource = MockCatalogSource(assetReader, latency)

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** TMDB metadata for Home/Anime/Drama; null when the build has no token (→ mock only). */
    val tmdb: TmdbCatalogSource? = BuildConfig.TMDB_TOKEN.takeIf { it.isNotBlank() }
        ?.let { token -> TmdbCatalogSource(TmdbApi(OkHttpTmdbHttp(httpClient, token))) }

    /** TMDB for video spaces (falls back to mock on error), mock for isolated spaces. */
    val catalog: CatalogRepository = HybridCatalog(tmdb, catalogSource)
    val search: SearchRepository = SearchRepository(catalog)
    val remoteSearch: RemoteSearch? get() = tmdb

    val settings: SettingsStore = SettingsStore(appContext)

    val deviceProfile: DeviceProfile by lazy { DeviceProfile.fromContext(appContext) }

    /** Kick off catalog parsing while the splash plays. */
    fun warmUp() {
        appScope.launch { runCatching { catalogSource.preload() } }
    }
}
