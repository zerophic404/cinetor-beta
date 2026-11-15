# Phase 9 handoff — Desktop (Tauri 2) · Windows exe/msi + Linux deb/AppImage

Branch `desktop/phase-9` → PR → main. Notes caveman-style.

## Done
- **9.1 shell** `desktop/src-tauri/` (Tauri 2, id `com.mythbrix.cinetor`, Cinetor 0.5.0)
  - `tauri.conf.json`: `frontendDist ../../web/build`, `beforeBuildCommand npm run build:web` → `desktop/scripts/build-web.mjs` builds web with `BASE_PATH=''` (root). Window opens `/app`, 1280x800, min 820x520, `decorations:false`, hidden until setup (no white flash).
  - `tauri.windows.conf.json`: same window but `transparent:true` → `window-vibrancy` Mica (Win11) → Acrylic (Win10) → none. Web asks `vibrancy_kind` → `html.tauri-glass` translucent bg. Linux = opaque (`html.tauri-linux`, no backdrop blur in title bar).
  - Plugins: single-instance (2nd launch focuses window), window-state (size/pos remembered; mini size restored before close so it's never saved).
  - Mini player: `set_mini_mode(enabled)` → 480x270 logical, always-on-top, bottom-right of monitor work area; restore = old bounds + maximized flag. Emits `mini-mode`.
  - Native downloads `src/dl.rs` (reqwest rustls + tokio):
    - progressive: probe `Range: bytes=0-0` → up to 16 Range connections (≥1 MiB each) into preallocated `<name>.download`; sidecar `<name>.cinetor-part` JSON (url,total,chunks[start,end,pos]) saved every 2 s + on error; resume rewinds 64 KiB/chunk; per-chunk 5 retries w/ backoff; no Range → 1 stream, restart on error. Done → rename to `Downloads/Cinetor/<name>` (unique ` (n)`).
    - HLS: master → variant by height/bandwidth; media playlist segments parallel (`connections`) into `<stem>.cinetor-hls/` (finished segment files = resume), concat (init + segs) → one `.ts` / `.mp4` + `<stem>.m3u8` with `EXT-X-BYTERANGE` into that file. No ffmpeg. AES/byte-range sources → error.
    - Events `dl://progress {doneBytes,totalBytes,speed,chunks,segmentsDone,segmentsTotal}`, `dl://done {path,playlist,size}`, `dl://error`. Commands `dl_start/dl_pause/dl_remove/dl_read_playlist/dl_dir`. Pause = abort task (data stays).
    - Rust unit tests (split, master/pick, media parse, local playlist, sanitize) run on Linux CI (`cargo test --release --lib`).
  - Asset protocol on, scope `$DOWNLOAD/Cinetor/**` → offline playback via `convertFileSrc`.
- **Web side** `web/src/lib/desktop/`
  - `env.ts` pure (vitest `env.test.ts`): `isTauriEnv`, `chooseBackend` (Tauri + progressive/HLS → native; DASH + browsers → OPFS/shaka), `nativeFileName`, `localPlaylist`, `applyNativeProgress`.
  - `tauri.ts` lazy `@tauri-apps/api` (new web dep `^2.9.0`), `native-dl.ts` bridge, `desktop.svelte.ts` store, `TitleBar.svelte` (glass, `data-tauri-drag-region`, min/max/close), `DesktopMini.svelte` (whole-window video, drag via `startDragging`, play/restore/close; auto-restore when playback stops).
  - Root layout: Tauri → `desktop.init()`, no SW/install prompt, TitleBar; `html.tauri { --safe-t: 36px }` pushes every screen below the bar. `app.html` adds `tauri` class pre-paint.
  - MiniPlayer: "Pop out" button only in Tauri → `desktop.setMini(true)`.
  - Download manager: record `backend` chosen at start; `runNativeJob` streams progress into the same record/UI (chunks + segments + speed meter); offline play = asset URL (progressive) or blob byte-range playlist (HLS); remove/cancel → `dl_remove`. Details sheet shows saved path.
- **9.2 CI** `.github/workflows/desktop.yml`: matrix windows-latest + ubuntu-22.04, Node 22, stable Rust + rust-cache, `npm run tauri -- build` (no tauri-action), Linux deps (webkit2gtk-4.1, gstreamer plugins good/bad/libav, libfuse2), asserts deb Depends gstreamer, artifacts `desktop-windows`/`desktop-linux`, `release` job (main/tags only) → `softprops/action-gh-release@v2` to **Ashrix404/cinetor-app** tag `desktop-build-<run_number>`, `make_latest: false` (Android stays "latest"), + `SHA256SUMS-desktop.txt`. Triggers: push main (desktop/**, web/**, shared/**, workflow), tags `v*`/`desktop-v*`, dispatch.
- deb Depends: gstreamer1.0-plugins-good, -bad, -libav. AppImage `bundleMediaFramework: true` (GStreamer inside → bigger AppImage, video works on distros w/o gst).
- Icons committed in `desktop/src-tauri/icons/` (from `shared/scripts/gen_icons.py --only=desktop`).

## Deviations / decisions
- `npx tauri build` directly instead of tauri-action (simpler; publishing via softprops to the public repo).
- Global media keys: not added (WebView2/WebKit Media Session handles hardware keys when playing). System PiP button still shown where WebView supports it.
- Native HLS: demuxed audio renditions (EXT-X-MEDIA audio URI) not merged — muxed streams only. DASH on desktop uses shaka offline (IndexedDB) as on web.
- NSIS `installMode: currentUser` (no admin prompt); WebView2 bootstrapper download if missing.
- Release only on main/tags (branch runs = artifacts only), same as android.yml.

## Not verified (no Windows/Linux GUI tester)
- Mica look, drag region, mini window placement, native download + offline playback were not exercised on a real desktop — only compile + bundle in CI. First manual test checklist: launch, drag/max/close, pop out mini, download MP4 (16 conn) + HLS, pause/resume after app restart, play offline.

## Next
- Phase 10 (release.yml v0.x tag → all assets + checksums; landing download buttons → releases page).
