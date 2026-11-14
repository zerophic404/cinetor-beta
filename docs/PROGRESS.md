# Cinetor — PROGRESS (single source of truth)

Update this file after EVERY finished task (not just phases). Commit + push right away.
Status: [x] done · [~] in progress / partial · [ ] not started
Plan: docs/plans/2026-11-14-cinetor-master-plan.md · Decisions: docs/handoff/decisions.md · Phase notes: docs/handoff/phase-*.md

## Resume here
- RULE (user): every green build → publish APK/exe/deb/AppImage to Releases of PUBLIC repo Ashrix404/cinetor-app (secret RELEASE_TOKEN). See decisions.md. Do this in android.yml now (Phase 2), desktop in Phase 9.
- Current: Phase 2 (Android 2.0–2.3) + Web 8.2.
- 15:21 relay cron interrupted. wip/phase-2 now at c4b9096: CI wired (emulator smoke+screenshots, public cinetor-app releases, gh-pages deploy, signing). Android CI RED: TmdbCatalogSource refs missing `RemoteSearch` interface. Web CI green on branch.
- Partial work: branch `wip/phase-2` (commit 7ccf277) — TMDB source (android core/data/tmdb/, MockSourceProvider, Models changes, android.yml TMDB env, build.gradle) + web data utils (color.ts, images.ts, lru.ts, mock-sources.ts, types.ts). Not compiled yet. Next session: merge/rebase that branch into main work, finish tasks, CI green, then merge to main.
- Last green main: 22615e7 (Ph1 review fixes), Android + Web CI green.

## Phase checklist
### Phase 0 — setup, brand, mock  [x]
- [x] 0.1 monorepo + CI · [x] 0.2 brand kit (64 assets) · [x] 0.3 tokens + mock catalog (267 items)
### Phase 1 — Android foundation  [x]
- [x] 1.1 data layer · [x] 1.2 design system/3 themes/skeletons · [x] 1.3 perf tier · [x] 1.4 shell/nav/splash · [x] review fixes
### Phase 2 — Android content  [~]
- [x] 2.0 TMDB source (HybridCatalog: TMDB video spaces, mock fallback/isolated) · [ ] 2.1 SpaceScreen (hero carousel, tabs, chips, rows, tint) · [ ] 2.2 Details + skeleton · [x] 2.3 Search (in-mem index, DataStore history/profile, TMDB merge, LRU100) · [x] CI emulator smoke + screenshots
### Phase 3 — Player + downloads  [ ]
- [ ] 3.1 core · [ ] 3.2 preloader/instant play · [ ] 3.3 UI portrait/landscape · [ ] 3.4 gestures · [ ] 3.5 subtitles · [ ] 3.6 floating/PiP/service · [ ] 3.7 download engine + UI
### Phase 4 — Isolated spaces  [ ]
- [ ] 4.1 Live TV + Sports + EPG · [ ] 4.2 Music · [ ] 4.3 Comics reader · [ ] 4.4 Adult gate
### Phase 5 — Profiles/Settings/About  [ ]
- [ ] 5.1 profiles + library · [ ] 5.2 settings · [ ] 5.3 about (links + TMDB credit)
### Phase 6 — Android TV  [ ]
- [ ] 6.1 packaging · [ ] 6.2 TV UI + focus memory + TV player keys
### Phase 7 — Perf + release  [ ]
- [ ] 7.1 R8, ABI splits, baseline profile, benchmarks · [ ] 7.2 signed release CI (secrets ready)
### Phase 8 — Web/PWA  [~]
- [x] 8.1 foundation + shell + PWA basics · [~] 8.2 screens — Ph2-equivalent DONE on wip/phase-2 (TMDB source, SpaceView hero/tabs/chips/rows/tint, details+skeleton, worker search+local history); left: isolated-space specifics (EPG/reader/adult gate), profiles/settings/about, Playwright · [ ] 8.3 player + downloads + search worker · [ ] 8.4 TV mode · [ ] 8.5 full PWA + landing · [ ] 8.6 deploy → cinetor-app gh-pages
### Phase 9 — Desktop  [ ]
- [ ] 9.1 Tauri shell + native downloader · [ ] 9.2 exe/msi/deb/AppImage CI
### Phase 10 — Release  [ ]
- [ ] release.yml v0.1.0 · [ ] QA matrix · [ ] final report to user

## Log (newest first, one line each)
- 2026-11-14 15:55 Web 8.2 (Ph2-equivalent) green on wip/phase-2 (64f8689..b41d132), run https://github.com/Ashrix404/cinetor/actions/runs/43581335439 . 57 vitest pass. TMDB live verified on local preview of CI artifact (hero auto-advance, tint, details, episodes, search remote+typo, history). Details = /app/title?id= (query param, prerenderable); /app/watch = stub player (native MP4 only) until 8.3. Search worker + own prefix index (no FlexSearch dep). Continue progress = placeholder until playback history. Rows not truly virtualized (content-visibility only).
- 2026-11-14 15:50 Android 2.0 + 2.3 done on wip/phase-2 (2955048), Android CI green incl. emulator smoke: https://github.com/Ashrix404/cinetor/actions/runs/43581257120 . Deviation: in-memory precomputed index instead of Room FTS4; history in DataStore JSON (no Room yet); no 24h disk query cache, no voice search/filter chips/skeleton grid yet (UI basic).
- 2026-11-14 15:10 rate limit hit mid Phase 2 (both subagents 429). Partial saved to wip/phase-2. PROGRESS.md created.
- 2026-11-14 12:38 Ph1 review done + fixes (22615e7).
- 2026-11-14 12:30 Ph1 Android + Web 8.1 green.
- 2026-11-14 11:55 Ph0 green.
