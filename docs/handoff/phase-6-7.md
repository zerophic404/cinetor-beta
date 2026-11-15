# Phase 6 + 7 handoff (Android TV, perf, signed release)

Branch `wip/phase-6` (worktree ~/apk-projects/cinetor-dl) from main 78b4f2d → merged to main (see PROGRESS log).

## 6.1 TV packaging — done
- Manifest already had LEANBACK_LAUNCHER, `android:banner` (320×180 xhdpi), touchscreen/leanback `required=false`.
- Added `android.software.picture_in_picture` + `android.hardware.microphone` `required=false`, `<queries>` RECOGNIZE_SPEECH (voice search on API 30+).
- CI asserts via `aapt dump badging` (leanback-launchable-activity, banner, 3 optional features).

## 6.2 TV UI layer — done (`com.mythbrix.cinetor.tvui`)
- `TvShell` (5% overscan margins, Menu → Settings, D-pad with nothing focused → content, content refocus after
  route change / player close), `TvSideNav` (glass, icons collapsed 72dp → 232dp labels + groups while focused;
  Search · Home · Anime · Drama | SPACES Live TV · Music · Comics · Adult(gate) | MORE Downloads · Library · Profiles · Settings;
  entering lands on selected item via focusProperties.enter). Search = in-space search inside isolated spaces.
- `TvHomeScreen` (same SpaceViewModel as phone): `TvHero` (backdrop + scrims, Play/More info/My List; auto-advance
  paused while focus inside → `HeroPause.FOCUS`, also off-screen/lifecycle/setting), `TvTabs`, `TvRow`, `TvCard`
  (own focusable cards: scale 1.1, accent glow shadow, theme-accent 3dp border, glass sheen, focus sound if setting on).
- `FocusMemory` (pure) per screen (row,index) + per-row column; hero = row −1. Restored after Details back, player
  close (`LocalTvRefocus` tick) and row reloads (clamped). `TvGrid` pure D-pad rules (Up/Down keep remembered column,
  skip empty rows, Left at 0 → side nav, edge = stay). `TvFocusRegistry` maps (row,index) → FocusRequester.
- `TvPlayerScreen` (PlayerHost picks it when `deviceProfile.isTv`): no gestures; `TvPlayerKeys`/`TvScrubber` pure:
  Left/Right ±10 s, hold = accelerating scrub by hold time (10→30→60→120 s/s, preview while held, commit on release),
  OK play/pause + controls, Up controls with Settings focused, Down episode rail (movies: related same-genre),
  Back hides overlay then exits (consumed in onPreviewKeyEvent on key-up), Menu = player settings sheet, media keys
  (play/pause/play/pause/FF/REW/next). Live: Up/Down pass through → MainActivity zap; CH± + digits unchanged.
- Comics reader D-pad page turn already existed (4.3). Details/Search/Settings/Downloads/Music/EPG reachable by D-pad
  (Compose clickables are focusable; shell enters content after navigation). Search: mic button → RecognizerIntent.
- Tests `tvui/TvUiTest`: dpad_navigatesRowsAndNav, sideNav_hasVideoSpacesIsolatedGroupAndSettings,
  returnFromDetails_restoresFocusedCard, tvPlayer_rightKeySeeks10s, holdAcceleratesScrub, keyCodes_mapToTvKeys.
- CI `tv-smoke` job: android-emulator-runner `target: android-tv`, api 30, x86, profile tv_1080p — worked first try.
  `.github/scripts/tv-smoke.sh` drives only `adb shell input keyevent`; artifact `tv-screenshots` (home, side nav,
  row focus, details, back-restore, hero, player/seek/scrub/controls/rail/settings, settings, search). Gates release.

## 7.1 Optimization — done (partly deviating)
- Release: `isMinifyEnabled` + `isShrinkResources`, `android.enableR8.fullMode=true`; keep rules for @Serializable
  app classes + enums (Media3/Room/WorkManager/OkHttp consumer rules). Phone emulator smoke now runs the R8 release APK.
- ABI splits arm64-v8a / armeabi-v7a / x86_64 + universal (native code = Compose graphics-path only).
- Baseline profile: `profileinstaller` + hand-written `src/main/baseline-prof.txt` (startup/home/details/player/tvui +
  hot libraries); CI asserts `assets/dexopt/baseline.prof` bundled. NO generated profile / macrobenchmark module (deviation).
- Compose: `compose-stability.conf` (catalog models, player/settings state, read-only collections); compiler
  reports+metrics uploaded in artifact `r8-mapping-and-compose-reports` (with R8 mapping.txt). Strong skipping default.
- Coil: tier disk cache 48/128/256 MB (memory % + RGB_565 on LOW already). No idle row prefetch (deviation).
- versionCode = GITHUB_RUN_NUMBER, versionName = tag (vX.Y.Z) or `0.1.<run>`.
- Sizes (run 44): per-ABI ≈ 16.8 MB, universal 16.9 MB (mock catalog 547 files bundled ≈ 15 MB of that), debug 37.9 MB.
  Target < 20 MB met; plan's 15 MB not met because of bundled mock images.

## 7.2 Signed release — done
- Signing from secrets (PKCS12, storeType pkcs12, alias cinetor) for release AND debug (same cert → updates install).
- CI asserts every APK's cert SHA-256 = 7b0ef774…c44e (public fingerprint of ~/.config/cinetor/cinetor-release.p12);
  main / v* builds fail without keystore.
- Release job (main + v* tags, needs build + phone smoke + TV smoke): softprops → PUBLIC Ashrix404/cinetor-app,
  tag `android-build-<run>` (or the v tag), assets `Cinetor-<ver>-{arm64-v8a,armeabi-v7a,x86_64,universal}.apk`
  (SIGNED release). Debug universal only as CI artifact `apk-debug` (replaced debug in releases — decision).

## CI runs (wip/phase-6)
- 6.1: https://github.com/Ashrix404/cinetor/actions/runs/43678402983
- 6.2: https://github.com/Ashrix404/cinetor/actions/runs/43679742160 , fixes https://github.com/Ashrix404/cinetor/actions/runs/43681409616
- 7.1: https://github.com/Ashrix404/cinetor/actions/runs/43682848369
- 7.2: https://github.com/Ashrix404/cinetor/actions/runs/43684811368

## Open / next
- Details/Settings/EPG on TV reuse phone layouts (D-pad works, not 10-foot tuned). TV Live player has no channel-list button.
- tv-material not used (own focusable cards/carousel; avoids extra dep).
- Macrobenchmark (cold start / jank / memory) + generated baseline profile still open → Phase 10 QA.
- Next: Phase 9 desktop (other agent) → Phase 10 release v0.1.0 (tag `v0.1.0` now produces signed `Cinetor-0.1.0-*.apk`).
