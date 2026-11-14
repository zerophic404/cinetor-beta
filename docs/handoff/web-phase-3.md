# Handoff — Web phase 3 (8.3 downloads · 8.4 TV mode · 8.5 PWA + landing)
Caveman. Branch web/phase-3 (worktree ~/apk-projects/cinetor-web), from main 7dcf4d3.

## Done
- 8.3 downloads `web/src/lib/download/`
  - math.ts: splitRanges (1–8 conn, ≥1 MiB chunks), resumeFrom (min persisted/committed), estimateSize, SpeedMeter (3 s window, active-time avg, 60 s sparkline), eta, backoff, format.
  - queue.ts: FIFO, max concurrent (default 2, 1–5), pause/resume/cancel/prioritize; runner injected.
  - progressive.ts: HEAD + `Range: bytes=0-0` probe (206 ⇒ ranges), N parallel Range fetches, 4 MiB write-once pieces → PieceStore, offsets persisted after every piece, resume, no-Range fallback → 1 conn, size verify, assemble = Blob of lazy OPFS Files (no copy).
  - store.ts: OPFS pieces `/cinetor-downloads/<id>.<chunk>.<seq>.part` (IndexedDB blob fallback when no createWritable), records in IDB `cinetor-dl/downloads`, quota/persist.
  - adaptive.ts: shaka.offline.Storage; probe via throwaway shaka Player on detached <video> → qualities (per height, est size = bw×dur), audio, text; trackSelectionCallback matches choice; response filter counts segments + bytes (speed); abort = pause (restarts pass, shaka can't resume partial).
  - manager.svelte.ts: singleton runes state, auto-resume interrupted on load, auto-retry ×3 backoff, live stats 1 s, offlineMedia (key `…@offline` so warm network buffer never reused), offlineStub (play offline when catalog can't load), adult hidden unless `localStorage cinetor.adultUnlocked=1`.
  - UI: DownloadButton (details + per-episode compact; states idle/queued/%/paused/done/retry; hidden for Live TV), DownloadOptionsSheet (quality+size, audio, subs, connections 1–8, free space, iOS caveat), DownloadDetailsSheet (%, bytes, speed/avg/ETA/elapsed, 60 s sparkline, per-chunk bars or segments, quality/codec/bitrate/container/tracks/size/storage path/host/timestamps/retries/last error, actions), `/app/downloads` (Active/Completed/Failed, storage bar, persist button, concurrency + connections settings, iOS banner), Me → Downloads link.
  - Browser QA on CI artifact: MP4 21.9 MB 4×range done in 2 s → OPFS; HLS 184p 65 segs with live speed/ETA/sparkline → shaka IDB; offline playback.
- 8.4 TV `web/src/lib/tv/spatial.ts`: nearest-neighbour (primary gap + 4×misalignment + 0.1×center offset), modal-dialog scope, auto-enable TV UA (Tizen/webOS/Android TV/AFTx/BRAVIA…) / `?tv=1` (persist; `?tv=0` off) / first arrow key; Back = Escape/Backspace/461/10009; focus memory per route (key + index, retries while content loads); glass glow ring + scale (`html[data-tv]`). Player: ←/→ ±10 s, Enter play/pause, ↑ shows + focuses controls, Back minimizes; inside controls spatial nav moves.
- 8.5 PWA: `scripts/dup-index.mjs` postbuild copies `x.html` → `x/index.html` (fixes `/app/` 404 on GH Pages); manifest shortcuts Search/Live TV/Music/Downloads with icons + 6 screenshots (`static/screens`, real captures of mock-content screens); offline page `/offline` via workbox precacheFallback for non-app navigations; shaka chunk dropped from precache (manifestTransforms) + CacheFirst runtime cache for `/_app/immutable/*.js`; install button (beforeinstallprompt) + iOS Share→Add to Home Screen sheet (landing hero + Me); full landing (animated logo, features, screenshot carousel, platform download cards → cinetor-app releases/latest, Open Web App, credits + TMDB attribution).
- Tests: download.test.ts (18: split/resume/no-range/pause/size/speed/ETA/queue/adaptive select/adult hidden), spatial.test.ts (8).

## Deviations
- HLS/DASH pause/resume restarts segment download (shaka offline has no partial resume). Segment total = estimate (done/fraction).
- No dynamic work-stealing between connections (static split). No Wi-Fi-only/charging (no reliable web API).
- Probe of progressive size can take a few seconds (HEAD + 1-byte range GET).
- Screenshots = real UI captures of mock-art screens (no TMDB posters).

## CI runs
- see bottom (appended per step).
