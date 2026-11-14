# Web phase 4 — isolated spaces, profiles, settings, about

Branch `web/phase-4` (worktree ~/apk-projects/cinetor-web). CI = web.yml (build+vitest+svelte-check).

## Done
- A Live TV & Sports: `lib/tv/epg.ts` (pure: now-line, guide blocks, filters, zap wrap, sports tabs) + tests;
  `LiveTv.svelte` (category+genre chips, channel grid, EPG guide w/ now-line, skeleton, ★ favorites per profile),
  `Sports.svelte` (Live now/Upcoming/Results, scoreboard). Player live mode: LIVE badge, PgUp/PgDn, ↑/↓ when
  controls hidden, centre swipe (Gestures `swipeV`), quick channel list on watch page.

## SHAs / runs
- (pending)

## Deviations
- EPG programme offsets relative to first TV open this session (`live.base`).

## Open bugs
- none known
