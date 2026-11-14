import { describe, expect, it } from 'vitest';
import { CatalogRepository, NO_LATENCY } from './catalog';
import { Catalog } from './source';
import { fileFetcher } from './test-utils';
import {
  classify,
  mapDetails,
  mapResult,
  parseTmdbId,
  tmdbPlan,
  TmdbCatalogSource,
  TmdbClient,
  type Fetcher,
  type TmdbResult
} from './tmdb';
import { imgAttrs, tmdbUrl } from './images';

const TODAY = '2026-11-14';

const movie: TmdbResult = {
  id: 27205,
  media_type: 'movie',
  title: 'Inception',
  original_title: 'Inception',
  release_date: '2010-07-15',
  vote_average: 8.369,
  genre_ids: [28, 878],
  overview: 'Dreams.',
  poster_path: '/p.jpg',
  backdrop_path: '/b.jpg'
};
const tv: TmdbResult = {
  id: 1396,
  media_type: 'tv',
  name: 'Breaking Bad',
  original_name: 'Breaking Bad',
  first_air_date: '2008-01-20',
  vote_average: 8.9,
  genre_ids: [18, 80],
  origin_country: ['US'],
  original_language: 'en',
  poster_path: '/bb.jpg',
  backdrop_path: null
};

/** Records URLs; answers every list endpoint with `results`, or fails. */
function fakeFetch(calls: string[], opts: { fail?: boolean; results?: TmdbResult[] } = {}): Fetcher {
  return async (url) => {
    calls.push(url);
    if (opts.fail) return { ok: false, status: 503, json: async () => ({}) };
    return { ok: true, status: 200, json: async () => ({ page: 1, results: opts.results ?? [movie, tv] }) };
  };
}

const params = (url: string) => Object.fromEntries(new URL(url).searchParams.entries());

describe('TMDB mapping', () => {
  it('maps movie and tv list results to Items', () => {
    const m = mapResult(movie, 'HOME', 'movie')!;
    expect(m.id).toBe('tmdb-home-movie-27205');
    expect(m.type).toBe('MOVIE');
    expect(m.title).toBe('Inception');
    expect(m.altTitle).toBeUndefined();
    expect(m.year).toBe(2010);
    expect(m.rating).toBe(8.4);
    expect(m.genres).toEqual(['Action', 'Sci-Fi']);
    expect(m.poster).toBe('tmdb:/p.jpg');
    expect(m.dominantColor).toMatch(/^#[0-9A-F]{6}$/);

    const t = mapResult(tv, 'HOME', 'tv')!;
    expect(t.type).toBe('SERIES');
    expect(t.title).toBe('Breaking Bad');
    expect(t.year).toBe(2008);
    expect(t.genres).toEqual(['Drama', 'Crime']);
    // No backdrop → poster reused.
    expect(t.backdrop).toBe('tmdb:/bb.jpg');
    expect(parseTmdbId(t.id)).toEqual({ space: 'HOME', media: 'tv', id: 1396 });
  });

  it('drops adult, persons and posterless results', () => {
    expect(mapResult({ ...movie, adult: true }, 'HOME', 'movie')).toBeNull();
    expect(mapResult({ ...movie, media_type: 'person' }, 'HOME', 'movie')).toBeNull();
    expect(mapResult({ ...movie, poster_path: null }, 'HOME', 'movie')).toBeNull();
  });

  it('maps details with lazy seasons and certification', () => {
    const d = mapDetails(
      {
        ...tv,
        genres: [{ id: 18, name: 'Drama' }],
        episode_run_time: [47],
        seasons: [
          { season_number: 0, episode_count: 3 },
          { season_number: 1, name: 'Season 1', episode_count: 7 }
        ],
        content_ratings: { results: [{ iso_3166_1: 'US', rating: 'TV-MA' }] },
        credits: { cast: [{ name: 'Bryan Cranston' }] }
      },
      'HOME',
      'tv'
    )!;
    expect(d.maturity).toBe('TV-MA');
    expect(d.seasons).toEqual([{ n: 1, title: 'Season 1', episodes: [], episodeCount: 7 }]);
    expect(d.durationSec).toBe(47 * 60);
    expect(d.cast).toEqual(['Bryan Cranston']);
  });

  it('builds tier-sized TMDB image URLs with srcset', () => {
    expect(tmdbUrl('tmdb:/p.jpg', 'w342')).toBe('https://image.tmdb.org/t/p/w342/p.jpg');
    const low = imgAttrs('tmdb:/p.jpg', 'poster', 'LOW', (p) => p);
    expect(low.srcset).not.toContain('w780');
    const high = imgAttrs('tmdb:/p.jpg', 'hero', 'HIGH', (p) => p);
    expect(high.srcset).toContain('w1280');
    expect(imgAttrs('images/x.webp', 'poster', 'MID', (p) => `/m/${p}`).src).toBe('/m/images/x.webp');
  });
});

describe('TMDB query plan', () => {
  it('anime queries use genre 16 + origin country JP', () => {
    const rows = tmdbPlan('ANIME', TODAY).flatMap((c) => c.rows);
    expect(rows.length).toBeGreaterThan(0);
    for (const r of rows) {
      expect(String(r.params.with_genres).split(',')).toContain('16');
      expect(r.params.with_origin_country).toBe('JP');
    }
  });

  it('k-drama uses KR, never animation; C/J use CN/JP', () => {
    const plan = tmdbPlan('DRAMA', TODAY);
    const k = plan.find((c) => c.id === 'dr-kdrama')!;
    for (const r of k.rows) {
      expect(r.media).toBe('tv');
      expect(r.params.with_origin_country).toBe('KR');
      expect(String(r.params.without_genres).split('|')).toContain('16');
    }
    expect(plan.find((c) => c.id === 'dr-cdrama')!.rows[0].params.with_origin_country).toBe('CN');
    expect(plan.find((c) => c.id === 'dr-jdrama')!.rows[0].params.with_origin_country).toBe('JP');
  });

  it('home covers trending/popular/top_rated/now_playing/upcoming + tv equivalents', () => {
    const paths = tmdbPlan('HOME', TODAY).flatMap((c) => c.rows.map((r) => r.path));
    for (const p of ['/trending/movie/week', '/movie/popular', '/movie/top_rated', '/movie/now_playing', '/movie/upcoming', '/trending/tv/week', '/tv/popular', '/tv/top_rated'])
      expect(paths).toContain(p);
  });

  it('every request carries include_adult=false + bearer token', async () => {
    const seen: { url: string; auth?: string }[] = [];
    const client = new TmdbClient({
      token: 'eyJhbGciOiJIUzI1NiJ9.test',
      fetcher: async (url, init) => {
        seen.push({ url, auth: init?.headers?.Authorization });
        return { ok: true, status: 200, json: async () => ({ results: [] }) };
      }
    });
    await client.get('/discover/tv', { with_genres: 16, include_adult: 'true' });
    expect(params(seen[0].url).include_adult).toBe('false');
    expect(seen[0].auth).toBe('Bearer eyJhbGciOiJIUzI1NiJ9.test');
  });

  it('source builds anime file through discover with 16 + JP', async () => {
    const calls: string[] = [];
    const src = new TmdbCatalogSource({ client: new TmdbClient({ token: 't', fetcher: fakeFetch(calls) }), persist: null, now: () => Date.parse(TODAY) });
    const f = await src.file('ANIME');
    expect(f.space).toBe('ANIME');
    expect(f.items.every((i) => i.space === 'ANIME' && i.id.startsWith('tmdb-anime-'))).toBe(true);
    const discover = calls.filter((u) => u.includes('/discover/'));
    expect(discover.length).toBe(calls.length);
    for (const u of discover) {
      const p = params(u);
      expect(p.with_genres.split(',')).toContain('16');
      expect(p.with_origin_country).toBe('JP');
      expect(p.include_adult).toBe('false');
    }
  });
});

describe('TMDB search classification', () => {
  it('search drops adult + persons and classifies into Home/Anime/Drama only', async () => {
    const results: TmdbResult[] = [
      movie,
      { ...movie, id: 1, adult: true, title: 'Adult thing' },
      { id: 2, media_type: 'person', name: 'Someone' },
      { id: 3, media_type: 'tv', name: 'Frieren', genre_ids: [16, 10765], origin_country: ['JP'], original_language: 'ja', poster_path: '/f.jpg' },
      { id: 4, media_type: 'tv', name: 'Crash Landing', genre_ids: [18], origin_country: ['KR'], original_language: 'ko', poster_path: '/c.jpg' }
    ];
    const src = new TmdbCatalogSource({ client: new TmdbClient({ token: 't', fetcher: fakeFetch([], { results }) }), persist: null });
    const out = await src.search('x');
    expect(out.map((i) => i.space)).toEqual(['HOME', 'ANIME', 'DRAMA']);
    expect(out.some((i) => i.title === 'Adult thing')).toBe(false);
    expect(classify({ id: 9, media_type: 'tv', genre_ids: [16], origin_country: ['US'] })).toBe('HOME');
  });
});

describe('Catalog fallback', () => {
  const mock = () => new CatalogRepository({ baseUrl: '', fetcher: fileFetcher(), latency: NO_LATENCY, persist: null });

  it('falls back to mock when TMDB errors', async () => {
    const calls: string[] = [];
    const tmdb = new TmdbCatalogSource({ client: new TmdbClient({ token: 't', fetcher: fakeFetch(calls, { fail: true }) }), persist: null });
    const c = new Catalog({ mock: mock(), tmdb, online: () => true });
    const f = await c.file('HOME');
    expect(calls.length).toBeGreaterThan(0);
    expect(c.originOf('HOME')).toBe('mock');
    expect(f.items[0].id.startsWith('tmdb-')).toBe(false);
  });

  it('uses mock without token, and isolated spaces never hit TMDB', async () => {
    const calls: string[] = [];
    const none = new Catalog({ mock: mock(), tmdb: new TmdbCatalogSource({ client: new TmdbClient({ token: '' }), persist: null }) });
    expect(none.remoteEnabled).toBe(false);
    await none.file('ANIME');
    expect(none.originOf('ANIME')).toBe('mock');

    const tmdb = new TmdbCatalogSource({ client: new TmdbClient({ token: 't', fetcher: fakeFetch(calls) }), persist: null });
    const c = new Catalog({ mock: mock(), tmdb, online: () => true });
    for (const s of ['TV', 'MUSIC', 'COMICS', 'ADULT'] as const) {
      await c.file(s);
      expect(c.originOf(s)).toBe('mock');
    }
    expect(calls).toEqual([]);
    await c.file('DRAMA');
    expect(c.originOf('DRAMA')).toBe('tmdb');
  });

  it('remote items get mock video sources attached', async () => {
    const tmdb = new TmdbCatalogSource({
      client: new TmdbClient({
        token: 't',
        fetcher: async () => ({ ok: true, status: 200, json: async () => ({ ...movie, genres: [{ id: 28, name: 'Action' }], runtime: 148 }) })
      }),
      persist: null
    });
    const c = new Catalog({ mock: mock(), tmdb, online: () => true });
    const got = await c.item('tmdb-home-movie-27205');
    expect(got?.durationSec).toBe(148 * 60);
    expect(got?.sources?.length).toBeGreaterThan(0);
  });
});
