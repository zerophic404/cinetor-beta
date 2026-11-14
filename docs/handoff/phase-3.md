# Phase 3 — Player + downloads (Android) — DONE

Main merge 696e0ee ("Merge Phase 3 (player + downloads)"). Branches: wip/phase-3 (player 3.1–3.6), dl/phase-3 (3.7).
Detail notes: dl-phase-3.md (download engine), PROGRESS.md log (3.1–3.6 per task).

## Done
- 3.1 Media3 core: ONE ExoPlayer (PlayerFactory), tier buffers, 200/80 MB LRU SimpleCache, HLS/DASH/SS/RTSP/progressive.
- 3.2 preloader: MediaPlaybackPreloader owned slot, warm on Details, Ready dot, instant start; resume DataStore.
- 3.3 UI: PlayerHost overlay above NavHost (FULL/MINI/HIDDEN), portrait+landscape, settings sheet, autoplay next, skip intro, sleep.
- 3.4 gestures, 3.5 subtitles (side-load, sync offset, style per profile), 3.6 mini + PiP + PlaybackService MediaSession.
- 3.7 downloads: chunked multi-conn HTTP (work stealing, resume), Media3 DownloadManager HLS/DASH, queue engine,
  FGS dataSync + WorkManager resume, Downloads screen, options/details sheets, per-episode + season buttons.
- Merge resolution: union deps (single media3 1.5.1 + session + Room/WorkManager/KSP); manifest both services
  (PlaybackService mediaPlayback + CinetorDownloadService dataSync + WorkManager SystemForegroundService dataSync),
  FGS + FGS_MEDIA_PLAYBACK + FGS_DATA_SYNC + POST_NOTIFICATIONS + NETWORK_STATE + WAKE_LOCK; AppContainer = player + downloads;
  Details = Play/Resume + warm + Ready dot + resume bar AND DownloadButton/season/next-3; EpisodeRow = progress + badge + dl button.
- Offline playback (new):
  - core/download/OfflinePlayback.kt pure `OfflineResolver.resolve(completed, itemId, episodeId, fileExists)` →
    `OfflinePlan.LocalFile(path, mime, sidecars)` | `Cached(downloadId, kind)` | null (online). Media3Downloads.offlineSource uses it.
  - ExoCinetorPlayer.load → `mediaSourceFor`: LocalFile → DefaultDataSource on file (no network); Cached →
    `DownloadRequest.toMediaItem()` via `provider.cacheDataSourceFactory(upstream)`; else online LRU path. Logs
    `source offline-file|offline-cache <key>` (tag of ExoCinetorPlayer). Offline subs = asset/file plan subs + downloaded sidecars.
  - PlayerFactory(…) { downloads.cacheProvider } → warm/preload also picks offline source automatically.
  - Downloads screen Play + DownloadDetailsSheet Play → `container.playDownload(record)`: catalog item if answers ≤1.5 s,
    else `OfflineResolver.playableItem(record)` stub (airplane mode). Resume = same ResumeStore (profile+item+episode).
  - Tests OfflineResolverTest 5: downloadedItem_resolvesToLocalSource, downloadedEpisode_hls_resolvesToDownloadCache,
    missingFileOrIncomplete_fallsBackToOnline, sidecarSubs_resolvedNextToFile_andOnlineSubsDropped,
    playableItem_fromEpisodeRecord_resolvesSameEpisode.

## Commits
- f2c911a merge dl/phase-3 → wip/phase-3 · 1f347fc offline playback · 696e0ee merge → main.

## CI
- wip/phase-3 green (build + unit + emulator smoke): https://github.com/Ashrix404/cinetor/actions/runs/43622939407
- main: https://github.com/Ashrix404/cinetor/actions/runs/43624369659
- Release: https://github.com/Ashrix404/cinetor-app/releases/tag/android-build-30 (APK universal + per-ABI)

## Deviations
- Own FGS for downloads (not Media3 DownloadService) — see dl-phase-3.md.
- Offline playback not exercised on emulator (smoke has no airplane-mode download→play step); covered by pure resolver tests + code path.
- Remote (http) side-loaded subs dropped when playing offline (else load error); progressive sidecars used instead.
- HLS/DASH offline: segments read from download cache; the separate player LRU cache is bypassed for those.
- Playing a download while online with the catalog item: still uses local copy (no bandwidth), by design.

## Known gaps
- No SD card/SAF location, no auto-delete-watched, comics/music downloads not wired (dl-phase-3.md).
- 4.4 must call `downloads.setAdultSessionUnlocked()` from PIN session.
- No 150% volume boost; seek step fixed 10 s until 5.2 settings; My List in-memory until 5.1.

## Next
- Phase 4: 4.1 Live TV+Sports+EPG, 4.2 Music, 4.3 Comics reader, 4.4 Adult gate (Android, worktree ~/apk-projects/cinetor).
- Parallel: Web 8.3 downloads on branch web/phase-3 (worktree ~/apk-projects/cinetor-web, other agent).
