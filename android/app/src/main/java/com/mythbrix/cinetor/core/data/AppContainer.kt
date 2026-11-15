package com.mythbrix.cinetor.core.data

import android.content.Context
import com.mythbrix.cinetor.BuildConfig
import com.mythbrix.cinetor.core.data.tmdb.OkHttpTmdbHttp
import com.mythbrix.cinetor.core.data.tmdb.TmdbApi
import com.mythbrix.cinetor.core.data.tmdb.TmdbCatalogSource
import com.mythbrix.cinetor.core.data.gateway.FileGatewayDiskCache
import com.mythbrix.cinetor.core.data.gateway.GatewayApi
import com.mythbrix.cinetor.core.data.gateway.GatewayCache
import com.mythbrix.cinetor.core.data.gateway.GatewayCatalogSource
import com.mythbrix.cinetor.core.data.gateway.OkHttpGatewayHttp
import com.mythbrix.cinetor.core.data.gateway.PaletteDominantColors
import com.mythbrix.cinetor.core.data.gateway.SharedPrefsSlugStore
import com.mythbrix.cinetor.core.player.GatewaySourceResolver
import com.mythbrix.cinetor.core.download.DownloadRecord
import com.mythbrix.cinetor.core.download.DownloadsModule
import com.mythbrix.cinetor.core.download.OfflineResolver
import com.mythbrix.cinetor.core.player.SourceResolver
import kotlinx.coroutines.withTimeoutOrNull
import com.mythbrix.cinetor.core.perf.DeviceProfile
import com.mythbrix.cinetor.core.perf.PerformanceTier
import com.mythbrix.cinetor.core.perf.detectTier
import com.mythbrix.cinetor.core.player.MediaPlaybackPreloader
import com.mythbrix.cinetor.core.player.PlaybackPreloader
import com.mythbrix.cinetor.core.player.PlayerFactory
import com.mythbrix.cinetor.core.player.ResumeRepository
import com.mythbrix.cinetor.core.player.ResumeStore
import com.mythbrix.cinetor.core.prefs.ResumeDataStore
import com.mythbrix.cinetor.core.prefs.SearchHistoryDataStore
import com.mythbrix.cinetor.core.prefs.SettingsStore
import com.mythbrix.cinetor.feature.player.PlayerStateHolder
import com.mythbrix.cinetor.feature.music.MusicController
import com.mythbrix.cinetor.feature.music.MusicRepository
import com.mythbrix.cinetor.feature.tv.TvPrefs
import com.mythbrix.cinetor.feature.tv.TvRepository
import com.mythbrix.cinetor.feature.profile.ActiveProfileStore
import com.mythbrix.cinetor.feature.profile.HistoryRecordingResumeStore
import com.mythbrix.cinetor.feature.profile.ProfileDb
import com.mythbrix.cinetor.feature.profile.ProfileManager
import com.mythbrix.cinetor.feature.profile.RoomProfileStore
import com.mythbrix.cinetor.feature.settings.SettingsStateHolder
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.mythbrix.cinetor.core.prefs.SubtitleStyleDataStore
import com.mythbrix.cinetor.feature.player.subs.SubtitleController
import com.mythbrix.cinetor.feature.player.subs.SubtitleStyleStore
import com.mythbrix.cinetor.feature.player.subs.loadSubtitleText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
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

    val latency: MockLatency = MockLatency(MockLatency.Default.minMs, MockLatency.Default.maxMs)

    /** Bundled mock data for the isolated spaces only (Live TV & Sports, Music, Comics). */
    val catalogSource: MockCatalogSource = MockCatalogSource(assetReader, latency, spaces = listOf(Space.TV, Space.MUSIC, Space.COMICS))

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** TMDB client kept as a metadata helper only (not a catalog source any more). */
    val tmdb: TmdbCatalogSource? by lazy {
        BuildConfig.TMDB_TOKEN.takeIf { it.isNotBlank() }?.let { token -> TmdbCatalogSource(TmdbApi(OkHttpTmdbHttp(httpClient, token))) }
    }

    /** User's gateway (docs/gateway.md); base URL from Settings → Developer (blank = default). */
    val gatewayApi: GatewayApi by lazy {
        GatewayApi(
            http = OkHttpGatewayHttp(httpClient),
            cache = GatewayCache(disk = FileGatewayDiskCache(java.io.File(appContext.cacheDir, "gateway"))),
            baseUrl = { runCatching { settingsState.state.value.gatewayUrl }.getOrDefault("") },
        )
    }

    private val adultUnlocked: () -> Boolean = { runCatching { adult.enabled.value && adult.unlocked.value }.getOrDefault(false) }

    /** Comics metadata/chapters from MangaDex; chapter pages via the gateway proxy (docs/gateway.md). */
    val comicsApi: com.mythbrix.cinetor.core.data.comics.MangaDexApi by lazy {
        com.mythbrix.cinetor.core.data.comics.MangaDexApi(
            http = OkHttpGatewayHttp(httpClient),
            cache = GatewayCache(disk = FileGatewayDiskCache(java.io.File(appContext.cacheDir, "comics"))),
            gatewayBase = { runCatching { settingsState.state.value.gatewayUrl }.getOrDefault("") },
        )
    }

    val comicsCatalog: com.mythbrix.cinetor.core.data.comics.MangaDexCatalogSource by lazy {
        com.mythbrix.cinetor.core.data.comics.MangaDexCatalogSource(comicsApi)
    }

    /** Home / Anime / Drama (+ Adult while unlocked) — gateway only, no mock fallback. */
    val gateway: GatewayCatalogSource by lazy {
        GatewayCatalogSource(
            api = gatewayApi,
            slugs = SharedPrefsSlugStore(appContext),
            colors = PaletteDominantColors(appContext, httpClient, appScope),
            adultAllowed = adultUnlocked,
        )
    }

    /** Gateway for video spaces + unlocked Adult (errors → retry UI, never mock); mock for TV / Music / Comics. */
    val catalog: CatalogRepository = HybridCatalog(
        chain = listOf(gateway),
        mock = catalogSource,
        adultChain = { if (adultUnlocked()) listOf(gateway) else emptyList() },
        mockFallback = { it == Space.TV || it == Space.MUSIC || it == Space.COMICS },
        comicsChain = { listOf(comicsCatalog) },
    )
    val search: SearchRepository = SearchRepository(catalog)
    val remoteSearch: RemoteSearch? get() = gateway

    /** Real playable sources (gateway streams) attached at warm / play / download time. */
    val streams: GatewaySourceResolver by lazy {
        GatewaySourceResolver(
            api = gatewayApi,
            slugOf = { id -> gateway.slugOf(id) },
            maxHeight = { defaultMaxHeight() },
            log = { com.mythbrix.cinetor.core.legal.DebugLog.log("playback") { it } },
            onNoSource = { id -> gateway.markUnplayable(id) },
        )
    }

    private fun defaultMaxHeight(): Int? = runCatching {
        val s = settingsState.state.value
        val metered = (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
            ?.isActiveNetworkMetered ?: false
        val cap = maxHeightFor(if (metered) s.qualityMobile else s.qualityWifi, s.dataSaver)
        // Low-tier devices: 720p cap by default (software decode of 1080p stutters).
        if (playerTier == PerformanceTier.LOW) minOf(cap ?: 720, 720) else cap
    }.getOrNull()

    /** Local-only recent searches (per profile; isolated spaces in their own buckets). */
    val searchHistory: SearchHistory = SearchHistory(SearchHistoryDataStore(appContext))
    val searchEngine: SearchEngine = SearchEngine(search, remoteSearch, QueryCache(100))

    val settings: SettingsStore = SettingsStore(appContext)

    /** Items seen on cards (Details warm-up + shared poster before the full item loads). */
    val peek: ItemPeekCache = ItemPeekCache()

    /** Main-thread scope for player work (ExoPlayer is single-threaded on the main looper). */
    val mainScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Auto-detected tier for player buffers/cache (user effects override only affects visuals). */
    val playerTier: PerformanceTier by lazy { detectTier(deviceProfile) }

    /** Owns the ONE shared ExoPlayer + 200 MB (LOW: 80 MB) SimpleCache. Process-scoped → survives rotation. */
    val playerFactory: PlayerFactory by lazy { PlayerFactory(appContext, playerTier, { downloads.cacheProvider }, { settingsState.state.value.cacheSizeMb }) }

    /** Resume positions (DataStore JSON keyed profile+item+episode); every save also lands in the profile's Room history. */
    val resume: ResumeStore = HistoryRecordingResumeStore(ResumeRepository(ResumeDataStore(appContext))) { entry ->
        profiles.recordHistory(entry)
    }

    /** Profiles + per-profile history / watchlist (Task 5.1, Room `profiles.db`). */
    val profiles: ProfileManager by lazy {
        ProfileManager(
            store = RoomProfileStore(ProfileDb.get(appContext).profiles()),
            activeStore = object : ActiveProfileStore {
                override suspend fun load(): String? = settings.activeProfile()
                override suspend fun save(id: String) = settings.setActiveProfile(id)
            },
            scope = appScope,
            onDeleted = { id -> wipeProfileData(id) },
        ).also { pm -> appScope.launch { runCatching { pm.load() } } }
    }

    val activeProfileId: String get() = profiles.activeId.value

    /** Settings model (Task 5.2); root collects it → theme/effects apply live without restart. */
    val settingsState: SettingsStateHolder by lazy {
        SettingsStateHolder(
            source = settings,
            scope = appScope,
            profileTheme = profiles.active.map { it.theme }.distinctUntilChanged(),
            onProfileTheme = { t -> profiles.setTheme(t) },
        )
    }

    /** Data saver: warm only resolves, never buffers (Settings → Data & storage). */
    @Volatile
    var dataSaver: Boolean = false

    /** Settings → Data & storage → Preload on details (off = resolve only, like data saver). */
    @Volatile
    var preloadDetails: Boolean = true

    private suspend fun wipeProfileData(id: String) {
        runCatching { resume.clearProfile(id) }
        runCatching { searchHistory.clear(id, null) }
        Space.entries.forEach { sp -> runCatching { searchHistory.clear(id, sp) } }
    }

    /** Settings → Clear history: active profile's watch history, resume positions and recent searches. */
    suspend fun clearHistory() {
        val id = activeProfileId
        profiles.clearHistory(id)
        wipeProfileData(id)
    }

    /** Image + temp cache size in bytes (player/download caches excluded: they manage their own limits). */
    suspend fun cacheBytes(): Long = kotlinx.coroutines.withContext(Dispatchers.IO) {
        appContext.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Clears Coil's memory + disk image cache. */
    suspend fun clearImageCache() = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val loader = coil3.SingletonImageLoader.get(appContext)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

    /** Settings → Playback default quality → ExoPlayer height cap (data saver caps at 480p). */
    private fun maxHeightFor(q: com.mythbrix.cinetor.core.prefs.VideoQuality, saver: Boolean): Int? {
        val h = when (q) {
            com.mythbrix.cinetor.core.prefs.VideoQuality.AUTO -> null
            com.mythbrix.cinetor.core.prefs.VideoQuality.P1080 -> 1080
            com.mythbrix.cinetor.core.prefs.VideoQuality.P720 -> 720
            com.mythbrix.cinetor.core.prefs.VideoQuality.P480 -> 480
            com.mythbrix.cinetor.core.prefs.VideoQuality.P360 -> 360
        }
        return if (saver) minOf(h ?: 480, 480) else h
    }

    /** Preferred audio/subtitle language + quality cap: active profile override, else app setting ("original" = no preference). */
    private fun applyLanguagePrefs() {
        runCatching {
            val s = settingsState.state.value
            val p = profiles.active.value
            val audio = (p.audioLang ?: s.audioLang).takeIf { it != "original" }
            val text = (p.subtitleLang ?: s.subtitleLang).takeIf { it != "original" && s.subtitlesOn }
            val metered = (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
                ?.isActiveNetworkMetered ?: false
            val maxHeight = maxHeightFor(if (metered) s.qualityMobile else s.qualityWifi, s.dataSaver)
            val exo = playerFactory.player().media3Player()
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(audio)
                .setPreferredTextLanguage(text)
                .setMaxVideoSize(Int.MAX_VALUE, maxHeight ?: Int.MAX_VALUE)
                .build()
        }
    }

    /** Instant-play warm-up over the shared Media3 player (Task 3.2). */
    val preloader: PlaybackPreloader = MediaPlaybackPreloader(
        scope = mainScope,
        playerProvider = { playerFactory.player() },
        resume = resume,
        profileId = { activeProfileId },
        dataSaver = { dataSaver || !preloadDetails },
        log = { com.mythbrix.cinetor.core.legal.DebugLog.log("playback") { it } },
        prepare = { item, ep -> if (streams.handles(item)) streams.attach(item, ep) else item },
    )

    /** App-scoped player state (PlayerHost above the NavHost): survives rotation + navigation. */
    val playback: PlayerStateHolder by lazy {
        PlayerStateHolder(
            scope = mainScope,
            preloader = preloader,
            resume = resume,
            profileId = { activeProfileId },
            resolveFull = { item -> runCatching { catalog.item(item.id) }.getOrDefault(item) },
            lineupFor = { item -> tv.lineupFor(item) },
            audioHandler = { item -> music.handles(item) },
            beforePlay = { music.yieldToVideo(); applyLanguagePrefs() },
            autoplayNext = { settingsState.state.value.autoplayNext },
            needsPrepare = { item -> streams.handles(item) },
            prepare = { item, ep, fresh -> streams.attach(item, ep, fresh) },
            fallbackFor = { item, ep -> streams.fallback(item, ep) },
        )
    }

    /** Live TV & Sports catalog cache (isolated) + favourites / reminders (Task 4.1). */
    val tv: TvRepository by lazy { TvRepository(catalog) { catalogSource.file(Space.TV).epgAnchor } }
    val tvPrefs: TvPrefs by lazy { TvPrefs(appContext) }

    /** Music library + app-scoped music player over the ONE shared player (Task 4.2). */
    val musicLibrary: MusicRepository by lazy { MusicRepository(catalog) }
    val music: MusicController by lazy {
        MusicController(
            scope = mainScope,
            preloader = preloader,
            takeOver = { if (playback.state.value.item != null) playback.close() },
            contextFor = { track -> musicLibrary.contextFor(track) },
            tracksOf = { album -> musicLibrary.tracksOf(album) },
            resolve = { id -> runCatching { catalog.item(id) }.getOrNull() },
        )
    }

    /** In-space search result tap for TV: play the channel / live event with its lineup. */
    fun playTvById(id: String) {
        mainScope.launch {
            val item = runCatching { catalog.item(id) }.getOrNull() ?: return@launch
            runCatching { tv.load() }
            playback.playChannel(item, tv.lineupFor(item))
        }
    }

    /** Per-profile subtitle style (Task 3.5). */
    val subtitleStyles: SubtitleStyleStore = SubtitleStyleStore(SubtitleStyleDataStore(appContext))

    /** Side-loaded subs + sync offset + style for the player (app-scoped: survives rotation). */
    val subtitles: SubtitleController by lazy {
        SubtitleController(
            scope = mainScope,
            store = subtitleStyles,
            profileId = { activeProfileId },
            loader = { uri -> loadSubtitleText(appContext, uri) },
        ).also { it.loadStyle() }
    }

    /** "My List" ids of the active profile (Room watchlist, Task 5.1). */
    val myList: StateFlow<Set<String>> get() = profiles.watchlistIds

    fun toggleMyList(item: Item) {
        appScope.launch { profiles.toggleWatchlist(item) }
    }

    val deviceProfile: DeviceProfile by lazy { DeviceProfile.fromContext(appContext) }

    /** Download engine + offline cache (Task 3.7). Created at startup so queued downloads resume. */
    val downloads: DownloadsModule by lazy {
        DownloadsModule(appContext, appScope, httpClient, detectTier(deviceProfile) == PerformanceTier.LOW, settings.adultEnabled)
    }

    /**
     * Play a completed download (Downloads screen). Uses the catalog item when it answers fast (next episode,
     * metadata), else a stub rebuilt from the record so airplane mode still works. The player itself picks the
     * local file / download cache; resume position is the same store as streaming.
     */
    fun playDownload(record: DownloadRecord) {
        mainScope.launch {
            val full = withTimeoutOrNull(1_500) { runCatching { catalog.item(record.itemId) }.getOrNull() }
                ?.takeIf { SourceResolver.request(it, record.episodeId)?.episodeId == record.episodeId }
            playback.play(full ?: OfflineResolver.playableItem(record), record.episodeId)
        }
    }

    /** Comics space (Task 4.3): reading progress, chapter downloads, reader overlay. */
    val comics: com.mythbrix.cinetor.feature.comics.ComicsModule by lazy {
        com.mythbrix.cinetor.feature.comics.ComicsModule(
            context = appContext,
            scope = appScope,
            // MangaDex chapters carry no page URLs until the reader opens them (gateway → direct fallback).
            pageResolver = { chapter ->
                if (comicsCatalog.isComicChapter(chapter.id)) comicsCatalog.pages(chapter.id) else chapter.pages
            },
        )
    }

    /** Adult gate (Task 4.4): PIN session mirrored to the download engine; FLAG_SECURE + lifecycle wiring. */
    val adult: com.mythbrix.cinetor.feature.adult.AdultGate by lazy {
        com.mythbrix.cinetor.feature.adult.AdultGate(
            store = com.mythbrix.cinetor.feature.adult.AdultDataStore(appContext, settings),
            scope = appScope,
            kidsProfile = profiles.isKids,
            onSession = { unlocked -> mainScope.launch { downloads.setAdultSessionUnlocked(unlocked) } },
        ).also { gate ->
            com.mythbrix.cinetor.feature.adult.AdultLifecycle.install(
                appContext as android.app.Application, gate, { playback }, mainScope,
            )
        }
    }

    /** Kick off catalog parsing while the splash plays. */
    fun warmUp() {
        // Gate first (main thread, before any activity): adult downloads hidden + lifecycle callbacks registered.
        runCatching { adult }
        appScope.launch {
            settingsState.state.collect { st ->
                dataSaver = st.dataSaver
                preloadDetails = st.preloadDetails
                latency.enabled = st.mockLatency
                // Developer debug log stays off unless the user turned it on (hardening §2).
                com.mythbrix.cinetor.core.legal.DebugLog.setEnabled(st.debugLog)
            }
        }
        // Profile switch → that profile's subtitle style.
        mainScope.launch { profiles.activeId.collect { runCatching { subtitles.loadStyle() } } }
        appScope.launch { runCatching { catalogSource.preload() } }
        appScope.launch(Dispatchers.Main) { runCatching { downloads } }
    }
}
