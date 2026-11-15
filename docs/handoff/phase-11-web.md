# Phase 11 — WEB + DESKTOP (branch feat/gateway)

Caveman log. Me do web + desktop hardening. Both CI green.
## GREEN RUNS (final, 88c890b)

- Web: https://github.com/Ashrix404/cinetor/actions/runs/43754757759 (23 test files pass, svelte-check clean)
- Desktop: https://github.com/Ashrix404/cinetor/actions/runs/43754761146 (linux + windows, 13 cargo tests pass)

## COMMITS

| SHA | What |
| --- | --- |
| 77b4144 | WIP downloads UX (inherited, unverified) — turned out web-green as-is: run 43753072694 |
| cf74a02 | §4 downloads UX complete (web + desktop) + vitest/cargo tests. Web 43753864725, Desktop 43753868019 |
| 88c890b | §2/§3 re-check + real leak fixes + more tests. Web 43754757759, Desktop 43754761146 |

## TASK 1 — WIP commit to green

No fix needed. 77b4144 already passed web.yml (run 43753072694).
BUT desktop was broken by it and nobody knew: `dl_pick_dir` used `tauri_plugin_dialog`
that was NOT a dependency, and the two new commands were never registered.
Fixed in cf74a02: `tauri-plugin-dialog = "2"` in Cargo.toml, `.plugin(tauri_plugin_dialog::init())`,
`dl_set_dir` + `dl_pick_dir` in `generate_handler!`, `"dialog:allow-open"` in capabilities/default.json.

## TASK 2 — §4 downloads UX (done)

- **De-dupe**: rewrote `dedupeOptions` in `web/src/lib/download/math.ts`.
  Old key included bandwidth → same file from 2 sources did NOT collapse. New `optionKey`
  = resolution + format + size(MB) only. Duplicate keeps the **primary else fastest** source
  and stays in the first-seen position. Added `groupByQuality` + `qualityList`.
- **Index bug fixed**: sheet rendered the deduped list but computed the selected index against the
  ORIGINAL list → user could start a different quality than the one shown. Both now use `qualityList`.
- **Start flow**: confirm sheet shows quality/size/**location**, then toast
  "Downloading started" + "Show downloads" action. New `Toast.svelte` + `toast.svelte.ts` host in /app layout.
- **Delete flow**: new `DeleteDownloadDialog.svelte` — explicit **Cancel / Remove entry / Delete file**.
  Used by the details sheet AND a new per-row trash button on the downloads page.
  `downloads.remove(id, { deleteFile })`; on desktop `deleteFile:false` keeps the real file.
- **Web location**: keeps OPFS, plus "Save a copy…" via File System Access API on completed
  progressive downloads. Where unsupported it says so plainly (no silent no-op).
- **Desktop location**: native Tauri folder picker, default `Downloads/Cinetor`, remembered in
  `download-dir.txt` in the app config dir, changeable from Downloads settings ("Save to" row).
- **Rust bug fixed**: `dl_remove` only deleted a file if it was inside the *current* folder, so
  "Delete file" silently did nothing after the user changed folders. New pure `removable_path()`
  also allows the file Cinetor wrote (by sanitized name/stem) outside the current folder, and still
  refuses anything else (`/etc/passwd`, user's own files).
- Tests: `web/src/lib/download/options.test.ts` (16), location copy in `env.test.ts`,
  cargo `dir_choice_requires_absolute_path`, `dir_pref_round_trips_and_rejects_junk`,
  `removable_path_allows_own_files_after_folder_change`.

## TASK 3 — §2 end-to-end (2 REAL leaks found and fixed)

1. **Clipboard leak**: `Player.svelte` "Copy link" did
   `navigator.clipboard.writeText(media.source.url)` → handed the user the gateway proxy stream URL.
   Also `<a href={ext}>` published it in the DOM.
   Fix: new pure `isLocalMediaUrl()` in `player/sources.ts`. "Open in external player" only when the
   source is local (blob:/file:/asset:/localhost) i.e. an already-downloaded file; otherwise the card
   offers **Downloads** + "Download this title first". "Copy link" → **"Copy app link"** (copies
   `location.href`, the app's own watch URL, never a stream URL).
2. **On-disk sidecar leak**: Rust `.cinetor-part` resume sidecar sat in the user's Downloads folder
   with the plaintext source URL — exactly what §2 forbids. Fix: `Sidecar.url` → `url_hash`
   (serde `"u"`), filled by new `url_fingerprint()` (salted FNV-1a, 16 hex chars, non-reversible).
   Resume still matches the same source and rejects a different one.

Verified clean, no change needed: title-page share uses `location.href`; no `console.*` or sourcemaps
in prod (`vite.config.ts`: `sourcemap:false`, esbuild `drop:['console','debugger']`, `minify:'esbuild'`);
no `.map` shipped; nothing assigned onto `window`/`globalThis`.

New assertions in `web/src/lib/hardening.test.ts` (now 16 tests): `isLocalMediaUrl` table; grep-based
guards that no `writeText`/`navigator.share` argument and no `href={...}` mentions a media source url;
no stream/gateway/engine/token published on `window`/`globalThis`; sidecar is hash-only;
build hygiene (sourcemap false, console dropped, never `sourcemap:true`).
Cargo: `sidecar_never_stores_the_source_url_in_plaintext`, `url_fingerprint_is_stable_and_not_reversible`.

## TASK 4 — §3 resizable mini player (verified, already correct)

- Web `MiniPlayer.svelte` + `mini-math.ts`: corner handle (pointer events) + keyboard ±5 % steps,
  `clampMiniWidth` 25–80 % of viewport (floor 160 px), `miniHeight` = 16:9, width persisted as a
  **viewport fraction** under `cinetor.miniWidth` via `localKV`, restored on mount and re-clamped on
  render so a junk/out-of-range stored value can never produce a bad size. No fix needed.
- Desktop `mini.rs`: `set_resizable(true)` + min size cleared, `on_bounds_changed` re-applies 16:9
  on every resize, `set_always_on_top(true)`, bounds persisted to `mini-window.json` in the app
  config dir so size/position survive restarts, `clamp_pos` keeps it on screen, `restore()` puts the
  full window back. No fix needed.
- Added persistence assertions to `hardening.test.ts` ("mini player persistence (§3)").

## TASK 5 — desktop.yml green

Green on both targets, twice (43753868019, then 43754761146). No Cargo.lock is committed, so the new
dialog plugin resolved fine. Did not touch `android/`.

## DEVIATIONS / DECISIONS (full autonomy, no user asked)

1. **Web "Remove entry" vs "Delete file"** both free the stored video. A browser download has no
   separate user file — OPFS/IDB *is* the storage. Kept all three spec buttons, and the dialog says
   plainly that on the web both free the video and only desktop can keep the file. Not a fake choice.
2. **"Open in external player" removed for remote sources.** §2 ("never in share intents/links")
   outranks convenience. Unsupported-format card now points at Downloads instead.
3. **Sidecar hash, not encryption.** §2 allows "encrypted or hashed". A hash needs no key storage and
   is enough for resume matching. Old sidecars no longer match → that download restarts once. Accepted.
4. **Folder row lives on the Downloads page** (which already holds the download settings block)
   rather than a new Settings → Downloads route. No new prerender entry needed.
5. **Size-less variants collapse.** Gateway MP4 variants carry no byte size, so two same-resolution
   MP4s look identical and become one option. That is the spec's intent ("never show the same option
   repeatedly"); the primary/fastest one is kept.

## OPEN BUGS / NOT DONE

- `DownloadRecord.url` / `.host` / `.directUrl` are still plaintext in app-private IndexedDB +
  localStorage (devtools-visible). §2's wording targets on-disk sidecars, which is fixed. Encrypting
  the web record touches resume + re-resolve in several places; left alone deliberately. Next phase.
- `DownloadDetailsSheet` "Stored in" shows the OPFS/IDB key. Local storage name only, no host — fine,
  but it is developer-ish copy that could be hidden behind the debug toggle later.
- No runtime QA of the new flows (no local build on this 3.6 GB box). Dedupe, grouping, location copy,
  folder validation, deletion scope, sidecar and mini-player geometry are all unit-tested instead.
- Android side of §4 (SAF picker) is the other agent's track — untouched here.

## NEXT

Merge `feat/gateway` → main, then versioned 0.3.0 release (web gh-pages + desktop installers to
`Ashrix404/cinetor-app`). Release jobs are main-only, so they were correctly skipped on this branch.
