// Comics catalogue: MangaDex public API for metadata/chapters, gateway `manga?action=c2|chapter`
// (with direct MangaDex at-home + data-saver fallback) for chapter pages, and Tauri `md_get`
// CORS-free bridge on Windows/Linux Desktop. Pure TS (injected transport) so Vitest drives it.
//
// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer, scrape
// or hack anything without Mythbrix's explicit permission.
import { fallbackColor } from '../data/color';
import type { CatalogFile, Category, Chapter, Item, Row } from '../data/types';
import { isTauriEnv } from '../desktop/env';

export const MD_API = 'https://api.mangadex.org';
export const MD_UPLOADS = 'https://uploads.mangadex.org';
/** Non-browser UA for the direct fallback (browsers drop it; native transports send it). */
export const MD_UA = 'CinetorApp/1.0';

export type ComicCatId = 'co-manga' | 'co-manhwa' | 'co-manhua';

export interface ComicCat {
  id: ComicCatId;
  title: string;
  /** MangaDex `originalLanguage[]` values. */
  langs: string[];
}

export const COMIC_CATS: ComicCat[] = [
  { id: 'co-manga', title: 'Manga', langs: ['ja'] },
  { id: 'co-manhwa', title: 'Manhwa', langs: ['ko'] },
  { id: 'co-manhua', title: 'Manhua', langs: ['zh', 'zh-hk'] }
];

/** Comics ids keep the mock `co-` prefix so spaceForId() still resolves COMICS. */
export const MD_PREFIX = 'co-md:';
export const mdItemId = (uuid: string): string => `${MD_PREFIX}${uuid}`;
export const mdUuidOf = (id: string): string | null => (id.startsWith(MD_PREFIX) ? id.slice(MD_PREFIX.length) : null);
export const isMangadexId = (id: string): boolean => id.startsWith(MD_PREFIX);
/** Chapter id in the UI (`ch:<uuid>`); the uuid is the MangaDex chapter id. */
export const mdChapterId = (uuid: string): string => `ch:${uuid}`;
export const mdChapterUuid = (id: string): string | null => (id.startsWith('ch:') ? id.slice(3) : null);

// ------------------------------------------------------------------------------------ wire types

interface MdLocalized {
  [lang: string]: string;
}
export interface MdRel {
  id?: string;
  type?: string;
  attributes?: { fileName?: string; name?: string };
}
export interface MdManga {
  id?: string;
  attributes?: {
    title?: MdLocalized;
    altTitles?: MdLocalized[];
    description?: MdLocalized;
    year?: number | null;
    status?: string;
    contentRating?: string;
    originalLanguage?: string;
    tags?: { attributes?: { name?: MdLocalized; group?: string } }[];
  };
  relationships?: MdRel[];
}
export interface MdList {
  data?: MdManga[];
  total?: number;
  limit?: number;
  offset?: number;
}
export interface MdChapter {
  id?: string;
  attributes?: {
    chapter?: string | null;
    title?: string | null;
    pages?: number;
    translatedLanguage?: string;
    externalUrl?: string | null;
    volume?: string | null;
  };
}
export interface MdFeed {
  data?: MdChapter[];
  total?: number;
}
export interface MdAtHome {
  baseUrl?: string;
  chapter?: { hash?: string; data?: string[]; dataSaver?: string[] };
}
export interface MdStats {
  statistics?: Record<string, { rating?: { average?: number | null; bayesian?: number | null }; follows?: number }>;
}
export interface ChapterPageSet {
  primary: string[];
  backup: string[];
}

// ------------------------------------------------------------------------------------ urls

const localized = (v: MdLocalized | undefined, langs = ['en', 'ja-ro', 'ja', 'ko-ro', 'ko', 'zh-ro', 'zh']): string => {
  if (!v) return '';
  for (const l of langs) if (v[l]) return v[l];
  const first = Object.values(v).find((x) => typeof x === 'string' && x.trim());
  return first ?? '';
};

export interface MdListQuery {
  cat?: ComicCat;
  limit?: number;
  offset?: number;
  title?: string;
  order?: 'followedCount' | 'latestUploadedChapter' | 'rating' | 'createdAt' | 'year' | 'relevance';
}

export function mdListUrl(q: MdListQuery = {}): string {
  const u = new URL(`${MD_API}/manga`);
  u.searchParams.set('limit', String(q.limit ?? 24));
  if (q.offset) u.searchParams.set('offset', String(q.offset));
  if (q.title) u.searchParams.set('title', q.title);
  const order = q.order ?? (q.title ? 'relevance' : 'followedCount');
  u.searchParams.set(`order[${order}]`, 'desc');
  u.searchParams.append('includes[]', 'cover_art');
  u.searchParams.append('includes[]', 'author');
  for (const r of ['safe', 'suggestive']) u.searchParams.append('contentRating[]', r);
  u.searchParams.append('availableTranslatedLanguage[]', 'en');
  for (const l of q.cat?.langs ?? []) u.searchParams.append('originalLanguage[]', l);
  u.searchParams.append('hasAvailableChapters', 'true');
  return u.toString();
}

export const mdDetailUrl = (uuid: string): string =>
  `${MD_API}/manga/${encodeURIComponent(uuid)}?includes[]=cover_art&includes[]=author&includes[]=artist`;

export function mdFeedUrl(uuid: string, offset = 0): string {
  const u = new URL(`${MD_API}/manga/${encodeURIComponent(uuid)}/feed`);
  u.searchParams.append('translatedLanguage[]', 'en');
  u.searchParams.set('order[chapter]', 'asc');
  u.searchParams.set('limit', '500');
  if (offset) u.searchParams.set('offset', String(offset));
  u.searchParams.set('includeExternalUrl', '0');
  for (const r of ['safe', 'suggestive']) u.searchParams.append('contentRating[]', r);
  return u.toString();
}

export const mdStatsUrl = (ids: string[]): string =>
  `${MD_API}/statistics/manga?${ids.map((i) => `manga[]=${encodeURIComponent(i)}`).join('&')}`;

/** Cover art (512px) — plain <img>, no CORS needed. */
export function mdCoverUrl(uuid: string, fileName: string, size: 256 | 512 | 0 = 512): string {
  const f = size ? `${fileName}.${size}.jpg` : fileName;
  return `${MD_UPLOADS}/covers/${uuid}/${f}`;
}

/** Gateway chapter-page-server proxy (`action=c2` primary, `action=chapter` fallback). */
export const mdGatewayPagesUrl = (gatewayBase: string, chapterUuid: string, action: 'c2' | 'chapter' = 'c2'): string =>
  `${gatewayBase.replace(/\/+$/, '')}/api/public/v1/manga?action=${action}&id=${encodeURIComponent(chapterUuid)}`;

/** Direct MangaDex at-home fallback (needs a non-browser UA). */
export const mdAtHomeUrl = (chapterUuid: string): string => `${MD_API}/at-home/server/${encodeURIComponent(chapterUuid)}`;

/** at-home payload → ordered page image URLs ([] when the chapter has no pages). */
export function mdPageUrls(r: MdAtHome | null | undefined, dataSaver = false): string[] {
  const base = (r?.baseUrl ?? '').replace(/\/+$/, '');
  const hash = r?.chapter?.hash;
  const files = (dataSaver ? r?.chapter?.dataSaver : r?.chapter?.data) ?? [];
  if (!base || !hash || !files.length) return [];
  const dir = dataSaver ? 'data-saver' : 'data';
  return files.filter((f): f is string => typeof f === 'string' && f.length > 0).map((f) => `${base}/${dir}/${hash}/${f}`);
}

/** Parse either MangaDex at-home JSON or direct gateway `{ pages: [...] }` JSON into primary + backup URLs. */
export function mdPageSet(raw: unknown): ChapterPageSet {
  if (!raw || typeof raw !== 'object') return { primary: [], backup: [] };
  const obj = raw as Record<string, unknown>;
  const inner = obj.data && typeof obj.data === 'object' && !Array.isArray(obj.data) ? (obj.data as Record<string, unknown>) : obj;
  const atHome = inner as MdAtHome;
  const full = mdPageUrls(atHome, false);
  const saver = mdPageUrls(atHome, true);
  if (full.length || saver.length) {
    return {
      primary: full.length ? full : saver,
      backup: saver.length ? saver : full
    };
  }
  const rawPages = Array.isArray(inner.pages) ? inner.pages : Array.isArray(inner.images) ? inner.images : [];
  const direct = rawPages.filter((p): p is string => typeof p === 'string' && p.trim().length > 0);
  const rawBackup = Array.isArray(inner.dataSaver)
    ? inner.dataSaver
    : Array.isArray(inner.backupPages)
      ? inner.backupPages
      : [];
  const directBackup = rawBackup.filter((p): p is string => typeof p === 'string' && p.trim().length > 0);
  return {
    primary: direct,
    backup: directBackup.length ? directBackup : direct
  };
}

// ------------------------------------------------------------------------------------ mapping

export const catForLang = (lang: string | undefined): ComicCat =>
  COMIC_CATS.find((c) => c.langs.includes((lang ?? '').toLowerCase())) ?? COMIC_CATS[0];

const stripMd = (s: string): string =>
  s
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/[*_>#`]/g, '')
    .replace(/\r/g, '')
    .replace(/\n{2,}/g, '\n')
    .trim();

/** MangaDex manga → Item (COMIC). `pages` (chapters) are attached by the source. */
export function mapMdManga(m: MdManga, cat?: ComicCat, rating?: number): Item | null {
  if (!m.id || !m.attributes) return null;
  const a = m.attributes;
  const title = localized(a.title) || localized(a.altTitles?.[0]);
  if (!title) return null;
  const cover = (m.relationships ?? []).find((r) => r.type === 'cover_art')?.attributes?.fileName;
  const author = (m.relationships ?? []).find((r) => r.type === 'author')?.attributes?.name;
  const c = cat ?? catForLang(a.originalLanguage);
  const id = mdItemId(m.id);
  const poster = cover ? mdCoverUrl(m.id, cover) : '';
  if (!poster) return null;
  const vertical = c.id !== 'co-manga';
  const genres = (a.tags ?? [])
    .filter((t) => t.attributes?.group === 'genre' || t.attributes?.group === 'theme')
    .map((t) => localized(t.attributes?.name))
    .filter(Boolean)
    .slice(0, 6);
  return {
    id,
    space: 'COMICS',
    categoryId: c.id,
    type: 'COMIC',
    title,
    altTitle: localized(a.altTitles?.[0]) || undefined,
    year: a.year ?? 0,
    rating: Math.round((rating ?? 0) * 10) / 10,
    maturity: a.contentRating === 'suggestive' ? '16+' : 'NR',
    genres,
    synopsis: stripMd(localized(a.description)),
    poster,
    backdrop: poster,
    dominantColor: fallbackColor(id),
    accentColor: '#E11D2E',
    artist: author || undefined,
    format: vertical ? 'LONGSTRIP' : 'PAGED',
    readingDirection: vertical ? 'TTB' : 'RTL',
    pages: []
  };
}

/** Feed → chapters (pages resolved lazily; `pageCount` from metadata, duplicates dropped, sorted numerically). */
export function mapMdChapters(feed: MdFeed | null | undefined): Chapter[] {
  const rows = (feed?.data ?? [])
    .filter((c) => c.id && !c.attributes?.externalUrl && (c.attributes?.pages ?? 0) > 0)
    .map((c, idx) => {
      const rawNum = (c.attributes?.chapter ?? '').trim();
      const numVal = rawNum ? Number(rawNum) : Number.NaN;
      return { c, idx, rawNum, numVal };
    })
    .sort((a, b) => {
      const aFin = Number.isFinite(a.numVal);
      const bFin = Number.isFinite(b.numVal);
      if (aFin && bFin && a.numVal !== b.numVal) return a.numVal - b.numVal;
      if (aFin !== bFin) return aFin ? -1 : 1;
      return a.idx - b.idx;
    });
  const seen = new Set<string>();
  const out: Chapter[] = [];
  for (const { c, rawNum } of rows) {
    const key = rawNum || c.id!;
    if (seen.has(key)) continue;
    seen.add(key);
    const num = rawNum || String(out.length + 1);
    const name = (c.attributes?.title ?? '').trim();
    const baseTitle = rawNum ? (name ? `Ch. ${num} · ${name}` : `Chapter ${num}`) : name ? `Oneshot · ${name}` : 'Oneshot';
    out.push({
      id: mdChapterId(c.id!),
      n: out.length + 1,
      title: baseTitle,
      pages: [],
      pageCount: c.attributes?.pages ?? 0
    });
  }
  return out;
}

export function mdRatings(s: MdStats | null | undefined): Record<string, number> {
  const out: Record<string, number> = {};
  for (const [id, v] of Object.entries(s?.statistics ?? {})) {
    const r = v?.rating?.bayesian ?? v?.rating?.average;
    if (typeof r === 'number' && r > 0) out[id] = r;
  }
  return out;
}

// ------------------------------------------------------------------------------------ source

export type JsonFetch = (url: string, init?: { signal?: AbortSignal; headers?: Record<string, string> }) => Promise<unknown>;

export interface ComicsSourceOptions {
  /** Browser-safe transport (gateway proxy; MangaDex direct only where CORS allows it). */
  web: JsonFetch;
  /** Desktop/native transport that can set a non-browser UA (undefined on web). */
  native?: JsonFetch | null;
  /** Gateway base URL (page-server proxy). '' = gateway off. */
  gatewayBase: () => string;
  ttlMs?: number;
  now?: () => number;
}

interface Cached {
  ts: number;
  v: unknown;
}

const tauriBridgeFetch: JsonFetch = async (url, init) => {
  const { invoke } = await import('../desktop/tauri');
  const text = await invoke<string>('md_get', { url, ua: init?.headers?.['User-Agent'] ?? MD_UA });
  return JSON.parse(text);
};

/** MangaDex-backed COMICS catalogue. Never throws for a single missing chapter. */
export class ComicsSource {
  private mem = new Map<string, Cached>();
  private inflight = new Map<string, Promise<unknown>>();
  private seen = new Map<string, Item>();
  private pageSets = new Map<string, ChapterPageSet>();
  private fileP: { ts: number; p: Promise<CatalogFile> } | null = null;
  calls = 0;

  constructor(private o: ComicsSourceOptions) {}

  private get ttl(): number {
    return this.o.ttlMs ?? 10 * 60_000;
  }
  private get clock(): number {
    return (this.o.now ?? Date.now)();
  }
  private get nativeTransport(): JsonFetch | null {
    if (this.o.native) return this.o.native;
    return isTauriEnv() ? tauriBridgeFetch : null;
  }

  /** Metadata transport: native first (Tauri `md_get` — no CORS, real UA), then browser fetch. */
  private async json<T>(url: string, signal?: AbortSignal, fresh = false): Promise<T> {
    if (!fresh) {
      const hit = this.mem.get(url);
      if (hit && this.clock - hit.ts < this.ttl) return hit.v as T;
      const running = this.inflight.get(url);
      if (running) return running as Promise<T>;
    }
    const p = (async () => {
      this.calls++;
      const headers = { accept: 'application/json', 'User-Agent': MD_UA };
      let err: unknown;
      for (const t of [this.nativeTransport, this.o.web]) {
        if (!t) continue;
        try {
          const v = await t(url, { signal, headers });
          this.mem.set(url, { ts: this.clock, v });
          return v;
        } catch (e) {
          err = e;
        }
      }
      throw err instanceof Error ? err : new Error('Comics source unavailable');
    })();
    if (!fresh) {
      this.inflight.set(url, p);
      p.finally(() => this.inflight.delete(url)).catch(() => undefined);
    }
    return p as Promise<T>;
  }

  cached(id: string): Item | undefined {
    return this.seen.get(id);
  }

  file(): Promise<CatalogFile> {
    if (this.fileP && this.clock - this.fileP.ts < this.ttl) return this.fileP.p;
    const p = this.build();
    p.catch(() => (this.fileP = null));
    this.fileP = { ts: this.clock, p };
    return p;
  }

  private mapList(list: MdList | null, cat: ComicCat | undefined, ratings: Record<string, number>): Item[] {
    const out: Item[] = [];
    for (const m of list?.data ?? []) {
      if (!m.id) continue;
      const it = mapMdManga(m, cat, ratings[m.id]);
      if (it && !out.some((x) => x.id === it.id)) out.push(it);
    }
    return out;
  }

  private async fetchRatings(ids: string[], signal?: AbortSignal): Promise<Record<string, number>> {
    const uniq = [...new Set(ids)].slice(0, 100);
    if (!uniq.length) return {};
    const stats = await this.json<MdStats>(mdStatsUrl(uniq), signal).catch(() => null);
    return mdRatings(stats);
  }

  private async build(): Promise<CatalogFile> {
    const per = await Promise.all(
      COMIC_CATS.map(async (cat) => {
        const [pop, top, latest, newest] = await Promise.all([
          this.json<MdList>(mdListUrl({ cat, limit: 24, order: 'followedCount' })).catch(() => null),
          this.json<MdList>(mdListUrl({ cat, limit: 16, order: 'rating' })).catch(() => null),
          this.json<MdList>(mdListUrl({ cat, limit: 24, order: 'latestUploadedChapter' })).catch(() => null),
          this.json<MdList>(mdListUrl({ cat, limit: 20, order: 'createdAt' })).catch(() => null)
        ]);
        const allIds = [pop, top, latest, newest].flatMap((l) => (l?.data ?? []).map((m) => m.id).filter((id): id is string => !!id));
        const ratings = await this.fetchRatings(allIds);
        return {
          cat,
          popular: this.mapList(pop, cat, ratings),
          top: this.mapList(top, cat, ratings),
          latest: this.mapList(latest, cat, ratings),
          newest: this.mapList(newest, cat, ratings)
        };
      })
    );
    const items = new Map<string, Item>();
    const categories: Category[] = [];
    for (const { cat, popular, top, latest, newest } of per) {
      if (!popular.length && !top.length && !latest.length && !newest.length) continue;
      const rows: Row[] = [];
      const add = (list: Item[]) => {
        for (const i of list) if (!items.has(i.id)) items.set(i.id, i);
        return list.map((i) => i.id);
      };
      if (popular.length) rows.push({ id: `${cat.id}-popular`, title: `Popular ${cat.title}`, style: 'POSTER', itemIds: add(popular) });
      if (top.length) rows.push({ id: `${cat.id}-top`, title: `Top Rated ${cat.title}`, style: 'TOP10', itemIds: add(top.slice(0, 10)) });
      if (latest.length) rows.push({ id: `${cat.id}-latest`, title: 'Latest Updates', style: 'POSTER', itemIds: add(latest) });
      if (newest.length) rows.push({ id: `${cat.id}-new`, title: 'New Releases', style: 'POSTER', itemIds: add(newest) });
      categories.push({ id: cat.id, space: 'COMICS', title: cat.title, heroIds: rows[0].itemIds.slice(0, 5), rows });
    }
    if (!categories.length) throw new Error('Comics: empty catalogue');
    const all = [...items.values()];
    for (const i of all) if (!this.seen.has(i.id)) this.seen.set(i.id, i);
    return { space: 'COMICS', version: 1, genres: [...new Set(all.flatMap((i) => i.genres))].sort(), categories, items: all };
  }

  private async fetchAllChapters(uuid: string, signal?: AbortSignal): Promise<Chapter[]> {
    const collected: MdChapter[] = [];
    for (let offset = 0; offset < 1500; offset += 500) {
      const feed = await this.json<MdFeed>(mdFeedUrl(uuid, offset), signal).catch(() => null);
      const batch = feed?.data ?? [];
      collected.push(...batch);
      if (batch.length < 500 || offset + batch.length >= (feed?.total ?? 0)) break;
    }
    return mapMdChapters({ data: collected });
  }

  /** Full title: detail + chapter list (chapter pages stay lazy). */
  async item(id: string, signal?: AbortSignal): Promise<Item | undefined> {
    const uuid = mdUuidOf(id);
    if (!uuid) return undefined;
    const known = this.seen.get(id);
    const [detail, chapters] = await Promise.all([
      this.json<{ data?: MdManga }>(mdDetailUrl(uuid), signal).catch(() => null),
      this.fetchAllChapters(uuid, signal)
    ]);
    const base = detail?.data
      ? mapMdManga(detail.data, known ? COMIC_CATS.find((c) => c.id === known.categoryId) : undefined, known?.rating)
      : null;
    const it = base ?? known;
    if (!it) return undefined;
    const full: Item = { ...known, ...it, pages: chapters.length ? chapters : (known?.pages ?? []) };
    this.seen.set(id, full);
    return full;
  }

  /** Cached data-saver backup page URLs for a resolved chapter ([] if unavailable). */
  backupPages(chapterId: string): string[] {
    const uuid = mdChapterUuid(chapterId) ?? chapterId;
    return this.pageSets.get(uuid)?.backup ?? [];
  }

  /** Primary + data-saver backup page sets: gateway `c2` → gateway `chapter` → MangaDex `at-home`. */
  async chapterPageSet(chapterId: string, signal?: AbortSignal, opts?: { fresh?: boolean }): Promise<ChapterPageSet> {
    const uuid = mdChapterUuid(chapterId) ?? chapterId;
    if (!opts?.fresh) {
      const hit = this.pageSets.get(uuid);
      if (hit) return hit;
    }
    const gw = this.o.gatewayBase().replace(/\/+$/, '');
    const tries: (() => Promise<ChapterPageSet>)[] = [];
    if (gw) {
      for (const action of ['c2', 'chapter'] as const) {
        tries.push(async () => {
          const r = await this.o.web(mdGatewayPagesUrl(gw, uuid, action), { signal, headers: { accept: 'application/json' } });
          return mdPageSet(r);
        });
      }
    }
    tries.push(async () => mdPageSet(await this.json<MdAtHome>(mdAtHomeUrl(uuid), signal, opts?.fresh)));
    for (const t of tries) {
      try {
        const set = await t();
        if (set.primary.length) {
          this.pageSets.set(uuid, set);
          return set;
        }
      } catch {
        /* next strategy */
      }
    }
    return { primary: [], backup: [] };
  }

  /** Pages of a chapter: gateway proxy → direct at-home → [] ("chapter unavailable"). */
  async chapterPages(chapterId: string, signal?: AbortSignal, opts?: { fresh?: boolean; dataSaver?: boolean }): Promise<string[]> {
    const set = await this.chapterPageSet(chapterId, signal, { fresh: opts?.fresh });
    return opts?.dataSaver && set.backup.length ? set.backup : set.primary;
  }

  async search(query: string, signal?: AbortSignal, catId?: ComicCatId): Promise<Item[]> {
    const q = query.trim();
    if (!q) return [];
    const cat = catId ? COMIC_CATS.find((c) => c.id === catId) : undefined;
    const list = await this.json<MdList>(mdListUrl({ title: q, cat, limit: 24 }), signal).catch(() => null);
    const ids = (list?.data ?? []).map((m) => m.id).filter((id): id is string => !!id);
    const ratings = await this.fetchRatings(ids, signal);
    const out = this.mapList(list, cat, ratings);
    for (const it of out) if (!this.seen.has(it.id)) this.seen.set(it.id, it);
    return out;
  }
}
