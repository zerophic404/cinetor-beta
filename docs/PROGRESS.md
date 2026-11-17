# Cinetor — PROGRESS (single source of truth)

Update this file after EVERY finished task (not just phases). Commit + push right away.
Status: [x] done · [~] in progress / partial · [ ] not started
Plan: docs/plans/2026-11-14-cinetor-master-plan.md · Decisions: docs/handoff/decisions.md · Phase notes: docs/handoff/phase-*.md

## Resume here
- Active private repository: `Ashrix404/cinetor-v2` (`main`), publishing signed Android/TV APKs, Windows (`.exe`/`.msi`) & Linux (`.deb`/`.AppImage`) desktop installers, and `gh-pages` PWA to public `Ashrix404/cinetor-app`.
- Phase 12 (`v2.0.0` Cinetor Beta Parity & Liquid Glass Upgrade) in progress across 16 parallel branches (`agent/01-tokens-themes` .. `agent/16-qa-security-ci`):
  - 8-theme Liquid Glass design system (`Glass`, `Matte`, `Material`, `Aura`, `Pearl` light mode, `Noir`, `Obsidian`, `Ember`) + zero-recomposition shimmer & Aura capsule navigation across Android, Android TV, Web PWA & Desktop.
  - Full Cinetor Beta gateway shelf parity (Home Top-10 ranked charts, Short TV, Bengali/Indian TV, K/C/J/Pakistani Drama, Anime/Cartoons, gated 18+ Adult), network-aware Wi-Fi/mobile stream ranking, and poster rating/dub badges.
  - Live gateway Music space (charts, search, albums/playlists, time-synced LRC lyrics, FLAC/320kbps playback & downloads) + resilient Manga/Manhwa/Manhua Comics reader.
  - Chromeless in-app YouTube player (`youtube-nocookie.com` IFrame bridge) + multi-season/multi-episode batch series downloader on Android, Web & Desktop.
  - R8 full-mode `@JavascriptInterface` / `@Serializable` / Media3 / Haze keep rules, unified `v*` tag release pipeline (`android.yml`, `desktop.yml`, `web.yml`), crash-hardened phone & TV emulator smoke scripts, and leak/persistence regression tests ready on `agent/16-qa-security-ci`.

## Phase checklist
### Phase 0 — setup, brand, mock  [x]
- [x] 0.1 monorepo + CI · [x] 0.2 brand kit (64 assets) · [x] 0.3 tokens + mock catalog (267 items)
### Phase 1 — Android foundation  [x]
- [x] 1.1 data layer · [x] 1.2 design system/3 themes/skeletons · [x] 1.3 perf tier · [x] 1.4 shell/nav/splash · [x] review fixes
### Phase 2 — Android content  [x]
- [x] 2.0 TMDB source (HybridCatalog: TMDB video spaces, mock fallback/isolated) · [x] 2.1 SpaceScreen (hero carousel, tabs, chips, rows, tint) · [x] 2.2 Details + skeleton (warm hook, Play+Ready dot, Download placeholder) · [x] 2.3 Search (in-mem index, DataStore history/profile, TMDB merge, LRU100) · [x] CI emulator smoke + screenshots
### Phase 3 — Player + downloads  [x]
- [x] 3.1 core · [x] 3.2 preloader/instant play · [x] 3.3 UI portrait/landscape · [x] 3.4 gestures · [x] 3.5 subtitles · [x] 3.6 floating/PiP/service · [x] 3.7 download engine + UI · [x] offline playback of downloads
### Phase 4 — Isolated spaces  [x]
- [x] 4.1 Live TV + Sports + EPG · [x] 4.2 Music · [x] 4.3 Comics reader · [x] 4.4 Adult gate (merged 78b4f2d)
### Phase 5 — Profiles/Settings/About  [x]
- [x] 5.1 profiles + library · [x] 5.2 settings · [x] 5.3 about (links + TMDB credit) (merged 78b4f2d)
### Phase 6 — Android TV  [x]
- [x] 6.1 packaging (badging asserted in CI) · [x] 6.2 TV UI (tvui: shell/side nav/hero/rows/cards) + FocusMemory + TV player keys + TV emulator smoke — notes docs/handoff/phase-6-7.md
### Phase 7 — Perf + release  [x]
- [x] 7.1 R8 full + shrink, ABI splits + universal, profileinstaller + hand-written baseline profile, Compose stability config · [x] 7.2 signed release APKs (per-ABI + universal) → cinetor-app releases
### Phase 8 — Web/PWA  [x]
- [x] 8.1 foundation + shell + PWA basics · [x] 8.2 screens · [x] 8.3 player (shaka + native fallback, persistent `<video>`, gestures, subs, resizable mini/PiP) · [x] 8.4 TV spatial mode · [x] 8.5 full PWA + OPFS downloads · [x] 8.6 deploy → cinetor-app gh-pages
### Phase 9 — Desktop  [x]
- [x] 9.1 Tauri 2 shell + native multi-connection/HLS downloader + resizable pop-out mini window · [x] 9.2 Windows (`.exe`/`.msi`) + Linux (`.deb`/`.AppImage`) CI
### Phase 10 — Release  [x]
- [x] v0.1.0 public release + README + QA matrix
### Phase 11 — Gateway, Comics, Hardening & Downloads UX  [x]
- [x] 11.1 26 curated gateway home sections + short-tv + zee-bangla + gated 18+ · [x] 11.2 MangaDex + gateway c2 comics · [x] 11.3 Hardening §1–§4 (ownership notice, zero URL leaks, resizable PiP/mini, deduped downloads + SAF/native folder picker) (merged c4670b9, 447c11e, a8a8f16)
### Phase 12 — v2.0.0 Cinetor Beta Parity & Liquid Glass Upgrade  [~]
- [~] 12.1 8-theme tokens & Liquid Glass UI (Android, TV, Web, Desktop) · [~] 12.2 Gateway Beta shelves, dub/rating badges & network-aware stream ranking · [~] 12.3 Live Gateway Music (LRC synced lyrics + FLAC/320kbps) & Spaces hub · [~] 12.4 Chromeless in-app YouTube player & multi-season batch series downloader · [x] 12.5 R8 `@JavascriptInterface`/serialization/Haze keep rules, unified `v*` CI release workflows & hardening/smoke guards

## Log (newest first, one line each)
- 2026-11-18 Phase 12 QA/Security/CI (`agent/16-qa-security-ci`): added R8 keep rules for WebView `@JavascriptInterface` (`AndroidBridge`), `kotlinx.serialization`, Media3/FFmpeg/FLAC, and Haze/RenderEffect; unified `v*` tag release publishing in `android.yml` + `desktop.yml` + `web.yml` to `Ashrix404/cinetor-app`; hardened `emulator-smoke.sh` + `tv-smoke.sh` logcat crash gates and `hardening.test.ts` `youtube-nocookie.com` iframe guards.
- 2026-11-16 Phase 11 merged on `main` (`447c11e`, `c4670b9`, `a8a8f16`): gateway 26 home sections + comics + hardening §1–§4 + web decoder fallback & PWA cache heal.
- 2026-11-15 13:25 Ph6+7 merged (PR #4, android-build-46 signed), Ph9 merged (PR #3, desktop-build-4), Ph10: v0.1.0 public release + README; old test releases deleted. DONE.
- 2026-11-15 Ph6+7 done on wip/phase-6 (caa9aac..), runs 43678402983 (6.1) 43681409616 (6.2) 43682848369 (7.1) 43684811368 (7.2) green incl. phone + Android TV emulator smoke; merged to main 1d0c502 (PR #4), main run https://github.com/Ashrix404/cinetor/actions/runs/43687370076 green → https://github.com/Ashrix404/cinetor-app/releases/tag/android-build-46 (signed Cinetor-0.1.46-{arm64-v8a,armeabi-v7a,x86_64,universal}.apk). Per-ABI 16.8 MB. Handoff docs/handoff/phase-6-7.md.
- 2026-11-15 00:40 Ph3 merged (android-build-30), web 8.3–8.5 merged; Ph4 started; packed for limit, auto-resume 04:45.
- 2026-11-15 00:20 Phase 3 merged to main 696e0ee (dl/phase-3 → wip/phase-3 f2c911a, offline playback 1f347fc: OfflineResolver local file / download cache, Downloads Play → player, OfflineResolverTest 5). CI wip https://github.com/Ashrix404/cinetor/actions/runs/43622939407 , main https://github.com/Ashrix404/cinetor/actions/runs/43624369659 green; APK https://github.com/Ashrix404/cinetor-app/releases/tag/android-build-30 .
- 2026-11-14 20:10 Ph3 code complete on two branches, packed for limit; auto-resume cron scheduled.
- 2026-11-14 16:55 Phase 2 merged to main 9231ed9; APK android-build-14 public; site deployed.
- 2026-11-14 16:35 Android 2.1 + 2.2 done on wip/phase-2 (ed1e761..76b6f89), Android CI green incl. emulator smoke: https://github.com/Ashrix404/cinetor/actions/runs/43585743790 (also 43584661105). 62 unit tests (new: 12 space/hero/tint + 7 details/preloader). Screenshots reviewed (home hero/tabs/chips/rows, anime, drama, details+Ready dot, download sheet, series tab swap) OK. Deviations: PlaybackPreloader = interface + SimulatedPlaybackPreloader (timer readiness) until 3.2; My List in-memory (AppContainer) until 5.1; CONTINUE/episode progress = deterministic mock; Play opens Details on hero / toast on Details until player; shared poster transition MID/HIGH only (LOW = fade+scale); smoke bottom-bar taps fixed (were hitting system nav bar).
- 2026-11-14 16:20 Web 8.3 player part green on wip/phase-2 (5b2b4c1), run https://github.com/Ashrix404/cinetor/actions/runs/43584460543 . 85 vitest pass (28 new: gesture math, SRT→VTT/offset, preload latest-wins/abort/cancel, mini replaced single audio, resume/continueWatching isolation). Browser QA on CI artifact: preload buffers in park (Ready dot), Play moves same element (1 <video>), HLS quality list 184–1080p, double-tap +10/+20, hold 2×→restore, drag seek/volume/brightness, ext SRT load, keys, minimize→mini across routes, snap/expand/swipe-off, new Play replaces mini, Resume label. Deviations: autoplay blocked w/o user gesture → tap-to-play overlay; embedded stream subs no sync offset (native render, hint shown); Document PiP not done (video PiP only); shaka chunk (777 KB) also precached by SW glob; downloads pending.
- 2026-11-14 15:55 Web 8.2 (Ph2-equivalent) green on wip/phase-2 (64f8689..b41d132), run https://github.com/Ashrix404/cinetor/actions/runs/43581335439 . 57 vitest pass. TMDB live verified on local preview of CI artifact (hero auto-advance, tint, details, episodes, search remote+typo, history). Details = /app/title?id= (query param, prerenderable); /app/watch = stub player (native MP4 only) until 8.3. Search worker + own prefix index (no FlexSearch dep). Continue progress = placeholder until playback history. Rows not truly virtualized (content-visibility only).
- 2026-11-14 15:50 Android 2.0 + 2.3 done on wip/phase-2 (2955048), Android CI green incl. emulator smoke: https://github.com/Ashrix404/cinetor/actions/runs/43581257120 . Deviation: in-memory precomputed index instead of Room FTS4; history in DataStore JSON (no Room yet); no 24h disk query cache, no voice search/filter chips/skeleton grid yet (UI basic).
- 2026-11-14 15:10 rate limit hit mid Phase 2 (both subagents 429). Partial saved to wip/phase-2. PROGRESS.md created.
- 2026-11-14 12:38 Ph1 review done + fixes (22615e7).
- 2026-11-14 12:30 Ph1 Android + Web 8.1 green.
- 2026-11-14 11:55 Ph0 green.
