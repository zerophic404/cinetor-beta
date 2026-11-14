# Phase 5 handoff (Android: profiles, settings, about)

Branch `wip/phase-5` (worktree ~/apk-projects/cinetor-dl), base wip/phase-4a. Status: IN PROGRESS.

## Done (code)
- 5.1 Profiles+library: `feature/profile/{ProfileData,Profiles,ProfilePicker,EditProfile,LibraryScreen,MeScreen}.kt`.
  Room `profiles.db` (ProfileDb: profile, history, watchlist). `ProfileManager` (max 5, PIN hashed via PinHasher, kids, theme, langs).
  Main profile id = "default" → old resume/search data kept. Active id in SettingsStore `active_profile`.
  `HistoryRecordingResumeStore` wraps resume → every save → Room history (video spaces only).
  My List = Room watchlist (`container.toggleMyList(item)`). Search recents / resume / subtitle style per active profile.
  Kids → `AdultGate(kidsProfile = profiles.isKids)` → toggle greyed, switcher hides Adult.
  Delete profile → history+watchlist+resume+search recents wiped. Startup "Who's watching?" only if >1 profile (cold start).
- 5.2 Settings: `core/prefs/AppSettings.kt` (model + SettingsCodec JSON blob `app_settings_v1`), `SettingsStore : SettingsSource`,
  `feature/settings/{SettingsStateHolder,SettingsScreen}.kt`. Groups: Content, Appearance (theme preview cards), Playback,
  Subtitles & audio, Data & storage, Profiles, TV, Developer, About. Root collects state → live theme, no restart.
  Wired: double-tap step + hold speed + gestures on/off (GesturePrefs → GestureController), autoplay next, auto skip intro,
  auto PiP/background play (legacy keys mirrored), data saver + preload-on-details (preloader resolve-only),
  quality Wi-Fi/mobile + data saver → ExoPlayer max height, langs → preferred audio/text, cache size → SimpleCache (restart),
  clear image cache (Coil), clear history, reduce motion (LOW-tier motion + banner still), dynamic color, banner autoplay,
  mock latency, 18+ PIN change.
- 5.3 About: `core/AppLinks.kt`, `feature/about/{AboutContent,AboutScreen}.kt`. BuildConfig.BUILD_NUMBER = GITHUB_RUN_NUMBER.
- Tests: ProfilesTest (continueWatching_onlyVideoSpaces, deleteProfile_removesItsData, maxFiveProfiles,
  kidsProfile_cannotEnableAdult, profilePin_requiredToSwitch, historyRecordingResumeStore_writesProfileHistory),
  SettingsTest (themeChange_appliesWithoutRestart, settings_persistAcrossProcessDeath, doubleTapStep_followsSetting),
  AboutTest (about_linksOpenExactUrls, about_copyExact).
- Smoke: `.github/scripts/smoke-phase5.sh` (shots 70-79), sourced after 4b.

## Deviations
- Settings order: Content group first (18+ toggle must stay on first screen for smoke-4b + parental visibility).
- Me: Theme/Effects rows removed (now in Settings); keeps Me rows on one screen.
- Image quality, TV focus sounds, maturity limit: stored only (not yet applied).
- History records video spaces only (isolation; Adult never in Library).

## CI
- (pending)

## Open bugs
- none known yet
