package com.mythbrix.cinetor.core.data

import android.content.Context
import com.mythbrix.cinetor.BuildConfig
import com.mythbrix.cinetor.core.data.tmdb.OkHttpTmdbHttp
import com.mythbrix.cinetor.core.data.tmdb.TmdbApi
import com.mythbrix.cinetor.core.data.tmdb.TmdbCatalogSource
import com.mythbrix.cinetor.core.download.DownloadRecord
import com.mythbrix.cinetor.core.download.DownloadsModule
import com.mythbrix.cinetor.core.download.OfflineResolver
import com.mythbrix.cinetor.core.player.SourceResolver
import kotlinx.coroutines.withTimeoutOrNull
import com.mythbrix.cinetor.core.perf.DeviceProfile
import android.util.Log
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
import com.mythbrix.cinetor.core.player.DEFAULT_PROFILE
import com.mythbrix.cinetor.core.prefs.SubtitleStyleDataStore
import com.mythbrix.cinetor.feature.player.subs.SubtitleController
import com.mythbrix.cinetor.feature.player.subs.SubtitleStyleStore
import com.mythbrix.cinetor.feature.player.subs.loadSubtitleText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val playerFactory: PlayerFactory by lazy { PlayerFactory(appContext, playerTier) { downloads.cacheProvider } }

    /** Resume positions (DataStore JSON keyed profile+item+episode; Room later if added). */
    val resume: ResumeStore = ResumeRepository(ResumeDataStore(appContext))

    /** Data saver: warm only resolves, never buffers. Settings toggle arrives in 5.2. */
    @Volatile
    var dataSaver: Boolean = false

    /** Instant-play warm-up over the shared Media3 player (Task 3.2). */
    val preloader: PlaybackPreloader = MediaPlaybackPreloader(
        scope = mainScope,
        playerProvider = { playerFactory.player() },
        resume = resume,
        dataSaver = { dataSaver },
        log = { Log.i("CinetorPlayback", it) },
    )

    /** App-scoped player state (PlayerHost above the NavHost): survives rotation + navigation. */
    val playback: PlayerStateHolder by lazy {
        PlayerStateHolder(
            scope = mainScope,
            preloader = preloader,
            resume = resume,
            resolveFull = { item -> runCatching { catalog.item(item.id) }.getOrDefault(item) },
            lineupFor = { item -> tv.lineupFor(item) },
            audioHandler = { item -> music.handles(item) },
            beforePlay = { music.yieldToVideo() },
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
            profileId = { DEFAULT_PROFILE },
            loader = { uri -> loadSubtitleText(appContext, uri) },
        ).also { it.loadStyle() }
    }

    private val _myList = MutableStateFlow<Set<String>>(emptySet())

    /** "My List" ids (in-memory until profiles/library persist it in Phase 5). */
    val myList: StateFlow<Set<String>> = _myList.asStateFlow()

    fun toggleMyList(id: String) = _myList.update { if (id in it) it - id else it + id }

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

    /** Kick off catalog parsing while the splash plays. */
    fun warmUp() {
        appScope.launch { runCatching { catalogSource.preload() } }
        appScope.launch(Dispatchers.Main) { runCatching { downloads } }
    }
}
