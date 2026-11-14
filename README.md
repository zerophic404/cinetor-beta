# Cinetor

Cinetor is a cross-platform streaming app with a red-and-black look. One monorepo holds three clients that share the same brand assets, design tokens and mock catalog:

| Platform | Tech | Location |
|---|---|---|
| Android phone + Android TV | Kotlin, Jetpack Compose (Leanback-ready launcher) | `android/` |
| Web app + website + PWA | Svelte 5 / SvelteKit | `web/` |
| Desktop (Windows + Linux) | Tauri 2 shell around the web build | `desktop/` |

## Monorepo layout

```
cinetor/
  shared/             brand (SVG logos), design tokens (themes/motion), mock catalog + scripts
  android/            Gradle project (Kotlin + Compose, package com.mythbrix.cinetor)
  web/                SvelteKit app (landing at /, PWA at /app)
  desktop/            Tauri 2 shell (src-tauri) pointing at web/build
  .github/workflows/  android.yml, web.yml, desktop.yml
  docs/plans/         project plan
```

## Builds

**All builds run on GitHub Actions.** Nothing has to be compiled locally.

- **Android** (`.github/workflows/android.yml`): every push to `main` that touches `android/**` or `shared/**` runs `gradle assembleDebug` and `testDebugUnitTest`. The debug APK is uploaded as the `apk` artifact and attached to a `build-N` GitHub Release.
- **Web** (`.github/workflows/web.yml`): builds and tests the SvelteKit app, then deploys it to GitHub Pages (base path `/cinetor`) from `main`.
- **Desktop** (`.github/workflows/desktop.yml`): runs `tauri-action` on Windows (`.exe`/`.msi`) and Ubuntu 22.04 (`.deb`/`.AppImage`). Pushing a `v*` tag attaches the installers to a release.

Android toolchain: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9, JDK 17, compileSdk 35, minSdk 24.

## Credits

- Developed By **Mythbrix**
- Thanks to [Ahmed Ishtiaq Zarir](https://ahmedishtiaquezarir.pro.bd/)
- Telegram Channel: https://t.me/mythbrix_vault
- Telegram Group: https://t.me/cinetor
