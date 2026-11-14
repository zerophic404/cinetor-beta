# Web phase 4 — isolated spaces, profiles, settings, about

Branch `web/phase-4` (worktree ~/apk-projects/cinetor-web). CI = web.yml (build+vitest+svelte-check). All letters green.

## Done
- A Live TV & Sports — `lib/tv/epg.ts` (pure: now-line, guide blocks, filters, zap wrap, sports tabs), `LiveTv.svelte`
  (group+genre chips, channel grid, EPG guide w/ now-line, SkeletonEpgGrid, ★ favorites/profile), `Sports.svelte`
  (Live now/Upcoming/Results, scoreboard). Player live mode: LIVE badge, PgUp/PgDn/ChannelUp/Down, ↑/↓ when controls
  hidden, centre swipe (Gestures `swipeV`), quick channel list on watch page.
- B Music — `music/queue.ts` + `core.ts` (pure), `player.svelte.ts` (ONE `<audio>` in /app layout), MusicHome (hero,
  genre chips, albums, top tracks, artists, moods), `/app/music/album?id=[&track=]`, NowPlaying (blur art bg tinted
  by album colour, seek, shuffle/repeat, queue drag/▲▼/Alt+↑↓/remove), glass MusicBar above nav.
  `media/arbiter.ts`: music play stops video session; video `play` pauses music.
- C Comics — `comics/reader.ts` (pure), `/app/comics/title?id=` (chapter skeleton, progress bars, bookmark=myList),
  `/app/comics/read?id=&ch=` (manga rtl paged, manhwa/manhua vertical, override saved/title, tap zones, swipe, slider,
  arrows/PgUp/PgDn, preload 3 / LOW 1, next chapter auto-load, progress per chapter+profile).
- D 18+ — `adult/gate.ts` (age confirm, 4-digit PIN SHA-256 via crypto.subtle, per-session unlock, 5-min hidden
  relock, kids blocked), AdultLock/PinPad on /app/adult, adult search, title + watch of ad-* ids. Hidden from Spaces
  until enabled. Adult searches not stored in recents.
- E Profiles — `profile/profiles.ts` (max 5, initials+colour, kids, optional PIN, theme; delete sweeps
  `cinetor.<kind>.<id>.*` + resume + search history). Picker/editor in Me. `/app/library` (watchlist incl. saved
  albums/comics, continue watching = video spaces only, clear history).
- F `/app/settings` — Appearance (theme cards live, effects tier, reduce motion), Playback (autoplay next,
  double-tap secs → gestures step, hold speed, gestures on/off), Subtitles (auto-on + lang, size/colour/bg), Data
  (preload on details, latency, clear cache/history), Content (18+ toggle/PIN change), Profiles, About link.
- G `/app/about` — animated logo, version 0.4.0 (= package.json), Telegram links, exact credits, TMDB; `lib/about.ts`.

## Tests added (vitest)
tv/epg.test, music/music.test, comics/comics.test, adult/gate.test, profile/profiles.test, settings/settings.test, about.test.

## SHAs / runs
- A 5051e3b · B de13bfb · C b69f728 · D 775bdf3 · E 0529372 · F+G 5314a63 (run 43653471444 green) · QA fixes: see PR.
- Browser QA on CI artifact: TV grid/guide/sports, zap wrap 101→130, music play survives nav, NowPlaying queue,
  manga rtl keys + progress, manhwa vertical auto-appends chapters, 18+ enable→reload asks PIN, kids profile blocks 18+, about links.

## Deviations
- EPG offsets relative to first TV open this session (`live.base`). Profile switch = full reload (simplest reset).
- Settings are device-wide; theme + subtitle style per profile. 18+ unlock is in-memory (reload = new session).
- Mobile nav now shows the logo as the space switcher (was hidden < 900px → spaces unreachable on phones).

## Open bugs / follow-ups
- TV genre chips repeat group names (channel genres = group). Music media-session handlers not restored for video.
