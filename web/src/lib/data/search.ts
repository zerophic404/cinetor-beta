import type { CatalogRepository } from './catalog';
import { VIDEO_SPACES, type Item, type Space } from './types';

/** Lowercase, strip diacritics (NFD + combining marks), collapse whitespace. */
export function normalize(s: string): string {
  return s
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim();
}

/**
 * Match score (lower = better), or -1 for no match.
 * 0 title prefix · 1 word prefix in title · 2 altTitle prefix/word prefix · 3 substring anywhere.
 */
export function matchScore(item: Pick<Item, 'title' | 'altTitle'>, nq: string): number {
  if (!nq) return -1;
  const t = normalize(item.title);
  const a = item.altTitle ? normalize(item.altTitle) : '';
  if (t.startsWith(nq)) return 0;
  if (t.split(' ').some((w) => w.startsWith(nq)) || t.includes(' ' + nq)) return 1;
  if (a && (a.startsWith(nq) || a.split(' ').some((w) => w.startsWith(nq)))) return 2;
  if (t.includes(nq) || (a && a.includes(nq))) return 3;
  // Ignore spaces/punctuation: "spiritcafe" still finds "Spirit Café".
  const squash = nq.replace(/ /g, '');
  if (squash && (t.replace(/ /g, '').includes(squash) || a.replace(/ /g, '').includes(squash))) return 3;
  return -1;
}

export function searchItems(items: Iterable<Item>, query: string, limit = 60): Item[] {
  const nq = normalize(query);
  if (!nq) return [];
  const hits: { item: Item; score: number }[] = [];
  for (const item of items) {
    const score = matchScore(item, nq);
    if (score >= 0) hits.push({ item, score });
  }
  hits.sort((x, y) => x.score - y.score || y.item.rating - x.item.rating || x.item.title.localeCompare(y.item.title));
  return hits.slice(0, limit).map((h) => h.item);
}

export interface GroupedResults {
  query: string;
  total: number;
  groups: { space: Space; items: Item[] }[];
}

/** Global search — ONLY Home, Anime, Drama. Isolated spaces are never fetched or returned. */
export async function search(repo: CatalogRepository, query: string, limitPerSpace = 40): Promise<GroupedResults> {
  const groups = await Promise.all(
    VIDEO_SPACES.map(async (space) => ({
      space,
      items: searchItems((await repo.items(space)).filter((i) => i.space === space), query, limitPerSpace)
    }))
  );
  const nonEmpty = groups.filter((g) => g.items.length);
  return { query, total: nonEmpty.reduce((n, g) => n + g.items.length, 0), groups: nonEmpty };
}

/** In-space search: scoped to exactly one space (used by isolated spaces and per-space filters). */
export async function searchInSpace(repo: CatalogRepository, space: Space, query: string, limit = 60): Promise<Item[]> {
  return searchItems((await repo.items(space)).filter((i) => i.space === space), query, limit);
}
