// Comics reader pure logic: default reading mode per kind, tap zones, key steps, preload window,
// chapter filtering/sorting, resilient page fallback, and per-chapter read progress (per profile).
import type { Chapter, Item } from '../data/types';
import { readJSON, writeJSON, type SyncKV } from '../data/kv';

export type ReadMode = 'rtl' | 'ltr' | 'vertical';
export type ComicKind = 'manga' | 'manhwa' | 'manhua' | 'comic';
export type Tier = 'LOW' | 'MID' | 'HIGH';

export const COMIC_KIND_LABEL: Record<ComicKind, string> = {
  manga: 'Manga',
  manhwa: 'Manhwa',
  manhua: 'Manhua',
  comic: 'Comic'
};

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

export const MODE_LABEL: Record<ReadMode, string> = {
  rtl: 'Paged · right to left',
  ltr: 'Paged · left to right',
  vertical: 'Vertical webtoon'
};

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

/** Arrow/page keys → page step (+1 next / −1 prev / 0 ignore) in paged mode. */
export function keyStep(key: string, mode: ReadMode): -1 | 0 | 1 {
  if (mode === 'vertical') return 0;
  if (key === 'PageDown' || key === ' ') return 1;
  if (key === 'PageUp') return -1;
  if (key === 'ArrowLeft') return mode === 'rtl' ? 1 : -1;
  if (key === 'ArrowRight') return mode === 'rtl' ? -1 : 1;
  return 0;
}

/** Vertical webtoon keyboard scrolling (+1 down, −1 up, 0 ignore). */
export function verticalScrollStep(key: string, shiftKey = false): -1 | 0 | 1 {
  if (key === 'ArrowDown' || key === 'PageDown' || key === 'j') return 1;
  if (key === 'ArrowUp' || key === 'PageUp' || key === 'k') return -1;
  if (key === ' ') return shiftKey ? -1 : 1;
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

export interface ChapterFilterOptions {
  order?: 'asc' | 'desc';
  query?: string;
  unreadOnly?: boolean;
  isRead?: (chapterId: string) => boolean;
}

/** Filter and sort chapters for the comic details screen. */
export function filterChapters(chapters: readonly Chapter[], opts: ChapterFilterOptions = {}): Chapter[] {
  const q = (opts.query ?? '').trim().toLowerCase();
  const filtered = chapters.filter((ch) => {
    if (opts.unreadOnly && opts.isRead?.(ch.id)) return false;
    if (!q) return true;
    if (String(ch.n) === q || String(ch.n).includes(q)) return true;
    return ch.title.toLowerCase().includes(q);
  });
  return opts.order === 'desc' ? [...filtered].reverse() : filtered;
}

function withCacheBust(url: string, tag: number): string {
  if (!/^https?:\/\//i.test(url)) return url;
  const sep = url.includes('?') ? '&' : '?';
  return `${url}${sep}retry=${tag}`;
}

/**
 * Resilient per-page URL selector:
 * attempt 0 → primary (or data-saver when preferred)
 * attempt 1 → data-saver backup (or full quality if data-saver was primary)
 * attempt 2 → cache-busted retry of the fallback URL
 * attempt 3+ → null (caller shows inline retry / triggers fresh chapter server reload)
 */
export function resolvePageSrc(
  primaryUrl: string,
  backupUrl: string | undefined,
  attempt: number,
  preferDataSaver = false
): string | null {
  if (!primaryUrl && !backupUrl) return null;
  const first = preferDataSaver && backupUrl ? backupUrl : primaryUrl || backupUrl!;
  const second = preferDataSaver
    ? primaryUrl && primaryUrl !== first
      ? primaryUrl
      : undefined
    : backupUrl && backupUrl !== first
      ? backupUrl
      : undefined;
  const candidates: string[] = [first];
  if (second) candidates.push(second);
  const retryBase = second ?? first;
  if (/^https?:\/\//i.test(retryBase)) candidates.push(withCacheBust(retryBase, 1));
  return candidates[attempt] ?? null;
}

// ---- progress ----
export interface ChapterProgress {
  page: number;
  total: number;
  at: number;
}
export interface RecentComicEntry {
  itemId: string;
  chapterId: string;
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

  private prefix() {
    return `cinetor.read.${this.profile}.`;
  }
  private key(itemId: string) {
    return `${this.prefix()}${itemId}`;
  }
  private load(itemId: string): ItemProgress {
    const v = readJSON<ItemProgress>(this.kv, this.key(itemId), { chapters: {} });
    return v && typeof v === 'object' && v.chapters ? v : { chapters: {} };
  }

  /** Save the page (0-based) reached in one chapter; other chapters are untouched. */
  save(itemId: string, chapterId: string, page: number, total: number): void {
    if (total <= 0) return;
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
    return c && c.total > 0 ? Math.min(1, (c.page + 1) / c.total) : 0;
  }

  isRead(itemId: string, chapterId: string): boolean {
    return this.fraction(itemId, chapterId) >= 1;
  }

  markRead(itemId: string, chapterId: string, total = 1): void {
    const t = Math.max(1, total);
    this.save(itemId, chapterId, t - 1, t);
  }

  clearItem(itemId: string): void {
    this.kv.del(this.key(itemId));
  }

  last(itemId: string): { chapterId: string; page: number } | undefined {
    const p = this.load(itemId);
    if (!p.last) return undefined;
    return { chapterId: p.last, page: p.chapters[p.last]?.page ?? 0 };
  }

  /** Recently read comics for this profile, newest first. */
  recentItems(): RecentComicEntry[] {
    const pref = this.prefix();
    const out: RecentComicEntry[] = [];
    for (const k of this.kv.keys()) {
      if (!k.startsWith(pref)) continue;
      const itemId = k.slice(pref.length);
      if (!itemId) continue;
      const p = this.load(itemId);
      if (!p.last) continue;
      const c = p.chapters[p.last];
      if (!c) continue;
      out.push({ itemId, chapterId: p.last, page: c.page, total: c.total, at: c.at });
    }
    return out.sort((a, b) => b.at - a.at);
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

  getDataSaver(): boolean {
    return this.kv.get(`cinetor.readsaver.${this.profile}`) === '1';
  }

  setDataSaver(on: boolean): void {
    const k = `cinetor.readsaver.${this.profile}`;
    if (on) this.kv.set(k, '1');
    else this.kv.del(k);
  }
}
