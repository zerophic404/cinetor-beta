# Cinetor — Master Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax. Builds run ONLY on GitHub Actions (user PC = 3.6 GB RAM, no JDK/Gradle/SDK — see skill android-apk-cloud-build).

Goal: One streaming/reading/music app "Cinetor" — Android phone + Android TV/Google TV/TV boxes (Kotlin), Windows + Linux desktop (Tauri), and an installable PWA website (iOS/any device) — glassmorphism UI, 3 themes, mock data only, instant-play player.

Architecture: Monorepo with two native code bases sharing one data contract + design tokens:
1. `android/` — Kotlin + Jetpack Compose (phone) + Compose for TV (`androidx.tv`) in ONE APK, Media3 ExoPlayer.
2. `web/` — Svelte 5 + SvelteKit (static adapter) + Vite PWA. Same UI ships as website/PWA and is wrapped by `desktop/` (Tauri 2) for `.exe/.msi` (Windows) and `.deb/.AppImage` (Linux). Tauri chosen over Electron: ~10 MB installer vs ~100 MB, ~80 MB RAM vs ~300 MB.
3. `shared/` — mock catalog JSON, JSON schema, design tokens (3 themes), brand SVGs; generator scripts emit Kotlin + CSS from tokens so both apps look identical.

Tech stack:
- Android: Kotlin 2.0.21, AGP 8.7.3, compileSdk 35, minSdk 24, Compose BOM 2024.12, Material3, androidx.tv:tv-material 1.0.0, Navigation Compose (type-safe routes), Media3 1.5.x (exoplayer, hls, dash, smoothstreaming, rtsp, session, ui), Coil 3, Haze (dev.chrisbanes.haze) for blur, kotlinx.serialization, DataStore, Room (history/watchlist), androidx.palette, core-splashscreen, profileinstaller + baselineprofile. DI = manual `AppContainer` (no Hilt → faster CI, smaller APK).
- Web: Svelte 5, SvelteKit adapter-static, TypeScript, vite-plugin-pwa (Workbox), shaka-player (HLS+DASH+live), @noriginmedia/norigin-spatial-navigation-style focus engine (own small Svelte port), Vitest, Playwright.
- Desktop: Tauri 2, window-vibrancy (Mica/Acrylic on Windows), tauri-action on CI.
- CI: GitHub Actions (private repo `Ashrix404/cinetor`), GitHub Pages / Cloudflare Pages for website.

Spec: this file (user request 2026-11-14, Bangla). No separate spec doc.

---

## Global Constraints

- App name: `Cinetor` (applicationId `com.mythbrix.cinetor`, Tauri identifier `com.mythbrix.cinetor`).
- About screen copy (exact): "Developed By Mythbrix"; "Thanks to Ahmed Ishtiaq Zarir" with the name hyperlinked to `https://ahmedishtiaquezarir.pro.bd/`; Telegram Channel + Telegram Group as hyperlinks (URLs = OPEN QUESTION, placeholder `TELEGRAM_CHANNEL_URL` / `TELEGRAM_GROUP_URL` in one constants file per platform).
- Sections (exact): Home (Movies, Series) · Anime · Drama (K-Drama, C-Drama, J-Drama) · TV (Live TV, Sports) · Comics (Manga, Manhwa, Manhua) · Music · Adult · Settings (App + Profile).
- Global search returns ONLY Home, Anime, Drama items. TV, Adult, Comics, Music are isolated: own space, own nav, own in-space search, never in global search/recents/continue-watching/recommendations.
- Every section: category tabs + genre chips + auto-sliding big hero banner per category; UI tint follows current banner/focused thumbnail color.
- 3 user-selectable themes on all platforms: Noir (default), Obsidian, Ember — all red/black.
- Mock data only — no real scrapers/providers. Video URLs = public test streams.
- Thumbnail tap → details with skeleton shimmer while video is pre-resolved and pre-buffered; Play must start < 300 ms on warmed item.
- Performance floor device: 2 GB RAM, 4× Cortex-A53, Android 7 (API 24), 720p. TV floor: Android TV 9 box, 1 GB RAM.
- Adult: off by default, 18+ confirm + 4-digit PIN to enable and enter, placeholder art only (no explicit media in mock), FLAG_SECURE, blocked in Kids profiles.
- No local heavy builds. Every artifact (APK, exe, msi, deb, AppImage, website) produced by CI.

## Review Focus

1. User taps thumbnail then backs out / taps another before metadata loads → preload cancelled, no wrong video plays, only one player instance alive. (Test: Task 3.4 `rapidSwitch_keepsSinglePlayer_andLatestItem`.)
2. Mini/floating player active, user opens another title and presses Play → old item replaced, never two audios. (Task 3.6 `miniPlayer_replacedByNewPlay_singleAudio`.)
3. Isolation leak: adult/TV/music/comic item appears in global search, recent searches, or Home "Continue Watching". (Task 2.2 `globalSearch_excludesIsolatedSections`, Task 5.1 `continueWatching_onlyVideoSpaces`.)
4. Low-RAM phone with blur on → auto-tier disables live blur, hero images decoded at screen size, no OOM. (Task 1.3 `tier_lowRam_disablesLiveBlur`, Task 7.1 memory benchmark < 200 MB.)
5. TV: focus lost/jumps to top after row reload, banner auto-slide, or returning from player. (Task 6.2 `returnFromDetails_restoresFocusedCard`.)

---

## Repository layout

```
cinetor/
  shared/
    brand/            logo.svg, mark.svg, wordmark.svg, splash.svg, banner-tv.svg, og.svg
    tokens/           themes.json (Noir/Obsidian/Ember), motion.json, radii/spacing
    mock/             catalog.schema.json, home.json, anime.json, drama.json, tv.json,
                      music.json, comics.json, adult.json, images/ (generated webp)
    scripts/          gen_mock.py, gen_images.py, gen_tokens.py, gen_icons.py
  android/            Gradle project (single :app module + :baselineprofile)
  web/                SvelteKit app (landing at /, PWA at /app)
  desktop/            Tauri 2 shell (src-tauri) pointing at web/build
  .github/workflows/  android.yml, web.yml, desktop.yml, release.yml
  docs/plans/         this plan
```

## Shared data contract (`shared/mock/catalog.schema.json`)

```
Space        = HOME | ANIME | DRAMA | TV | MUSIC | COMICS | ADULT
Category     = { id, space, title, heroIds[], rows: Row[] }        // e.g. drama/kdrama
Row          = { id, title, style: POSTER|LANDSCAPE|TOP10|CONTINUE|CIRCLE|CHANNEL, itemIds[] }
Item         = { id, space, categoryId, type: MOVIE|SERIES|CHANNEL|EVENT|ALBUM|TRACK|COMIC,
                 title, altTitle?, year, rating, maturity, genres[], synopsis,
                 poster, backdrop, logo?, dominantColor "#RRGGBB", accentColor "#RRGGBB",
                 durationSec?, seasons?: Season[], sources?: Source[], subtitles?: Sub[],
                 introStartSec?, introEndSec?, pages?: string[] (COMIC chapters), live?: bool }
Season       = { n, episodes: [{ id, n, title, durationSec, still, sources[], subtitles[] }] }
Source       = { url, kind: HLS|DASH|MP4|MKV|WEBM|RTSP|SS|MP3, label?, headers?{} }
Sub          = { lang, label, url, format: VTT|SRT|ASS }
```
`dominantColor`/`accentColor` precomputed by `gen_images.py` → zero runtime palette cost; runtime Palette only as fallback for future real sources.

Mock sizes: Home 40 movies + 20 series · Anime 30 · Drama K15/C10/J10 · TV 30 channels + 10 sports events · Music 10 albums/50 tracks · Comics 10 each type, 3 chapters × 12 pages · Adult 12 placeholders. Mock latency: `MockLatency(min=300ms, max=1200ms)` configurable (Settings → Developer) so skeletons are visible.

Test streams (verify reachability in Task 0.2, replace dead ones):
- HLS multi-quality: `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8`
- HLS multi-audio + subs: `https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8`
- DASH multi-audio + subs: `https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd`
- MP4: `https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4`
- Live HLS: `https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8`
- Audio: `https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3` (…-Song-16)

Mock images: generated locally (Pillow) — gradient + noise + title typography posters/backdrops/stills/comic pages as WebP. Deterministic, offline, no copyright, colors known. Adult placeholders = abstract gradients + "18+" badge.

## Design system

BRAND = RED + BLACK (user decision). Logo = option 6 "Noir Red" (`shared/brand/logo.svg`, source `~/Desktop/Cinetor-Logos/6-noir-red.svg`): black glass tile, red C-ring (gradient #FF2D3B→#B00610) open to the right, white play triangle. All 3 themes are red/black variants.

Themes (`shared/tokens/themes.json`):
| Token | Noir (default) | Obsidian | Ember |
|---|---|---|---|
| base bg | #000000 (AMOLED) | #0E0E10 graphite | #0A0505 warm black |
| surface | #0B0B0B | #17171A | #160A0A |
| glass fill | white 6% | white 8% | #FF2D3B 7% |
| glass stroke | white 10% | white 14% | #FF6A4D 22% |
| accent | #E50914 | #C8102E (wine red) | #FF2D3B → #FF7A1A gradient |
| accent glow | #FF2D3B 45% | #C8102E 35% | #FF5A1F 50% |
| text primary | #FFFFFF | #F2F2F5 | #FFF4F0 |
| text secondary | #A3A3A3 | #9A9AA3 | #D9B8AE |
| blur radius | 14dp | 24dp | 22dp |
| dynamic tint strength | 18% | 30% | 26% |
Noir = battery/low-end friendly (true black, least blur). Obsidian = premium frosted dark glass. Ember = vibrant red→orange glow. Dynamic thumbnail tint is blended toward red (never overrides accent) so the red/black identity holds.

Glass recipe: background = blurred content behind (Haze / CSS `backdrop-filter`) + fill + 1px gradient stroke (top-left brighter) + 3% noise texture + soft shadow. Cards scale 1.0→1.08 on focus/press with glow in item `accentColor`.

Performance tiers (`PerformanceTier { LOW, MID, HIGH }`):
- LOW if `ActivityManager.isLowRamDevice` OR totalRam < 3 GB OR SDK < 26 OR cores ≤ 4 (TV boxes usually here).
- HIGH if totalRam ≥ 6 GB AND SDK ≥ 31; else MID.
- Web: `navigator.deviceMemory`, `hardwareConcurrency`, `prefers-reduced-motion`, plus 1 s rAF frame-time probe at boot (avg > 20 ms → downgrade one tier).
| Feature | LOW | MID | HIGH |
|---|---|---|---|
| Live blur | off (tinted 88% opaque glass + noise) | bars/sheets only | everywhere |
| Hero Ken-Burns/parallax | off | parallax | both |
| Animation durations | ×0.6, no spring overshoot | normal | normal |
| Image decode | RGB_565, exact size, mem cache 12% heap | ARGB, 20% | 25% |
| Crossfade | off | 150 ms | 250 ms |
| Background color morph | 2-stop | 3-stop | animated mesh |
User override: Settings → Appearance → Effects quality (Auto/Low/Medium/High).

Motion: shared `motion.json` → durations 120/200/320/480 ms, easing emphasized `cubic(0.2,0,0,1)`, springs (stiffness 400, damping 0.8). Shared-element card → details transition (Compose `SharedTransitionLayout`; web View Transitions API with fallback).

## Navigation model

- Video spaces (searchable): bottom glass bar (phone) / nav rail (tablet, landscape) / left glass side drawer (TV, expands on focus): Home · Anime · Drama · Search · Me.
- Isolated spaces: "Spaces" switcher (logo tap / TV drawer bottom group): Live TV & Sports · Music · Comics · Adult (only if enabled). Entering a space swaps the nav bar to that space's own tabs + its own in-space search. Back from space root → video spaces.
- Me: profile, watchlist, history, downloads placeholder, settings, about.
- Flow: animated splash → profile picker (skipped if 1 profile) → Home.

---

## Phase 0 — Repo, brand, tokens, mock data

### Task 0.1: Monorepo + CI skeleton
Files: create `README.md`, `.gitignore`, `.github/workflows/{android,web,desktop}.yml` (no-op build jobs), copy `~/apk-projects/android-apk-template` → `android/` and rename to `com.mythbrix.cinetor`.
- [ ] Create private repo `Ashrix404/cinetor` via `~/apk-projects/bin/ship-apk.sh` conventions.
- [ ] Push; verify all 3 workflows green (`gh run list`).
- [ ] Commit `chore: monorepo skeleton`.

### Task 0.2: Brand assets
Files: `shared/brand/*.svg`, `shared/scripts/gen_icons.py`.
Logo = chosen option 6 "Noir Red" (black glass tile, red C-ring #FF2D3B→#B00610, white play triangle); wordmark "CINETOR" geometric sans, wide tracking.
Outputs of `gen_icons.py` (cairosvg, light enough locally; also runs in CI):
- Android: adaptive icon fg/bg + monochrome (themed icon), legacy mipmaps, TV banner 320×180 (`xhdpi`), splash AVD source.
- Web: favicon.svg/.ico, icon-192/512, maskable-512, apple-touch-icon-180, iOS startup images (all iPhone/iPad sizes), og-image 1200×630.
- Desktop: `icon.ico` (16–256), `icon.icns`, 32/128/256/512 PNG.
- Play-style feature graphic 1024×500, GitHub social banner.
- [ ] Test: `python shared/scripts/gen_icons.py --check` asserts every output file exists with exact pixel size.
- [ ] Commit `feat(brand): Cinetor logo, icons, banners`.

### Task 0.3: Tokens + mock catalog generators
Files: `shared/tokens/*.json`, `shared/mock/catalog.schema.json`, `shared/scripts/{gen_mock.py,gen_images.py,gen_tokens.py}`.
- `gen_tokens.py` → `android/app/src/main/java/com/mythbrix/cinetor/design/Tokens.kt` and `web/src/lib/design/tokens.css` (CSS vars per `[data-theme]`).
- `gen_mock.py` → 7 JSON files (sizes above), copied to `android/app/src/main/assets/mock/` and `web/static/mock/`.
- [ ] Test `shared/scripts/test_mock.py`: every file validates against schema; every `heroIds`/`itemIds` resolves; ids unique globally; only HOME/ANIME/DRAMA items have `type in {MOVIE,SERIES}`; every video item has ≥1 source; ADULT items have no non-placeholder image.
- [ ] Script `check_streams.py` HEADs every source URL; report dead ones (run once, not in CI gate).
- [ ] Commit `feat(shared): tokens + mock catalog`.

---

## Phase 1 — Android foundation

Package root `com.mythbrix.cinetor` (below abbreviated `c.`). Folders: `core/{data,perf,player,ui}`, `design/`, `feature/{home,details,search,player,tv,music,comics,adult,profile,settings,about,splash}`, `navigation/`, `tvui/`.

### Task 1.1: Data layer over mock assets
Files: `c/core/data/{Models.kt,CatalogRepository.kt,MockCatalogSource.kt,SearchRepository.kt,AppContainer.kt}`.
Interfaces (produces):
- `interface CatalogRepository { suspend fun categories(space: Space): List<Category>; suspend fun item(id: String): Item; suspend fun row(rowId: String): List<Item>; suspend fun byGenre(space: Space, genre: String): List<Item> }`
- `class SearchRepository(catalog) { suspend fun search(q: String): List<Item>; suspend fun searchInSpace(space: Space, q: String): List<Item> }`
- `MockCatalogSource` parses assets once (kotlinx.serialization, lazily, off main), applies `MockLatency`.
- [ ] Unit tests: `globalSearch_excludesIsolatedSections` (query matching an ADULT, TV, MUSIC, COMICS title returns 0 items from those spaces), `searchInSpace_onlyThatSpace`, `search_isDiacriticAndCaseInsensitive`, `item_unknownId_throwsNotFound`.
- [ ] CI green; commit.

### Task 1.2: Design system + 3 themes
Files: `c/design/{Tokens.kt (generated),CinetorTheme.kt,Glass.kt,Shimmer.kt,DynamicTint.kt,Typography.kt}`.
Produces:
- `@Composable fun CinetorTheme(theme: AppTheme, tier: PerformanceTier, content)`; `enum AppTheme { NOIR, OBSIDIAN, EMBER }`.
- `Modifier.glass(shape, level: GlassLevel = SURFACE)` — uses Haze when tier allows, else tinted fallback.
- `Modifier.shimmer()` + skeleton composables `SkeletonBox`, `SkeletonText(lines)`, `SkeletonPosterRow`, `SkeletonHero`, `SkeletonDetails`, `SkeletonEpisodeList`, `SkeletonEpgGrid` (TV guide "skeleton chart"), `SkeletonChapterList`.
- `DynamicTintState` — `fun setSource(color: Color)`; animates app background gradient (`animateColorAsState`, 600 ms).
- [ ] Screenshot/unit test: `theme_tokensMatchJson` (Kotlin token values equal themes.json), Compose test `glass_lowTier_hasNoBlurEffect`.

### Task 1.3: Performance tier
Files: `c/core/perf/{PerformanceTier.kt,DeviceProfile.kt}`.
Produces `fun detectTier(p: DeviceProfile): PerformanceTier` (pure) with rules from Design system.
- [ ] Unit tests: `tier_lowRam_disablesLiveBlur` (2 GB → LOW, `tier.liveBlur == false`), `tier_8gbApi34_isHigh`, `tier_tvBox1gb_isLow`, `tier_userOverride_wins`.

### Task 1.4: App shell, navigation, animated splash
Files: `MainActivity.kt`, `c/navigation/{Routes.kt,CinetorNavHost.kt,AdaptiveNavBar.kt,SpacesSwitcher.kt}`, `c/feature/splash/AnimatedSplash.kt`, `res/drawable/avd_splash.xml`.
- Android 12+ SplashScreen API with AVD (ring draws, play triangle pops, ≤ 900 ms) → Compose intro (glass tile + light sweep + letter reveal "CINETOR", ≤ 600 ms; LOW tier: 300 ms fade). Catalog parsing + first hero image prefetch run during splash.
- Bottom bar / rail / TV drawer chosen by `WindowSizeClass` + `isTv` (`UiModeManager.currentModeType == UI_MODE_TYPE_TELEVISION`).
- [ ] Compose UI test (CI emulator): `splash_thenHome_within2s`, `spaceSwitch_swapsNavBar`.

---

## Phase 2 — Android content screens

### Task 2.0: TMDB metadata source (Home/Anime/Drama only)
Files: `c/core/data/tmdb/{TmdbApi.kt,TmdbCatalogSource.kt,TmdbMapper.kt,ColorCache.kt}`, web mirror `web/src/lib/data/tmdb.ts`.
- Token: CI secret `TMDB_TOKEN` (v4 bearer) → `BuildConfig.TMDB_TOKEN` (Android) / `PUBLIC_TMDB_TOKEN` env at web build. Local copy `~/.config/cinetor/tmdb.env` (never committed). Later: Cloudflare Worker proxy.
- Mapping: Home Movies = trending/popular/top_rated/now_playing/upcoming + discover by genre; Series = tv equivalents; Anime = discover with genre 16 + `with_origin_country=JP`; Drama K/C/J = discover tv `with_origin_country=KR|CN|JP` excluding genre 16; global search = `/search/multi` filtered to those three spaces' rules. `include_adult=false` always.
- Images `image.tmdb.org/t/p/{w185|w342|w780|w1280}` chosen by tier + card size. `dominantColor` computed once per image off-main (Palette on a 64px thumbnail) and cached (memory LRU + DataStore/IndexedDB).
- Video `sources` still mock (test streams) — attached by `MockSourceProvider`.
- Fallback: no token / offline / error → MockCatalogSource. About shows TMDB attribution: "This product uses the TMDB API but is not endorsed or certified by TMDB." + TMDB logo.
- [ ] Tests: `mapper_mapsMovieAndTv`, `anime_queryUsesGenre16AndJP`, `drama_kdramaUsesKR`, `tmdbError_fallsBackToMock`, `search_neverIncludesAdult`.

### Task 2.1: Section screen (shared by every space)
Files: `c/feature/home/{SpaceScreen.kt,SpaceViewModel.kt,HeroCarousel.kt,CategoryTabs.kt,GenreChips.kt,ContentRow.kt,Cards.kt}`.
- `SpaceScreen(space: Space)` = CategoryTabs (Home: Movies|Series, Drama: K|C|J, TV: Live|Sports, Comics: Manga|Manhwa|Manhua, Music: Albums|Tracks, Anime/Adult: genres-led) + HeroCarousel per category + GenreChips filter + rows.
- HeroCarousel: `HorizontalPager` infinite, auto-advance 5 s, pauses on touch/drag/off-screen/app-background, page indicator, backdrop + logo/title + genres + Play / My List; each page change → `DynamicTintState.setSource(item.dominantColor)`. Visible row card focus/scroll also feeds tint (debounced 150 ms).
- Row styles: POSTER, LANDSCAPE, TOP10 (big outlined numerals), CONTINUE (progress bar), CIRCLE (artists), CHANNEL (logo tile with LIVE badge).
- Lazy lists with stable `key`, `contentType`, immutable models (`@Immutable`), images requested at exact card size.
- First load shows `SkeletonHero` + 3 `SkeletonPosterRow`.
- [ ] Tests: `hero_autoAdvancesEvery5s` (virtual clock), `hero_pausesWhileDragging`, `genreChip_filtersRows`, `categoryTab_swapsHero`.

### Task 2.2: Details screen with skeleton + instant-play warmup
Files: `c/feature/details/{DetailsScreen.kt,DetailsViewModel.kt,EpisodePicker.kt,CastRow.kt}`.
Consumes `PlaybackPreloader` (Task 3.2).
- Tap card → shared-element transition of poster → details. While `item(id)` resolves: `SkeletonDetails` + `SkeletonEpisodeList` shimmer. In parallel `PlaybackPreloader.warm(item)` resolves source + prepares player (see 3.2).
- Content: blurred backdrop tinted by item color, title/logo, meta chips, synopsis (expand), Play/Resume (shows "Ready" dot once buffered), Trailer (mock), My List, Share, season tabs, episode list (stills, progress), More Like This (same space only).
- [ ] Tests: `details_showsSkeletonUntilLoaded`, `details_callsWarmOnOpen_andCancelOnLeave`, `moreLikeThis_sameSpaceOnly`.

### Task 2.3: Search (global, video spaces only)
Files: `c/feature/search/{SearchScreen.kt,SearchViewModel.kt}`.
- Debounce 250 ms, results grouped Home/Anime/Drama with filter chips (type, genre, year), recent searches (stored per profile; isolated spaces never written here), trending suggestions, skeleton grid while searching, voice search button (`RecognizerIntent`, also TV mic).
- Search history is LOCAL ONLY (Room table `search_history(profileId, query, ts)`, max 50, swipe-to-delete, clear all, pause history toggle); never synced or sent anywhere.
- Super-fast results (target: first results < 50 ms for local, < 400 ms for TMDB on 4G):
  - Local FTS index: Room FTS4 table over every Home/Anime/Drama item seen (mock + TMDB cache), prefix + typo-tolerant (normalized, diacritics stripped) → instant results on every keystroke, no debounce for local.
  - Remote (TMDB `/search/multi`) after 200 ms debounce, `flatMapLatest` cancels stale calls, OkHttp HTTP/2 connection pre-warmed when search screen opens, in-memory LRU of last 100 queries + 24 h disk cache, results merged/deduped into local list without reflow (stable keys).
  - Suggestions = history prefix matches + local title matches while typing.
  - Isolated spaces each get the same engine scoped to their own index (in-space search), separate history table column `space`.
- [ ] Tests: `searchHistory_localPerProfile_neverIncludesIsolated`, `localSearch_returnsUnder50ms` (1,000-item index), `staleRemoteResult_isDiscarded`, `queryCache_hitSkipsNetwork`.
- [ ] Tests: `globalSearch_excludesIsolatedSections` (UI level), `recentSearches_perProfile`.

---

## Phase 3 — Player (Android)

### Task 3.1: Player core
Files: `c/core/player/{PlayerFactory.kt,CinetorPlayer.kt,SourceResolver.kt,TrackModels.kt}`.
- ONE shared `ExoPlayer` for the app (LOW tier never creates a second); `DefaultRenderersFactory` with `EXTENSION_RENDERER_MODE_PREFER` (ffmpeg audio ext optional later for AC3/EAC3/DTS), decoder fallback enabled.
- `DefaultLoadControl`: bufferForPlaybackMs 500, bufferForPlaybackAfterRebufferMs 1500, minBuffer 15 s (LOW) / 30 s, maxBuffer 30 s / 50 s; prioritizeTime.
- `SourceResolver.toMediaItem(source: Source, subs: List<Sub>): MediaItem` handles HLS/DASH/SS/RTSP/progressive (MP4/MKV/WebM/MP3), custom headers via `DefaultHttpDataSource.Factory().setDefaultRequestProperties`, side-loaded subtitles via `SubtitleConfiguration`. Shared 200 MB `SimpleCache` (LOW: 80 MB).
- `CinetorPlayer` exposes `StateFlow<PlayerUiState>` (position, buffered, isPlaying, tracks, speed, error) — UI never touches ExoPlayer directly.
- [ ] Tests: `resolver_m3u8_isHls`, `resolver_mpd_isDash`, `resolver_mkv_isProgressive`, `resolver_headers_applied`.

### Task 3.2: PlaybackPreloader (instant play)
Files: `c/core/player/PlaybackPreloader.kt`.
Produces `fun warm(item: Item, episodeId: String? = null)`, `fun cancel(itemId: String)`, `fun take(itemId: String): CinetorPlayer?`, `val readiness: StateFlow<Map<String, Readiness>>` (`RESOLVING|BUFFERING|READY|ERROR`).
- warm: resolve source → `setMediaItem` + `prepare()` with `playWhenReady=false`, seek to resume position → buffers first segments while user reads details. Hero item on screen additionally preloaded via Media3 `DefaultPreloadManager` (MID/HIGH only, max 2 items).
- Play: `take()` hands the same prepared instance to the player surface → `playWhenReady=true`. Latest `warm` wins; previous cancelled (Review Focus 1). Data saver mode: warm only resolves, doesn't buffer.
- Instrument `AnalyticsListener.onRenderedFirstFrame` → log `tap→firstFrame` ms.
- [ ] Tests: `warmedItem_firstFrameUnder300ms` (instrumented, local MP4 fixture), `rapidSwitch_keepsSinglePlayer_andLatestItem`, `cancel_releasesBuffer`.

### Task 3.3: Player UI — portrait, landscape, controls
Files: `c/feature/player/{PlayerScreen.kt,PlayerControls.kt,SeekBar.kt,SettingsSheet.kt,PlayerViewModel.kt}`.
- Portrait: 16:9 player on top, title/episodes/related below (scrollable, YouTube-style); vertical videos fill. Landscape/fullscreen: immersive, auto-rotate by sensor, manual rotate button, notch-safe.
- Controls (glass, auto-hide 3 s): play/pause, ±10 s, seek bar (buffered layer, chapter/intro marks, scrub time bubble; thumbnail preview if sprite present), title, episode list drawer, next episode, Skip Intro (from `introStartSec/EndSec`), lock, aspect (Fit/Fill/Zoom/Stretch/16:9/4:3), rotate, PiP, cast placeholder, settings.
- Settings sheet: Quality (Auto + each height, shows current auto resolution), Playback speed (0.25–3.0 in 0.25 steps), Audio track (language/label), Subtitles (track, off, load external file, style), Sleep timer (15/30/60/end of episode).
- Resume position saved every 5 s + on pause/stop (Room), autoplay next with 10 s countdown card.
- [ ] Tests: `quality_selectOverridesTrack`, `speed_appliedToPlayer`, `audioTrack_select`, `resume_restoresPosition`, `rotation_doesNotRecreatePlayer`.

### Task 3.4: Gestures
Files: `c/feature/player/gestures/{PlayerGestures.kt,GestureMath.kt,BrightnessController.kt,VolumeController.kt}`.
- Double-tap left/right third: −/+10 s (configurable 5/10/15/30), consecutive taps accumulate (10→20→30) with ripple + label.
- Long-press (hold) anywhere: 2× speed while held with "2× ▸▸" pill; release restores previous speed.
- Vertical drag left half: brightness (window `screenBrightness`, restored on exit); right half: volume (AudioManager STREAM_MUSIC; optional boost to 150% via `LoudnessEnhancer`).
- Horizontal drag: seek with preview time + delta ("+0:45"). Pinch: zoom to fill. Single tap: toggle controls. All disabled while locked.
- `GestureMath` pure functions (drag delta → volume/brightness/seek).
- [ ] Unit tests: `doubleTap_accumulates`, `hold_sets2x_releaseRestores`, `verticalDrag_leftIsBrightness_rightIsVolume`, `horizontalDrag_clampsToDuration`, `locked_ignoresGestures`.

### Task 3.5: Subtitle styling + sync
Files: `c/feature/player/subs/{SubtitleStyle.kt,SubtitleOverlay.kt,SubtitleSync.kt}`.
- Style: size 50–200%, text color, background color + opacity, edge (none/outline/shadow), font (default/serif/mono/bold), vertical position, per-profile defaults.
- Sync offset −10 s…+10 s in 50 ms steps: side-loaded SRT/VTT parsed by Cinetor and rendered by `SubtitleOverlay` with offset (supports both directions); embedded tracks: positive delay via cue buffering, negative offset converts track to side-loaded when the URL is known, else disabled with hint.
- [ ] Tests: `offset_shiftsCueTimes`, `srtParse_basic`, `style_persistsPerProfile`.

### Task 3.7: Download engine + Downloads UI
Files: `c/core/download/{DownloadEngine.kt,ChunkedHttpDownloader.kt,CinetorDownloadService.kt,DownloadRepository.kt,DownloadModels.kt}`, `c/feature/downloads/{DownloadButton.kt,DownloadOptionsSheet.kt,DownloadsScreen.kt,DownloadDetailsSheet.kt}`.
- Download button next to Play on EVERY details/episode screen (appears once metadata loads; skeleton placeholder before). Episodes: per-episode, whole season, "next 3 unwatched". Music: track/album. Comics: chapter/range (pages zipped in app storage). Live TV: no download (button hidden).
- Engine:
  - HLS/DASH/SS → Media3 `DownloadManager` + `DownloadService` (segment download, parallel segment fetch `setMaxParallelDownloads`, pick quality/audio/subtitle tracks via `DownloadHelper`).
  - Progressive (MP4/MKV/WebM/MP3) → `ChunkedHttpDownloader`: HTTP Range multi-connection (1–16 connections, default 4 on Wi-Fi / 2 on mobile / 2 on LOW tier), dynamic chunk splitting (steal work from slow connections), resume after kill/reboot (per-chunk offsets persisted in Room), retry with exponential backoff, checksum/size verify, falls back to single connection if server lacks `Accept-Ranges`.
  - Queue with priority & reorder, max concurrent downloads (1–5, default 2), pause/resume/cancel/retry, Wi-Fi-only, charging-only, speed limit, storage location (internal / SD card via SAF), low-storage guard, auto-delete watched (optional), WorkManager constraints + foreground service (dataSync type) with notification actions (pause/resume/cancel) and grouped progress.
  - Offline playback through `CacheDataSource` / local file; downloaded items get "Downloaded" badge, playable in airplane mode; resume position shared.
- Options sheet before start: quality list with ESTIMATED SIZE per quality (from bitrate × duration or Content-Length HEAD), audio tracks, subtitle tracks, location, connections.
- Downloads screen: tabs Active / Completed / Failed, storage bar (used by Cinetor / free), sort, multi-select delete.
- Download details sheet shows FULL info: title + poster, status, progress % + bytes downloaded / total, current speed + average speed + speed graph (last 60 s), ETA, elapsed time, connections active (per-chunk progress bars for progressive, segments done/total for HLS/DASH), quality/resolution, codec/bitrate, container format, audio + subtitle tracks chosen, file path, source host, started/finished timestamps, retry count, last error message, actions (pause/resume/retry/cancel/delete/open/play).
- Adult downloads stored in a separate app-private folder, hidden from Downloads unless adult space unlocked; Kids profile cannot download adult.
- [ ] Tests: `chunked_splitsRangesEvenly`, `chunked_resumesFromPersistedOffsets`, `chunked_noAcceptRanges_singleConnection`, `queue_respectsMaxConcurrent`, `wifiOnly_pausesOnMobile`, `sizeEstimate_fromBitrateAndDuration`, `downloadedItem_playsOffline`, `adultDownloads_hiddenWhenLocked`.

### Task 3.6: Floating players
Files: `c/feature/player/{PlayerHost.kt,MiniPlayer.kt,PipController.kt}`, `c/core/player/PlaybackService.kt` (MediaSessionService).
- `PlayerHost` lives ABOVE the NavHost so playback survives navigation. States `FULL | MINI | HIDDEN`.
- In-app floating player: swipe-down/back from player → draggable mini window (16:9, ~40% width), snaps to 4 corners, tap = expand, swipe off = stop; music uses a glass mini bar instead.
- System floating (PiP): auto-enter on Home press while playing (API 26+, `setAutoEnterEnabled` 31+), PiP actions ±10 s / play-pause, aspect from video.
- MediaSessionService: notification controls, lock-screen, Bluetooth/headset keys, background audio (music always, video if setting on).
- [ ] Tests: `miniPlayer_replacedByNewPlay_singleAudio`, `navigation_keepsPlayback`, `pip_actions_seek`.

---

## Phase 4 — Isolated spaces (Android)

All reuse `SpaceScreen` + design system, but have own repository scope, own in-space search, own history store (`space` column), never feed global features.

### Task 4.1: Live TV & Sports
Files: `c/feature/tv/{LiveTvScreen.kt,EpgGrid.kt,SportsScreen.kt,ChannelZapper.kt}`.
- Live: channel categories (News, Entertainment, Kids, Movies, Music, Sports…) + genre chips, hero = featured live channel (muted preview on HIGH only), channel grid, EPG guide grid (time × channel, now-line, `SkeletonEpgGrid` while loading — the "skeleton chart"), favorites.
- Player live mode: LIVE badge, go-to-live, DVR seek window if available, channel up/down zapping (swipe up/down in fullscreen; CH+/CH− and D-pad up/down on TV), quick channel list overlay.
- Sports: Live now / Upcoming / Results tabs, event cards with team crests (generated), mock scoreboard, start-time reminder (local notification).
- [ ] Tests: `epg_nowLinePosition`, `zap_nextChannelReusesPlayer`, `liveItems_notInGlobalSearch`.

### Task 4.2: Music
Files: `c/feature/music/{MusicHome.kt,AlbumScreen.kt,NowPlaying.kt,QueueSheet.kt,MusicMiniBar.kt}`.
- Albums/Tracks/Artists, genre chips, hero banner, now-playing full screen (big blurred artwork bg tinted by album color, animated progress, shuffle/repeat, queue reorder, lyrics placeholder), glass mini bar above nav, background playback via `PlaybackService`.
- [ ] Tests: `queue_reorder`, `music_backgroundContinuesOnNavigate`.

### Task 4.3: Comics (Manga / Manhwa / Manhua)
Files: `c/feature/comics/{ComicsHome.kt,ComicDetails.kt,ReaderScreen.kt,ReaderSettings.kt}`.
- Details: chapter list with `SkeletonChapterList`, read progress, bookmark.
- Reader modes: Manga = paged right-to-left; Manhwa/Manhua = vertical continuous (webtoon); user can override (LTR paged / vertical). Pinch/double-tap zoom, tap zones, page slider, brightness, keep-screen-on, preload next 3 pages (LOW: 1), next-chapter auto-load. TV: D-pad page turn.
- [ ] Tests: `manga_defaultsRtl`, `manhwa_defaultsVertical`, `progress_savedPerChapter`.

### Task 4.4: Adult (gated)
Files: `c/feature/adult/{AdultGate.kt,PinPad.kt,AdultSpace.kt}`.
- Hidden from Spaces until Settings → Content → "Show 18+ section": confirm age dialog → set 4-digit PIN (stored hashed, DataStore). Entry requires PIN per session (timeout 5 min background). `FLAG_SECURE` while inside. Kids profile: toggle disabled. Excluded from search, recents, history-on-home, notifications, recommendations.
- Placeholder content only; full player works.
- [ ] Tests: `adult_hiddenByDefault`, `adult_requiresPinEachSession`, `kidsProfile_cannotEnable`, `adult_notInContinueWatching`.

---

## Phase 5 — Profiles, Settings, About (Android)

### Task 5.1: Profiles + library
Files: `c/feature/profile/{ProfilePicker.kt,EditProfile.kt,LibraryScreen.kt}`, Room `CinetorDb` (`profile`, `history(space, itemId, pos, updatedAt)`, `watchlist`).
- Up to 5 profiles: name, avatar (generated set), color, Kids flag, optional profile PIN. Per-profile: history, watchlist, recents, theme, subtitle/audio defaults.
- Home "Continue Watching" = HOME/ANIME/DRAMA history only.
- [ ] Tests: `continueWatching_onlyVideoSpaces`, `deleteProfile_removesItsData`.

### Task 5.2: Settings
Files: `c/feature/settings/*`.
Groups: Appearance (Theme Noir/Obsidian/Ember with live preview cards, Effects quality Auto/Low/Med/High, Reduce motion, Dynamic color on/off) · Playback (default quality Wi-Fi/mobile, autoplay next, skip intro auto, double-tap seconds, hold speed (2× default), gestures on/off, auto PiP, background play) · Subtitles & Audio (preferred languages, style defaults) · Data & Storage (data saver, image quality, preload on details on/off, cache size, clear cache/history) · Content (18+ toggle + PIN change, maturity limit) · Profiles · TV (focus sounds, banner autoplay) · Developer (mock latency) · About.
- [ ] Tests: `themeChange_appliesWithoutRestart`, `settings_persistAcrossProcessDeath`.

### Task 5.3: About
Files: `c/feature/about/AboutScreen.kt`, `c/core/AppLinks.kt` (`TELEGRAM_CHANNEL_URL`, `TELEGRAM_GROUP_URL`, `ZARIR_URL = "https://ahmedishtiaquezarir.pro.bd/"`).
- Glass card: animated logo, "Cinetor", version/build, Telegram Channel (link), Telegram Group (link), "Developed By Mythbrix", "Thanks to Ahmed Ishtiaq Zarir" (name = hyperlink via `LinkAnnotation.Url`), open-source licenses.
- [ ] Test: `about_linksOpenExactUrls`, `about_copyExact`.

---

## Phase 6 — Android TV / Google TV / TV boxes

### Task 6.1: TV packaging
- Manifest: `LEANBACK_LAUNCHER` intent, `android:banner`, `uses-feature touchscreen required=false`, `leanback required=false` (same APK runs on phone + TV), `android.software.picture_in_picture required=false`. Works on Android TV, Google TV, Fire TV, generic boxes. Other smart TVs (Tizen/webOS) → use PWA in TV browser (Phase 8 spatial nav).

### Task 6.2: TV UI layer
Files: `c/tvui/{TvShell.kt,TvSideNav.kt,TvHero.kt,TvRow.kt,TvCard.kt,FocusMemory.kt}`.
- 10-foot layout (overscan-safe 5% margins), tv-material `Card`/`Carousel`, focus = scale 1.1 + glass glow + accent border + optional focus sound; side nav collapses to icons, expands on focus; hero carousel pauses when focus is inside it.
- `FocusMemory` stores last focused (row, index) per screen; restored after back/reload (Review Focus 5). Key handling: D-pad, OK, Back, Menu → settings sheet, media keys, CH± in live TV, number keys for channel number.
- TV player: no gestures; Left/Right = ±10 s (hold = accelerating scrub), OK = play/pause/controls, Up = controls/settings row, Down = episode/related rail, Back = hide controls then exit.
- [ ] Compose TV tests with key events: `dpad_navigatesRowsAndNav`, `returnFromDetails_restoresFocusedCard`, `tvPlayer_rightKeySeeks10s`.

---

## Phase 7 — Android performance & release

### Task 7.1: Optimization pass
- R8 full mode + resource shrinking; ABI splits (arm64-v8a, armeabi-v7a, x86_64) + universal APK; target per-ABI APK < 15 MB.
- Baseline profile module generated on CI emulator (startup, Home scroll, details open, player open); `profileinstaller`.
- Compose: strong skipping, stable models, `derivedStateOf` for scroll-driven UI, no allocations in draw, `graphicsLayer` for animations (no recomposition per frame).
- Coil: tier-aware cache sizes, hardware bitmaps (not on LOW API<26), prefetch next row images on idle.
- Macrobenchmark on CI emulator: cold start < 1.5 s (MID profile), Home scroll jank < 5%, memory < 200 MB on LOW.
- [ ] Commit benchmark results to `docs/perf/android.md`.

### Task 7.2: Android CI release
`android.yml`: on tag `v*` → `assembleRelease` signed (keystore from secrets `CINETOR_KEYSTORE_B64`, `..._PASS`), upload ABI APKs + universal to GitHub Release; on push → debug APK artifact. Uses `ship-apk.sh` flow for iteration.

---

## Phase 8 — Web app + website + PWA

Same features/flows as Android; Svelte components mirror Compose ones.

### Task 8.1: Web foundation
Files: `web/src/lib/{data/catalog.ts,data/search.ts,design/tokens.css,design/glass.css,perf/tier.ts,motion.ts}`, `web/src/routes/(app)/app/+layout.svelte`.
- Same repository semantics as Task 1.1 (fetch `/mock/*.json`, cache in memory + IndexedDB). Same unit tests in Vitest: `globalSearch excludes isolated spaces`, `tier lowMemory disables blur`.
- Glass via `backdrop-filter: blur(var(--blur)) saturate(140%)`; LOW tier → `[data-tier=low]` swaps to solid tint. Themes via `[data-theme=noir|obsidian|ember]`. Dynamic tint = CSS vars animated with `@property` + transition.

### Task 8.2: Web screens
- Spaces, hero carousel (auto 5 s, pause on hover/focus/visibility hidden), category tabs, genre chips, rows (virtualized horizontal lists, `content-visibility:auto`, `loading=lazy`, `decoding=async`, responsive `srcset`), details with skeleton shimmer, search, isolated spaces (Live TV + EPG, Sports, Music, Comics reader, Adult gate), profiles, settings, about (exact copy + links, `target=_blank rel=noopener`).
- View Transitions API card→details morph (fallback fade).
- Playwright tests mirror Android names (Chromium + WebKit + mobile emulation).

### Task 8.3: Web player
Files: `web/src/lib/player/{engine.ts (shaka singleton),Preloader.ts,Player.svelte,Controls.svelte,Gestures.ts,MiniPlayer.svelte,Subtitles.svelte}`.
- Single persistent `<video>` element owned by root layout; preloader attaches source on details open (`preload`, shaka `load()` paused) and the element is MOVED (not recreated) into the player/mini container → instant start, buffer kept.
- Features: quality, speed, audio tracks, subtitles (tracks, external file VTT/SRT via file input, style, sync offset), hold-to-2× (touch long-press / mouse hold / hold `Shift+.`), double-tap ±10 s, swipe seek, volume swipe (not on iOS: volume is read-only → show system-volume hint), brightness swipe (CSS `filter: brightness()` overlay — web can't change screen brightness), lock, aspect, fullscreen + `screen.orientation.lock('landscape')` where supported, in-app draggable mini player, system PiP (`requestPictureInPicture`, Document PiP on Chromium), Media Session API (lock screen / headset keys), keyboard shortcuts (Space/K, J/L, ←/→, ↑/↓, F, M, C, `<`/`>`, N next).
- MKV/HEVC: browser-dependent; show "open in external player" fallback.
- Downloads (web/PWA): HLS/DASH via `shaka.offline.Storage` (IndexedDB), progressive files via multi-range fetch into OPFS (Origin Private File System) with resume; `navigator.storage.persist()` + quota display; same Downloads screen + full details sheet (progress, bytes, speed, ETA, quality, tracks, size). iOS PWA caveat: storage quota limited and may be evicted — shown in UI.
- Search (web): FlexSearch index in a Web Worker (instant local results), TMDB with AbortController-cancelled requests + LRU cache, history in IndexedDB (local only, per profile).

### Task 8.4: TV/remote mode for web
- Spatial navigation engine (`web/src/lib/tv/spatial.ts`) for arrow keys/remote on Tizen/webOS/other TV browsers + desktop keyboard; auto-enabled by UA/`?tv=1`/first arrow key press; focus memory per screen.

### Task 8.5: PWA + website
- `vite-plugin-pwa`: manifest (`name: "Cinetor"`, `display: standalone`, `orientation: any`, theme/bg colors per theme, icons incl. maskable, screenshots, shortcuts: Search, Live TV, Music), Workbox: precache shell, images stale-while-revalidate (max 300 entries / 7 days), mock JSON network-first, NEVER cache media segments, offline fallback page.
- iOS: `apple-touch-icon`, `apple-mobile-web-app-capable`, status-bar style, all startup images, safe-area `env()` insets; in-app "Install" sheet: Android/desktop uses `beforeinstallprompt`, iOS shows "Share → Add to Home Screen" illustrated guide.
- Animated splash inline in `app.html` (pure SVG/CSS, renders before JS, removed on app ready).
- Landing site at `/`: glass hero with animated logo, feature sections, screenshots carousel, download buttons (Android APK, Android TV APK, Windows .exe, Linux .deb/.AppImage — links to latest GitHub Release), "Open Web App"/"Install" buttons, about/credits footer (same copy + links).
- [ ] Lighthouse CI: Performance ≥ 90 mobile, PWA installable, Accessibility ≥ 90. Bundle: initial JS < 120 KB gz (player chunk lazy).

### Task 8.6: Web deploy
`web.yml`: build → deploy GitHub Pages (or Cloudflare Pages if custom domain). SPA fallback 404.html.

---

## Phase 9 — Desktop (Windows + Linux)

### Task 9.1: Tauri shell
Files: `desktop/src-tauri/{tauri.conf.json,Cargo.toml,src/main.rs,capabilities/*.json}`.
- Loads built `web/build` (bundled, works offline for shell), `decorations: false` + custom glass title bar (drag region, min/max/close), Windows Mica/Acrylic via `window-vibrancy`, Linux translucent fallback by tier.
- Desktop extras: "Mini player" mode = window shrinks to 16:9 always-on-top corner window; system PiP where WebView supports it; global media keys; single-instance; remember window size.
- Desktop downloads: native Rust engine as Tauri commands (`reqwest` + tokio, multi-connection Range chunks up to 16, resume via sidecar `.cinetor-part` metadata, HLS/DASH segment downloader + remux to MP4 via bundled-free approach: keep `.ts`/fMP4 segments concatenated; optional ffmpeg if present) saving to user Downloads/Cinetor folder; progress events streamed to the same web Downloads UI.
- Linux: WebKitGTK needs GStreamer for MSE → `.deb` depends `gstreamer1.0-plugins-good, gstreamer1.0-plugins-bad, gstreamer1.0-libav`. RISK: if HLS/DASH playback fails on WebKitGTK in testing, fallback = Electron build for Linux only (same web code, `desktop-electron/`), keep Tauri for Windows.

### Task 9.2: Desktop CI
`desktop.yml`: matrix `windows-latest` (NSIS `.exe` + `.msi`), `ubuntu-22.04` (`.deb` + `.AppImage`) via `tauri-apps/tauri-action`; attach to GitHub Release on tag. Targets: installer < 15 MB, idle RAM < 150 MB.

---

## Phase 10 — Release, QA, delivery

- `release.yml`: tag `v0.1.0` → Android + Desktop + Web jobs → single GitHub Release with all assets + checksums; website download buttons point at `releases/latest`.
- QA matrix (manual + CI): low-end phone profile (emulator 2 GB, API 24), modern phone (API 35), Android TV emulator (1080p, API 28/34), Chrome/Edge/Firefox desktop, Safari iOS (PWA via BrowserStack-free alternative: Playwright WebKit + manual on user device), Windows exe, Linux deb.
- Deliver APKs to user's Telegram Saved Messages (@Mythbrix) per skill notes.

---

## Execution order & parallelism

1. Phase 0 (serial).
2. Track A (Android): 1 → 2 → 3 → 4 → 5 → 6 → 7. Track B (Web): 8.1 → 8.2 → 8.3 → 8.4 → 8.5 → 8.6 → 9. A and B run in parallel after Phase 0 (shared contract fixed).
3. Phase 10.
Milestone demos: M1 = Android Home/Details/skeletons with red/black themes · M2 = Android player complete · M3 = all spaces + settings + TV · M4 = PWA live · M5 = exe/deb + v0.1.0 release.

## Decisions (answered 2026-11-14)
1. TELEGRAM_CHANNEL_URL = `https://t.me/mythbrix_vault`; TELEGRAM_GROUP_URL = `https://t.me/cinetor`.
2. Section name "Comics" confirmed.
3. Website on free GitHub Pages now (`ashrix404.github.io/cinetor`, base path `/cinetor`); custom domain later → keep base path configurable (`BASE_PATH` env).
4. Logo: multiple variants generated, user picks from desktop preview before Task 0.2 finalizes.
5. Execution: subagent-driven development.
