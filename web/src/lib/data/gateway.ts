// Antiblock gateway (user's own Vercel project) — primary catalog for HOME / ANIME / DRAMA and,
// only while the 18+ gate is unlocked, ADULT. Pure TS (no $app imports) so Vitest drives it with a
// fake fetch. Spec: docs/gateway.md · samples: docs/gateway-samples/*.json.
import { fallbackColor } from './color';
import type { PersistentCache } from './idb';
import type { CatalogFile, Category, Episode, Item, Row, RowMore, RowStyle, Season, Source, Space } from './types';

export const GATEWAY_DEFAULT = 'https://antiblock-gateway.vercel.app';
/** localStorage override (Settings → Developer). Empty string = gateway off. */
export const GATEWAY_KEY = 'cinetor.gatewayBase';
export const GW_API = '/api/public/v1';

export type GwSpace = 'HOME' | 'ANIME' | 'DRAMA' | 'ADULT';
export const isGwSpace = (s: Space): s is GwSpace => s === 'HOME' || s === 'ANIME' || s === 'DRAMA' || s === 'ADULT';

interface StorageLike {
  getItem(k: string): string | null;
  setItem(k: string, v: string): void;
  removeItem(k: string): void;
}

const ls = (): StorageLike | null => {
  try {
    return typeof localStorage !== 'undefined' ? localStorage : null;
  } catch {
    return null;
  }
};

/** '' → off; otherwise an absolute http(s) origin/path without trailing slash (null = invalid). */
export function normalizeBase(v: string): string | null {
  const s = v.trim().replace(/\/+$/, '');
  if (!s) return '';
  return /^https?:\/\/[^\s/]+/i.test(s) ? s : null;
}

export function gatewayBase(store: StorageLike | null = ls()): string {
  const v = store?.getItem(GATEWAY_KEY);
  if (v === null || v === undefined) return GATEWAY_DEFAULT;
  const n = normalizeBase(v);
  return n === null ? GATEWAY_DEFAULT : n;
}

/** null resets to the default; '' disables the gateway (TMDB / mock fallback). */
export function setGatewayBase(v: string | null, store: StorageLike | null = ls()): void {
  if (!store) return;
  if (v === null) store.removeItem(GATEWAY_KEY);
  else store.setItem(GATEWAY_KEY, normalizeBase(v) ?? GATEWAY_DEFAULT);
}

// ------------------------------------------------------------------------------------ ids

export const GW_PREFIX = 'gw:';
/** 18+ ids reuse the mock 'ad-' prefix so every existing adult gate (spaceForId) applies. */
export const GW_ADULT_PREFIX = 'ad-gw:';

export const isGatewayId = (id: string): boolean => id.startsWith(GW_PREFIX) || id.startsWith(GW_ADULT_PREFIX);
export const isGatewayAdultId = (id: string): boolean => id.startsWith(GW_ADULT_PREFIX);

export const gwItemId = (subjectId: string, space?: Space): string => `${space === 'ADULT' ? GW_ADULT_PREFIX : GW_PREFIX}${subjectId}`;
export const gwEpisodeId = (itemId: string, se: number, ep: number): string => `${itemId}-s${se}e${ep}`;

/** subject_id of an item / episode id ('gw:123', 'ad-gw:123', 'gw:123-s1e2'). */
export function subjectIdOf(id: string): string | null {
  const m = /^(?:ad-gw:|gw:)([^-\s]+)/.exec(id);
  return m ? m[1] : null;
}

// ------------------------------------------------------------------------------------ wire types

export interface GwListItem {
  name?: string | null;
  poster_url?: string | null;
  slug?: string | null;
  subject_id?: string | null;
  badge?: string | null;
  rating?: string | number | null;
  year?: string | number | null;
  genre?: string | null;
  type?: string | null;
  country?: string | null;
  description?: string | null;
  release_date?: string | null;
  has_resource?: boolean | null;
  duration?: number | null;
  subtitles?: string[] | null;
  dubs?: unknown[] | null;
}

export interface GwPage {
  page?: number;
  per_page?: number;
  has_more?: boolean;
  items?: GwListItem[];
}

export interface GwHome {
  sections?: { section: string; count?: number; items?: GwListItem[] }[];
}

export interface GwImage {
  url?: string | null;
  avgHueDark?: string | null;
  avgHueLight?: string | null;
}

export interface GwSubject {
  subjectId?: string;
  subjectType?: number;
  title?: string;
  description?: string;
  releaseDate?: string;
  duration?: number;
  genre?: string;
  cover?: GwImage | null;
  stills?: GwImage | null;
  countryName?: string;
  imdbRatingValue?: string;
  detailPath?: string;
}

export interface GwDetail {
  code?: number;
  data?: {
    subject?: GwSubject;
    stars?: { name?: string; character?: string; staffType?: number }[];
    resource?: { seasons?: { se: number; maxEp: number; allEp?: string }[] };
  };
}

export interface GwSuggest {
  suggestions?: { title?: string | null }[];
}

// ------------------------------------------------------------------------------------ mapping

export const splitGenres = (g?: string | null): string[] =>
  (g ?? '')
    .split(',')
    .map((x) => x.trim())
    .filter(Boolean);

const num = (v: unknown): number => {
  const n = typeof v === 'number' ? v : parseFloat(String(v ?? ''));
  return Number.isFinite(n) ? n : 0;
};
const yearOf = (y: unknown, date?: string | null): number => Math.trunc(num(y)) || Number((date ?? '').slice(0, 4)) || 0;
const hex = (c?: string | null): string | undefined => (c && /^#[0-9a-f]{6}$/i.test(c) ? c.toUpperCase() : undefined);

/** Lazy source: resolved by gateway-play (`/stream/{id}` → proxy MP4 variants) right before play/download. */
export function streamPlaceholder(subjectId: string, slug: string, se: number, ep: number): Source {
  const q = new URLSearchParams({ detail_path: slug, se: String(se), ep: String(ep) });
  return { url: `gw-stream:${subjectId}?${q}`, kind: 'MP4', label: 'Auto' };
}

export const isAdultish = (r: Pick<GwListItem, 'genre' | 'badge'>): boolean => /erotic|adult/i.test(r.genre ?? '') || r.badge === '18+';

const ANIME_RE = /\b(anime|animation)\b/i;
const DRAMA_COUNTRY_RE = /korea|china|japan|taiwan|hong ?kong/i;

/** Space for a search / deep-link result (adult never lands in a global space: null). */
export function classifyGw(r: GwListItem): Exclude<GwSpace, 'ADULT'> | null {
  if (isAdultish(r)) return null;
  if (ANIME_RE.test(r.genre ?? '')) return 'ANIME';
  if (r.type === 'tv' && DRAMA_COUNTRY_RE.test(r.country ?? '')) return 'DRAMA';
  return 'HOME';
}

export interface GwMapOptions {
  categoryId?: string;
  colorOf?: (poster: string) => string | undefined;
}

/** List row item → Item (null when unusable: no id/slug/poster, or 18+ outside the adult space). */
export function mapGwItem(r: GwListItem, space: GwSpace, opts: GwMapOptions = {}): Item | null {
  if (!r.subject_id || !r.slug || !r.poster_url || !r.name) return null;
  // Every visible title must be playable.
  if (r.has_resource === false) return null;
  if (space !== 'ADULT' && isAdultish(r)) return null;
  const id = gwItemId(r.subject_id, space);
  const poster = r.poster_url;
  const tv = r.type === 'tv';
  const item: Item = {
    id,
    space,
    categoryId: opts.categoryId ?? `gw-${space.toLowerCase()}`,
    type: tv ? 'SERIES' : 'MOVIE',
    title: r.name,
    year: yearOf(r.year, r.release_date),
    rating: Math.round(num(r.rating) * 10) / 10,
    maturity: space === 'ADULT' ? '18+' : 'NR',
    genres: splitGenres(r.genre),
    synopsis: r.description ?? '',
    poster,
    backdrop: poster,
    dominantColor: opts.colorOf?.(poster) ?? fallbackColor(id),
    accentColor: '#FF2D3B',
    slug: r.slug,
    country: r.country ?? undefined
  };
  if (r.duration) item.durationSec = r.duration;
  if (!tv) item.sources = [streamPlaceholder(r.subject_id, r.slug, 0, 0)];
  return item;
}

/** `/detail/{slug}` → full Item: stills backdrop, cover hue, cast, inline seasons/episodes (all lazy sources). */
export function mapGwDetail(d: GwDetail, space: GwSpace, opts: GwMapOptions & { slug?: string } = {}): Item | null {
  const s = d.data?.subject;
  if (!s?.subjectId || !s.title) return null;
  const slug = s.detailPath || opts.slug;
  if (!slug) return null;
  const id = gwItemId(s.subjectId, space);
  const poster = s.cover?.url || s.stills?.url || '';
  if (!poster) return null;
  const backdrop = s.stills?.url || poster;
  const tv = s.subjectType === 2;
  const item: Item = {
    id,
    space,
    categoryId: opts.categoryId ?? `gw-${space.toLowerCase()}`,
    type: tv ? 'SERIES' : 'MOVIE',
    title: s.title,
    year: yearOf(undefined, s.releaseDate),
    rating: Math.round(num(s.imdbRatingValue) * 10) / 10,
    maturity: space === 'ADULT' ? '18+' : 'NR',
    genres: splitGenres(s.genre),
    synopsis: s.description ?? '',
    poster,
    backdrop,
    dominantColor: hex(s.cover?.avgHueDark) ?? opts.colorOf?.(poster) ?? fallbackColor(id),
    accentColor: '#FF2D3B',
    slug,
    country: s.countryName || undefined,
    cast: (d.data?.stars ?? [])
      .filter((x) => x.name && x.staffType !== 2)
      .slice(0, 10)
      .map((x) => x.name!)
  };
  if (s.duration) item.durationSec = s.duration;
  if (!tv) {
    item.sources = [streamPlaceholder(s.subjectId, slug, 0, 0)];
    return item;
  }
  const raw = (d.data?.resource?.seasons ?? []).filter((x) => x.se > 0 && x.maxEp > 0).sort((a, b) => a.se - b.se);
  const seasons: Season[] = (raw.length ? raw : [{ se: 1, maxEp: 1 }]).map((x) => ({
    n: x.se,
    title: `Season ${x.se}`,
    episodeCount: x.maxEp,
    episodes: Array.from({ length: x.maxEp }, (_, i): Episode => ({
      id: gwEpisodeId(id, x.se, i + 1),
      n: i + 1,
      title: `Episode ${i + 1}`,
      durationSec: 0,
      still: backdrop,
      sources: [streamPlaceholder(s.subjectId!, slug, x.se, i + 1)],
      subtitles: []
    }))
  }));
  item.seasons = seasons;
  return item;
}

// ------------------------------------------------------------------------------------ plan

export interface GwListSpec {
  kind: 'list';
  id: string;
  title: string;
  style: RowStyle;
  path: string;
  params: Record<string, string>;
}
/** Rows taken from `/home` sections (by name or majority type). */
export interface GwSectionSpec {
  kind: 'sections';
  match: RegExp | 'movie' | 'tv';
  /** Max rows produced. */
  limit: number;
}
export interface GwCategorySpec {
  id: string;
  title: string;
  /** Hero: 'banner' (+ type filter) or the first row's items. */
  hero: 'banner-movie' | 'banner-tv' | 'first';
  rows: (GwListSpec | GwSectionSpec)[];
}

const list = (id: string, title: string, path: string, params: Record<string, string> = {}, style: RowStyle = 'POSTER'): GwListSpec => ({
  kind: 'list',
  id,
  title,
  style,
  path,
  params
});

export function gatewayPlan(space: GwSpace): GwCategorySpec[] {
  switch (space) {
    case 'HOME':
      return [
        { id: 'gw-movies', title: 'Movies', hero: 'banner-movie', rows: [list('gw-movies-latest', 'Latest Movies', '/movies'), { kind: 'sections', match: 'movie', limit: 12 }] },
        { id: 'gw-series', title: 'Series', hero: 'banner-tv', rows: [list('gw-series-latest', 'Latest Series', '/tv-series'), { kind: 'sections', match: 'tv', limit: 12 }] }
      ];
    case 'ANIME':
      return [
        {
          id: 'gw-anime',
          title: 'Popular',
          hero: 'first',
          rows: [list('gw-anime-popular', 'Popular Animation', '/animation'), list('gw-anime-series', 'Anime Series', '/tv-series', { genre: 'Anime' }), { kind: 'sections', match: /anime/i, limit: 2 }]
        },
        { id: 'gw-anime-action', title: 'Action', hero: 'first', rows: [list('gw-anime-action-r', 'Action & Adventure', '/animation', { genre: 'Action' })] },
        { id: 'gw-anime-comedy', title: 'Comedy', hero: 'first', rows: [list('gw-anime-comedy-r', 'Comedy', '/animation', { genre: 'Comedy' })] }
      ];
    case 'DRAMA': {
      const cat = (id: string, title: string, country: string, section: RegExp): GwCategorySpec => ({
        id,
        title,
        hero: 'first',
        rows: [
          list(`${id}-trending`, `Trending ${title}`, '/tv-series', { country }),
          { kind: 'sections', match: section, limit: 1 },
          list(`${id}-romance`, 'Romance', '/tv-series', { country, genre: 'Romance' }),
          list(`${id}-comedy`, 'Light & Funny', '/tv-series', { country, genre: 'Comedy' })
        ]
      });
      return [cat('gw-kdrama', 'K-Drama', 'Korea', /k-?drama/i), cat('gw-cdrama', 'C-Drama', 'China', /c-?drama/i), cat('gw-jdrama', 'J-Drama', 'Japan', /j-?drama/i)];
    }
    case 'ADULT':
      // Ids match the adult space nav tabs.
      return [
        { id: 'ad-featured', title: 'Movies', hero: 'first', rows: [list('ad-gw-movies', 'Latest', '/adult', { kind: 'movies' })] },
        { id: 'ad-series', title: 'Series', hero: 'first', rows: [list('ad-gw-series', 'Series', '/adult', { kind: 'tv-series' })] }
      ];
  }
}

/** First paged list of a category: genre chips query it with `genre=` (null: client-side filter only). */
export function genreBase(space: GwSpace, categoryId: string): { path: string; params: Record<string, string> } | null {
  if (space === 'ADULT') return null;
  const c = gatewayPlan(space).find((x) => x.id === categoryId) ?? gatewayPlan(space)[0];
  const l = c?.rows.find((r): r is GwListSpec => r.kind === 'list');
  if (!l) return null;
  const { genre: _g, ...params } = l.params;
  void _g;
  return { path: l.path, params };
}

// ------------------------------------------------------------------------------------ client

export type GwFetcher = (url: string, init?: { signal?: AbortSignal; headers?: Record<string, string> }) => Promise<{ ok: boolean; status: number; json(): Promise<unknown> }>;

export interface GatewayClientOptions {
  base: () => string;
  fetcher?: GwFetcher;
  persist?: PersistentCache | null;
  /** Catalog cache (memory + persistent). */
  ttlMs?: number;
  timeoutMs?: number;
  now?: () => number;
}

interface Cached {
  ts: number;
  v: unknown;
}

type ClientOpts = Required<Omit<GatewayClientOptions, 'persist'>> & { persist: PersistentCache | null };

export class GatewayClient {
  private mem = new Map<string, Cached>();
  private inflight = new Map<string, Promise<unknown>>();
  private o: ClientOpts;
  /** Network requests made (tests). */
  calls = 0;

  constructor(opts: GatewayClientOptions) {
    this.o = {
      base: opts.base,
      fetcher: opts.fetcher ?? ((u, init) => fetch(u, init)),
      ttlMs: opts.ttlMs ?? 10 * 60_000,
      timeoutMs: opts.timeoutMs ?? 10_000,
      now: opts.now ?? (() => Date.now()),
      persist: opts.persist ?? null
    };
  }

  get base(): string {
    return this.o.base();
  }

  get enabled(): boolean {
    return !!this.base;
  }

  url(path: string, params: Record<string, string | number | undefined> = {}): string {
    const u = new URL(`${this.base}${GW_API}${path}`);
    for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') u.searchParams.set(k, String(v));
    return u.toString();
  }

  /** Raw GET with timeout (no cache). */
  async fetchJson<T>(url: string, signal?: AbortSignal, timeoutMs = this.o.timeoutMs): Promise<T> {
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), timeoutMs);
    const onAbort = () => ctl.abort();
    signal?.addEventListener('abort', onAbort);
    try {
      this.calls++;
      const res = await this.o.fetcher(url, { signal: ctl.signal, headers: { accept: 'application/json' } });
      if (!res.ok) throw new Error(`Gateway HTTP ${res.status}`);
      return (await res.json()) as T;
    } finally {
      clearTimeout(t);
      signal?.removeEventListener('abort', onAbort);
    }
  }

  /** Cached GET: memory → persistent (fresh) → network → stale persistent on failure. */
  async get<T>(path: string, params: Record<string, string | number | undefined> = {}, signal?: AbortSignal): Promise<T> {
    if (!this.enabled) throw new Error('Gateway off');
    const url = this.url(path, params);
    const now = this.o.now();
    const hit = this.mem.get(url);
    if (hit && now - hit.ts < this.o.ttlMs) return hit.v as T;
    const running = this.inflight.get(url);
    if (running) return running as Promise<T>;
    const p = (async () => {
      const key = `gw:${url}`;
      const stored = this.o.persist ? ((await this.o.persist.get(key)) as Cached | undefined) : undefined;
      if (stored && typeof stored.ts === 'number' && now - stored.ts < this.o.ttlMs) {
        this.mem.set(url, stored);
        return stored.v;
      }
      try {
        const v = await this.fetchJson<unknown>(url, signal);
        const rec = { ts: this.o.now(), v };
        this.mem.set(url, rec);
        if (this.o.persist) void this.o.persist.set(key, rec);
        return v;
      } catch (e) {
        if (stored && !signal?.aborted) return stored.v;
        throw e;
      }
    })();
    this.inflight.set(url, p);
    p.finally(() => this.inflight.delete(url)).catch(() => undefined);
    return p as Promise<T>;
  }
}

// ------------------------------------------------------------------------------------ source

export interface GatewaySourceOptions {
  client: GatewayClient;
  colorOf?: (poster: string) => string | undefined;
  /** Dominant-color cache etc. use the same store; slugs of seen ids are persisted for deep links. */
  persist?: PersistentCache | null;
  /** Titles whose stream failed to resolve (hidden from every list). */
  isDead?: (subjectId: string) => boolean;
}

/** Subject ids that resolved to no playable source — persisted (localStorage) so lists hide them. */
export class DeadTitles {
  private set: Set<string>;
  constructor(private store: StorageLike | null = ls(), private key = 'cinetor.gw.dead') {
    let arr: string[] = [];
    try {
      arr = JSON.parse(store?.getItem(key) ?? '[]') as string[];
    } catch {
      arr = [];
    }
    this.set = new Set(Array.isArray(arr) ? arr.slice(-500) : []);
  }
  has(id: string): boolean {
    return this.set.has(id);
  }
  add(id: string): void {
    if (this.set.has(id)) return;
    this.set.add(id);
    try {
      this.store?.setItem(this.key, JSON.stringify([...this.set].slice(-500)));
    } catch {
      /* quota */
    }
  }
  delete(id: string): void {
    if (this.set.delete(id)) this.store?.setItem(this.key, JSON.stringify([...this.set]));
  }
}

export interface PageResult {
  items: Item[];
  more: RowMore;
}

const ROW_STYLE_LIMIT = 36;

export class GatewayCatalogSource {
  private seen = new Map<string, Item>();
  private files = new Map<GwSpace, { ts: number; p: Promise<CatalogFile> }>();
  private details = new Map<string, Promise<Item | undefined>>();
  private slugs = new Map<string, string>();

  constructor(private o: GatewaySourceOptions) {}

  get enabled(): boolean {
    return this.o.client.enabled;
  }

  get client(): GatewayClient {
    return this.o.client;
  }

  cached(id: string): Item | undefined {
    return this.seen.get(id);
  }

  /** Deep links carry the slug (`&slug=`); remember it so `/detail/{slug}` can be fetched. */
  rememberSlug(id: string, slug: string): void {
    const sid = subjectIdOf(id);
    if (!sid || !slug) return;
    this.slugs.set(sid, slug);
    if (this.o.persist) void this.o.persist.set(`gw:slug:${sid}`, slug);
  }

  private async slugOf(id: string): Promise<string | undefined> {
    const sid = subjectIdOf(id);
    if (!sid) return undefined;
    const s = this.seen.get(id)?.slug ?? this.slugs.get(sid);
    if (s) return s;
    const stored = this.o.persist ? ((await this.o.persist.get(`gw:slug:${sid}`)) as string | undefined) : undefined;
    return typeof stored === 'string' ? stored : undefined;
  }

  private remember(items: Iterable<Item>): void {
    for (const i of items) {
      if (!this.seen.has(i.id)) this.seen.set(i.id, i);
      if (i.slug) {
        const sid = subjectIdOf(i.id);
        if (sid && !this.slugs.has(sid)) {
          this.slugs.set(sid, i.slug);
          if (this.o.persist) void this.o.persist.set(`gw:slug:${sid}`, i.slug);
        }
      }
    }
  }

  private map(raw: GwListItem[] | undefined, space: GwSpace, categoryId: string): Item[] {
    const out: Item[] = [];
    const ids = new Set<string>();
    for (const r of raw ?? []) {
      if (r.subject_id && this.o.isDead?.(r.subject_id)) continue;
      const it = mapGwItem(r, space, { categoryId, colorOf: this.o.colorOf });
      if (!it || ids.has(it.id)) continue;
      ids.add(it.id);
      out.push(it);
    }
    return out;
  }

  file(space: GwSpace): Promise<CatalogFile> {
    const cur = this.files.get(space);
    if (cur && Date.now() - cur.ts < 10 * 60_000) return cur.p;
    const p = this.build(space);
    p.catch(() => this.files.delete(space));
    this.files.set(space, { ts: Date.now(), p });
    return p;
  }

  private async build(space: GwSpace): Promise<CatalogFile> {
    if (!this.enabled) throw new Error('Gateway off');
    const plan = gatewayPlan(space);
    const needsHome = space !== 'ADULT' && plan.some((c) => c.hero.startsWith('banner') || c.rows.some((r) => r.kind === 'sections'));
    const homeP = needsHome ? this.o.client.get<GwHome>('/home').catch(() => null) : Promise.resolve(null);
    const lists = plan.flatMap((c) => c.rows.filter((r): r is GwListSpec => r.kind === 'list').map((r) => ({ c, r })));
    const pages = await Promise.all(lists.map(({ r }) => this.o.client.get<GwPage>(r.path, { ...r.params, page: 1 }).catch(() => null)));
    const home = await homeP;
    const sections = (home?.sections ?? []).filter((s) => Array.isArray(s.items) && s.items.length);

    const items = new Map<string, Item>();
    const add = (list: Item[]) => {
      for (const i of list) if (!items.has(i.id)) items.set(i.id, i);
      return list.map((i) => i.id);
    };
    const categories: Category[] = [];
    const usedSections = new Set<string>();
    for (const c of plan) {
      const rows: Row[] = [];
      for (const spec of c.rows) {
        if (spec.kind === 'list') {
          const idx = lists.findIndex((x) => x.r === spec);
          const pg = pages[idx];
          const mapped = this.map(pg?.items, space, c.id).slice(0, ROW_STYLE_LIMIT);
          if (!mapped.length) continue;
          rows.push({
            id: spec.id,
            title: spec.title,
            style: spec.style,
            itemIds: add(mapped),
            more: { path: spec.path, params: spec.params, page: pg?.page ?? 1, hasMore: !!pg?.has_more }
          });
          continue;
        }
        let n = 0;
        for (const s of sections) {
          if (n >= spec.limit) break;
          if (/^banner$/i.test(s.section) || usedSections.has(`${c.id}|${s.section}`)) continue;
          const raw = s.items ?? [];
          let ok: boolean;
          if (spec.match instanceof RegExp) ok = spec.match.test(s.section);
          else {
            const same = raw.filter((x) => x.type === spec.match).length;
            ok = same * 2 > raw.length && !/anime|k-?drama|c-?drama/i.test(s.section);
          }
          if (!ok) continue;
          const mapped = this.map(raw, space, c.id);
          if (!mapped.length) continue;
          usedSections.add(`${c.id}|${s.section}`);
          rows.push({
            id: `${c.id}-sec-${s.section.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`,
            title: s.section.replace(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/gu, '').trim() || s.section,
            style: /popular/i.test(s.section) && n === 0 ? 'TOP10' : 'POSTER',
            itemIds: add(mapped.slice(0, /popular/i.test(s.section) && n === 0 ? 10 : 20))
          });
          n++;
        }
      }
      if (!rows.length) continue;
      let heroIds: string[] = [];
      if (c.hero !== 'first') {
        const banner = sections.find((s) => /^banner$/i.test(s.section));
        const want = c.hero === 'banner-movie' ? 'movie' : 'tv';
        const mapped = this.map(banner?.items, space, c.id);
        const typed = mapped.filter((i) => (want === 'movie' ? i.type === 'MOVIE' : i.type === 'SERIES'));
        heroIds = add((typed.length >= 3 ? typed : mapped).slice(0, 6));
      }
      if (!heroIds.length) heroIds = rows[0].itemIds.slice(0, 6);
      categories.push({ id: c.id, space, title: c.title, heroIds, rows });
    }
    if (!categories.length) throw new Error('Gateway: empty catalog');
    const all = [...items.values()];
    this.remember(all);
    return { space, version: 1, genres: [...new Set(all.flatMap((i) => i.genres))].sort(), categories, items: all };
  }

  /** Next page of a paged row (infinite rows). */
  async morePage(space: GwSpace, more: RowMore, categoryId = `gw-${space.toLowerCase()}`, signal?: AbortSignal): Promise<PageResult> {
    const page = more.page + 1;
    const pg = await this.o.client.get<GwPage>(more.path, { ...more.params, page }, signal);
    const items = this.map(pg.items, space, categoryId);
    this.remember(items);
    return { items, more: { ...more, page: pg.page ?? page, hasMore: !!pg.has_more && items.length > 0 } };
  }

  /** Genre chip → server-side `genre=` list of the category's main endpoint (page 1). */
  async genrePage(space: GwSpace, categoryId: string, genre: string, signal?: AbortSignal): Promise<PageResult | null> {
    const b = genreBase(space, categoryId);
    if (!b) return null;
    return this.morePage(space, { path: b.path, params: { ...b.params, genre }, page: 0, hasMore: true }, categoryId, signal);
  }

  item(id: string): Promise<Item | undefined> {
    let p = this.details.get(id);
    if (!p) {
      p = this.loadDetail(id);
      p.catch(() => this.details.delete(id));
      this.details.set(id, p);
    }
    return p;
  }

  private async loadDetail(id: string): Promise<Item | undefined> {
    const slug = await this.slugOf(id);
    const known = this.seen.get(id);
    if (!slug) return known;
    const d = await this.o.client.get<GwDetail>(`/detail/${encodeURIComponent(slug)}`);
    const adultId = isGatewayAdultId(id);
    const subj = d.data?.subject;
    // A deep-linked 18+ title never maps into a global space (and vice versa).
    if (!adultId && subj && isAdultish({ genre: subj.genre })) return undefined;
    const space: GwSpace = adultId ? 'ADULT' : (known?.space as GwSpace | undefined) ?? classifyGw({ genre: subj?.genre, country: subj?.countryName, type: subj?.subjectType === 2 ? 'tv' : 'movie' }) ?? 'HOME';
    const it = mapGwDetail(d, space, { slug, categoryId: known?.categoryId, colorOf: this.o.colorOf });
    if (!it) return known;
    if (it.id !== id) return known;
    if (known && !it.synopsis) it.synopsis = known.synopsis;
    this.seen.set(id, { ...known, ...it });
    return it;
  }

  /** `/search` → Home/Anime/Drama items (18+ dropped). */
  async search(query: string, signal?: AbortSignal): Promise<Item[]> {
    const q = query.trim();
    if (!q) return [];
    const pg = await this.o.client.get<GwPage>('/search', { q, page: 1 }, signal);
    const out: Item[] = [];
    for (const r of pg.items ?? []) {
      const space = classifyGw(r);
      if (!space || (r.subject_id && this.o.isDead?.(r.subject_id))) continue;
      const it = mapGwItem(r, space, { colorOf: this.o.colorOf });
      if (it && !out.some((x) => x.id === it.id)) out.push(it);
    }
    this.remember(out);
    return out;
  }

  /** Raw search hits (stream resolver: TMDB title → gateway subject). */
  async searchRaw(query: string, signal?: AbortSignal): Promise<GwListItem[]> {
    const pg = await this.o.client.get<GwPage>('/search', { q: query.trim(), page: 1 }, signal);
    return pg.items ?? [];
  }

  async suggest(query: string, signal?: AbortSignal): Promise<string[]> {
    const q = query.trim();
    if (q.length < 2) return [];
    const r = await this.o.client.get<GwSuggest>('/search/suggest', { q }, signal);
    return [...new Set((r.suggestions ?? []).map((s) => (s.title ?? '').trim()).filter(Boolean))].slice(0, 8);
  }
}
