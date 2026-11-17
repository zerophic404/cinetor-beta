// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
// scrape or hack anything without Mythbrix's explicit permission.
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

export interface GwDubEntry {
  subject_id?: string | null;
  subjectId?: string | null;
  slug?: string | null;
  detailPath?: string | null;
  language_name?: string | null;
  lanName?: string | null;
  language?: string | null;
  lan?: string | null;
  type?: number | null;
}

export interface GwListItem {
  name?: string | null;
  poster_url?: string | null;
  slug?: string | null;
  subject_id?: string | null;
  badge?: string | null;
  rating?: string | number | null;
  year?: string | number | null;
  genre?: string | null;
  type?: string | number | null;
  subjectType?: number | null;
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
  corner?: string | null;
  cover?: GwImage | null;
  stills?: GwImage | null;
  countryName?: string;
  imdbRatingValue?: string;
  detailPath?: string;
  dubs?: GwDubEntry[] | null;
}

export interface GwEpisodeWire {
  episode?: number;
  name?: string | null;
  stillUrl?: string | null;
  still_url?: string | null;
  stillPath?: string | null;
}

export interface GwSeasonWire {
  se: number;
  season?: number;
  maxEp: number;
  allEp?: string;
  episodes?: GwEpisodeWire[];
}

export interface GwDetail {
  code?: number;
  data?: {
    subject?: GwSubject;
    stars?: { name?: string; character?: string; staffType?: number; avatarUrl?: string | null }[];
    resource?: { seasons?: GwSeasonWire[] };
  };
}

export interface GwSuggest {
  suggestions?: { title?: string | null }[];
}

// ------------------------------------------------------------------------------------ safety & dubs

const ADULT_BLOCKED_RE =
  /\b(teen\w*|school\w*|college girl|student\w*|tuition|beti|bachi|underage|minors?|enjo|first (time|pain)|lolita|loli|shota|child\w*|kids?|young girls?|virgin|step[- ]?(daughter|son|sister|brother|mom|mother)|rape\w*|forced?|drugged|sleeping|unconscious|incest|dog|animal\w*|bestial\w*|anime|animation|hentai|cartoon|leak\w*|mms|hidden cam\w*|spy cam\w*)\b/i;

/**
 * Cinetor Beta `AdultCatalog.safeName` blocklist filter:
 * blocks anything hinting at minors, non-consent, leaks/hidden-cam, incest, animals, or cartoons/hentai.
 */
export function safeName(name: string | null | undefined): boolean {
  const trimmed = (name ?? '').trim();
  return trimmed.length > 0 && !ADULT_BLOCKED_RE.test(trimmed);
}

export const DUB_LANGUAGES = ['Hindi', 'Bengali', 'Tamil', 'Telugu', 'English'] as const;
export type DubLanguage = (typeof DUB_LANGUAGES)[number];

function matchDubName(raw: string): DubLanguage | null {
  const t = raw.trim();
  if (!t) return null;
  if (/\bhindi\b|^hi$/i.test(t)) return 'Hindi';
  if (/\bbengali\b|\bbangla\b|^bn$/i.test(t)) return 'Bengali';
  if (/\btamil\b|^ta$/i.test(t)) return 'Tamil';
  if (/\btelugu\b|^te$/i.test(t)) return 'Telugu';
  if (/\benglish\b|^en$/i.test(t)) return 'English';
  return null;
}

/**
 * Extract canonical dub languages (`Hindi`, `Bengali`, `Tamil`, `Telugu`, `English`) from gateway
 * `dubs`, `badge`, `slug`, and bracketed/dubbed `name` markers without false-positiving plain English titles.
 */
export function extractDubLanguages(input: {
  name?: string | null;
  badge?: string | null;
  slug?: string | null;
  dubs?: unknown[] | null;
}): DubLanguage[] {
  const found: DubLanguage[] = [];
  const push = (lang: DubLanguage | null) => {
    if (lang && !found.includes(lang)) found.push(lang);
  };

  for (const entry of input.dubs ?? []) {
    if (typeof entry === 'string') {
      push(matchDubName(entry));
      continue;
    }
    if (entry && typeof entry === 'object') {
      const d = entry as GwDubEntry;
      if (typeof d.type === 'number' && d.type !== 0) continue;
      push(matchDubName(d.language_name ?? d.lanName ?? d.language ?? d.lan ?? ''));
    }
  }

  const badge = input.badge ?? '';
  if (badge) {
    if (/\bhindi\b/i.test(badge)) push('Hindi');
    if (/\bbengali\b|\bbangla\b|\bzee\s*bangla\b|\bstar\s*jalsha\b|\bsony\s*aath\b/i.test(badge)) push('Bengali');
    if (/\btamil\b/i.test(badge)) push('Tamil');
    if (/\btelugu\b/i.test(badge)) push('Telugu');
    if (/\benglish\b/i.test(badge)) push('English');
  }

  const slug = input.slug ?? '';
  if (slug) {
    if (/(?:^|-)hindi(?:-dub(?:bed)?)?(?:-|$)/i.test(slug)) push('Hindi');
    if (/(?:^|-)bengali(?:-|$)|zee-?bangla|star-?jalsha|sony-?aath/i.test(slug)) push('Bengali');
    if (/(?:^|-)tamil(?:-dub(?:bed)?)?(?:-|$)/i.test(slug)) push('Tamil');
    if (/(?:^|-)telugu(?:-dub(?:bed)?)?(?:-|$)/i.test(slug)) push('Telugu');
    if (/(?:^|-)english-dub(?:bed)?(?:-|$)/i.test(slug)) push('English');
  }

  const name = input.name ?? '';
  if (name) {
    if (/\bhindi\b|[\[(][^\])]*\bhin\b[^\])]*[\])]/i.test(name)) push('Hindi');
    if (/\bbengali\b|\bbangla\b|[\[(][^\])]*\bben\b[^\])]*[\])]/i.test(name)) push('Bengali');
    if (/\btamil\b|[\[(][^\])]*\btam\b[^\])]*[\])]/i.test(name)) push('Tamil');
    if (/\btelugu\b|[\[(][^\])]*\btel\b[^\])]*[\])]/i.test(name)) push('Telugu');
    if (/\benglish\s*dub(?:bed)?\b|[\[(][^\])]*\b(?:english|eng)\b[^\])]*[\])]|\bdual\s*audio\b/i.test(name)) push('English');
  }

  return found;
}

function mergeGenresWithDubs(baseGenres: string[], dubs: readonly string[]): string[] {
  const out = [...baseGenres];
  for (const d of dubs) {
    if (d && !out.some((g) => g.toLowerCase() === d.toLowerCase())) out.push(d);
  }
  return out;
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

export const isAdultish = (r: Pick<GwListItem, 'genre' | 'badge'>): boolean =>
  /erotic|adult/i.test(r.genre ?? '') || (r.badge ?? '').includes('18+');

const ANIME_RE = /\b(anime|animation)\b/i;
const DRAMA_COUNTRY_RE = /korea|china|japan|taiwan|hong ?kong|pakistan|thailand/i;
const HOME_SKIP_RE = /anime|live|football|sport/i;

export function isAnimeItem(r: { genre?: string | null; genres?: readonly string[] | null }): boolean {
  if (r.genre && ANIME_RE.test(r.genre)) return true;
  return (r.genres ?? []).some((g) => ANIME_RE.test(g));
}

function isSeriesWire(r: Pick<GwListItem, 'type' | 'subjectType'>): boolean {
  const t = String(r.type ?? '').toLowerCase();
  return t === 'tv' || t === '7' || r.type === 2 || r.type === 7 || r.subjectType === 2 || r.subjectType === 7;
}

/** Space for a search / deep-link result (adult never lands in a global space: null). */
export function classifyGw(r: GwListItem): Exclude<GwSpace, 'ADULT'> | null {
  if (isAdultish(r)) return null;
  if (ANIME_RE.test(r.genre ?? '')) return 'ANIME';
  if (isSeriesWire(r) && DRAMA_COUNTRY_RE.test(r.country ?? '')) return 'DRAMA';
  return 'HOME';
}

export interface GwMapOptions {
  categoryId?: string;
  colorOf?: (poster: string) => string | undefined;
}

/** List row item → Item (null when unusable: no id/slug/poster, unplayable, unsafe, or 18+ outside adult space). */
export function mapGwItem(r: GwListItem, space: GwSpace, opts: GwMapOptions = {}): Item | null {
  if (!r.subject_id || !r.slug || !r.poster_url || !r.name) return null;
  if (r.has_resource !== true) return null;
  if (space === 'ADULT') {
    if (!safeName(r.name)) return null;
  } else if (isAdultish(r)) {
    return null;
  }
  const id = gwItemId(r.subject_id, space);
  const poster = r.poster_url;
  const tv = isSeriesWire(r);
  const dubs = extractDubLanguages({ name: r.name, badge: r.badge, slug: r.slug, dubs: r.dubs });
  const item: Item = {
    id,
    space,
    categoryId: opts.categoryId ?? `gw-${space.toLowerCase()}`,
    type: tv ? 'SERIES' : 'MOVIE',
    title: r.name,
    year: yearOf(r.year, r.release_date),
    rating: Math.round(num(r.rating) * 10) / 10,
    maturity: space === 'ADULT' ? '18+' : 'NR',
    genres: mergeGenresWithDubs(splitGenres(r.genre), dubs),
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
  if (space === 'ADULT' && !safeName(s.title)) return null;
  const slug = s.detailPath || opts.slug;
  if (!slug) return null;
  const id = gwItemId(s.subjectId, space);
  const poster = s.cover?.url || s.stills?.url || '';
  if (!poster) return null;
  const backdrop = s.stills?.url || poster;
  const rawSeasons = d.data?.resource?.seasons ?? [];
  const tv = s.subjectType === 2 || s.subjectType === 7 || rawSeasons.some((x) => (x.se || x.season || 0) > 0 && (x.maxEp > 0 || (x.episodes?.length ?? 0) > 0));
  const dubs = extractDubLanguages({ name: s.title, badge: s.corner, slug, dubs: s.dubs });
  const item: Item = {
    id,
    space,
    categoryId: opts.categoryId ?? `gw-${space.toLowerCase()}`,
    type: tv ? 'SERIES' : 'MOVIE',
    title: s.title,
    year: yearOf(undefined, s.releaseDate),
    rating: Math.round(num(s.imdbRatingValue) * 10) / 10,
    maturity: space === 'ADULT' ? '18+' : 'NR',
    genres: mergeGenresWithDubs(splitGenres(s.genre), dubs),
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
  const normalized = rawSeasons
    .map((x) => {
      const seNum = x.se || x.season || 0;
      const fromObjs = (x.episodes ?? []).map((e) => e.episode ?? 0).filter((n) => n > 0);
      const fromListed = (x.allEp ?? '')
        .split(',')
        .map((p) => parseInt(p.trim(), 10))
        .filter((n) => Number.isFinite(n) && n > 0);
      const epNums = fromObjs.length
        ? fromObjs
        : fromListed.length
          ? fromListed
          : Array.from({ length: Math.max(0, x.maxEp || 0) }, (_, i) => i + 1);
      const detailByNum = new Map<number, GwEpisodeWire>();
      for (const epObj of x.episodes ?? []) {
        if (epObj.episode && epObj.episode > 0) detailByNum.set(epObj.episode, epObj);
      }
      return { se: seNum, epNums, detailByNum };
    })
    .filter((x) => x.se > 0 && x.epNums.length > 0)
    .sort((a, b) => a.se - b.se);

  const effectiveSeasons = normalized.length ? normalized : [{ se: 1, epNums: [1], detailByNum: new Map<number, GwEpisodeWire>() }];
  const seasons: Season[] = effectiveSeasons.map((x) => ({
    n: x.se,
    title: `Season ${x.se}`,
    episodeCount: x.epNums.length,
    episodes: x.epNums.map((epNum): Episode => {
      const epMeta = x.detailByNum.get(epNum);
      return {
        id: gwEpisodeId(id, x.se, epNum),
        n: epNum,
        title: epMeta?.name?.trim() || `Episode ${epNum}`,
        durationSec: 0,
        still: epMeta?.stillUrl || epMeta?.still_url || epMeta?.stillPath || backdrop,
        sources: [streamPlaceholder(s.subjectId!, slug, x.se, epNum)],
        subtitles: []
      };
    })
  }));
  item.seasons = seasons;
  return item;
}

// ------------------------------------------------------------------------------------ plan & shelves

export interface GwListSpec {
  kind: 'list';
  id: string;
  title: string;
  style: RowStyle;
  path: string;
  params: Record<string, string>;
}
/** Rows taken from `/home` sections (by name, majority type, or 'all' = every curated section in order). */
export interface GwSectionSpec {
  kind: 'sections';
  match: RegExp | 'movie' | 'tv' | 'all';
  /** Max rows produced. */
  limit: number;
}
export interface GwCategorySpec {
  id: string;
  title: string;
  /** Hero: 'banner' (+ optional type filter) or the first row's items. */
  hero: 'banner' | 'banner-movie' | 'banner-tv' | 'first';
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

/**
 * Port of Cinetor Beta `MovieBoxCatalog.sectionShelf`: maps curated `/home` section headings to
 * their closest pageable gateway endpoint so horizontal rows and "See all" paginate endlessly.
 */
export function sectionMore(heading: string): RowMore {
  const h = heading.toLowerCase();
  const mk = (path: string, params: Record<string, string>): RowMore => ({ path, params, page: 0, hasMore: true });
  if (h.includes('short tv') || h.includes('short drama')) return mk('/short-tv', { sort: 'hottest' });
  if (h.includes('zee bangla')) return mk('/tv-series', { sort: 'latest', genre: 'Zee Bangla', country: 'India' });
  if (h.includes('star jalsha') || h.includes('star jalsa')) return mk('/tv-series', { sort: 'latest', genre: 'Star Jalsha', country: 'India' });
  if (h.includes('sony aath') || h.includes('sony ath')) return mk('/tv-series', { sort: 'latest', genre: 'Sony AATH', country: 'India' });
  if (h.includes('pakistan')) return mk('/tv-series', { sort: 'hottest', country: 'Pakistan' });
  if (h.includes('indian drama')) return mk('/tv-series', { sort: 'hottest', country: 'India' });
  if (h.includes('bollywood') || (h.includes('indian') && !h.includes('drama'))) return mk('/movies', { sort: 'hottest', country: 'India' });
  if (h.includes('south')) return mk('/movies', { sort: 'hottest', country: 'India' });
  if (h.includes('hollywood')) return mk('/movies', { sort: 'hottest', country: 'United States' });
  if (h.includes('k-drama') || h.includes('korean')) return mk('/tv-series', { sort: 'hottest', country: 'Korea' });
  if (h.includes('c-drama') || h.includes('chinese')) return mk('/tv-series', { sort: 'hottest', country: 'China' });
  if (h.includes('western')) return mk('/tv-series', { sort: 'hottest', country: 'United States' });
  if (h.includes('series') || h.includes(' tv') || h.includes('shows')) return mk('/tv-series', { sort: 'hottest' });
  if (h.includes('coming') || h.includes('new') || h.includes('latest')) return mk('/movies', { sort: 'latest' });
  if (h.includes('horror')) return mk('/movies', { sort: 'hottest', genre: 'Horror' });
  if (h.includes('action')) return mk('/movies', { sort: 'hottest', genre: 'Action' });
  if (h.includes('comedy')) return mk('/movies', { sort: 'hottest', genre: 'Comedy' });
  if (h.includes('romance')) return mk('/movies', { sort: 'hottest', genre: 'Romance' });
  return mk('/movies', { sort: 'hottest' });
}

export function gatewayPlan(space: GwSpace): GwCategorySpec[] {
  switch (space) {
    case 'HOME':
      return [
        {
          id: 'gw-home',
          title: 'For You',
          hero: 'banner',
          rows: [
            { kind: 'sections', match: 'all', limit: 40 },
            list('gw-home-short-tv', '🔥 Hot Short TV', '/short-tv', { sort: 'hottest' }),
            list('gw-home-zee-bangla-serials', 'Zee Bangla Serials', '/zee-bangla'),
            list('gw-home-zee-bangla', 'Zee Bangla Shows', '/tv-series', { sort: 'latest', genre: 'Zee Bangla', country: 'India' }),
            list('gw-home-star-jalsha', 'Star Jalsha Shows', '/tv-series', { sort: 'latest', genre: 'Star Jalsha', country: 'India' }),
            list('gw-home-sony-aath', 'Sony AATH Series', '/tv-series', { sort: 'latest', genre: 'Sony AATH', country: 'India' }),
            list('gw-home-indian-dramas', 'Indian Dramas', '/tv-series', { sort: 'hottest', country: 'India' }),
            list('gw-home-latest-movies', 'Latest Movies', '/movies', { sort: 'latest' }),
            list('gw-home-latest-series', 'Latest Series', '/tv-series', { sort: 'latest' }),
            list('gw-home-hollywood', 'Hollywood', '/movies', { sort: 'hottest', country: 'United States' }),
            list('gw-home-action', 'Action', '/movies', { sort: 'hottest', genre: 'Action' }),
            list('gw-home-comedy', 'Comedy', '/movies', { sort: 'hottest', genre: 'Comedy' }),
            list('gw-home-horror', 'Horror', '/movies', { sort: 'hottest', genre: 'Horror' }),
            list('gw-home-thriller', 'Thriller', '/movies', { sort: 'hottest', genre: 'Thriller' }),
            list('gw-home-crime-series', 'Crime Series', '/tv-series', { sort: 'hottest', genre: 'Crime' })
          ]
        },
        {
          id: 'gw-movies',
          title: 'Movies',
          hero: 'banner-movie',
          rows: [
            list('gw-movies-latest', 'Latest Movies', '/movies', { sort: 'latest' }),
            list('gw-movies-hollywood', 'Hollywood', '/movies', { sort: 'hottest', country: 'United States' }),
            list('gw-movies-action', 'Action', '/movies', { sort: 'hottest', genre: 'Action' }),
            list('gw-movies-comedy', 'Comedy', '/movies', { sort: 'hottest', genre: 'Comedy' }),
            list('gw-movies-horror', 'Horror', '/movies', { sort: 'hottest', genre: 'Horror' }),
            list('gw-movies-thriller', 'Thriller', '/movies', { sort: 'hottest', genre: 'Thriller' }),
            { kind: 'sections', match: 'movie', limit: 12 }
          ]
        },
        {
          id: 'gw-series',
          title: 'Series',
          hero: 'banner-tv',
          rows: [
            list('gw-series-latest', 'Latest Series', '/tv-series', { sort: 'latest' }),
            list('gw-series-crime', 'Crime Series', '/tv-series', { sort: 'hottest', genre: 'Crime' }),
            list('gw-series-indian', 'Indian Dramas', '/tv-series', { sort: 'hottest', country: 'India' }),
            list('gw-series-zee-bangla', 'Zee Bangla Shows', '/tv-series', { sort: 'latest', genre: 'Zee Bangla', country: 'India' }),
            list('gw-series-star-jalsha', 'Star Jalsha Shows', '/tv-series', { sort: 'latest', genre: 'Star Jalsha', country: 'India' }),
            list('gw-series-sony-aath', 'Sony AATH Series', '/tv-series', { sort: 'latest', genre: 'Sony AATH', country: 'India' }),
            { kind: 'sections', match: 'tv', limit: 12 }
          ]
        },
        {
          id: 'gw-short-tv',
          title: 'Short TV',
          hero: 'first',
          rows: [
            list('gw-short-tv-latest', 'Trending Short TV', '/short-tv'),
            list('gw-short-tv-hot', '🔥 Hot Short TV', '/short-tv', { sort: 'hottest' }),
            { kind: 'sections', match: /short (drama|tv)/i, limit: 2 }
          ]
        },
        {
          id: 'gw-zee-bangla',
          title: 'Zee Bangla',
          hero: 'first',
          rows: [
            list('gw-zee-bangla-latest', 'Zee Bangla Serials', '/zee-bangla'),
            list('gw-zee-bangla-shows', 'Zee Bangla Shows', '/tv-series', { sort: 'latest', genre: 'Zee Bangla', country: 'India' }),
            list('gw-zee-bangla-jalsha', 'Star Jalsha Shows', '/tv-series', { sort: 'latest', genre: 'Star Jalsha', country: 'India' }),
            list('gw-zee-bangla-aath', 'Sony AATH Series', '/tv-series', { sort: 'latest', genre: 'Sony AATH', country: 'India' }),
            { kind: 'sections', match: /zee ?bangla|indian drama/i, limit: 2 }
          ]
        }
      ];
    case 'ANIME':
      return [
        {
          id: 'gw-anime',
          title: 'All',
          hero: 'first',
          rows: [
            list('gw-anime-trending', 'Trending Anime', '/tv-series', { sort: 'hottest', genre: 'Anime' }),
            list('gw-anime-latest', 'Latest Anime', '/tv-series', { sort: 'latest', genre: 'Anime' }),
            list('gw-anime-rating', 'Top Rated Anime', '/tv-series', { sort: 'rating', genre: 'Anime' }),
            list('gw-anime-movies', 'Animated Movies', '/movies', { sort: 'hottest', genre: 'Animation' }),
            list('gw-anime-cartoons', 'Cartoons', '/animation', { sort: 'hottest' }),
            { kind: 'sections', match: /anime/i, limit: 3 }
          ]
        },
        {
          id: 'gw-anime-series-tab',
          title: 'Anime Series',
          hero: 'first',
          rows: [
            list('gw-anime-s-trending', 'Trending Anime', '/tv-series', { sort: 'hottest', genre: 'Anime' }),
            list('gw-anime-s-latest', 'Latest Anime', '/tv-series', { sort: 'latest', genre: 'Anime' }),
            list('gw-anime-s-rating', 'Top Rated Anime', '/tv-series', { sort: 'rating', genre: 'Anime' })
          ]
        },
        {
          id: 'gw-anime-movies-tab',
          title: 'Animated Movies',
          hero: 'first',
          rows: [
            list('gw-anime-m-hot', 'Animated Movies', '/movies', { sort: 'hottest', genre: 'Animation' }),
            list('gw-anime-m-latest', 'Latest Animated Movies', '/movies', { sort: 'latest', genre: 'Animation' })
          ]
        },
        {
          id: 'gw-anime-cartoons-tab',
          title: 'Cartoons',
          hero: 'first',
          rows: [
            list('gw-anime-c-hot', 'Cartoons', '/animation', { sort: 'hottest' }),
            list('gw-anime-c-latest', 'Latest Cartoons', '/animation', { sort: 'latest' })
          ]
        },
        {
          id: 'gw-anime-action',
          title: 'Action',
          hero: 'first',
          rows: [list('gw-anime-action-r', 'Action & Adventure', '/animation', { sort: 'hottest', genre: 'Action' })]
        },
        {
          id: 'gw-anime-comedy',
          title: 'Comedy',
          hero: 'first',
          rows: [list('gw-anime-comedy-r', 'Comedy', '/animation', { sort: 'hottest', genre: 'Comedy' })]
        }
      ];
    case 'DRAMA':
      return [
        {
          id: 'gw-drama-all',
          title: 'All',
          hero: 'first',
          rows: [
            list('gw-dr-k-trending', 'Trending K-Drama', '/tv-series', { sort: 'hottest', country: 'Korea' }),
            list('gw-dr-k-latest', 'Latest K-Drama', '/tv-series', { sort: 'latest', country: 'Korea' }),
            list('gw-dr-k-rating', 'Top Rated K-Drama', '/tv-series', { sort: 'rating', country: 'Korea' }),
            list('gw-dr-k-romance', 'Korean Romance', '/tv-series', { sort: 'hottest', genre: 'Romance', country: 'Korea' }),
            list('gw-dr-k-movies', 'Korean Movies', '/movies', { sort: 'hottest', country: 'Korea' }),
            list('gw-dr-c-trending', 'Trending C-Drama', '/tv-series', { sort: 'hottest', country: 'China' }),
            list('gw-dr-c-latest', 'Latest C-Drama', '/tv-series', { sort: 'latest', country: 'China' }),
            list('gw-dr-c-rating', 'Top Rated C-Drama', '/tv-series', { sort: 'rating', country: 'China' }),
            list('gw-dr-c-costume', 'Costume & Historical', '/tv-series', { sort: 'hottest', genre: 'History', country: 'China' }),
            list('gw-dr-c-romance', 'Chinese Romance', '/tv-series', { sort: 'hottest', genre: 'Romance', country: 'China' }),
            list('gw-dr-c-movies', 'Chinese Movies', '/movies', { sort: 'hottest', country: 'China' }),
            list('gw-dr-j-trending', 'Trending J-Drama', '/tv-series', { sort: 'hottest', country: 'Japan' }),
            list('gw-dr-j-latest', 'Latest J-Drama', '/tv-series', { sort: 'latest', country: 'Japan' }),
            list('gw-dr-j-movies', 'Japanese Movies', '/movies', { sort: 'hottest', country: 'Japan' }),
            list('gw-dr-pk-trending', 'Trending Pakistani Drama', '/tv-series', { sort: 'hottest', country: 'Pakistan' }),
            list('gw-dr-pk-best', 'All-Time Best Pakistani Drama', '/tv-series', { sort: 'rating', country: 'Pakistan' }),
            list('gw-dr-in-trending', 'Indian Dramas', '/tv-series', { sort: 'hottest', country: 'India' }),
            list('gw-dr-th-trending', 'Thai Dramas', '/tv-series', { sort: 'hottest', country: 'Thailand' }),
            list('gw-dr-tw-trending', 'Taiwanese Dramas', '/tv-series', { sort: 'hottest', country: 'Taiwan' }),
            list('gw-dr-other-asian', 'Other Asian Dramas', '/tv-series', { sort: 'latest', country: 'Thailand' })
          ]
        },
        {
          id: 'gw-kdrama',
          title: 'K-Drama',
          hero: 'first',
          rows: [
            list('gw-kdrama-trending', 'Trending K-Drama', '/tv-series', { sort: 'hottest', country: 'Korea' }),
            list('gw-kdrama-latest', 'Latest K-Drama', '/tv-series', { sort: 'latest', country: 'Korea' }),
            list('gw-kdrama-rating', 'Top Rated K-Drama', '/tv-series', { sort: 'rating', country: 'Korea' }),
            list('gw-kdrama-romance', 'Korean Romance', '/tv-series', { sort: 'hottest', genre: 'Romance', country: 'Korea' }),
            list('gw-kdrama-movies', 'Korean Movies', '/movies', { sort: 'hottest', country: 'Korea' }),
            { kind: 'sections', match: /k-?drama/i, limit: 2 }
          ]
        },
        {
          id: 'gw-cdrama',
          title: 'C-Drama',
          hero: 'first',
          rows: [
            list('gw-cdrama-trending', 'Trending C-Drama', '/tv-series', { sort: 'hottest', country: 'China' }),
            list('gw-cdrama-latest', 'Latest C-Drama', '/tv-series', { sort: 'latest', country: 'China' }),
            list('gw-cdrama-rating', 'Top Rated C-Drama', '/tv-series', { sort: 'rating', country: 'China' }),
            list('gw-cdrama-costume', 'Costume & Historical', '/tv-series', { sort: 'hottest', genre: 'History', country: 'China' }),
            list('gw-cdrama-romance', 'Chinese Romance', '/tv-series', { sort: 'hottest', genre: 'Romance', country: 'China' }),
            list('gw-cdrama-movies', 'Chinese Movies', '/movies', { sort: 'hottest', country: 'China' }),
            { kind: 'sections', match: /c-?drama/i, limit: 2 }
          ]
        },
        {
          id: 'gw-jdrama',
          title: 'J-Drama',
          hero: 'first',
          rows: [
            list('gw-jdrama-trending', 'Trending J-Drama', '/tv-series', { sort: 'hottest', country: 'Japan' }),
            list('gw-jdrama-latest', 'Latest J-Drama', '/tv-series', { sort: 'latest', country: 'Japan' }),
            list('gw-jdrama-romance', 'Japanese Romance', '/tv-series', { sort: 'hottest', genre: 'Romance', country: 'Japan' }),
            list('gw-jdrama-movies', 'Japanese Movies', '/movies', { sort: 'hottest', country: 'Japan' })
          ]
        },
        {
          id: 'gw-pkdrama',
          title: 'Pakistani Drama',
          hero: 'first',
          rows: [
            list('gw-pkdrama-trending', 'Trending Pakistani Drama', '/tv-series', { sort: 'hottest', country: 'Pakistan' }),
            list('gw-pkdrama-best', 'All-Time Best Pakistani Drama', '/tv-series', { sort: 'rating', country: 'Pakistan' }),
            list('gw-pkdrama-latest', 'Latest Pakistani Drama', '/tv-series', { sort: 'latest', country: 'Pakistan' })
          ]
        },
        {
          id: 'gw-indrama',
          title: 'Indian Drama',
          hero: 'first',
          rows: [
            list('gw-indrama-trending', 'Indian Dramas', '/tv-series', { sort: 'hottest', country: 'India' }),
            list('gw-indrama-latest', 'Latest Indian Dramas', '/tv-series', { sort: 'latest', country: 'India' }),
            list('gw-indrama-zeebangla', 'Zee Bangla Serials', '/zee-bangla'),
            list('gw-indrama-zb-shows', 'Zee Bangla Shows', '/tv-series', { sort: 'latest', genre: 'Zee Bangla', country: 'India' }),
            list('gw-indrama-jalsha', 'Star Jalsha Shows', '/tv-series', { sort: 'latest', genre: 'Star Jalsha', country: 'India' })
          ]
        },
        {
          id: 'gw-drama-others',
          title: 'Others',
          hero: 'first',
          rows: [
            list('gw-others-thai', 'Thai Dramas', '/tv-series', { sort: 'hottest', country: 'Thailand' }),
            list('gw-others-taiwan', 'Taiwanese Dramas', '/tv-series', { sort: 'hottest', country: 'Taiwan' }),
            list('gw-others-asian', 'Other Asian Dramas', '/tv-series', { sort: 'latest', country: 'Thailand' })
          ]
        }
      ];
    case 'ADULT':
      // First two ids match the adult space nav tabs (`ad-featured` = Movies, `ad-series` = Series).
      return [
        {
          id: 'ad-featured',
          title: 'Movies',
          hero: 'first',
          rows: [
            list('ad-gw-movies-latest', 'Latest', '/adult', { kind: 'movies', sort: 'latest' }),
            list('ad-gw-movies-popular', 'Popular', '/adult', { kind: 'movies', sort: 'hottest' }),
            list('ad-gw-movies-rating', 'Top Rated', '/adult', { kind: 'movies', sort: 'rating' }),
            list('ad-gw-movies-ph', 'Filipino', '/adult', { kind: 'movies', sort: 'latest', country: 'Philippines' }),
            list('ad-gw-movies-kr', 'Korean', '/adult', { kind: 'movies', sort: 'latest', country: 'Korea' }),
            list('ad-gw-movies-jp', 'Japanese', '/adult', { kind: 'movies', sort: 'latest', country: 'Japan' }),
            list('ad-gw-movies-in', 'Indian', '/adult', { kind: 'movies', sort: 'latest', country: 'India' }),
            list('ad-gw-movies-us', 'Western', '/adult', { kind: 'movies', sort: 'latest', country: 'United States' }),
            list('ad-gw-movies-eu', 'European', '/adult', { kind: 'movies', sort: 'latest', country: 'France' })
          ]
        },
        {
          id: 'ad-series',
          title: 'Series',
          hero: 'first',
          rows: [
            list('ad-gw-series-latest', 'Latest Series', '/adult', { kind: 'tv-series', sort: 'latest' }),
            list('ad-gw-series-popular', 'Popular Series', '/adult', { kind: 'tv-series', sort: 'hottest' }),
            list('ad-gw-series-rating', 'Top Rated Series', '/adult', { kind: 'tv-series', sort: 'rating' }),
            list('ad-gw-series-ph', 'Filipino Series', '/adult', { kind: 'tv-series', sort: 'latest', country: 'Philippines' }),
            list('ad-gw-series-kr', 'Korean Series', '/adult', { kind: 'tv-series', sort: 'latest', country: 'Korea' }),
            list('ad-gw-series-jp', 'Japanese Series', '/adult', { kind: 'tv-series', sort: 'latest', country: 'Japan' }),
            list('ad-gw-series-in', 'Indian Series', '/adult', { kind: 'tv-series', sort: 'latest', country: 'India' }),
            list('ad-gw-series-us', 'Western Series', '/adult', { kind: 'tv-series', sort: 'latest', country: 'United States' })
          ]
        },
        {
          id: 'ad-popular',
          title: 'Popular',
          hero: 'first',
          rows: [
            list('ad-pop-movies', 'Popular Movies', '/adult', { kind: 'movies', sort: 'hottest' }),
            list('ad-pop-series', 'Popular Series', '/adult', { kind: 'tv-series', sort: 'hottest' })
          ]
        },
        {
          id: 'ad-top-rated',
          title: 'Top Rated',
          hero: 'first',
          rows: [
            list('ad-rate-movies', 'Top Rated Movies', '/adult', { kind: 'movies', sort: 'rating' }),
            list('ad-rate-series', 'Top Rated Series', '/adult', { kind: 'tv-series', sort: 'rating' })
          ]
        }
      ];
  }
}

const CANONICAL_CHIPS: Record<GwSpace, readonly string[]> = {
  HOME: ['Action', 'Comedy', 'Horror', 'Romance', 'Thriller', 'Crime', 'Sci-Fi', 'Drama', 'Adventure', 'Fantasy'],
  ANIME: ['Action', 'Adventure', 'Fantasy', 'Comedy', 'Romance', 'Horror', 'Sci-Fi'],
  DRAMA: ['Romance', 'Drama', 'Comedy', 'History', 'Thriller', 'Crime', 'Fantasy'],
  ADULT: []
};

/** First paged list of a category: genre chips query it with `genre=` (null: client-side filter only). */
export function genreBase(space: GwSpace, categoryId: string): { path: string; params: Record<string, string> } | null {
  if (space === 'ADULT') return null;
  const plan = gatewayPlan(space);
  const c = plan.find((x) => x.id === categoryId) ?? plan[0];
  const hasList = (x: GwCategorySpec | undefined) => x?.rows.some((r) => r.kind === 'list');
  const owner = hasList(c) ? c : plan.find(hasList);
  const l = owner?.rows.find((r): r is GwListSpec => r.kind === 'list');
  if (!l) return null;
  const { genre: _g, ...params } = l.params;
  void _g;
  return { path: l.path, params };
}

/** Special chip filter overrides matching Cinetor Beta `MovieBoxCatalog.filterPage`. */
function specialChipMore(space: GwSpace, categoryId: string, chip: string): { path: string; params: Record<string, string> } | null {
  switch (chip) {
    case 'Movies':
      return { path: '/movies', params: { sort: 'hottest' } };
    case 'Series':
      return { path: '/tv-series', params: { sort: 'hottest' } };
    case 'K-Drama':
      return { path: '/tv-series', params: { sort: 'hottest', country: 'Korea' } };
    case 'C-Drama':
      return { path: '/tv-series', params: { sort: 'hottest', country: 'China' } };
    case 'J-Drama':
      return { path: '/tv-series', params: { sort: 'hottest', country: 'Japan' } };
    case 'Pakistani Drama':
      return { path: '/tv-series', params: { sort: 'hottest', country: 'Pakistan' } };
    case 'Indian Drama':
      return { path: '/tv-series', params: { sort: 'hottest', country: 'India' } };
    case 'Others':
      return { path: '/tv-series', params: { sort: 'hottest', country: 'Thailand' } };
    case 'Costume':
    case 'Historical':
      return { path: '/tv-series', params: { sort: 'hottest', genre: 'History', country: 'China' } };
    default: {
      if (space === 'HOME' && categoryId === 'gw-home') {
        return { path: '/movies', params: { sort: 'hottest', genre: chip } };
      }
      const b = genreBase(space, categoryId);
      return b ? { path: b.path, params: { ...b.params, genre: chip } } : null;
    }
  }
}

// ------------------------------------------------------------------------------------ client

export type GwFetcher = (
  url: string,
  init?: { signal?: AbortSignal; headers?: Record<string, string> }
) => Promise<{ ok: boolean; status: number; json(): Promise<unknown> }>;

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

  /** Raw GET with timeout (no cache). Error message stays generic and host-free. */
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
  /** Optional 18+ lock check when called directly outside `Catalog`. */
  adultUnlocked?: () => boolean;
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

function listSpecKey(path: string, params: Record<string, string>): string {
  const entries = Object.entries(params)
    .filter(([, v]) => v !== '')
    .sort(([a], [b]) => a.localeCompare(b));
  return `${path}?${new URLSearchParams(entries).toString()}`;
}

function adultDedupKey(item: Item): string {
  return `${item.title.toLowerCase().replace(/[^a-z0-9]+/g, '')}|${item.type}`;
}

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

  private assertAdultAccess(space: GwSpace): void {
    if (space === 'ADULT' && this.o.adultUnlocked && !this.o.adultUnlocked()) {
      throw new Error('18+ is locked');
    }
  }

  cached(id: string): Item | undefined {
    if (isGatewayAdultId(id) && this.o.adultUnlocked && !this.o.adultUnlocked()) return undefined;
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
    const adultKeys = new Set<string>();
    for (const r of raw ?? []) {
      if (r.subject_id && this.o.isDead?.(r.subject_id)) continue;
      if (space === 'HOME' && isAnimeItem(r)) continue;
      if (space === 'DRAMA' && isAnimeItem(r) && !/\b(drama|romance)\b/i.test(r.genre ?? '')) continue;
      const it = mapGwItem(r, space, { categoryId, colorOf: this.o.colorOf });
      if (!it || ids.has(it.id)) continue;
      if (space === 'ADULT') {
        const ak = adultDedupKey(it);
        if (adultKeys.has(ak)) continue;
        adultKeys.add(ak);
      }
      ids.add(it.id);
      out.push(it);
    }
    return out;
  }

  file(space: GwSpace): Promise<CatalogFile> {
    this.assertAdultAccess(space);
    const cur = this.files.get(space);
    if (cur && Date.now() - cur.ts < 10 * 60_000) return cur.p;
    const p = this.build(space);
    p.catch(() => this.files.delete(space));
    this.files.set(space, { ts: Date.now(), p });
    return p;
  }

  private async build(space: GwSpace): Promise<CatalogFile> {
    this.assertAdultAccess(space);
    if (!this.enabled) throw new Error('Gateway off');
    const plan = gatewayPlan(space);
    const needsHome = space !== 'ADULT' && plan.some((c) => c.hero.startsWith('banner') || c.rows.some((r) => r.kind === 'sections'));
    const homeP = needsHome ? this.o.client.get<GwHome>('/home').catch(() => null) : Promise.resolve(null);

    const uniqueLists = new Map<string, GwListSpec>();
    for (const c of plan) {
      for (const r of c.rows) {
        if (r.kind === 'list') {
          const key = listSpecKey(r.path, r.params);
          if (!uniqueLists.has(key)) uniqueLists.set(key, r);
        }
      }
    }
    const entries = [...uniqueLists.entries()];
    const fetched = await Promise.all(
      entries.map(([key, spec]) =>
        this.o.client
          .get<GwPage>(spec.path, { ...spec.params, page: 1 })
          .then((pg) => [key, pg] as const)
          .catch(() => [key, null] as const)
      )
    );
    const pageByKey = new Map<string, GwPage | null>(fetched);

    const home = await homeP;
    const sections = (home?.sections ?? []).filter((s) => Array.isArray(s.items) && s.items.length);

    const items = new Map<string, Item>();
    const add = (listItems: Item[]) => {
      for (const i of listItems) if (!items.has(i.id)) items.set(i.id, i);
      return listItems.map((i) => i.id);
    };

    const categories: Category[] = [];
    const usedSections = new Set<string>();

    for (const c of plan) {
      const rows: Row[] = [];
      let curatedAdded = 0;
      for (const spec of c.rows) {
        if (spec.kind === 'list') {
          const pg = pageByKey.get(listSpecKey(spec.path, spec.params));
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
          if (space === 'HOME' && HOME_SKIP_RE.test(s.section)) continue;
          const raw = s.items ?? [];
          let ok: boolean;
          if (spec.match === 'all') ok = true;
          else if (spec.match instanceof RegExp) ok = spec.match.test(s.section);
          else {
            const same = raw.filter((x) => String(x.type ?? '').toLowerCase() === spec.match).length;
            ok = same * 2 > raw.length && !/anime|k-?drama|c-?drama/i.test(s.section);
          }
          if (!ok) continue;
          const mapped = this.map(raw, space, c.id);
          if (!mapped.length) continue;
          usedSections.add(`${c.id}|${s.section}`);
          const isTopChart =
            (space === 'HOME' && c.id === 'gw-home' && curatedAdded === 0) || /^\W*(?:popular|top\s*10)/i.test(s.section);
          rows.push({
            id: `${c.id}-sec-${s.section.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')}`,
            title: s.section.trim(),
            style: isTopChart ? 'TOP10' : 'POSTER',
            itemIds: add(mapped.slice(0, isTopChart ? 10 : ROW_STYLE_LIMIT)),
            ...(isTopChart ? {} : { more: sectionMore(s.section) })
          });
          curatedAdded++;
          n++;
        }
      }
      if (!rows.length) continue;
      let heroIds: string[] = [];
      if (c.hero !== 'first') {
        const banner = sections.find((s) => /^banner$/i.test(s.section));
        const want = c.hero === 'banner-movie' ? 'MOVIE' : c.hero === 'banner-tv' ? 'SERIES' : null;
        const mapped = this.map(banner?.items, space, c.id);
        const typed = want ? mapped.filter((i) => i.type === want) : mapped;
        heroIds = add((typed.length >= 3 ? typed : mapped).slice(0, want ? 6 : 10));
      }
      if (!heroIds.length) heroIds = rows[0].itemIds.slice(0, 6);
      categories.push({ id: c.id, space, title: c.title, heroIds, rows });
    }

    if (!categories.length) throw new Error('Catalog unavailable');
    const all = [...items.values()];
    this.remember(all);

    const dubSet = new Set<string>(DUB_LANGUAGES.map((d) => d.toLowerCase()));
    const discovered = [...new Set(all.flatMap((i) => i.genres).filter((g) => !dubSet.has(g.toLowerCase())))];
    const canonical = CANONICAL_CHIPS[space];
    const orderedGenres = [
      ...canonical.filter((c) => discovered.some((g) => g.toLowerCase() === c.toLowerCase())),
      ...discovered.filter((g) => !canonical.some((c) => c.toLowerCase() === g.toLowerCase())).sort()
    ];

    return { space, version: 1, genres: orderedGenres, categories, items: all };
  }

  /** Next page of a paged row (infinite rows). */
  async morePage(space: GwSpace, more: RowMore, categoryId = `gw-${space.toLowerCase()}`, signal?: AbortSignal): Promise<PageResult> {
    this.assertAdultAccess(space);
    const page = more.page + 1;
    const pg = await this.o.client.get<GwPage>(more.path, { ...more.params, page }, signal);
    const items = this.map(pg.items, space, categoryId);
    this.remember(items);
    return { items, more: { ...more, page: pg.page ?? page, hasMore: !!pg.has_more && items.length > 0 } };
  }

  /** Endless `rowPage` pagination for both explicit browse shelves and curated `/home` section rows. */
  async rowPage(
    space: GwSpace,
    row: Pick<Row, 'id' | 'title' | 'more'>,
    categoryId = `gw-${space.toLowerCase()}`,
    signal?: AbortSignal
  ): Promise<PageResult> {
    const cursor = row.more ?? sectionMore(row.title);
    return this.morePage(space, cursor, categoryId, signal);
  }

  /** Genre/filter chip → server-side `filterPage` list of the space/category (page 1). */
  async genrePage(space: GwSpace, categoryId: string, genre: string, signal?: AbortSignal): Promise<PageResult | null> {
    if (space === 'ADULT') return null;
    const spec = specialChipMore(space, categoryId, genre);
    if (!spec) return null;
    if (genre === 'Others' && space === 'DRAMA') {
      const [th, tw] = await Promise.all([
        this.morePage(space, { path: '/tv-series', params: { sort: 'hottest', country: 'Thailand' }, page: 0, hasMore: true }, categoryId, signal).catch(() => null),
        this.morePage(space, { path: '/tv-series', params: { sort: 'hottest', country: 'Taiwan' }, page: 0, hasMore: true }, categoryId, signal).catch(() => null)
      ]);
      const combined = [...(th?.items ?? []), ...(tw?.items ?? [])];
      const uniq = [...new Map(combined.map((i) => [i.id, i])).values()];
      return {
        items: uniq,
        more: th?.more ?? tw?.more ?? { path: '/tv-series', params: { sort: 'hottest', country: 'Thailand' }, page: 1, hasMore: false }
      };
    }
    const res = await this.morePage(space, { path: spec.path, params: spec.params, page: 0, hasMore: true }, categoryId, signal);
    if (space === 'ANIME') {
      return { ...res, items: res.items.filter((i) => isAnimeItem({ genres: i.genres })) };
    }
    return res;
  }

  item(id: string): Promise<Item | undefined> {
    if (isGatewayAdultId(id) && this.o.adultUnlocked && !this.o.adultUnlocked()) {
      return Promise.resolve(undefined);
    }
    let p = this.details.get(id);
    if (!p) {
      p = this.loadDetail(id);
      p.catch(() => this.details.delete(id));
      this.details.set(id, p);
    }
    return p;
  }

  private async loadDetail(id: string): Promise<Item | undefined> {
    const adultId = isGatewayAdultId(id);
    if (adultId && this.o.adultUnlocked && !this.o.adultUnlocked()) return undefined;
    const slug = await this.slugOf(id);
    const known = this.seen.get(id);
    if (!slug) return known;
    const d = await this.o.client.get<GwDetail>(`/detail/${encodeURIComponent(slug)}`);
    const subj = d.data?.subject;
    if (!adultId && subj && isAdultish({ genre: subj.genre, badge: subj.corner })) return undefined;
    if (adultId && subj && !safeName(subj.title)) return undefined;
    const space: GwSpace = adultId
      ? 'ADULT'
      : (known?.space as GwSpace | undefined) ??
        classifyGw({ genre: subj?.genre, country: subj?.countryName, type: subj?.subjectType === 2 ? 'tv' : 'movie' }) ??
        'HOME';
    const it = mapGwDetail(d, space, { slug, categoryId: known?.categoryId, colorOf: this.o.colorOf });
    if (!it) return known;
    if (it.id !== id) return known;
    if (known) {
      if (!it.synopsis) it.synopsis = known.synopsis;
      const knownDubs = known.genres.filter((g) => DUB_LANGUAGES.some((dl) => dl.toLowerCase() === g.toLowerCase()));
      it.genres = mergeGenresWithDubs(it.genres, knownDubs);
    }
    this.seen.set(id, { ...known, ...it });
    return it;
  }

  /** `/search` → Home/Anime/Drama items (18+ strictly dropped). */
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

  /** Gated in-space 18+ search (only when unlocked, filtered through `safeName`, `ad-gw:` ids). */
  async adultSearch(query: string, signal?: AbortSignal): Promise<Item[]> {
    this.assertAdultAccess('ADULT');
    const q = query.trim();
    if (!q) return [];
    const pg = await this.o.client.get<GwPage>('/search', { q, page: 1 }, signal);
    const out: Item[] = [];
    for (const r of pg.items ?? []) {
      if (!isAdultish(r) || (r.subject_id && this.o.isDead?.(r.subject_id))) continue;
      const it = mapGwItem(r, 'ADULT', { colorOf: this.o.colorOf });
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
