# Gateway → Web/PWA + Desktop (branch feat/gateway)

Caveman notes. Spec: docs/gateway.md. Android = separate branch feat/gateway-android.

## Done
- **Catalog** `web/src/lib/data/gateway.ts` (pure, vitest): `GATEWAY_DEFAULT` + localStorage override `cinetor.gatewayBase` (Settings → Developer; '' = off). `GatewayClient` (10 s timeout, memory + IndexedDB cache 10 min, stale IDB on network fail, in-flight dedupe). `GatewayCatalogSource`: plans per space —
  - HOME: Movies (`/movies` paged + movie-majority `/home` sections, Banner hero) · Series (`/tv-series` + tv sections).
  - ANIME: Popular (`/animation`, `/tv-series?genre=Anime`, anime home section) · Action · Comedy (`genre=`).
  - DRAMA: K/C/J (`/tv-series?country=Korea|China|Japan` + Romance/Comedy + matching home section).
  - ADULT: `ad-featured` Movies (`/adult?kind=movies`) · `ad-series` (`kind=tv-series`).
  - Ids `gw:<subject_id>`; 18+ ids `ad-gw:<id>` (reuse `ad-` prefix → every existing adult gate via spaceForId). Episodes `<id>-s<se>e<ep>`. `Item.slug` kept; slugs persisted (`gw:slug:<id>` IDB) + `&slug=` in detailsHref → deep links work.
  - Detail `/detail/{slug}`: stills backdrop, cover.avgHueDark color, cast, inline seasons/episodes from `resource.seasons`.
  - Only `has_resource=true`; titles that resolve to 0 playable sources → `DeadTitles` (localStorage, ≤500) → hidden everywhere.
  - Infinite rows (`Row.more`, PosterRow sentinel) + server-side genre chips (paged grid).
  - Search: `/search` = remote part of the worker search engine (18+ dropped); `/search/suggest` merged into suggestions.
- **Gateway-only** (user override): HOME/ANIME/DRAMA/ADULT never use mock or TMDB (`Catalog.ownsSpace`). Offline → cached gateway data or error + Retry. Mock shipped ONLY for TV/Music/Comics (`scripts/sync-shared.mjs` copies those JSON + 184 referenced assets; build 17 → 9.5 MB). `repo` limited to those spaces. TMDB code/tests kept (legacy path when no gateway wired), not used by the app.
- **Playback** `web/src/lib/data/gateway-play.ts`: lazy placeholder sources `gw-stream:<id>?detail_path&se&ep` (TMDB-origin: `gw-find:` → `/api/v1/resolve` then gateway search title+year match). `StreamResolver` → `/stream/{id}?…&full=1` + `/captions` → MP4 variants via proxy `/stream/{id}/play?url=<cdn>&res=` (abs URL, Range 206), highest first, `direct {url, headers}` kept. Captions → `/subtitle?url=` (VTT, CORS ok). Cache ≤20 min (signed `t=`).
  - DASH/HLS NOT offered: gateway proxy doesn't rewrite manifests (relative segment URLs hit the proxy path) and url-proxy has no Cookie (403) → MP4 only. Titles with no MP4 = dead/hidden.
  - `controller.prepareMedia(item, ep)` = offline copy else resolved media; title page preload + watch page use it (instant play: same key). `Media.variants` → quality menu reloads another proxy MP4 at the same time (pref saved `cinetor.gwQuality`, default ≤1080). Engine fallback: other variants → re-resolve (`engine.recover`) on load error and once mid-playback (expired link). `Media.noSource` → "No source available".
- **Downloads**: options sheet resolves the placeholder, quality radio over variants, size probe via proxy. Record keeps `lazyUrl`/`directUrl`/`directHeaders`; link re-resolved before a run when >15 min old or retried. Desktop native: `url=direct + headers`, `fallbackUrl=proxy`, `resumeKey` (Rust `dl.rs`: `client_with_headers`, `pick_route` probe direct 12 s → else proxy; sidecar matches by key, so re-signed URLs still resume; cargo test added). 18+ downloads allowed, hidden unless unlocked (existing visibleDownloads).
- **Navigation**: top logo Spaces switcher + SpacesSheet removed (logo now plain brand on the desktop rail). `/app/me` → glass "Spaces" grid (Live TV & Sports, Music, Comics · Manga/Manhwa/Manhua, 18+ only when enabled; PIN asked by AdultLock). Isolated-space last tab "Me" (was Exit → Home); TV back from a space root → Me. Pure `lib/spaces.ts`.
- **18+**: catalog asks `setAdultGate(() => adult.enabled && adult.unlocked)` (set in /app layout before children render) → locked = zero gateway calls, no metadata, not in global search/recents/continue (existing space filters). Adult search = local index of the adult pool only (no remote).
- Images: aoneroom CDN resize `?x-oss-process=image/resize,w_N` → srcset per tier; color sampling at w_100.
- Landing: "Real streaming…" copy.

## Tests (vitest 189 pass; gateway.test.ts 23)
mapping home/paged/genre/detail movie+tv/search/suggest, has_resource + dead filter, cache, image srcset, proxy/subtitle/direct-play URLs, variants/applyResolved/noSource, resolver (full=1, find via resolve→search, dead), TMDB title match, catalog chain (gateway-only, no mock fallback), TV/Music/Comics never call gateway, 18+ only when unlocked, global search never 18+, Me spaces entries.

## CI
- web.yml green: https://github.com/Ashrix404/cinetor/actions/runs/43711173909 (svelte-check 0 errors).
- desktop.yml green: https://github.com/Ashrix404/cinetor/actions/runs/43712006872 (linux + windows bundles, cargo tests 5 incl. headers/sidecar-key).

## Browser QA (CI artifact, local server)
Real posters on Home/Anime/Drama; movie preloaded (readyState 4) → Play → 1080p proxy MP4 playing; Rick and Morty S1E1: 11 eps, quality 720p→480p switch reloads, English subtitles render via /subtitle proxy; search "inception" → 24 gateway results + remote suggestion; 18+: hidden in Me until enabled → PIN pad → gateway list (ad-gw ids) → Play streams; Inception download sheet → 1080p MP4 2.2 GB via proxy.

## Not verified / caveats
- Desktop native download with headers not run on a real desktop (CDN returned 428 from a BD IP even with headers → fallback to proxy is the likely path there).
- Hero uses the portrait poster as backdrop for list items (detail has stills).
- Gateway rate limit 180 req/min/IP (Drama = ~10 list calls on first load, cached 10 min).
