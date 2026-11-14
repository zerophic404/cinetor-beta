// Video sources stay mock test streams: remote (TMDB) items borrow sources/subtitles from a
// deterministic mock item of the same space (Android MockSourceProvider equivalent).
import type { Episode, Item } from './types';

export function hashStr(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
}

const pickBy = <T>(list: T[], key: string): T | undefined => (list.length ? list[hashStr(key) % list.length] : undefined);

export function mockEpisodes(pool: Item[]): Episode[] {
  return pool.flatMap((i) => i.seasons?.flatMap((s) => s.episodes) ?? []).filter((e) => e.sources?.length);
}

export function attachEpisodeSources(episodes: Episode[], pool: Item[]): Episode[] {
  const donors = mockEpisodes(pool);
  const flat = pool.filter((i) => i.sources?.length);
  return episodes.map((e) => {
    if (e.sources.length) return e;
    const d = pickBy(donors, e.id);
    if (d) return { ...e, sources: d.sources, subtitles: d.subtitles, introStartSec: d.introStartSec, introEndSec: d.introEndSec };
    const f = pickBy(flat, e.id);
    return f ? { ...e, sources: f.sources ?? [], subtitles: f.subtitles ?? [] } : e;
  });
}

/** Copy of `item` with mock sources (movies) / episode sources (loaded seasons). */
export function attachMockSources(item: Item, pool: Item[]): Item {
  const out: Item = { ...item };
  if (!out.sources?.length) {
    const donor = pickBy(
      pool.filter((i) => i.sources?.length),
      item.id
    );
    if (donor) {
      out.sources = donor.sources;
      out.subtitles = donor.subtitles;
      out.introStartSec ??= donor.introStartSec;
      out.introEndSec ??= donor.introEndSec;
    }
  }
  if (out.seasons) out.seasons = out.seasons.map((s) => ({ ...s, episodes: attachEpisodeSources(s.episodes, pool) }));
  return out;
}
