package com.mythbrix.cinetor.core.data

import android.content.Context
import com.mythbrix.cinetor.core.perf.DeviceProfile
import com.mythbrix.cinetor.core.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual DI (no Hilt): one instance per process, owned by the Application. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val assetReader: AssetReader = AssetReader { path ->
        appContext.assets.open(path).bufferedReader().use { it.readText() }
    }

    val latency: MockLatency = MockLatency.Default

    val catalogSource: MockCatalogSource = MockCatalogSource(assetReader, latency)
    val catalog: CatalogRepository get() = catalogSource
    val search: SearchRepository = SearchRepository(catalogSource)

    val settings: SettingsStore = SettingsStore(appContext)

    val deviceProfile: DeviceProfile by lazy { DeviceProfile.fromContext(appContext) }

    /** Kick off catalog parsing while the splash plays. */
    fun warmUp() {
        appScope.launch { runCatching { catalogSource.preload() } }
    }
}
