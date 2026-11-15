# Cinetor ⇄ Antiblock Gateway (user's own Vercel project)

Base: `https://antiblock-gateway.vercel.app` (Vercel project `antiblock-gateway`, owner zerophic404). Make base URL a single constant per platform (`GatewayConfig.BASE_URL` / `GATEWAY_BASE`), overridable in Settings → Developer.
No auth needed for public endpoints. CORS `*`. Samples (trimmed, live-captured): `docs/gateway-samples/*.json`. Gateway source (read-only reference, NOT in repo): `~/apk-projects/antiblock-gateway-src/`.

## Public catalog API (`/api/public/v1/...`) — primary source for Home/Anime/Drama + playback
| Endpoint | Use |
|---|---|
| `GET /home` | `sections[]{section,count,items[]}` (Banner, trending…) → Home hero + rows |
| `GET /movies?page=&genre=&country=&year=` | Movies category (paged: page, per_page, has_more, items[]) |
| `GET /tv-series?page=&genre=&country=&year=` | Series; Drama K/C/J = `country=Korea|China|Japan` |
| `GET /animation?page=&genre=&country=` | Anime |
| `GET /search?q=&page=` | global search (items[] with subject_id, slug, type, poster_url, year, genre, rating, subtitles, dubs, duration) |
| `GET /search/suggest?q=` | suggestions |
| `GET /detail/{slug}` | `{code,data:{subject{subjectId,subjectType(1=movie,2=tv),title,description,releaseDate,duration,genre,cover{url},…}, …seasons/resource/stars}}` |
| `GET /stream/{subject_id}?detail_path={slug}&se={n}&ep={n}` | `{has_resource, direct_play_url, sources[]{source,resolution,format(MP4/DASH/HLS),url,size,duration,codec,headers{Referer,Origin,Cookie,…},isBdix}}` movie: se=0&ep=0 |
| `GET /stream/{id}/play?detail_path=&se=&ep=` | gateway video PROXY (Range supported, 206 video/mp4) — use when client cannot set headers (web/PWA/desktop webview) |
| `GET /stream/{id}/captions?detail_path=&se=&ep=` | `{count,captions[]}` subtitles |
| `GET /subtitle?url=` | subtitle fetch proxy |
| `GET /adult` | NOT used (adult section stays placeholder/gated) |

## Resolver API (`/api/v1/resolve?tmdb=&type=movie|tv&s=&e=&title=&year=&preferBdix=1`)
`{ok, sources[]{provider,quality,url,type,headers,isBdix}}`. Currently returns 0 sources (providers failing) → use only as secondary attempt for TMDB-origin items; never block UI on it.

## Integration rules
- Item id prefix `gw:` + subject_id; keep `slug` (detail_path) on the model. Map: name→title, poster_url→poster (also backdrop until better), rating, year, genre (comma) → genres, type movie/tv, subtitles/dubs → badges.
- dominantColor: compute from poster (Palette Android / canvas web), cached.
- Playback Android: pick sources sorted by resolution; Media3 per-item headers (DefaultHttpDataSource request properties from `headers`, incl Cookie/Referer/Origin); quality menu = gateway resolutions (plus HLS/DASH internal tracks); on error fall back to `direct_play_url` (absolute = BASE + path); captions → side-loaded subtitles.
- Playback web/desktop: browsers can't set Cookie/Referer → use `direct_play_url` proxy (MP4) or proxy URL per source; DASH/HLS with headers → proxy.
- Downloads: same resolved URL + headers (Android) / proxy URL (web, desktop native can set headers).
- Fallback chain: Gateway → TMDB metadata (no playable source → try `/api/v1/resolve`, else show "No source available") → mock catalog when offline.
- Isolated spaces (Live TV, Sports, Music, Comics) untouched; Adult unchanged.
- Timeouts: catalog 10 s, stream 15 s; cache catalog 10 min memory+disk; stream links expire (signed `t=`) → re-resolve on 403/expired, never cache > 30 min.

## Gateway update (re-inspected 2026-11-15 20:0x, deployment dpl_CRdqpRwBMi1NqfwPetD8KeTkfaJq)
New/changed, verified live:
- `GET /home` now returns 26 curated sections (was ~few): Banner, Trending Short Drama, 🔥Hot Short TV, Zee Bangla Shows, Indian Dramas, Original Western TV, Popular Series, Popular Movie, Nollywood Movie, Adult Animation, Epic Fantasy, Comedy, Teen Romance, Superhero Series, BL Story, Bet+, Action&Thriller, Gangster, Teen Fantasy, Coming Soon, Anime[English Dubbed], Action Movies, Horror Movies, Must-watch Black Shows, 💓Romance 💓, 🔔🎄Christmas Movies 2026. Render ALL sections as rows in order; `Banner` = hero carousel. Section names contain emoji — keep as-is.
- `GET /short-tv?page=` — micro-dramas / short TV (verified 200). New row/category.
- `GET /zee-bangla?page=` (alias `/zeebangla`) — Bengali serials (verified 200). New row/category.
- `GET /adult?kind=movies&page=&sort=latest&country=` — note `kind` + `sort` params.
- `GET /manga?action=c2&id=<mangadex-chapter-id>` — ONLY chapter-page-server proxy (MangaDex `at-home/server`). `/manga` with no action (or action=browse) returns 404 `{"error":"not_found"}` — browse is NOT in the gateway.
- Resolver providers added server-side (anime/luna, cinefreak, zeebangla) — still behind `/api/v1/resolve`, which returned 0 sources in testing; do not depend on it.

### Comics plan (all platforms)
Gateway has no comics browse. Use MangaDex public API directly for metadata + chapters, and the gateway ONLY for chapter page servers:
- Browse/category: `https://api.mangadex.org/manga?limit=&offset=&order[followedCount]=desc&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive&availableTranslatedLanguage[]=en` — Manga vs Manhwa vs Manhua via `originalLanguage[]=ja` / `ko` / `zh,zh-hk`.
- Search: same endpoint with `title=`.
- Cover: `https://uploads.mangadex.org/covers/<manga-id>/<cover-file>.512.jpg` (cover file from `includes[]=cover_art` relationship).
- Chapter list: `https://api.mangadex.org/manga/<id>/feed?translatedLanguage[]=en&order[chapter]=asc&limit=500&includeExternalUrl=0`.
- Pages: `GET {GATEWAY}/api/public/v1/manga?action=c2&id=<chapter-id>` → `{baseUrl, chapter:{hash,data[],dataSaver[]}}`; page URL = `{baseUrl}/data/{hash}/{file}`. KNOWN ISSUE: in testing this returned `{"chapter":{"data":[]}}` (upstream bot-challenge). Implement: try gateway proxy → on empty, call `https://api.mangadex.org/at-home/server/<chapter-id>` directly with a non-browser UA (`CinetorApp/1.0`) → if still empty, show "Chapter unavailable, try another" and keep the rest of the UI working. Must never crash.
- Reader UX unchanged (manga RTL paged, manhwa/manhua vertical). Mock comics data stays as offline fallback only.
