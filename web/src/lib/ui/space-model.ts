// Space screen view-model & poster/hero metadata helpers (pure, relative imports only).
import type { CatalogFile, Category, Item, Row, Source, Space } from '../data/types';

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

export type DubLanguage = 'Hindi' | 'Bengali' | 'Tamil' | 'Telugu' | 'English';

export interface BadgeCandidate {
  title: string;
  altTitle?: string;
  genres: string[];
  tags?: string[];
  dubLanguages?: string[];
  sources?: Pick<Source, 'height' | 'label'>[];
}

export interface GeneratedOrb {
  cx: number;
  cy: number;
  r: number;
  alpha: number;
}

export interface GeneratedArtSpec {
  light: string;
  dark: string;
  x1: number;
  x2: number;
  orbs: GeneratedOrb[];
  beamX1: number;
  beamX2: number;
}

export const MIN_WATCH_PROGRESS = 0.02;
export const MAX_WATCH_PROGRESS = 0.95;

/** Cinetor Beta brand placeholder palettes: crimson, ruby, wine, scarlet, and dark graphite neutrals. */
export const ART_PALETTES: readonly [string, string][] = [
  ['#7A0E1A', '#1A0609'],
  ['#8F1422', '#200709'],
  ['#4A0A12', '#12050A'],
  ['#A51A28', '#2A0A0F'],
  ['#3B3B40', '#111113'],
  ['#55555C', '#17171A'],
  ['#2A2A2F', '#0C0C0D'],
  ['#5C0A14', '#16060A']
];

const META_TAG_RE = /^\[?\s*(?:hindi|bengali|bangla|tamil|telugu|english|dual\s*audio|4k(?:\s*uhd)?|uhd|2160p|1080p|hd)(?:\s+dub(?:bed)?)?\s*\]?$/i;

export const isMetaBadgeTag = (tag: string): boolean => META_TAG_RE.test(tag.trim());

/** Parse dub language badge in Cinetor Beta priority order (Hindi → Bengali → Tamil → Telugu → English). */
export function detectDubBadge(item: BadgeCandidate): DubLanguage | null {
  const tagPool = [...(item.dubLanguages ?? []), ...(item.tags ?? []), ...item.genres].map((s) => s.trim());
  const titleText = `${item.title} ${item.altTitle ?? ''}`;

  if (/\bhindi\b/i.test(titleText) || tagPool.some((t) => /\bhindi\b/i.test(t))) return 'Hindi';
  if (/\b(?:bengali|bangla)\b/i.test(titleText) || tagPool.some((t) => /\b(?:bengali|bangla)\b/i.test(t))) return 'Bengali';
  if (/\btamil\b/i.test(titleText) || tagPool.some((t) => /\btamil\b/i.test(t))) return 'Tamil';
  if (/\btelugu\b/i.test(titleText) || tagPool.some((t) => /\btelugu\b/i.test(t))) return 'Telugu';
  if (
    tagPool.some((t) => /^\[?\s*english(?:\s+dub(?:bed)?)?\s*\]?$/i.test(t)) ||
    /\[\s*english(?:\s+dub(?:bed)?)?\s*\]|\(\s*english(?:\s+dub(?:bed)?)?\s*\)|\benglish\s+dub(?:bed)?\b|\bdual\s+audio\b/i.test(titleText)
  ) {
    return 'English';
  }
  return null;
}

/** Detect 4K UHD availability from title, tags/genres, or 2160p stream sources. */
export function has4kBadge(item: BadgeCandidate): boolean {
  const text = `${item.title} ${item.altTitle ?? ''}`;
  if (/\b(?:4k|2160p|uhd)\b/i.test(text)) return true;
  const tags = [...(item.tags ?? []), ...item.genres];
  if (tags.some((t) => /\b(?:4k|2160p|uhd)\b/i.test(t))) return true;
  if (item.sources?.some((s) => (s.height ?? 0) >= 2160 || /\b(?:4k|2160p|uhd)\b/i.test(s.label ?? ''))) return true;
  return false;
}

/** Clean genre labels for display (strips dub & resolution badge tags). */
export function displayGenres(item: Pick<BadgeCandidate, 'genres'>, max = 3): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const raw of item.genres) {
    const clean = raw.trim();
    if (!clean || isMetaBadgeTag(clean)) continue;
    const key = clean.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(clean);
    if (out.length >= max) break;
  }
  return out;
}

/** Format positive rating (e.g. 7.8); returns null for 0 / NaN / missing. */
export function formatRating(rating: number | null | undefined): string | null {
  if (typeof rating !== 'number' || !Number.isFinite(rating) || rating <= 0) return null;
  return rating.toFixed(1);
}

/** Clamp watch progress strictly to Cinetor Beta's 2%..95% window (hides peeks and finished items). */
export function visibleWatchProgress(progress: number | null | undefined): number | null {
  if (typeof progress !== 'number' || !Number.isFinite(progress)) return null;
  if (progress < MIN_WATCH_PROGRESS || progress > MAX_WATCH_PROGRESS) return null;
  return Math.round(progress * 1000) / 1000;
}

function hashSeed(seedKey: string): number {
  let h = 2166136261;
  const s = seedKey || 'cinetor';
  for (let i = 0; i < s.length; i++) {
    h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  }
  return h >>> 0;
}

/** Deterministic inline SVG geometry matching Cinetor Beta `Artwork.kt` GeneratedArt. */
export function generatedArtSpec(seedKey: string): GeneratedArtSpec {
  let a = hashSeed(seedKey) || 1;
  const next = () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const [light, dark] = ART_PALETTES[hashSeed(seedKey) % ART_PALETTES.length];
  const r2 = (v: number) => Math.round(v * 10) / 10;
  const x1 = r2(next() * 100);
  const x2 = r2(next() * 100);
  const orbs: GeneratedOrb[] = Array.from({ length: 3 }, () => ({
    cx: r2(next() * 100),
    cy: r2(next() * 80),
    r: r2(22 + next() * 36),
    alpha: Math.round((0.1 + next() * 0.15) * 1000) / 1000
  }));
  const beamX1 = r2(next() * 100);
  const beamX2 = r2(Math.max(0, Math.min(100, beamX1 + (next() - 0.5) * 90)));
  return { light, dark, x1, x2, orbs, beamX1, beamX2 };
}

/** Deduplicate items by `id`, preserving first-seen order. */
export function dedupeItems<T extends { id: string }>(items: readonly T[]): T[] {
  const seen = new Set<string>();
  const out: T[] = [];
  for (const item of items) {
    if (seen.has(item.id)) continue;
    seen.add(item.id);
    out.push(item);
  }
  return out;
}

/** Append incoming paged items without duplicating existing IDs. */
export function mergeUniqueItems<T extends { id: string }>(base: readonly T[], incoming: readonly T[]): T[] {
  if (!incoming.length) return [...base];
  const seen = new Set(base.map((i) => i.id));
  const out = [...base];
  for (const item of incoming) {
    if (seen.has(item.id)) continue;
    seen.add(item.id);
    out.push(item);
  }
  return out;
}

/** Whether a shelf header should expose the `See all` grid drawer trigger. */
export function canSeeAll(row: Pick<Row, 'style' | 'more'>, itemCount: number): boolean {
  if (row.style === 'TOP10' || row.style === 'CONTINUE' || row.style === 'CHANNEL') return false;
  return Boolean(row.more?.hasMore || itemCount >= 4);
}

/** Scrub any raw URLs or hostnames from user-facing error text. */
export function safeSpaceError(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed || /[a-z][a-z0-9+.-]*:\/\/|(?:[\w-]+\.)+[a-z]{2,12}(?:\/|:|$)/i.test(trimmed)) {
    return 'Check your connection and try again.';
  }
  return trimmed;
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
  const rawGenres = file.genres?.length ? file.genres : [...new Set(file.items.flatMap((i) => i.genres))];
  const genres = rawGenres.filter((g) => !isMetaBadgeTag(g));
  return { space: file.space, categories, tabs: categories.map((c) => ({ id: c.id, label: c.title })), byId, genres };
}

export function pickCategory(m: SpaceModel, id: string | null | undefined): Category | undefined {
  return m.categories.find((c) => c.id === id) ?? m.categories[0];
}

export const resolveIds = (ids: string[], byId: Map<string, Item>): Item[] =>
  dedupeItems(ids.map((id) => byId.get(id)).filter((i): i is Item => !!i));

const hasGenre = (i: Item, g: string) => {
  const l = g.toLowerCase();
  return i.genres.some((x) => x.toLowerCase() === l);
};

/** Hero slides for a category; a genre chip narrows them (unless that would empty the hero). */
export function heroItems(m: SpaceModel, c: Category | undefined, genre: string | null = null, max = 8): Item[] {
  if (!c) return [];
  let items = resolveIds(c.heroIds, m.byId);
  if (!items.length) items = resolveIds(c.rows.flatMap((r) => r.itemIds), m.byId);
  if (genre) {
    const filtered = items.filter((i) => hasGenre(i, genre));
    const pool = filtered.length ? filtered : resolveIds(c.rows.flatMap((r) => r.itemIds), m.byId).filter((i) => hasGenre(i, genre));
    if (pool.length) items = pool;
  }
  return dedupeItems(items).slice(0, max);
}

/** Genres that actually occur in the category, in the space's canonical order. */
export function categoryGenres(m: SpaceModel, c: Category | undefined): string[] {
  if (!c) return [];
  const present = new Set<string>();
  for (const r of c.rows) {
    for (const id of r.itemIds) {
      for (const g of m.byId.get(id)?.genres ?? []) {
        if (!isMetaBadgeTag(g)) present.add(g);
      }
    }
  }
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
