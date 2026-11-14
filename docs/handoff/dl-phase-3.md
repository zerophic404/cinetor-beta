# dl/phase-3 — Task 3.7 Download engine + Downloads UI (Android)

Branch `dl/phase-3` (worktree ~/apk-projects/cinetor-dl), from main 458fdf8. Merge after wip/phase-3 (player).

## Done
- core/download (pure Kotlin, unit tested):
  - `ChunkedHttpDownloader` — HEAD + `Range: bytes=0-0` probe, 1–16 connections (`splitRanges`), work stealing
    (`planSteal`: idle conn takes back part of slowest chunk, split by speed ratio), per-chunk offsets persisted
    every 1 s + on stop (Room `download_chunks`), resume, per-conn retry w/ exp backoff (4xx fail fast except 408/429),
    no Accept-Ranges / server ignores Range → single connection from 0, size verify, global `SpeedLimiter` token bucket.
  - `DownloadEngine` — queue authority: priority + reorder (`move`), max concurrent 1–5 (default 2), pause/resume/
    retry/cancel/delete, Wi-Fi-only / charging-only / low-storage (<300 MB) guard → WAITING + reason, live stats
    (current/avg speed, 60 s history, ETA, elapsed, chunk map), event log (Room `download_events`), process-death
    recovery (DOWNLOADING → QUEUED on start).
  - `DownloadPlanning` — source→kind, `isDownloadable` (Live TV/channels/events/comics hidden), quality options with
    estimated size (bitrate×duration HLS/DASH, Content-Length progressive), `buildRecord` (adult → private folder).
  - Defaults: connections 4 Wi-Fi / 2 mobile / 2 LOW tier (`defaultConnections`); user override in DataStore.
- Android wiring: `DownloadsModule` (AppContainer.downloads, created in warmUp): Room `CinetorDb` (v1, room 2.6.1 +
  KSP 2.0.21-1.0.28), `DeviceConditionsMonitor` (network callback, power broadcast, StatFs), `DownloadPrefs` (DataStore),
  `Media3Downloads` (SimpleCache NoOpEvictor in files/media3_downloads + DownloadManager; DownloadHelper probe +
  track selection by height/audio lang/text langs), `Media3Runner` (HLS/DASH), `ProgressiveRunner` (+ sidecar subs).
- `CinetorDownloadService` (FGS dataSync, grouped progress notif, Pause/Resume/Cancel actions, stops when idle).
  `ResumeDownloadsWorker` (WorkManager, constraints network/unmetered/charging/storage; runs foreground; resumes after
  reboot/kill). Manifest: FGS + FGS_DATA_SYNC + POST_NOTIFICATIONS + ACCESS_NETWORK_STATE + WAKE_LOCK; WorkManager
  SystemForegroundService merged with dataSync type. POST_NOTIFICATIONS requested on "Start download" (API 33+).
- UI feature/downloads: `DownloadButton` (NONE/QUEUED/WAITING/DOWNLOADING %/PAUSED/COMPLETED/FAILED) on Details
  (replaces placeholder sheet; series → season), per-episode `EpisodeDownloadButton` + "Download season" + "Next 3
  episodes", `DownloadedBadge` on episode stills; `DownloadOptionsSheet` (quality + est size, audio, subs, location,
  connections slider); `DownloadsScreen` (Me → Downloads; Active/Completed/Failed, storage bar, sort, long-press
  multi-delete, quick settings: Wi-Fi only, charging only, parallel, speed limit, connections); `DownloadDetailsSheet`
  (%, bytes, current+avg speed, 60 s graph, ETA, elapsed, per-chunk bars / segments, quality, resolution, codec,
  bitrate, container, audio/subs, engine, host, path, timestamps, retries, last error, log, actions).
- Adult: `adult` flag, files in files/downloads_private (+.nomedia), hidden from Downloads/Me count unless unlocked,
  notification never names adult titles.

## Tests (unit, fake HTTP, no network)
ChunkedDownloaderTest: chunked_splitsRangesEvenly, chunked_resumesFromPersistedOffsets, chunked_noAcceptRanges_singleConnection,
chunked_retriesWithBackoff_thenCompletes, workStealing_splitsSlowChunk, workStealing_endToEnd_coversWholeFile,
sizeEstimate_fromBitrateAndDuration. DownloadQueueTest: queue_respectsMaxConcurrent, wifiOnly_pausesOnMobile,
pauseResume_keepsQueueOrder, adultDownloads_hiddenWhenLocked, qualityOptions_estimateSizePerHeight.

## Player hook (for wip/phase-3 SourceResolver)
`container.downloads.cacheProvider: DownloadCacheProvider`
- `offlineSource(itemId, episodeId)` → `OfflineSource.LocalFile(file, mime)` (progressive: play `Uri.fromFile`) |
  `OfflineSource.Cached(request)` (HLS/DASH: `DefaultMediaSourceFactory(provider.cacheDataSourceFactory(upstream))
  .createMediaSource(request.toMediaItem())`) | null → online.
- `cache` = download SimpleCache (separate dir from player's LRU cache; one SimpleCache per dir).
- `container.downloads.downloadedIds: StateFlow<Set<String>>` (id = `itemId` or `itemId:episodeId`) for badges.

## Deviations
- Own FGS (CinetorDownloadService) instead of Media3 `DownloadService`: engine schedules both kinds, one notification;
  Media3 DownloadManager used directly (main thread). HLS/DASH segment count ≈ duration/6 s × % (Media3 exposes % only).
- Adult HLS/DASH segments share the media3 cache dir (app-private); only progressive files use the private folder.
- No SD-card/SAF location yet (internal only); no auto-delete-watched (no watch history yet); comics/music
  album/chapter download not wired (comics hidden; music tracks download as MP3 via Details if opened).
- Adult unlock = Settings "Show 18+" until Task 4.4 PIN session → 4.4 must call `downloads.setAdultSessionUnlocked()`.
- `downloadedItem_playsOffline` not unit tested (needs player; hook documented above).
- Merge note: build.gradle.kts media3 block copied verbatim from wip/phase-3 + Room/WorkManager lines after it;
  AppContainer gets `downloads` + warmUp line; DetailsScreen/EpisodePicker/MeScreen/NavHost/Routes touched.
