// Live TV & Sports pure logic: EPG timing, guide layout, channel filters, zapping, sports tabs.
// Program `startOffsetSec` values in the mock are relative to the guide's "base" moment (the
// time the TV space was opened), so something is always on air.
import type { Item, Program } from '../data/types';

export const SLOT_SEC = 30 * 60;
export const GUIDE_WINDOW_SEC = 4 * 3600;

export interface Timed {
  program: Program;
  start: number;
  end: number;
}

export const programTimes = (p: Program, baseSec: number): Timed => ({
  program: p,
  start: baseSec + p.startOffsetSec,
  end: baseSec + p.startOffsetSec + p.durationSec
});

/** Programme on air at `nowSec` (or undefined between programmes). */
export function currentProgram(programs: readonly Program[] | undefined, baseSec: number, nowSec: number): Timed | undefined {
  for (const p of programs ?? []) {
    const t = programTimes(p, baseSec);
    if (t.start <= nowSec && nowSec < t.end) return t;
  }
  return undefined;
}

/** Next programme starting after `nowSec`. */
export function nextProgram(programs: readonly Program[] | undefined, baseSec: number, nowSec: number): Timed | undefined {
  let best: Timed | undefined;
  for (const p of programs ?? []) {
    const t = programTimes(p, baseSec);
    if (t.start > nowSec && (!best || t.start < best.start)) best = t;
  }
  return best;
}

/** 0..1 progress of a programme at `nowSec`. */
export function programProgress(t: Pick<Timed, 'start' | 'end'>, nowSec: number): number {
  const d = t.end - t.start;
  if (d <= 0) return 0;
  return Math.min(1, Math.max(0, (nowSec - t.start) / d));
}

/** Guide window starts at the previous half-hour boundary. */
export const windowStartFor = (nowSec: number, slotSec = SLOT_SEC): number => Math.floor(nowSec / slotSec) * slotSec;

/**
 * Horizontal position (px) of the "now" line inside the guide, measured from the window start.
 * Clamped to [0, windowSec * pxPerSec]; null when `now` is outside the window.
 */
export function nowLinePosition(nowSec: number, windowStartSec: number, pxPerSec: number, windowSec = GUIDE_WINDOW_SEC): number | null {
  const dt = nowSec - windowStartSec;
  if (dt < 0 || dt > windowSec) return null;
  return Math.round(dt * pxPerSec * 100) / 100;
}

export interface Block {
  program: Program;
  left: number;
  width: number;
  start: number;
  end: number;
  live: boolean;
}

/** Programme blocks clipped to the guide window. */
export function guideBlocks(
  programs: readonly Program[] | undefined,
  baseSec: number,
  windowStartSec: number,
  pxPerSec: number,
  nowSec: number,
  windowSec = GUIDE_WINDOW_SEC
): Block[] {
  const winEnd = windowStartSec + windowSec;
  const out: Block[] = [];
  for (const p of programs ?? []) {
    const t = programTimes(p, baseSec);
    if (t.end <= windowStartSec || t.start >= winEnd) continue;
    const s = Math.max(t.start, windowStartSec);
    const e = Math.min(t.end, winEnd);
    out.push({ program: p, left: (s - windowStartSec) * pxPerSec, width: Math.max(1, (e - s) * pxPerSec), start: t.start, end: t.end, live: t.start <= nowSec && nowSec < t.end });
  }
  return out.sort((a, b) => a.left - b.left);
}

/** Half-hour tick labels across the window. */
export function timeSlots(windowStartSec: number, windowSec = GUIDE_WINDOW_SEC, slotSec = SLOT_SEC): number[] {
  const out: number[] = [];
  for (let t = windowStartSec; t < windowStartSec + windowSec; t += slotSec) out.push(t);
  return out;
}

export const channelsOf = (items: readonly Item[]): Item[] =>
  items.filter((i) => i.type === 'CHANNEL').sort((a, b) => (a.channelNumber ?? 0) - (b.channelNumber ?? 0));

export const channelGroups = (channels: readonly Item[]): string[] => [...new Set(channels.map((c) => c.channelGroup).filter((g): g is string => !!g))];

export const FAVORITES = '__fav';

export interface ChannelFilter {
  /** Channel group, FAVORITES, or null for all. */
  group: string | null;
  genre: string | null;
  favorites: readonly string[];
  baseSec: number;
  nowSec: number;
}

/** Genres a channel currently matches: its own genres + the on-air programme genre. */
export function channelGenres(ch: Item, baseSec: number, nowSec: number): string[] {
  const cur = currentProgram(ch.programs, baseSec, nowSec)?.program.genre;
  return [...new Set([...ch.genres, ...(cur ? [cur] : [])])];
}

export function filterChannels(channels: readonly Item[], f: ChannelFilter): Item[] {
  return channels.filter((c) => {
    if (f.group === FAVORITES) {
      if (!f.favorites.includes(c.id)) return false;
    } else if (f.group && c.channelGroup !== f.group) return false;
    if (f.genre && !channelGenres(c, f.baseSec, f.nowSec).some((g) => g.toLowerCase() === f.genre!.toLowerCase())) return false;
    return true;
  });
}

/** Genre chips for the current group (channel + on-air genres, file order). */
export function genreChips(channels: readonly Item[], order: readonly string[], baseSec: number, nowSec: number): string[] {
  const have = new Set(channels.flatMap((c) => channelGenres(c, baseSec, nowSec)));
  const ordered = order.filter((g) => have.has(g));
  return [...ordered, ...[...have].filter((g) => !order.includes(g)).sort()];
}

/** Channel up (+1) / down (−1) — wraps around the list. Unknown current → first/last. */
export function zapChannel<T extends { id: string }>(channels: readonly T[], currentId: string | null | undefined, dir: 1 | -1): T | undefined {
  const n = channels.length;
  if (!n) return undefined;
  const i = channels.findIndex((c) => c.id === currentId);
  if (i < 0) return dir > 0 ? channels[0] : channels[n - 1];
  return channels[(((i + dir) % n) + n) % n];
}

export function toggleFavorite(list: readonly string[], id: string): string[] {
  return list.includes(id) ? list.filter((x) => x !== id) : [id, ...list];
}

// ---- Sports ----
export type SportsTab = 'live' | 'upcoming' | 'results';

export function sportsTabs(items: readonly Item[]): Record<SportsTab, Item[]> {
  const ev = items.filter((i) => i.type === 'EVENT');
  const off = (i: Item) => i.startOffsetSec ?? 0;
  return {
    live: ev.filter((i) => i.status === 'LIVE').sort((a, b) => off(a) - off(b)),
    upcoming: ev.filter((i) => i.status === 'UPCOMING').sort((a, b) => off(a) - off(b)),
    results: ev.filter((i) => i.status === 'FINISHED').sort((a, b) => off(b) - off(a))
  };
}

/** Match clock for a live event ("63'"), capped at 90+ for football-length events. */
export function matchMinute(ev: Pick<Item, 'startOffsetSec' | 'durationSec'>, baseSec: number, nowSec: number): string {
  const elapsed = Math.max(0, nowSec - (baseSec + (ev.startOffsetSec ?? 0)));
  const min = Math.floor(elapsed / 60);
  const cap = Math.round((ev.durationSec ?? 7200) / 60);
  if (min >= cap) return `${cap}+'`;
  return `${min}'`;
}

export function startsIn(ev: Pick<Item, 'startOffsetSec'>, baseSec: number, nowSec: number): string {
  const s = Math.max(0, baseSec + (ev.startOffsetSec ?? 0) - nowSec);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h >= 24) return `in ${Math.floor(h / 24)}d ${h % 24}h`;
  return h ? `in ${h}h ${m}m` : `in ${m}m`;
}

export const clockLabel = (sec: number): string => {
  const d = new Date(sec * 1000);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
};
