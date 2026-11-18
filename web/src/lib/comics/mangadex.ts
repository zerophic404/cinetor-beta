// Comics catalogue & reader client:
// • Primary (Web & Desktop when gateway is active): 100% signed gateway endpoints
//   - Browse & live search via `/api/public/v1/manga/gql` (AniList GraphQL -> s4.anilist.co covers, titles, synopses, genres, scores)
//   - Chapters via `/api/public/v1/manga/c1` with fallback/merge via `/api/public/v1/manga/dex/...`
//   - Chapter pages via `/api/public/v1/manga/c2` & `/manga/dex/at-home/server/...`, wrapping hotlink-protected external images via `/api/public/v1/manga/img?u=...&r=...`
// • Fallback (when gatewayBase is empty): MangaDex public API + Tauri `md_get` bridge.
//
// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer, scrape
// or hack anything without Mythbrix's explicit permission.
import { fallbackColor } from '../data/color';
import { gatewayBase as defaultGatewayBase, shouldSignGatewayUrl, signedGatewayFetch } from '../data/gateway';
import { idbCache, type PersistentCache } from '../data/idb';
import type { CatalogFile, Category, Chapter, Item, Row } from '../data/types';
import { isTauriEnv } from '../desktop/env';

export const MD_API = 'https://api.mangadex.org';
export const MD_UPLOADS = 'https://uploads.mangadex.org';
export const MD_UA = 'CinetorApp/1.0';

export type ComicCatId = 'co-manga' | 'co-manhwa' | 'co-manhua';

export interface ComicCat {
  id: ComicCatId;
  title: string;
  /** MangaDex `originalLanguage[]` values. */
  langs: string[];
  /** AniList `countryOfOrigin` code. */
  country: 'JP' | 'KR' | 'CN';
}

export const COMIC_CATS: ComicCat[] = [
  { id: 'co-manga', title: 'Manga', langs: ['ja'], country: 'JP' },
  { id: 'co-manhwa', title: 'Manhwa', langs: ['ko'], country: 'KR' },
  { id: 'co-manhua', title: 'Manhua', langs: ['zh', 'zh-hk'], country: 'CN' }
];

/** Comics ids keep the `co-` prefix so `spaceForId()` resolves `COMICS`. */
export const AL_PREFIX = 'co-al:';
export const MD_PREFIX = 'co-md:';
export const READER_CH_PREFIX = 'ck-';

export const alItemId = (anilistId: number | string): string => `${AL_PREFIX}${anilistId}`;
export const mdItemId = (uuid: string): string => `${MD_PREFIX}${uuid}`;

export function alIdOf(id: string): number | null {
  const raw = id.startsWith(AL_PREFIX) ? id.slice(AL_PREFIX.length) : id.startsWith('mg:') ? id.slice(3) : '';
  if (!raw || !/^\d+$/.test(raw)) return null;
  const n = Number(raw);
  return Number.isSafeInteger(n) && n > 0 ? n : null;
}

export const mdUuidOf = (id: string): string | null => (id.startsWith(MD_PREFIX) ? id.slice(MD_PREFIX.length) : null);

/** True for all remote comic item IDs (`co-al:*` AniList and `co-md:*` MangaDex) handled by `ComicsSource`. */
export const isMangadexId = (id: string): boolean => id.startsWith(AL_PREFIX) || id.startsWith(MD_PREFIX) || id.startsWith('mg:');

/** Chapter id in the UI (`ch:<id>`); reader chapters carry `ch:ck-<readerId>`. */
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
    links?: Record<string, string>;
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

export interface AnilistMedia {
  id?: number;
  countryOfOrigin?: string | null;
  status?: string | null;
  averageScore?: number | null;
  genres?: string[] | null;
  chapters?: number | null;
  synonyms?: string[] | null;
  bannerImage?: string | null;
  description?: string | null;
  startDate?: { year?: number | null } | null;
  title?: { romaji?: string | null; english?: string | null; native?: string | null } | null;
  coverImage?: { extraLarge?: string | null; large?: string | null } | null;
}

export interface C1ChapterRow {
  id?: string;
  n?: string;
  t?: string;
}

// ------------------------------------------------------------------------------------ AniList GraphQL queries & URLs

const ANILIST_FIELDS = `
  id countryOfOrigin status averageScore genres chapters synonyms bannerImage description(asHtml: false)
  startDate { year }
  title { romaji english native }
  coverImage { extraLarge large }
`;

export const ANILIST_LIST_QUERY = `
  query($page: Int, $perPage: Int, $sort: [MediaSort], $country: CountryCode, $genre: String, $search: String) {
    Page(page: $page, perPage: $perPage) {
      media(type: MANGA, isAdult: false, countryOfOrigin: $country, genre: $genre, search: $search, sort: $sort, format_not_in: [NOVEL, ONE_SHOT]) {
        ${ANILIST_FIELDS}
      }
    }
  }
`;

export const ANILIST_DETAIL_QUERY = `
  query($id: Int) {
    Media(id: $id, type: MANGA, isAdult: false) {
      ${ANILIST_FIELDS}
    }
  }
`;

export function gwAnilistUrl(gatewayBase: string, query: string, variables: Record<string, unknown> = {}): string {
  const base = gatewayBase.replace(/\/+$/, '');
  const body = JSON.stringify({ query, variables });
  return `${base}/api/public/v1/manga/gql?body=${encodeURIComponent(body)}`;
}

export function gwC1Url(gatewayBase: string, names: string[]): string {
  const base = gatewayBase.replace(/\/+$/, '');
  return `${base}/api/public/v1/manga/c1?names=${encodeURIComponent(JSON.stringify(names))}`;
}

export function gwC2Url(gatewayBase: string, readerChapterId: string): string {
  const base = gatewayBase.replace(/\/+$/, '');
  return `${base}/api/public/v1/manga/c2?id=${encodeURIComponent(readerChapterId)}`;
}

export function gwDexUrl(gatewayBase: string, dexPathWithQuery: string): string {
  const base = gatewayBase.replace(/\/+$/, '');
  const p = dexPathWithQuery.startsWith('/') ? dexPathWithQuery : `/${dexPathWithQuery}`;
  return `${base}/api/public/v1/manga/dex${p}`;
}

/** Proxy external hotlink-protected comic image URLs with optional `Referer` (`r=`). */
export function gwMangaImgUrl(gatewayBase: string, imageUrl: string, referer?: string): string {
  const base = gatewayBase.replace(/\/+$/, '');
  if (!base || !/^https?:\/\//i.test(imageUrl)) return imageUrl;
  const rPart = referer ? `&r=${encodeURIComponent(referer)}` : '';
  return `${base}/api/public/v1/manga/img?u=${encodeURIComponent(imageUrl)}${rPart}`;
}

export function cleanAnilistHtml(html: string | null | undefined): string {
  if (!html) return '';
  return html
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;|&#x27;|&rsquo;/g, "'")
    .replace(/&ldquo;|&rdquo;/g, '"')
    .replace(/&mdash;/g, '—')
    .replace(/&ndash;/g, '–')
    .replace(/&nbsp;/g, ' ')
    .replace(/\r/g, '')
    .replace(/\n{2,}/g, '\n')
    .trim();
}

export function catForCountry(country: string | null | undefined): ComicCat {
  const c = (country ?? '').toUpperCase();
  if (c === 'KR') return COMIC_CATS[1];
  if (c === 'CN' || c === 'TW') return COMIC_CATS[2];
  return COMIC_CATS[0];
}

export function mapAnilistMedia(media: AnilistMedia | null | undefined): { item: Item; altTitles: string[] } | null {
  const numId = media?.id;
  if (!numId || numId <= 0) return null;
  const t = media.title;
  const title = (t?.english || t?.romaji || t?.native || '').trim();
  if (!title) return null;
  const cover = (media.coverImage?.extraLarge || media.coverImage?.large || '').trim();
  if (!cover) return null;
  const cat = catForCountry(media.countryOfOrigin);
  const id = alItemId(numId);
  const vertical = cat.id !== 'co-manga';
  const score = typeof media.averageScore === 'number' && media.averageScore > 0 ? Math.round((media.averageScore / 10) * 10) / 10 : 0;
  const rawAlts = [t?.english, t?.romaji, t?.native, ...(media.synonyms ?? []).slice(0, 4)]
    .map((s) => (typeof s === 'string' ? s.trim() : ''))
    .filter(Boolean);
  const altTitles = [...new Set(rawAlts)];
  const primaryAlt = altTitles.find((a) => a.toLowerCase() !== title.toLowerCase());
  const statusClean = (media.status ?? '').toLowerCase().replace(/_/g, ' ');
  const genres = (media.genres ?? []).filter((g): g is string => typeof g === 'string' && g.trim().length > 0).slice(0, 6);

  return {
    item: {
      id,
      space: 'COMICS',
      categoryId: cat.id,
      type: 'COMIC',
      title,
      altTitle: primaryAlt,
      year: media.startDate?.year ?? 0,
      rating: score,
      maturity: statusClean ? statusClean.charAt(0).toUpperCase() + statusClean.slice(1) : 'NR',
      genres,
      synopsis: cleanAnilistHtml(media.description),
      poster: cover,
      backdrop: (media.bannerImage || cover).trim(),
      dominantColor: fallbackColor(id),
      accentColor: '#E11D2E',
      format: vertical ? 'LONGSTRIP' : 'PAGED',
      readingDirection: vertical ? 'TTB' : 'RTL',
      pages: []
    },
    altTitles
  };
}

export function mapC1Chapters(rows: readonly C1ChapterRow[] | null | undefined): Chapter[] {
  const valid = (rows ?? [])
    .filter((r): r is { id: string; n: string; t?: string } => !!r?.id && typeof r.n === 'string' && r.n.trim().length > 0)
    .map((r, idx) => {
      const rawNum = r.n.trim();
      return { r, idx, rawNum, numVal: Number(rawNum) };
    })
    .sort((a, b) => {
      const af = Number.isFinite(a.numVal);
      const bf = Number.isFinite(b.numVal);
      if (af && bf && a.numVal !== b.numVal) return a.numVal - b.numVal;
      if (af !== bf) return af ? -1 : 1;
      return a.idx - b.idx;
    });

  const seen = new Set<string>();
  const out: Chapter[] = [];
  for (const { r, rawNum } of valid) {
    if (seen.has(rawNum)) continue;
    seen.add(rawNum);
    const name = (r.t ?? '').trim();
    out.push({
      id: mdChapterId(`${READER_CH_PREFIX}${r.id}`),
      n: out.length + 1,
      title: name && name !== rawNum ? `Ch. ${rawNum} · ${name}` : `Chapter ${rawNum}`,
      pages: [],
      pageCount: 0
    });
  }
  return out;
}

// ------------------------------------------------------------------------------------ MangaDex URLs & mapping

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

export function mdCoverUrl(uuid: string, fileName: string, size: 256 | 512 | 0 = 512): string {
  const f = size ? `${fileName}.${size}.jpg` : fileName;
  return `${MD_UPLOADS}/covers/${uuid}/${f}`;
}

export const mdGatewayPagesUrl = (gatewayBase: string, chapterUuid: string, action: 'c2' | 'chapter' = 'c2'): string =>
  `${gatewayBase.replace(/\/+$/, '')}/api/public/v1/manga?action=${action}&id=${encodeURIComponent(chapterUuid)}`;

export const mdAtHomeUrl = (chapterUuid: string): string => `${MD_API}/at-home/server/${encodeURIComponent(chapterUuid)}`;

export function mdPageUrls(r: MdAtHome | null | undefined, dataSaver = false): string[] {
  const base = (r?.baseUrl ?? '').replace(/\/+$/, '');
  const hash = r?.chapter?.hash;
  const files = (dataSaver ? r?.chapter?.dataSaver : r?.chapter?.data) ?? [];
  if (!base || !hash || !files.length) return [];
  const dir = dataSaver ? 'data-saver' : 'data';
  return files.filter((f): f is string => typeof f === 'string' && f.length > 0).map((f) => `${base}/${dir}/${hash}/${f}`);
}

export function mdPageSet(raw: unknown, gatewayForImg = ''): ChapterPageSet {
  if (!raw || typeof raw !== 'object') return { primary: [], backup: [] };
  const obj = raw as Record<string, unknown>;
  const inner = obj.data && typeof obj.data === 'object' && !Array.isArray(obj.data) ? (obj.data as Record<string, unknown>) : obj;
  const atHome = inner as MdAtHome;
  const full = mdPageUrls(atHome, false);
  const saver = mdPageUrls(atHome, true);
  if (full.length || saver.length) {
    const basePrimary = full.length ? full : saver;
    const primary = gatewayForImg ? basePrimary.map((u) => gwMangaImgUrl(gatewayForImg, u, 'https://mangadex.org/')) : basePrimary;
    const backup = saver.length ? saver : basePrimary;
    return { primary, backup };
  }
  const rawPages = Array.isArray(inner.pages) ? inner.pages : Array.isArray(inner.images) ? inner.images : [];
  const direct = rawPages.filter((p): p is string => typeof p === 'string' && p.trim().length > 0);
  const ref = typeof inner.ref === 'string' && inner.ref.trim() ? inner.ref.trim() : undefined;
  const rawBackup = Array.isArray(inner.dataSaver)
    ? inner.dataSaver
    : Array.isArray(inner.backupPages)
      ? inner.backupPages
      : [];
  const directBackup = rawBackup.filter((p): p is string => typeof p === 'string' && p.trim().length > 0);
  if (gatewayForImg && direct.length) {
    return {
      primary: direct.map((u) => gwMangaImgUrl(gatewayForImg, u, ref)),
      backup: directBackup.length ? directBackup : direct
    };
  }
  return {
    primary: direct,
    backup: directBackup.length ? directBackup : direct
  };
}

export const catForLang = (lang: string | undefined): ComicCat =>
  COMIC_CATS.find((c) => c.langs.includes((lang ?? '').toLowerCase())) ?? COMIC_CATS[0];

const stripMd = (s: string): string =>
  s
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/[*_>#`]/g, '')
    .replace(/\r/g, '')
    .replace(/\n{2,}/g, '\n')
    .trim();

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

function titleScore(candidates: string[], target: string): number {
  const norm = (s: string) =>
    s
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, ' ')
      .trim();
  const y = new Set(norm(target).split(' ').filter(Boolean));
  if (!y.size) return 0;
  let best = 0;
  for (const cand of candidates) {
    const x = new Set(norm(cand).split(' ').filter(Boolean));
    if (!x.size) continue;
    let both = 0;
    for (const w of x) if (y.has(w)) both++;
    const jaccard = both / (x.size + y.size - both);
    if (jaccard > best) best = jaccard;
  }
  return best;
}

function extractChapterNum(title: string): number | null {
  const m = /(?:Ch\.|Chapter)\s*(\d+(?:\.\d+)?)/i.exec(title);
  return m ? Number(m[1]) : null;
}

// ------------------------------------------------------------------------------------ persistence & source

export type JsonFetch = (url: string, init?: { signal?: AbortSignal; headers?: Record<string, string> }) => Promise<unknown>;

export interface ComicsSourceOptions {
  /** Browser-safe transport (`signedGatewayFetch` in production). */
  web?: JsonFetch;
  /** Desktop/native transport for direct MangaDex fallback when gateway is disabled. */
  native?: JsonFetch | null;
  /** Gateway base URL (`''` = gateway off -> direct MangaDex fallback). */
  gatewayBase?: () => string;
  /** Optional persistent IndexedDB cache (defaults to `idbCache()`). */
  persist?: PersistentCache | null;
  ttlMs?: number;
  now?: () => number;
}

interface Cached {
  ts: number;
  v: unknown;
}

interface StoredComicRecord {
  item: Item;
  altTitles: string[];
}

const SESSION_KEY_PREFIX = 'cinetor.comic.v2.';
const IDB_KEY_PREFIX = 'comic.v2.';

function readSessionComic(id: string): StoredComicRecord | null {
  try {
    if (typeof sessionStorage === 'undefined') return null;
    const raw = sessionStorage.getItem(SESSION_KEY_PREFIX + id);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as StoredComicRecord | null;
    return parsed && parsed.item && typeof parsed.item.id === 'string' ? parsed : null;
  } catch {
    return null;
  }
}

function writeSessionComic(id: string, rec: StoredComicRecord): void {
  try {
    if (typeof sessionStorage === 'undefined') return;
    sessionStorage.setItem(SESSION_KEY_PREFIX + id, JSON.stringify(rec));
  } catch {
    /* quota / SSR */
  }
}

const tauriBridgeFetch: JsonFetch = async (url, init) => {
  const { invoke } = await import('../desktop/tauri');
  const text = await invoke<string>('md_get', { url, ua: init?.headers?.['User-Agent'] ?? MD_UA });
  return JSON.parse(text);
};

const defaultSignedWebFetch: JsonFetch = async (url, init) => {
  const base = defaultGatewayBase();
  const reqInit: RequestInit = {
    signal: init?.signal,
    headers: { ...(init?.headers ?? {}), accept: 'application/json' }
  };
  const res = shouldSignGatewayUrl(url, base) ? await signedGatewayFetch(url, reqInit, base) : await fetch(url, reqInit);
  if (!res.ok) throw new Error(`Comics HTTP ${res.status}`);
  return res.json();
};

/** Gateway AniList + Reader (`c1`/`c2`/`dex`/`img`) COMICS catalogue with sessionStorage + IndexedDB persistence. */
export class ComicsSource {
  private mem = new Map<string, Cached>();
  private inflight = new Map<string, Promise<unknown>>();
  private seen = new Map<string, Item>();
  private altTitles = new Map<string, string[]>();
  private dexIds = new Map<string, string>();
  private chapterOwner = new Map<string, { itemId: string; num: number | null; anilistId: number | null }>();
  private pageSets = new Map<string, ChapterPageSet>();
  private fileP: { ts: number; gw: string; p: Promise<CatalogFile> } | null = null;
  private persist: PersistentCache | null;
  calls = 0;

  constructor(private o: ComicsSourceOptions = {}) {
    this.persist = o.persist !== undefined ? o.persist : idbCache();
  }

  private get ttl(): number {
    return this.o.ttlMs ?? 10 * 60_000;
  }
  private get clock(): number {
    return (this.o.now ?? Date.now)();
  }
  private get gw(): string {
    return (this.o.gatewayBase ? this.o.gatewayBase() : defaultGatewayBase()).replace(/\/+$/, '');
  }
  private get webTransport(): JsonFetch {
    return this.o.web ?? defaultSignedWebFetch;
  }
  private get nativeTransport(): JsonFetch | null {
    if (this.o.native) return this.o.native;
    return isTauriEnv() ? tauriBridgeFetch : null;
  }

  private rememberComic(item: Item, alts: string[] = []): Item {
    const prev = this.seen.get(item.id);
    const merged: Item = {
      ...prev,
      ...item,
      pages: item.pages && item.pages.length > 0 ? item.pages : (prev?.pages ?? [])
    };
    const mergedAlts = [...new Set([merged.title, ...(merged.altTitle ? [merged.altTitle] : []), ...alts, ...(this.altTitles.get(merged.id) ?? [])])].filter(Boolean);
    this.seen.set(merged.id, merged);
    this.altTitles.set(merged.id, mergedAlts);
    const rec: StoredComicRecord = { item: merged, altTitles: mergedAlts };
    writeSessionComic(merged.id, rec);
    void this.persist?.set(IDB_KEY_PREFIX + merged.id, rec);
    return merged;
  }

  /** Transport: gateway URLs always go through signed `webTransport`; direct MangaDex fallback tries `native` first. */
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
      const isDirectDex = url.startsWith(MD_API);
      const transports = isDirectDex ? [this.nativeTransport, this.webTransport] : [this.webTransport];
      let err: unknown;
      for (const t of transports) {
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
    const normId = id.startsWith('mg:') ? alItemId(id.slice(3)) : id;
    const memHit = this.seen.get(normId);
    if (memHit) return memHit;
    const sessHit = readSessionComic(normId);
    if (sessHit) {
      this.seen.set(normId, sessHit.item);
      this.altTitles.set(normId, sessHit.altTitles);
      return sessHit.item;
    }
    return undefined;
  }

  private async loadPersisted(id: string): Promise<Item | undefined> {
    const syncHit = this.cached(id);
    if (syncHit) return syncHit;
    const normId = id.startsWith('mg:') ? alItemId(id.slice(3)) : id;
    const raw = (await this.persist?.get(IDB_KEY_PREFIX + normId).catch(() => undefined)) as StoredComicRecord | undefined;
    if (raw && raw.item && typeof raw.item.id === 'string') {
      this.seen.set(normId, raw.item);
      this.altTitles.set(normId, raw.altTitles ?? [raw.item.title]);
      writeSessionComic(normId, raw);
      return raw.item;
    }
    return undefined;
  }

  file(): Promise<CatalogFile> {
    const gw = this.gw;
    if (this.fileP && this.fileP.gw === gw && this.clock - this.fileP.ts < this.ttl) return this.fileP.p;
    const p = gw ? this.buildViaGateway(gw) : this.buildDirectMd();
    p.catch(() => (this.fileP = null));
    this.fileP = { ts: this.clock, gw, p };
    return p;
  }

  // ---- Gateway AniList Browse & Search -----------------------------------------------------

  private async browseAnilist(
    gw: string,
    vars: { page?: number; perPage?: number; sort?: string[]; country?: 'JP' | 'KR' | 'CN'; genre?: string; search?: string },
    signal?: AbortSignal
  ): Promise<Item[]> {
    const url = gwAnilistUrl(gw, ANILIST_LIST_QUERY, {
      page: vars.page ?? 1,
      perPage: vars.perPage ?? 24,
      sort: vars.sort ?? (vars.search ? ['SEARCH_MATCH'] : ['TRENDING_DESC']),
      ...(vars.country ? { country: vars.country } : {}),
      ...(vars.genre ? { genre: vars.genre } : {}),
      ...(vars.search ? { search: vars.search } : {})
    });
    const res = await this.json<{ data?: { Page?: { media?: AnilistMedia[] } } }>(url, signal);
    const list = res?.data?.Page?.media ?? [];
    const out: Item[] = [];
    for (const m of list) {
      const mapped = mapAnilistMedia(m);
      if (!mapped || out.some((x) => x.id === mapped.item.id)) continue;
      out.push(this.rememberComic(mapped.item, mapped.altTitles));
    }
    return out;
  }

  private async buildViaGateway(gw: string): Promise<CatalogFile> {
    const [
      trending,
      popManhwa,
      popManga,
      popManhua,
      topRated,
      trendManga,
      topManga,
      trendManhwa,
      topManhwa,
      trendManhua
    ] = await Promise.all([
      this.browseAnilist(gw, { sort: ['TRENDING_DESC'], perPage: 24 }).catch(() => [] as Item[]),
      this.browseAnilist(gw, { country: 'KR', sort: ['POPULARITY_DESC'], perPage: 24 }).catch(() => [] as Item[]),
      this.browseAnilist(gw, { country: 'JP', sort: ['POPULARITY_DESC'], perPage: 24 }).catch(() => [] as Item[]),
      this.browseAnilist(gw, { country: 'CN', sort: ['POPULARITY_DESC'], perPage: 24 }).catch(() => [] as Item[]),
      this.browseAnilist(gw, { sort: ['SCORE_DESC'], perPage: 24 }).catch(() => [] as Item[]),
      this.browseAnilist(gw, { country: 'JP', sort: ['TRENDING_DESC'], perPage: 24 }).catch(() => [] as Item[]),
      this.browseAnilist(gw, { country: 'JP', sort: ['SCORE_DESC'], perPage: 20 }).catch(() => [] as Item[]),
      this.browseAnilist(gw, { country: 'KR', sort: ['TRENDING_DESC'], perPage: 24 }).catch(() => [] as Item[]),
      this.browseAnilist(gw, { country: 'KR', sort: ['SCORE_DESC'], perPage: 20 }).catch(() => [] as Item[]),
      this.browseAnilist(gw, { country: 'CN', sort: ['TRENDING_DESC'], perPage: 24 }).catch(() => [] as Item[])
    ]);

    const items = new Map<string, Item>();
    const addIds = (list: Item[]) => {
      for (const it of list) if (!items.has(it.id)) items.set(it.id, it);
      return list.map((it) => it.id);
    };

    const categories: Category[] = [];

    const allRows: Row[] = [];
    if (trending.length) allRows.push({ id: 'co-trending', title: 'Trending', style: 'POSTER', itemIds: addIds(trending) });
    if (popManhwa.length) allRows.push({ id: 'co-pop-manhwa', title: 'Popular Manhwa', style: 'POSTER', itemIds: addIds(popManhwa) });
    if (popManga.length) allRows.push({ id: 'co-pop-manga', title: 'Popular Manga', style: 'POSTER', itemIds: addIds(popManga) });
    if (popManhua.length) allRows.push({ id: 'co-pop-manhua', title: 'Popular Manhua', style: 'POSTER', itemIds: addIds(popManhua) });
    if (topRated.length) allRows.push({ id: 'co-top-rated', title: 'Top Rated', style: 'POSTER', itemIds: addIds(topRated) });
    if (allRows.length) {
      categories.push({ id: 'co-all', space: 'COMICS', title: 'Featured', heroIds: allRows[0].itemIds.slice(0, 6), rows: allRows });
    }

    const perCat: { id: ComicCatId; title: string; shelves: [string, string, Item[]][] }[] = [
      {
        id: 'co-manga',
        title: 'Manga',
        shelves: [
          ['co-manga-trending', 'Trending Manga', trendManga],
          ['co-manga-popular', 'Popular Manga', popManga],
          ['co-manga-top', 'Top Rated Manga', topManga]
        ]
      },
      {
        id: 'co-manhwa',
        title: 'Manhwa',
        shelves: [
          ['co-manhwa-trending', 'Trending Manhwa', trendManhwa],
          ['co-manhwa-popular', 'Popular Manhwa', popManhwa],
          ['co-manhwa-top', 'Top Rated Manhwa', topManhwa]
        ]
      },
      {
        id: 'co-manhua',
        title: 'Manhua',
        shelves: [
          ['co-manhua-trending', 'Trending Manhua', trendManhua],
          ['co-manhua-popular', 'Popular Manhua', popManhua]
        ]
      }
    ];

    for (const c of perCat) {
      const rows: Row[] = [];
      for (const [rid, rtitle, list] of c.shelves) {
        if (list.length) rows.push({ id: rid, title: rtitle, style: 'POSTER', itemIds: addIds(list) });
      }
      if (rows.length) {
        categories.push({ id: c.id, space: 'COMICS', title: c.title, heroIds: rows[0].itemIds.slice(0, 5), rows });
      }
    }

    if (!categories.length) throw new Error('Comics: empty catalogue');
    const all = [...items.values()];
    return {
      space: 'COMICS',
      version: 2,
      genres: [...new Set(all.flatMap((i) => i.genres))].sort(),
      categories,
      items: all
    };
  }

  // ---- Direct MangaDex fallback (only when gatewayBase === '') -----------------------------

  private mapMdList(list: MdList | null, cat: ComicCat | undefined, ratings: Record<string, number>): Item[] {
    const out: Item[] = [];
    for (const m of list?.data ?? []) {
      if (!m.id) continue;
      const it = mapMdManga(m, cat, ratings[m.id]);
      if (it && !out.some((x) => x.id === it.id)) out.push(this.rememberComic(it));
    }
    return out;
  }

  private async fetchRatings(ids: string[], signal?: AbortSignal): Promise<Record<string, number>> {
    const uniq = [...new Set(ids)].slice(0, 100);
    if (!uniq.length) return {};
    const stats = await this.json<MdStats>(mdStatsUrl(uniq), signal).catch(() => null);
    return mdRatings(stats);
  }

  private async buildDirectMd(): Promise<CatalogFile> {
    const per = await Promise.all(
      COMIC_CATS.map(async (cat) => {
        const [pop, latest] = await Promise.all([
          this.json<MdList>(mdListUrl({ cat, limit: 24, order: 'followedCount' })).catch(() => null),
          this.json<MdList>(mdListUrl({ cat, limit: 24, order: 'latestUploadedChapter' })).catch(() => null)
        ]);
        const allIds = [pop, latest].flatMap((l) => (l?.data ?? []).map((m) => m.id).filter((id): id is string => !!id));
        const ratings = await this.fetchRatings(allIds);
        return { cat, popular: this.mapMdList(pop, cat, ratings), latest: this.mapMdList(latest, cat, ratings) };
      })
    );
    const items = new Map<string, Item>();
    const categories: Category[] = [];
    for (const { cat, popular, latest } of per) {
      if (!popular.length && !latest.length) continue;
      const rows: Row[] = [];
      const add = (list: Item[]) => {
        for (const i of list) if (!items.has(i.id)) items.set(i.id, i);
        return list.map((i) => i.id);
      };
      if (popular.length) rows.push({ id: `${cat.id}-popular`, title: `Popular ${cat.title}`, style: 'POSTER', itemIds: add(popular) });
      if (latest.length) rows.push({ id: `${cat.id}-latest`, title: 'Latest Updates', style: 'POSTER', itemIds: add(latest) });
      categories.push({ id: cat.id, space: 'COMICS', title: cat.title, heroIds: rows[0].itemIds.slice(0, 5), rows });
    }
    if (!categories.length) throw new Error('Comics: empty catalogue');
    const all = [...items.values()];
    return { space: 'COMICS', version: 1, genres: [...new Set(all.flatMap((i) => i.genres))].sort(), categories, items: all };
  }

  // ---- Item detail & Chapters (`c1` -> `dex` fallback + merge) -----------------------------

  private async findGatewayDexUuid(gw: string, item: Item, names: string[], anilistId: number | null, signal?: AbortSignal): Promise<string | null> {
    const cached = this.dexIds.get(item.id);
    if (cached) return cached;
    for (const name of names) {
      const searchPath = `/manga?title=${encodeURIComponent(name)}&limit=12&order[relevance]=desc&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica`;
      const res = await this.json<MdList>(gwDexUrl(gw, searchPath), signal).catch(() => null);
      const rows = res?.data ?? [];
      if (!rows.length) continue;
      const exact = anilistId ? rows.find((r) => r.attributes?.links?.al === String(anilistId)) : undefined;
      let best: { id: string; score: number } | null = null;
      if (!exact) {
        for (const r of rows) {
          if (!r.id) continue;
          const tMap = r.attributes?.title ?? {};
          const candidates = [
            ...Object.values(tMap),
            ...(r.attributes?.altTitles ?? []).flatMap((alt) => Object.values(alt))
          ].filter((s): s is string => typeof s === 'string' && s.length > 0);
          const s = Math.max(...candidates.map((cand) => titleScore(names, cand)), 0);
          if (!best || s > best.score) best = { id: r.id, score: s };
        }
      }
      const picked = exact?.id ?? (best && best.score >= 0.5 ? best.id : rows[0]?.id);
      if (picked) {
        this.dexIds.set(item.id, picked);
        return picked;
      }
    }
    return null;
  }

  private async fetchGatewayDexChapters(gw: string, dexUuid: string, signal?: AbortSignal): Promise<Chapter[]> {
    const collected: MdChapter[] = [];
    for (let offset = 0; offset < 1500; offset += 500) {
      const feedPath =
        `/manga/${encodeURIComponent(dexUuid)}/feed?translatedLanguage[]=en&order[chapter]=asc&limit=500&offset=${offset}` +
        `&includeExternalUrl=0&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica`;
      const feed = await this.json<MdFeed>(gwDexUrl(gw, feedPath), signal).catch(() => null);
      const batch = feed?.data ?? [];
      collected.push(...batch);
      if (batch.length < 500 || offset + batch.length >= (feed?.total ?? 0)) break;
    }
    return mapMdChapters({ data: collected });
  }

  private async resolveGatewayChapters(gw: string, item: Item, anilistId: number | null, signal?: AbortSignal): Promise<Chapter[]> {
    const names = [...new Set([item.title, ...(item.altTitle ? [item.altTitle] : []), ...(this.altTitles.get(item.id) ?? [])])]
      .map((s) => s.trim())
      .filter(Boolean)
      .slice(0, 4);

    const c1Res = names.length
      ? await this.json<{ chapters?: C1ChapterRow[] }>(gwC1Url(gw, names), signal).catch(() => null)
      : null;
    const c1Chapters = mapC1Chapters(c1Res?.chapters);
    if (c1Chapters.length > 0) {
      for (const c of c1Chapters) {
        this.chapterOwner.set(c.id, { itemId: item.id, num: extractChapterNum(c.title), anilistId });
      }
      return c1Chapters;
    }

    const dexUuid = mdUuidOf(item.id) ?? (await this.findGatewayDexUuid(gw, item, names, anilistId, signal));
    if (!dexUuid) return [];
    return this.fetchGatewayDexChapters(gw, dexUuid, signal);
  }

  private async fetchDirectMdChapters(uuid: string, signal?: AbortSignal): Promise<Chapter[]> {
    const collected: MdChapter[] = [];
    for (let offset = 0; offset < 1500; offset += 500) {
      const feed = await this.json<MdFeed>(mdFeedUrl(uuid, offset), signal).catch(() => null);
      const batch = feed?.data ?? [];
      collected.push(...batch);
      if (batch.length < 500 || offset + batch.length >= (feed?.total ?? 0)) break;
    }
    return mapMdChapters({ data: collected });
  }

  /** Full title: detail + chapter list, persisted across reloads in memory + sessionStorage + IndexedDB. */
  async item(id: string, signal?: AbortSignal): Promise<Item | undefined> {
    const normId = id.startsWith('mg:') ? alItemId(id.slice(3)) : id;
    const known = await this.loadPersisted(normId);
    const gw = this.gw;
    const anilistId = alIdOf(normId);

    if (anilistId !== null) {
      if (!gw) return known;
      const detailPromise = this.json<{ data?: { Media?: AnilistMedia } }>(
        gwAnilistUrl(gw, ANILIST_DETAIL_QUERY, { id: anilistId }),
        signal
      ).catch(() => null);

      const detailRes = await detailPromise;
      const mapped = mapAnilistMedia(detailRes?.data?.Media);
      const baseItem = mapped ? this.rememberComic(mapped.item, mapped.altTitles) : known;
      if (!baseItem) return undefined;

      const chapters = await this.resolveGatewayChapters(gw, baseItem, anilistId, signal);
      const full: Item = {
        ...known,
        ...baseItem,
        pages: chapters.length ? chapters : (known?.pages ?? [])
      };
      return this.rememberComic(full, mapped?.altTitles);
    }

    const uuid = mdUuidOf(normId);
    if (!uuid) return known;
    if (gw) {
      const [detail, chapters] = await Promise.all([
        this.json<{ data?: MdManga }>(gwDexUrl(gw, `/manga/${encodeURIComponent(uuid)}?includes[]=cover_art&includes[]=author&includes[]=artist`), signal).catch(
          () => null
        ),
        this.fetchGatewayDexChapters(gw, uuid, signal)
      ]);
      const base = detail?.data
        ? mapMdManga(detail.data, known ? COMIC_CATS.find((c) => c.id === known.categoryId) : undefined, known?.rating)
        : null;
      const it = base ?? known;
      if (!it) return undefined;
      return this.rememberComic({ ...known, ...it, pages: chapters.length ? chapters : (known?.pages ?? []) });
    }

    const [detail, chapters] = await Promise.all([
      this.json<{ data?: MdManga }>(mdDetailUrl(uuid), signal).catch(() => null),
      this.fetchDirectMdChapters(uuid, signal)
    ]);
    const base = detail?.data
      ? mapMdManga(detail.data, known ? COMIC_CATS.find((c) => c.id === known.categoryId) : undefined, known?.rating)
      : null;
    const it = base ?? known;
    if (!it) return undefined;
    return this.rememberComic({ ...known, ...it, pages: chapters.length ? chapters : (known?.pages ?? []) });
  }

  /** Cached backup page URLs for a resolved chapter ([] if unavailable). */
  backupPages(chapterId: string): string[] {
    const uuid = mdChapterUuid(chapterId) ?? chapterId;
    return this.pageSets.get(uuid)?.backup ?? [];
  }

  /** Resolve chapter pages via `/manga/c2` (with `/manga/img` Referer proxy) + `/manga/dex/at-home/server/...` fallback. */
  async chapterPageSet(chapterId: string, signal?: AbortSignal, opts?: { fresh?: boolean }): Promise<ChapterPageSet> {
    const uuid = mdChapterUuid(chapterId) ?? chapterId;
    if (!opts?.fresh) {
      const hit = this.pageSets.get(uuid);
      if (hit) return hit;
    }
    const gw = this.gw;
    const tries: (() => Promise<ChapterPageSet>)[] = [];

    if (uuid.startsWith(READER_CH_PREFIX)) {
      const readerId = uuid.slice(READER_CH_PREFIX.length);
      if (gw && readerId) {
        tries.push(async () => {
          const raw = await this.json<unknown>(gwC2Url(gw, readerId), signal, opts?.fresh);
          return mdPageSet(raw, gw);
        });
      }
    } else {
      if (gw) {
        tries.push(async () => {
          const raw = await this.json<unknown>(gwDexUrl(gw, `/at-home/server/${encodeURIComponent(uuid)}`), signal, opts?.fresh);
          return mdPageSet(raw, gw);
        });
        for (const action of ['c2', 'chapter'] as const) {
          tries.push(async () => {
            const raw = await this.json<unknown>(mdGatewayPagesUrl(gw, uuid, action), signal, opts?.fresh);
            return mdPageSet(raw, gw);
          });
        }
      } else {
        tries.push(async () => mdPageSet(await this.json<MdAtHome>(mdAtHomeUrl(uuid), signal, opts?.fresh)));
      }
    }

    const owner = this.chapterOwner.get(chapterId);
    if (gw && owner && owner.num !== null) {
      tries.push(async () => {
        const item = this.seen.get(owner.itemId) ?? (await this.loadPersisted(owner.itemId));
        if (!item) return { primary: [], backup: [] };
        const names = [...new Set([item.title, ...(item.altTitle ? [item.altTitle] : []), ...(this.altTitles.get(item.id) ?? [])])].filter(Boolean);
        const dexUuid = await this.findGatewayDexUuid(gw, item, names, owner.anilistId, signal);
        if (!dexUuid) return { primary: [], backup: [] };
        const dexChs = await this.fetchGatewayDexChapters(gw, dexUuid, signal);
        const match = dexChs.find((d) => extractChapterNum(d.title) === owner.num);
        const matchUuid = match ? mdChapterUuid(match.id) : null;
        if (!matchUuid) return { primary: [], backup: [] };
        const raw = await this.json<unknown>(gwDexUrl(gw, `/at-home/server/${encodeURIComponent(matchUuid)}`), signal, opts?.fresh);
        return mdPageSet(raw, gw);
      });
    }

    for (const t of tries) {
      try {
        const set = await t();
        if (set.primary.length) {
          this.pageSets.set(uuid, set);
          return set;
        }
      } catch {
        /* try next source */
      }
    }
    return { primary: [], backup: [] };
  }

  async chapterPages(chapterId: string, signal?: AbortSignal, opts?: { fresh?: boolean; dataSaver?: boolean }): Promise<string[]> {
    const set = await this.chapterPageSet(chapterId, signal, { fresh: opts?.fresh });
    return opts?.dataSaver && set.backup.length ? set.backup : set.primary;
  }

  async search(query: string, signal?: AbortSignal, catId?: ComicCatId): Promise<Item[]> {
    const q = query.trim();
    if (!q) return [];
    const gw = this.gw;
    const cat = catId ? COMIC_CATS.find((c) => c.id === catId) : undefined;
    if (gw) {
      return this.browseAnilist(
        gw,
        {
          search: q,
          country: cat?.country,
          perPage: 24
        },
        signal
      ).catch(() => [] as Item[]);
    }
    const list = await this.json<MdList>(mdListUrl({ title: q, cat, limit: 24 }), signal).catch(() => null);
    const ids = (list?.data ?? []).map((m) => m.id).filter((id): id is string => !!id);
    const ratings = await this.fetchRatings(ids, signal);
    return this.mapMdList(list, cat, ratings);
  }
}
