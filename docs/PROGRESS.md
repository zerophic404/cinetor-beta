# Cinetor — PROGRESS (single source of truth)

Update this file after EVERY finished task (not just phases). Commit + push right away.
Status: [x] done · [~] in progress / partial · [ ] not started
Plan: docs/plans/2026-11-14-cinetor-master-plan.md · Decisions: docs/handoff/decisions.md · Phase notes: docs/handoff/phase-*.md

## Resume here
- RULE (user): every green main build → publish installers to PUBLIC Ashrix404/cinetor-app releases. Desktop from Phase 9.
- Phase 3 DONE, merged to main 696e0ee, release android-build-30. Notes: docs/handoff/phase-3.md (+ dl-phase-3.md).
- NEXT (max 2 agents, separate worktrees):
  1. Android Phase 4: 4.1 Live TV + Sports + EPG · 4.2 Music · 4.3 Comics reader · 4.4 Adult gate (call `downloads.setAdultSessionUnlocked()` from PIN session). Branch wip/phase-4, worktree ~/apk-projects/cinetor.
  2. Web 8.3 downloads (shaka offline + OPFS): branch web/phase-3, worktree ~/apk-projects/cinetor-web (another agent; merge when green).
  3. Then Phases 5→10 per plan. Final: report all download links to user.

## Phase checklist
### Phase 0 — setup, brand, mock  [x]
- [x] 0.1 monorepo + CI · [x] 0.2 brand kit (64 assets) · [x] 0.3 tokens + mock catalog (267 items)
### Phase 1 — Android foundation  [x]
- [x] 1.1 data layer · [x] 1.2 design system/3 themes/skeletons · [x] 1.3 perf tier · [x] 1.4 shell/nav/splash · [x] review fixes
### Phase 2 — Android content  [x]
- [x] 2.0 TMDB source (HybridCatalog: TMDB video spaces, mock fallback/isolated) · [x] 2.1 SpaceScreen (hero carousel, tabs, chips, rows, tint) · [x] 2.2 Details + skeleton (warm hook, Play+Ready dot, Download placeholder) · [x] 2.3 Search (in-mem index, DataStore history/profile, TMDB merge, LRU100) · [x] CI emulator smoke + screenshots
### Phase 3 — Player + downloads  [x]
- [x] 3.1 core · [x] 3.2 preloader/instant play · [x] 3.3 UI portrait/landscape · [x] 3.4 gestures · [x] 3.5 subtitles · [x] 3.6 floating/PiP/service · [x] 3.7 download engine + UI · [x] offline playback of downloads
### Phase 4 — Isolated spaces  [ ]
- [ ] 4.1 Live TV + Sports + EPG · [ ] 4.2 Music · [ ] 4.3 Comics reader · [ ] 4.4 Adult gate
### Phase 5 — Profiles/Settings/About  [ ]
- [ ] 5.1 profiles + library · [ ] 5.2 settings · [ ] 5.3 about (links + TMDB credit)
### Phase 6 — Android TV  [ ]
- [ ] 6.1 packaging · [ ] 6.2 TV UI + focus memory + TV player keys
### Phase 7 — Perf + release  [ ]
- [ ] 7.1 R8, ABI splits, baseline profile, benchmarks · [ ] 7.2 signed release CI (secrets ready)
### Phase 8 — Web/PWA  [~]
- [x] 8.1 foundation + shell + PWA basics · [~] 8.2 screens — Ph2-equivalent DONE on wip/phase-2 (TMDB source, SpaceView hero/tabs/chips/rows/tint, details+skeleton, worker search+local history); left: isolated-space specifics (EPG/reader/adult gate), profiles/settings/about, Playwright · [~] 8.3 player DONE on wip/phase-2 (shaka lazy chunk, persistent <video> moved park→player→mini, preload latest-wins + Ready dot, controls/settings/subs style+sync, gestures, keyboard, mini/PiP/Media Session, IDB resume, autoplay next); search worker done in 8.2; left: downloads (shaka offline + OPFS) · [ ] 8.4 TV mode · [ ] 8.5 full PWA + landing · [ ] 8.6 deploy → cinetor-app gh-pages
### Phase 9 — Desktop  [ ]
- [ ] 9.1 Tauri shell + native downloader · [ ] 9.2 exe/msi/deb/AppImage CI
### Phase 10 — Release  [ ]
- [ ] release.yml v0.1.0 · [ ] QA matrix · [ ] final report to user

## Log (newest first, one line each)
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
