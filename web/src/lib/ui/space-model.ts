// Space screen view-model (pure): category tabs, hero items, genre chip filtering of rows.
import type { CatalogFile, Category, Item, Row, Space } from '../data/types';

export interface Tab {
  id: string;
  label: string;
}

export interface SpaceModel {
  space: Space;
  categories: Category[];
  tabs: Tab[];
  byId: Map<string, Item>;
  /** Genre order from the file (falls back to discovery order). */
  genres: string[];
}

export interface ViewRow {
  row: Row;
  items: Item[];
}

/** Music tabs are Albums | Tracks (synthesized from the mock Discover/Moods categories). */
export function musicCategories(file: CatalogFile): Category[] {
  const byId = new Map(file.items.map((i) => [i.id, i] as const));
  const allRows = file.categories.flatMap((c) => c.rows);
  const make = (id: string, title: string, type: Item['type'], heroFrom: string[]): Category => {
    const rows: Row[] = [];
    const seen = new Set<string>();
    for (const r of allRows) {
      if (seen.has(r.id)) continue;
      seen.add(r.id);
      const itemIds = r.itemIds.filter((x) => byId.get(x)?.type === type);
      if (itemIds.length) rows.push({ ...r, itemIds });
    }
    let heroIds = heroFrom.filter((x) => byId.get(x)?.type === type);
    if (!heroIds.length)
      heroIds = file.items
        .filter((i) => i.type === type)
        .sort((a, b) => b.rating - a.rating)
        .slice(0, 5)
        .map((i) => i.id);
    return { id, space: file.space, title, heroIds, rows };
  };
  const heroes = file.categories.flatMap((c) => c.heroIds);
  return [make('mu-albums', 'Albums', 'ALBUM', heroes), make('mu-tracks', 'Tracks', 'TRACK', [])].filter((c) => c.rows.length);
}

export function buildSpaceModel(file: CatalogFile): SpaceModel {
  const categories = file.space === 'MUSIC' ? musicCategories(file) : file.categories;
  const byId = new Map(file.items.map((i) => [i.id, i] as const));
  const genres = file.genres?.length ? file.genres : [...new Set(file.items.flatMap((i) => i.genres))];
  return { space: file.space, categories, tabs: categories.map((c) => ({ id: c.id, label: c.title })), byId, genres };
}

export function pickCategory(m: SpaceModel, id: string | null | undefined): Category | undefined {
  return m.categories.find((c) => c.id === id) ?? m.categories[0];
}

export const resolveIds = (ids: string[], byId: Map<string, Item>): Item[] =>
  ids.map((id) => byId.get(id)).filter((i): i is Item => !!i);

const hasGenre = (i: Item, g: string) => {
  const l = g.toLowerCase();
  return i.genres.some((x) => x.toLowerCase() === l);
};

/** Hero slides for a category; a genre chip narrows them (unless that would empty the hero). */
export function heroItems(m: SpaceModel, c: Category | undefined, genre: string | null = null, max = 6): Item[] {
  if (!c) return [];
  let items = resolveIds(c.heroIds, m.byId);
  if (!items.length) items = resolveIds(c.rows.flatMap((r) => r.itemIds), m.byId);
  if (genre) {
    const filtered = items.filter((i) => hasGenre(i, genre));
    const pool = filtered.length ? filtered : resolveIds(c.rows.flatMap((r) => r.itemIds), m.byId).filter((i) => hasGenre(i, genre));
    if (pool.length) items = pool;
  }
  return [...new Map(items.map((i) => [i.id, i])).values()].slice(0, max);
}

/** Genres that actually occur in the category, in the space's canonical order. */
export function categoryGenres(m: SpaceModel, c: Category | undefined): string[] {
  if (!c) return [];
  const present = new Set<string>();
  for (const r of c.rows) for (const id of r.itemIds) for (const g of m.byId.get(id)?.genres ?? []) present.add(g);
  const ordered = m.genres.filter((g) => present.has(g));
  for (const g of present) if (!ordered.includes(g)) ordered.push(g);
  return ordered;
}

/** Rows of the category with items filtered by the genre chip; empty rows are dropped. */
export function filterRows(m: SpaceModel, c: Category | undefined, genre: string | null): ViewRow[] {
  if (!c) return [];
  const out: ViewRow[] = [];
  for (const row of c.rows) {
    let items = resolveIds(row.itemIds, m.byId);
    if (genre) items = items.filter((i) => hasGenre(i, genre));
    if (items.length) out.push({ row, items });
  }
  return out;
}

/** All items of the space with a genre (used when no row of the category has it). */
export function genreItems(m: SpaceModel, genre: string): Item[] {
  return [...m.byId.values()].filter((i) => i.space === m.space && hasGenre(i, genre)).sort((a, b) => b.rating - a.rating);
}
