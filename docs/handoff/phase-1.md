# Handoff — after Phase 0 + Phase 1 (Android) + Web 8.1 foundation
Date 2026-11-14. Caveman. Plan: docs/plans/2026-11-14-cinetor-master-plan.md

## Done
- Ph0: monorepo, CI (android/web/desktop yml), brand kit (shared/scripts/gen_icons.py, 64 assets; shared/brand/out is gitignored — android res already copied, web has compact copy web/static/brand via web/scripts/sync-brand.py), tokens (Noir/Obsidian/Ember), mock catalog 267 items + 538 imgs, 15 py tests.
- Ph1 Android (1.1–1.4) GREEN: commit e0dccca, run https://github.com/Ashrix404/cinetor/actions/runs/43566343019
  - core/data (Models, CatalogRepository + allItems(space), MockCatalogSource, SearchRepository, MockLatency, AppContainer), core/perf tier, core/prefs/SettingsStore (theme, effects, adult flag), design/ (CinetorTheme, glass via Haze 1.1.1 haze()/hazeChild(), shimmer + 8 skeletons, DynamicTint `Cinetor.tint.setSource()`, ShimmerHost), navigation (type-safe routes, space passed as String, bottom bar/rail/TV placeholder rail, SpacesSwitcher), splash AVD + Compose intro, placeholder screens. Gradle copies shared/mock into assets (CI asserts 547 files).
  - ~27 JVM tests. No emulator UI tests yet.
- Web 8.1 + shell + PWA basics GREEN: commit 2d4215c, run .../43565323012. SvelteKit kit2/vite7/vitest3.2/@vite-pwa/sveltekit, Node 22, no lockfile. 27 vitest. Routes /, /app, /app/[space], /app/[space]/search, /app/search, /app/me. Artifact `web-build` per run.

## Deviations / known
- Live blur only on overlays (bars, rail, sheets); cards = tinted glass.
- Pages deploy FAILS: private repo on free plan (422). Deploy job non-blocking. NEEDS USER DECISION (public repo vs Cloudflare/Netlify/Vercel) — asked in chat.
- Phase 1 + 8.1 NOT yet independently reviewed → first job of next session: one review subagent (spec + quality) over android/ and web/, fix critical/important.
- APK not visually checked on device.

## Next (run both tracks in parallel)
- Android Phase 2: Task 2.0 TMDB (secret TMDB_TOKEN → BuildConfig via android.yml env), 2.1 SpaceScreen real (replace placeholder + VM, hero → tint), 2.2 Details + skeleton (+ warm stub until 3.2), 2.3 Search (FTS local, history local, TMDB merge).
- Web: Task 8.2 real screens matching Android Ph2 (hero carousel, tabs, chips, rows, details skeleton, search w/ FlexSearch worker + IndexedDB history, TMDB via PUBLIC_TMDB_TOKEN from secret).
- Then relay → phase-2 handoff.
