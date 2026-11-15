// Comics reader pure logic: default reading mode per kind, tap zones, key steps, preload window,
// chapter navigation and per-chapter read progress (per profile).
import type { Chapter, Item } from '../data/types';
import { readJSON, writeJSON, type SyncKV } from '../data/kv';

export type ReadMode = 'rtl' | 'ltr' | 'vertical';
export type ComicKind = 'manga' | 'manhwa' | 'manhua' | 'comic';
export type Tier = 'LOW' | 'MID' | 'HIGH';

export function comicKind(item: Pick<Item, 'categoryId' | 'format' | 'readingDirection'>): ComicKind {
  const c = item.categoryId;
  if (c === 'co-manga') return 'manga';
  if (c === 'co-manhwa') return 'manhwa';
  if (c === 'co-manhua') return 'manhua';
  if (item.readingDirection === 'RTL') return 'manga';
  if (item.format === 'LONGSTRIP') return 'manhwa';
  return 'comic';
}

/** Manga → paged right-to-left; Manhwa / Manhua → vertical continuous; others by metadata. */
export function defaultMode(item: Pick<Item, 'categoryId' | 'format' | 'readingDirection'>): ReadMode {
  const k = comicKind(item);
  if (k === 'manga') return 'rtl';
  if (k === 'manhwa' || k === 'manhua') return 'vertical';
  if (item.format === 'LONGSTRIP' || item.readingDirection === 'TTB') return 'vertical';
  return item.readingDirection === 'RTL' ? 'rtl' : 'ltr';
}

export const effectiveMode = (item: Pick<Item, 'categoryId' | 'format' | 'readingDirection'>, override: ReadMode | null | undefined): ReadMode =>
  override ?? defaultMode(item);

export const MODE_LABEL: Record<ReadMode, string> = { rtl: 'Paged · right to left', ltr: 'Paged · left to right', vertical: 'Vertical scroll' };

/** Pages to preload after `page` (0-based): next 3, LOW tier only 1. */
export function preloadRange(page: number, total: number, tier: Tier): number[] {
  const n = tier === 'LOW' ? 1 : 3;
  const out: number[] = [];
  for (let i = page + 1; i <= page + n && i < total; i++) out.push(i);
  return out;
}

export type Zone = 'prev' | 'next' | 'menu';

/** Tap zones: outer 30 % turn pages (mirrored for RTL), centre toggles the chrome. */
export function tapZone(x: number, width: number, mode: ReadMode): Zone {
  if (width <= 0) return 'menu';
  const r = x / width;
  if (r > 0.3 && r < 0.7) return 'menu';
  const left = r <= 0.3;
  if (mode === 'rtl') return left ? 'next' : 'prev';
  return left ? 'prev' : 'next';
}

/** Arrow keys → page step (+1 next / −1 prev / 0 ignore). */
export function keyStep(key: string, mode: ReadMode): -1 | 0 | 1 {
  if (mode === 'vertical') return 0;
  if (key === 'PageDown' || key === ' ') return 1;
  if (key === 'PageUp') return -1;
  if (key === 'ArrowLeft') return mode === 'rtl' ? 1 : -1;
  if (key === 'ArrowRight') return mode === 'rtl' ? -1 : 1;
  return 0;
}

export function chapterIndex(chapters: readonly Chapter[], id: string | null | undefined): number {
  const i = chapters.findIndex((c) => c.id === id);
  return i < 0 ? 0 : i;
}

export const nextChapter = (chapters: readonly Chapter[], id: string): Chapter | undefined => chapters[chapters.findIndex((c) => c.id === id) + 1];
export const prevChapter = (chapters: readonly Chapter[], id: string): Chapter | undefined => {
  const i = chapters.findIndex((c) => c.id === id);
  return i > 0 ? chapters[i - 1] : undefined;
};

// ---- progress ----
export interface ChapterProgress {
  page: number;
  total: number;
  at: number;
}
interface ItemProgress {
  chapters: Record<string, ChapterProgress>;
  last?: string;
}

export class ReadProgress {
  constructor(
    private kv: SyncKV,
    private profile: string,
    private now: () => number = Date.now
  ) {}

  private key(itemId: string) {
    return `cinetor.read.${this.profile}.${itemId}`;
  }
  private load(itemId: string): ItemProgress {
    const v = readJSON<ItemProgress>(this.kv, this.key(itemId), { chapters: {} });
    return v && typeof v === 'object' && v.chapters ? v : { chapters: {} };
  }

  /** Save the page (0-based) reached in one chapter; other chapters are untouched. */
  save(itemId: string, chapterId: string, page: number, total: number): void {
    const p = this.load(itemId);
    p.chapters[chapterId] = { page: Math.max(0, Math.min(page, total - 1)), total, at: this.now() };
    p.last = chapterId;
    writeJSON(this.kv, this.key(itemId), p);
  }

  get(itemId: string, chapterId: string): ChapterProgress | undefined {
    return this.load(itemId).chapters[chapterId];
  }

  /** 0..1 read fraction of a chapter. */
  fraction(itemId: string, chapterId: string): number {
    const c = this.get(itemId, chapterId);
    return c && c.total > 0 ? (c.page + 1) / c.total : 0;
  }

  last(itemId: string): { chapterId: string; page: number } | undefined {
    const p = this.load(itemId);
    if (!p.last) return undefined;
    return { chapterId: p.last, page: p.chapters[p.last]?.page ?? 0 };
  }

  getMode(itemId: string): ReadMode | null {
    const v = this.kv.get(`cinetor.readmode.${this.profile}.${itemId}`);
    return v === 'rtl' || v === 'ltr' || v === 'vertical' ? v : null;
  }

  setMode(itemId: string, mode: ReadMode | null): void {
    const k = `cinetor.readmode.${this.profile}.${itemId}`;
    if (mode) this.kv.set(k, mode);
    else this.kv.del(k);
  }
}
