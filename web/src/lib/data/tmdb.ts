// TMDB metadata source for HOME / ANIME / DRAMA only (mirror of Android Task 2.0).
// Pure TS (no $app / $env imports) so Vitest can drive it with a mock fetch.
// include_adult=false on every request; adult results are dropped again client-side.
import { fallbackColor } from './color';
import type { PersistentCache } from './idb';
import { TMDB_PREFIX } from './images';
import type { CatalogFile, Category, Episode, Item, Row, RowStyle, Season } from './types';

export const TMDB_API = 'https://api.themoviedb.org/3';
export const TMDB_ATTRIBUTION = 'This product uses the TMDB API but is not endorsed or certified by TMDB.';

export type VideoSpace = 'HOME' | 'ANIME' | 'DRAMA';
export type Media = 'movie' | 'tv';

export type Fetcher = (
  url: string,
  init?: { headers?: Record<string, string>; signal?: AbortSignal }
) => Promise<{ ok: boolean; status: number; json(): Promise<unknown> }>;

export interface TmdbResult {
  id: number;
  media_type?: 'movie' | 'tv' | 'person';
  title?: string;
  name?: string;
  original_title?: string;
  original_name?: string;
  original_language?: string;
  origin_country?: string[];
  release_date?: string;
  first_air_date?: string;
  vote_average?: number;
  vote_count?: number;
  popularity?: number;
  genre_ids?: number[];
  overview?: string;
  poster_path?: string | null;
  backdrop_path?: string | null;
  adult?: boolean;
}

export interface TmdbPage {
  page?: number;
  results: TmdbResult[];
}

interface TmdbGenre {
  id: number;
  name: string;
}

interface TmdbDetails extends TmdbResult {
  genres?: TmdbGenre[];
  runtime?: number | null;
  episode_run_time?: number[];
  number_of_seasons?: number;
  seasons?: { season_number: number; name?: string; episode_count?: number }[];
  production_companies?: { name: string }[];
  networks?: { name: string }[];
  credits?: { cast?: { name: string }[] };
  recommendations?: TmdbPage;
  similar?: TmdbPage;
  release_dates?: { results?: { iso_3166_1: string; release_dates: { certification?: string }[] }[] };
  content_ratings?: { results?: { iso_3166_1: string; rating?: string }[] };
}

interface TmdbSeason {
  season_number: number;
  episodes?: {
    id: number;
    episode_number: number;
    name?: string;
    overview?: string;
    runtime?: number | null;
    still_path?: string | null;
  }[];
}

export const MOVIE_GENRES: Record<number, string> = {
  28: 'Action', 12: 'Adventure', 16: 'Animation', 35: 'Comedy', 80: 'Crime', 99: 'Documentary', 18: 'Drama',
  10751: 'Family', 14: 'Fantasy', 36: 'History', 27: 'Horror', 10402: 'Music', 9648: 'Mystery', 10749: 'Romance',
  878: 'Sci-Fi', 10770: 'TV Movie', 53: 'Thriller', 10752: 'War', 37: 'Western'
};

export const TV_GENRES: Record<number, string> = {
  10759: 'Action & Adventure', 16: 'Animation', 35: 'Comedy', 80: 'Crime', 99: 'Documentary', 18: 'Drama',
  10751: 'Family', 10762: 'Kids', 9648: 'Mystery', 10763: 'News', 10764: 'Reality', 10765: 'Sci-Fi & Fantasy',
  10766: 'Soap', 10767: 'Talk', 10768: 'War & Politics', 37: 'Western'
};

export const ANIME_GENRE = 16;
export const DRAMA_COUNTRIES = ['KR', 'CN', 'JP'] as const;
const DRAMA_LANGS = ['ko', 'zh', 'ja', 'cn'];
/** Excluded from Drama: animation, reality, talk (pipe = OR in TMDB). */
const DRAMA_WITHOUT = '16|10764|10767';

// ---------------------------------------------------------------------------------- ids

const SLUG: Record<VideoSpace, string> = { HOME: 'home', ANIME: 'anime', DRAMA: 'drama' };

export const isTmdbId = (id: string) => id.startsWith('tmdb-');

export function tmdbItemId(space: VideoSpace, media: Media, id: number): string {
  return `tmdb-${SLUG[space]}-${media}-${id}`;
}

export function parseTmdbId(id: string): { space: VideoSpace; media: Media; id: number } | null {
  const m = /^tmdb-(home|anime|drama)-(movie|tv)-(\d+)$/.exec(id);
  if (!m) return null;
  const space = (Object.keys(SLUG) as VideoSpace[]).find((s) => SLUG[s] === m[1])!;
  return { space, media: m[2] as Media, id: Number(m[3]) };
}

// ---------------------------------------------------------------------------------- query plan

export interface RowSpec {
  id: string;
  title: string;
  style: RowStyle;
  media: Media;
  path: string;
  params: Record<string, string | number>;
}

export interface CategorySpec {
  id: string;
  title: string;
  rows: RowSpec[];
}

type P = Record<string, string | number>;

/** Category/row → endpoint mapping per space. `today` = 'YYYY-MM-DD' (injectable for tests). */
export function tmdbPlan(space: VideoSpace, today: string): CategorySpec[] {
  const row = (cat: string, n: number, title: string, style: RowStyle, media: Media, path: string, params: P = {}): RowSpec => ({
    id: `tmdb-${cat}-r${n}`,
    title,
    style,
    media,
    path,
    params
  });

  if (space === 'HOME') {
    const dm = (g: number): P => ({ with_genres: g, sort_by: 'popularity.desc', 'vote_count.gte': 100 });
    const dt = (g: number): P => ({ with_genres: g, sort_by: 'popularity.desc', 'vote_count.gte': 50, without_genres: '16' });
    return [
      {
        id: 'home-movies',
        title: 'Movies',
        rows: [
          row('home-movies', 1, 'Trending Now', 'POSTER', 'movie', '/trending/movie/week'),
          row('home-movies', 2, 'Top 10 Movies', 'TOP10', 'movie', '/movie/popular'),
          row('home-movies', 3, 'New Releases', 'LANDSCAPE', 'movie', '/movie/now_playing'),
          row('home-movies', 4, 'Top Rated', 'POSTER', 'movie', '/movie/top_rated'),
          row('home-movies', 5, 'Coming Soon', 'LANDSCAPE', 'movie', '/movie/upcoming'),
          row('home-movies', 6, 'Action Packed', 'POSTER', 'movie', '/discover/movie', dm(28)),
          row('home-movies', 7, 'Sci-Fi Worlds', 'POSTER', 'movie', '/discover/movie', dm(878)),
          row('home-movies', 8, 'Edge-of-Seat Thrillers', 'POSTER', 'movie', '/discover/movie', dm(53))
        ]
      },
      {
        id: 'home-series',
        title: 'Series',
        rows: [
          row('home-series', 1, 'Trending Now', 'POSTER', 'tv', '/trending/tv/week'),
          row('home-series', 2, 'Top 10 Series', 'TOP10', 'tv', '/tv/popular'),
          row('home-series', 3, 'New Episodes', 'LANDSCAPE', 'tv', '/tv/on_the_air'),
          row('home-series', 4, 'Top Rated', 'POSTER', 'tv', '/tv/top_rated'),
          row('home-series', 5, 'Airing Today', 'LANDSCAPE', 'tv', '/tv/airing_today'),
          row('home-series', 6, 'Crime & Mystery', 'POSTER', 'tv', '/discover/tv', dt(80)),
          row('home-series', 7, 'Binge-worthy Drama', 'POSTER', 'tv', '/discover/tv', dt(18))
        ]
      }
    ];
  }

  if (space === 'ANIME') {
    const a = (extra: P = {}, genres = `${ANIME_GENRE}`): P => ({
      with_genres: genres,
      with_origin_country: 'JP',
      sort_by: 'popularity.desc',
      ...extra
    });
    const top: P = { sort_by: 'vote_average.desc', 'vote_count.gte': 300 };
    return [
      {
        id: 'an-trending',
        title: 'Trending',
        rows: [
          row('an-trending', 1, 'Trending Now', 'POSTER', 'tv', '/discover/tv', a()),
          row('an-trending', 2, 'Top 10 Anime', 'TOP10', 'tv', '/discover/tv', a(top)),
          row('an-trending', 3, 'New Releases', 'LANDSCAPE', 'tv', '/discover/tv', a({ sort_by: 'first_air_date.desc', 'first_air_date.lte': today, 'vote_count.gte': 20 })),
          row('an-trending', 4, 'Anime Movies', 'POSTER', 'movie', '/discover/movie', a())
        ]
      },
      {
        id: 'an-action',
        title: 'Action',
        rows: [
          row('an-action', 1, 'Big Battles', 'POSTER', 'tv', '/discover/tv', a({}, '16,10759')),
          row('an-action', 2, 'Top 10 Action', 'TOP10', 'tv', '/discover/tv', a(top, '16,10759'))
        ]
      },
      {
        id: 'an-fantasy',
        title: 'Fantasy',
        rows: [
          row('an-fantasy', 1, 'Fantasy Realms', 'POSTER', 'tv', '/discover/tv', a({}, '16,10765')),
          row('an-fantasy', 2, 'Top 10 Fantasy', 'TOP10', 'tv', '/discover/tv', a(top, '16,10765'))
        ]
      },
      {
        id: 'an-comedy',
        title: 'Comedy',
        rows: [
          row('an-comedy', 1, 'Feel-good Comedy', 'POSTER', 'tv', '/discover/tv', a({}, '16,35')),
          row('an-comedy', 2, 'Top 10 Comedy', 'TOP10', 'tv', '/discover/tv', a(top, '16,35'))
        ]
      },
      {
        id: 'an-movies',
        title: 'Movies',
        rows: [
          row('an-movies', 1, 'Popular Movies', 'POSTER', 'movie', '/discover/movie', a()),
          row('an-movies', 2, 'Top 10 Movies', 'TOP10', 'movie', '/discover/movie', a({ sort_by: 'vote_average.desc', 'vote_count.gte': 200 })),
          row('an-movies', 3, 'New Releases', 'LANDSCAPE', 'movie', '/discover/movie', a({ sort_by: 'primary_release_date.desc', 'primary_release_date.lte': today, 'vote_count.gte': 10 }))
        ]
      }
    ];
  }

  // DRAMA: K / C / J — discover tv by origin country, never animation.
  const cats: { id: string; title: string; country: (typeof DRAMA_COUNTRIES)[number] }[] = [
    { id: 'dr-kdrama', title: 'K-Drama', country: 'KR' },
    { id: 'dr-cdrama', title: 'C-Drama', country: 'CN' },
    { id: 'dr-jdrama', title: 'J-Drama', country: 'JP' }
  ];
  return cats.map(({ id, title, country }) => {
    const d = (extra: P = {}): P => ({
      with_origin_country: country,
      without_genres: DRAMA_WITHOUT,
      sort_by: 'popularity.desc',
      ...extra
    });
    return {
      id,
      title,
      rows: [
        row(id, 1, 'Trending Now', 'POSTER', 'tv', '/discover/tv', d()),
        row(id, 2, `Top 10 ${title}`, 'TOP10', 'tv', '/discover/tv', d({ sort_by: 'vote_average.desc', 'vote_count.gte': 100 })),
        row(id, 3, 'New Releases', 'LANDSCAPE', 'tv', '/discover/tv', d({ sort_by: 'first_air_date.desc', 'first_air_date.lte': today, 'vote_count.gte': 5 })),
        row(id, 4, 'Twisty Mysteries', 'POSTER', 'tv', '/discover/tv', d({ with_genres: 9648 })),
        row(id, 5, 'Light & Funny', 'POSTER', 'tv', '/discover/tv', d({ with_genres: 35 }))
      ]
    };
  });
}

// ---------------------------------------------------------------------------------- mapping

const mediaOf = (r: TmdbResult, hint?: Media): Media | 'person' =>
  r.media_type ?? hint ?? (r.title !== undefined || r.release_date !== undefined ? 'movie' : 'tv');

/** Which video space a multi-search / recommendation result belongs to (null = drop). */
export function classify(r: TmdbResult, hint?: Media): VideoSpace | null {
  const media = mediaOf(r, hint);
  if (media === 'person' || r.adult) return null;
  const genres = r.genre_ids ?? [];
  const countries = r.origin_country ?? [];
  const lang = r.original_language ?? '';
  const jp = countries.includes('JP') || lang === 'ja';
  if (genres.includes(ANIME_GENRE) && jp) return 'ANIME';
  if (
    media === 'tv' &&
    !genres.includes(ANIME_GENRE) &&
    (countries.some((c) => (DRAMA_COUNTRIES as readonly string[]).includes(c)) || DRAMA_LANGS.includes(lang))
  )
    return 'DRAMA';
  return 'HOME';
}

export interface MapOptions {
  categoryId?: string;
  /** Known dominant color for a poster path (memory cache peek). */
  colorOf?: (posterPath: string) => string | undefined;
}

const year = (d?: string) => (d ? Number(d.slice(0, 4)) || 0 : 0);
const round1 = (n?: number) => Math.round((n ?? 0) * 10) / 10;

/** TMDB list result → Item (null when unusable: adult, person, no poster). */
export function mapResult(r: TmdbResult, space: VideoSpace, hint: Media, opts: MapOptions = {}): Item | null {
  const media = mediaOf(r, hint);
  if (media === 'person' || r.adult || !r.poster_path) return null;
  const names = media === 'movie' ? MOVIE_GENRES : TV_GENRES;
  const id = tmdbItemId(space, media, r.id);
  const poster = `${TMDB_PREFIX}${r.poster_path}`;
  const title = (media === 'movie' ? r.title : r.name) ?? r.title ?? r.name ?? 'Untitled';
  const original = media === 'movie' ? r.original_title : r.original_name;
  return {
    id,
    space,
    categoryId: opts.categoryId ?? `tmdb-${space.toLowerCase()}`,
    type: media === 'movie' ? 'MOVIE' : 'SERIES',
    title,
    altTitle: original && original !== title ? original : undefined,
    year: year(media === 'movie' ? r.release_date : r.first_air_date),
    rating: round1(r.vote_average),
    maturity: 'NR',
    genres: (r.genre_ids ?? []).map((g) => names[g]).filter((g): g is string => !!g),
    synopsis: r.overview ?? '',
    poster,
    backdrop: `${TMDB_PREFIX}${r.backdrop_path ?? r.poster_path}`,
    dominantColor: opts.colorOf?.(poster) ?? fallbackColor(id),
    accentColor: '#FF2D3B'
  };
}

function certification(d: TmdbDetails, media: Media): string | undefined {
  if (media === 'movie') {
    const all = d.release_dates?.results ?? [];
    const us = all.find((x) => x.iso_3166_1 === 'US') ?? all[0];
    return us?.release_dates.map((x) => x.certification).find((c) => !!c);
  }
  const all = d.content_ratings?.results ?? [];
  return (all.find((x) => x.iso_3166_1 === 'US') ?? all[0])?.rating || undefined;
}

/** TMDB details → Item (seasons are lazy: episodes [] + episodeCount). */
export function mapDetails(d: TmdbDetails, space: VideoSpace, media: Media, opts: MapOptions = {}): Item | null {
  const base = mapResult({ ...d, genre_ids: d.genre_ids ?? d.genres?.map((g) => g.id), media_type: media }, space, media, opts);
  if (!base) return null;
  const item: Item = {
    ...base,
    genres: d.genres?.map((g) => g.name) ?? base.genres,
    maturity: certification(d, media) ?? 'NR',
    cast: d.credits?.cast?.slice(0, 10).map((c) => c.name),
    studio: d.production_companies?.[0]?.name ?? d.networks?.[0]?.name
  };
  if (media === 'movie') {
    if (d.runtime) item.durationSec = d.runtime * 60;
  } else {
    const seasons: Season[] = (d.seasons ?? [])
      .filter((s) => s.season_number > 0 && (s.episode_count ?? 0) > 0)
      .map((s) => ({ n: s.season_number, title: s.name || `Season ${s.season_number}`, episodes: [], episodeCount: s.episode_count }));
    item.seasons = seasons;
    const rt = d.episode_run_time?.[0];
    if (rt) item.durationSec = rt * 60;
  }
  return item;
}

export function mapSeason(s: TmdbSeason, item: Item): Episode[] {
  return (s.episodes ?? []).map((e) => ({
    id: `${item.id}-s${s.season_number}e${e.episode_number}`,
    n: e.episode_number,
    title: e.name || `Episode ${e.episode_number}`,
    synopsis: e.overview || undefined,
    durationSec: (e.runtime || Math.round((item.durationSec ?? 1440) / 60)) * 60,
    still: e.still_path ? `${TMDB_PREFIX}${e.still_path}` : item.backdrop,
    sources: [],
    subtitles: []
  }));
}

// ---------------------------------------------------------------------------------- client

export interface TmdbClientOptions {
  /** v4 read access token (Bearer) — or a v3 api key (short hex), sent as api_key. */
  token: string;
  fetcher?: Fetcher;
  api?: string;
  language?: string;
}

export class TmdbClient {
  private fetcher: Fetcher;
  constructor(private opts: TmdbClientOptions) {
    this.fetcher = opts.fetcher ?? ((u, init) => fetch(u, init));
  }

  get enabled(): boolean {
    return !!this.opts.token;
  }

  private get isV3Key(): boolean {
    return /^[0-9a-f]{32}$/i.test(this.opts.token);
  }

  url(path: string, params: Record<string, string | number> = {}): string {
    const u = new URL((this.opts.api ?? TMDB_API) + path);
    u.searchParams.set('language', this.opts.language ?? 'en-US');
    for (const [k, v] of Object.entries(params)) u.searchParams.set(k, String(v));
    // Never negotiable.
    u.searchParams.set('include_adult', 'false');
    if (this.isV3Key) u.searchParams.set('api_key', this.opts.token);
    return u.toString();
  }

  async get<T>(path: string, params: Record<string, string | number> = {}, signal?: AbortSignal): Promise<T> {
    if (!this.enabled) throw new Error('TMDB: no token');
    const headers: Record<string, string> = { accept: 'application/json' };
    if (!this.isV3Key) headers.Authorization = `Bearer ${this.opts.token}`;
    const res = await this.fetcher(this.url(path, params), { headers, signal });
    if (!res.ok) throw new Error(`TMDB ${path}: HTTP ${res.status}`);
    return (await res.json()) as T;
  }
}

// ---------------------------------------------------------------------------------- source

export interface TmdbSourceOptions {
  client: TmdbClient;
  colorOf?: (posterPath: string) => string | undefined;
  persist?: PersistentCache | null;
  /** Persisted catalog files younger than this are used without network. */
  ttlMs?: number;
  now?: () => number;
}

interface Persisted {
  ts: number;
  file: CatalogFile;
}

const FILE_KEY = (s: VideoSpace) => `tmdb:file:${s}:v1`;
const ROW_LIMIT = 20;

export class TmdbCatalogSource {
  private files = new Map<VideoSpace, Promise<CatalogFile>>();
  private seen = new Map<string, Item>();
  private details = new Map<string, Promise<Item | undefined>>();
  private related = new Map<string, Item[]>();
  private opts: Required<Omit<TmdbSourceOptions, 'colorOf' | 'persist'>> & Pick<TmdbSourceOptions, 'colorOf' | 'persist'>;

  constructor(opts: TmdbSourceOptions) {
    this.opts = { ttlMs: 6 * 3600_000, now: () => Date.now(), ...opts };
  }

  get enabled(): boolean {
    return this.opts.client.enabled;
  }

  /** Last-known list item (works offline once a file was loaded). */
  cached(id: string): Item | undefined {
    return this.seen.get(id);
  }

  file(space: VideoSpace): Promise<CatalogFile> {
    let p = this.files.get(space);
    if (!p) {
      p = this.loadFile(space);
      p.catch(() => this.files.delete(space));
      this.files.set(space, p);
    }
    return p;
  }

  private remember(file: CatalogFile): CatalogFile {
    for (const i of file.items) if (!this.seen.has(i.id)) this.seen.set(i.id, i);
    return file;
  }

  private async loadFile(space: VideoSpace): Promise<CatalogFile> {
    if (!this.enabled) throw new Error('TMDB: no token');
    const persist = this.opts.persist;
    const stored = persist ? ((await persist.get(FILE_KEY(space))) as Persisted | undefined) : undefined;
    const usable: Persisted | null =
      stored && stored.file?.space === space && Array.isArray(stored.file.items) && stored.file.items.length > 0 ? stored : null;
    if (usable && this.opts.now() - usable.ts < this.opts.ttlMs) return this.remember(usable.file);
    try {
      const file = await this.build(space);
      const record: Persisted = { ts: this.opts.now(), file };
      if (persist) void persist.set(FILE_KEY(space), record);
      return this.remember(file);
    } catch (e) {
      if (usable) return this.remember(usable.file);
      throw e;
    }
  }

  private async build(space: VideoSpace): Promise<CatalogFile> {
    const today = new Date(this.opts.now()).toISOString().slice(0, 10);
    const plan = tmdbPlan(space, today);
    const specs = plan.flatMap((c) => c.rows.map((r) => ({ cat: c, row: r })));
    let failures = 0;
    let firstError: unknown;
    const pages = await Promise.all(
      specs.map(({ row }) =>
        this.opts.client.get<TmdbPage>(row.path, row.params).then(
          (p) => (Array.isArray(p?.results) ? p.results : []),
          (e: unknown) => {
            failures++;
            firstError ??= e;
            return [] as TmdbResult[];
          }
        )
      )
    );
    if (failures === specs.length) throw firstError instanceof Error ? firstError : new Error('TMDB: all requests failed');

    const items = new Map<string, Item>();
    const rowsByCat = new Map<string, Row[]>();
    specs.forEach(({ cat, row }, idx) => {
      const ids: string[] = [];
      for (const r of pages[idx]) {
        const item = mapResult(r, space, row.media, { categoryId: cat.id, colorOf: this.opts.colorOf });
        if (!item || ids.includes(item.id)) continue;
        if (!items.has(item.id)) items.set(item.id, item);
        ids.push(item.id);
        if (ids.length >= (row.style === 'TOP10' ? 10 : ROW_LIMIT)) break;
      }
      if (!ids.length) return;
      const list = rowsByCat.get(cat.id) ?? [];
      list.push({ id: row.id, title: row.title, style: row.style, itemIds: ids });
      rowsByCat.set(cat.id, list);
    });

    const categories: Category[] = [];
    for (const c of plan) {
      const rows = rowsByCat.get(c.id);
      if (!rows?.length) continue;
      const heroIds = rows
        .flatMap((r) => r.itemIds)
        .filter((id, i, all) => all.indexOf(id) === i && !!items.get(id)?.backdrop)
        .slice(0, 5);
      categories.push({ id: c.id, space, title: c.title, heroIds, rows });
    }
    if (!categories.length) throw new Error('TMDB: empty catalog');
    const list = [...items.values()];
    return { space, version: 1, genres: [...new Set(list.flatMap((i) => i.genres))].sort(), categories, items: list };
  }

  /** Full details (credits, rating, lazy seasons). Also caches same-space recommendations. */
  item(id: string): Promise<Item | undefined> {
    let p = this.details.get(id);
    if (!p) {
      p = this.loadDetails(id);
      p.catch(() => this.details.delete(id));
      this.details.set(id, p);
    }
    return p;
  }

  private async loadDetails(id: string): Promise<Item | undefined> {
    const ref = parseTmdbId(id);
    if (!ref) return undefined;
    const append = ref.media === 'movie' ? 'credits,recommendations,release_dates' : 'credits,recommendations,content_ratings';
    const d = await this.opts.client.get<TmdbDetails>(`/${ref.media}/${ref.id}`, { append_to_response: append });
    if (d.adult) return undefined;
    const item = mapDetails(d, ref.space, ref.media, { colorOf: this.opts.colorOf });
    if (!item) return undefined;
    const recs = (d.recommendations?.results ?? [])
      .filter((r) => classify(r, ref.media) === ref.space)
      .map((r) => mapResult(r, ref.space, ref.media, { colorOf: this.opts.colorOf }))
      .filter((i): i is Item => !!i);
    this.related.set(id, recs);
    return item;
  }

  relatedTo(id: string): Item[] {
    return this.related.get(id) ?? [];
  }

  async season(item: Item, n: number): Promise<Episode[]> {
    const ref = parseTmdbId(item.id);
    if (!ref || ref.media !== 'tv') return [];
    const s = await this.opts.client.get<TmdbSeason>(`/tv/${ref.id}/season/${n}`);
    return mapSeason(s, item);
  }

  /** /search/multi → Home/Anime/Drama items only (persons + adult dropped). */
  async search(query: string, signal?: AbortSignal): Promise<Item[]> {
    const q = query.trim();
    if (!q) return [];
    const page = await this.opts.client.get<TmdbPage>('/search/multi', { query: q, page: 1 }, signal);
    const out: Item[] = [];
    for (const r of page.results ?? []) {
      const space = classify(r);
      if (!space) continue;
      const media = r.media_type === 'movie' || r.media_type === 'tv' ? r.media_type : r.title !== undefined ? 'movie' : 'tv';
      const item = mapResult(r, space, media, { colorOf: this.opts.colorOf });
      if (item) {
        out.push(item);
        if (!this.seen.has(item.id)) this.seen.set(item.id, item);
      }
    }
    return out;
  }
}
