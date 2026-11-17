import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { CatalogRepository, NO_LATENCY } from './catalog';
import {
  DUB_LANGUAGES,
  DeadTitles,
  GatewayCatalogSource,
  GatewayClient,
  classifyGw,
  extractDubLanguages,
  gatewayBase,
  gatewayPlan,
  gwItemId,
  isGatewayAdultId,
  mapGwDetail,
  mapGwItem,
  normalizeBase,
  safeName,
  sectionMore,
  subjectIdOf,
  type GwDetail,
  type GwFetcher,
  type GwListItem
} from './gateway';
import {
  LinkHealth,
  StreamResolver,
  applyResolved,
  captionsToSubs,
  cleanStreamLabel,
  detectNetworkKind,
  directPlayUrl,
  extractYouTubeVideoId,
  findPlaceholder,
  isDualAudio,
  isImageLink,
  isLazySource,
  matchSearch,
  parseFindRef,
  parseStreamRef,
  pickVariant,
  proxyUrl,
  rankSources,
  streamToSources,
  streamTrailerSource,
  subtitleProxy,
  toYouTubeEmbedUrl,
  type GwStream
} from './gateway-play';
import { gwImgUrl, imgAttrs, ossWidthFor, thumbnailUrl, tmdbBucketFor } from './images';
import { ComicsSource } from '../comics/mangadex';
import { Catalog } from './source';
import { fileFetcher } from './test-utils';
import { buildMedia } from '../player/sources';
import { meSpaceEntries } from '../spaces';

const SAMPLES = fileURLToPath(new URL('../../../../docs/gateway-samples/', import.meta.url));
const sample = (name: string) => JSON.parse(readFileSync(SAMPLES + name, 'utf8'));
const BASE = 'https://gw.test';

/** Fake gateway: path (+ query) → JSON. Records every URL. */
function fakeGateway(routes: Record<string, unknown | ((u: URL) => unknown)>, calls: string[] = []): GwFetcher {
  return async (url) => {
    calls.push(url);
    const u = new URL(url);
    const path = u.pathname.replace('/api/public/v1', '');
    const hit = routes[path];
    if (hit === undefined) return { ok: false, status: 404, json: async () => ({}) };
    const body = typeof hit === 'function' ? (hit as (u: URL) => unknown)(u) : hit;
    return { ok: true, status: 200, json: async () => body };
  };
}

const memKV = () => {
  const m = new Map<string, unknown>();
  return { get: async (k: string) => m.get(k), set: async (k: string, v: unknown) => void m.set(k, v) };
};

function source(routes: Record<string, unknown | ((u: URL) => unknown)>, calls: string[] = [], extra: { isDead?: (id: string) => boolean } = {}) {
  const client = new GatewayClient({ base: () => BASE, fetcher: fakeGateway(routes, calls), persist: null });
  return new GatewayCatalogSource({ client, persist: null, ...extra });
}

const homeRoutes = {
  '/home': sample('home.json'),
  '/movies': sample('movies.json'),
  '/tv-series': (u: URL) => (u.searchParams.get('country') === 'Korea' ? sample('tv-series_country_Korea.json') : sample('tv-series.json')),
  '/animation': sample('animation.json'),
  '/short-tv': sample('short-tv.json'),
  '/zee-bangla': sample('zee-bangla.json')
};

describe('gateway config + ids', () => {
  it('base URL: default, override, off, invalid', () => {
    const store = new Map<string, string>();
    const s = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v), removeItem: (k: string) => void store.delete(k) };
    expect(gatewayBase(s)).toBe('https://antiblock-gateway.vercel.app');
    store.set('cinetor.gatewayBase', 'https://x.example/ ');
    expect(gatewayBase(s)).toBe('https://x.example');
    store.set('cinetor.gatewayBase', '');
    expect(gatewayBase(s)).toBe('');
    expect(normalizeBase('ftp://nope')).toBeNull();
  });

  it('ids: gw: / ad-gw: + episode suffix', () => {
    expect(gwItemId('123')).toBe('gw:123');
    expect(gwItemId('123', 'ADULT')).toBe('ad-gw:123');
    expect(isGatewayAdultId('ad-gw:9')).toBe(true);
    expect(subjectIdOf('gw:123-s1e2')).toBe('123');
    expect(subjectIdOf('ad-gw:77')).toBe('77');
  });
});

describe('gateway mapping', () => {
  it('home: curated /home sections (top chart TOP10, anime & adult filtered) + Beta pageable shelves & /zee-bangla', async () => {
    const calls: string[] = [];
    const src = source(homeRoutes, calls);
    const f = await src.file('HOME');
    expect(f.categories.map((c) => c.id)).toEqual(['gw-home', 'gw-movies', 'gw-series', 'gw-short-tv', 'gw-zee-bangla']);
    const home = sample('home.json') as { sections: { section: string; items: GwListItem[] }[] };
    const banner = home.sections[0].items.map((i: GwListItem) => `gw:${i.subject_id}`);
    const forYou = f.categories[0];
    expect(forYou.heroIds.every((id) => banner.includes(id))).toBe(true);

    // Top chart row right under Hero is styled TOP10, and Popular Series / Popular Movie stay TOP10.
    expect(forYou.rows[0].style).toBe('TOP10');
    expect(forYou.rows.find((r) => r.title === 'Popular Series')?.style).toBe('TOP10');
    expect(forYou.rows.find((r) => r.title === 'Popular Movie')?.style).toBe('TOP10');

    // Anime sections (e.g. Anime[English Dubbed], Adult Animation) and anime items are excluded from HOME.
    expect(forYou.rows.some((r) => /anime/i.test(r.title))).toBe(false);
    expect(f.items.some((i) => i.genres.some((g) => /^anime$/i.test(g)))).toBe(false);

    // Beta pageable shelves on HOME:
    const rowTitles = forYou.rows.map((r) => r.title);
    for (const expected of [
      '🔥 Hot Short TV',
      'Zee Bangla Serials',
      'Zee Bangla Shows',
      'Star Jalsha Shows',
      'Sony AATH Series',
      'Indian Dramas',
      'Latest Movies',
      'Latest Series',
      'Hollywood',
      'Action',
      'Comedy',
      'Horror',
      'Thriller',
      'Crime Series'
    ]) {
      expect(rowTitles).toContain(expected);
    }

    // Curated POSTER sections get sectionMore cursors for endless rowPage pagination.
    const western = forYou.rows.find((r) => r.title === 'Original Western TV')!;
    expect(western.more).toMatchObject({ path: '/tv-series', params: { sort: 'hottest', country: 'United States' }, page: 0, hasMore: true });

    // Short TV / Zee Bangla categories use the paged endpoints.
    const short = f.categories.find((c) => c.id === 'gw-short-tv')!;
    expect(short.rows[0].more).toEqual({ path: '/short-tv', params: {}, page: 1, hasMore: true });
    const zee = f.categories.find((c) => c.id === 'gw-zee-bangla')!;
    expect(zee.rows[0].more?.path).toBe('/zee-bangla');
    const latest = f.categories[1].rows[0];
    expect(latest.id).toBe('gw-movies-latest');
    expect(latest.more).toEqual({ path: '/movies', params: { sort: 'latest' }, page: 1, hasMore: true });
    expect(f.items.every((i) => i.id.startsWith('gw:') && /^https:\/\//.test(i.poster) && !!i.slug)).toBe(true);
    const movie = f.items.find((i) => i.type === 'MOVIE')!;
    expect(isLazySource(movie.sources?.[0])).toBe(true);
    // Isolated endpoints never touched for HOME.
    expect(calls.some((u) => u.includes('/adult'))).toBe(false);
  });

  it('adult plan passes kind + sort + regional shelves and filters unsafe names via safeName', async () => {
    const p = JSON.stringify(gatewayPlan('ADULT'));
    expect(p).toContain('"kind":"movies"');
    expect(p).toContain('"sort":"latest"');
    expect(p).toContain('"sort":"hottest"');
    expect(p).toContain('"sort":"rating"');
    expect(gatewayPlan('ADULT').map((c) => c.id)).toEqual(['ad-featured', 'ad-series', 'ad-popular', 'ad-top-rated']);

    expect(safeName('Labas pasok')).toBe(true);
    expect(safeName('Romantic Evening')).toBe(true);
    for (const blocked of [
      'Schoolgirl Secret',
      'Teen Party',
      'College Girl Diary',
      'Underage Scandal',
      'Step-Sister Story',
      'Hidden Cam Room',
      'Spy Cam Footage',
      'Leaked MMS Clip',
      'Hentai OVA',
      'Forced Entry',
      'Loli Idol'
    ]) {
      expect(safeName(blocked)).toBe(false);
    }

    const src = source({
      '/adult': {
        page: 1,
        has_more: true,
        items: [
          { name: 'Midnight Desire', poster_url: 'https://p/ok.jpg', slug: 'ok-1', subject_id: '101', genre: 'Adult,Erotic', type: 'movie', has_resource: true },
          { name: 'Hidden Cam Leak', poster_url: 'https://p/bad1.jpg', slug: 'bad-1', subject_id: '102', genre: 'Adult,Erotic', type: 'movie', has_resource: true },
          { name: 'Schoolgirl Crush', poster_url: 'https://p/bad2.jpg', slug: 'bad-2', subject_id: '103', genre: 'Adult,Erotic', type: 'movie', has_resource: true }
        ]
      }
    });
    const f = await src.file('ADULT');
    expect(f.items.map((i) => i.id)).toEqual(['ad-gw:101']);
    expect(f.items[0].maturity).toBe('18+');
  });

  it('paged rows: next page, endless rowPage for curated sections, and genre chip filterPage', async () => {
    const calls: string[] = [];
    const src = source(
      {
        '/movies': (u: URL) => ({ ...sample('movies.json'), page: Number(u.searchParams.get('page')), has_more: false }),
        '/tv-series': (u: URL) => ({ ...sample('tv-series.json'), page: Number(u.searchParams.get('page')), has_more: true }),
        '/short-tv': (u: URL) => ({ ...sample('short-tv.json'), page: Number(u.searchParams.get('page')), has_more: false })
      },
      calls
    );
    const r = await src.morePage('HOME', { path: '/movies', params: {}, page: 1, hasMore: true }, 'gw-movies');
    expect(new URL(calls[0]).searchParams.get('page')).toBe('2');
    expect(r.more).toMatchObject({ page: 2, hasMore: false });
    expect(r.items.length).toBeGreaterThan(0);

    const g = await src.genrePage('HOME', 'gw-movies', 'Action');
    expect(new URL(calls[1]).searchParams.get('genre')).toBe('Action');
    expect(g?.items.length).toBeGreaterThan(0);

    // Curated section row without explicit list spec pages endlessly via sectionMore / rowPage:
    expect(sectionMore('🔥Hot Short TV').path).toBe('/short-tv');
    expect(sectionMore('Zee Bangla Shows')).toMatchObject({ path: '/tv-series', params: { sort: 'latest', genre: 'Zee Bangla', country: 'India' } });
    const secPage = await src.rowPage('HOME', { id: 'gw-home-sec-short', title: '🔥Hot Short TV' });
    expect(secPage.items.length).toBeGreaterThan(0);
    expect(secPage.more.page).toBe(1);

    // Drama country chip filter:
    const pk = await src.genrePage('DRAMA', 'gw-drama-all', 'Pakistani Drama');
    expect(pk?.more.params.country).toBe('Pakistan');
  });

  it('drama: full Beta tabs (All, K-Drama, C-Drama, J-Drama, Pakistani Drama, Indian Drama, Others) & shelves', () => {
    const d = gatewayPlan('DRAMA');
    expect(d.map((c) => c.title)).toEqual(['All', 'K-Drama', 'C-Drama', 'J-Drama', 'Pakistani Drama', 'Indian Drama', 'Others']);
    const allRowTitles = d[0].rows.filter((r) => r.kind === 'list').map((r) => r.title);
    for (const shelf of [
      'Trending K-Drama',
      'Latest K-Drama',
      'Top Rated K-Drama',
      'Korean Romance',
      'Korean Movies',
      'Trending C-Drama',
      'Latest C-Drama',
      'Top Rated C-Drama',
      'Costume & Historical',
      'Chinese Romance',
      'Chinese Movies',
      'Trending J-Drama',
      'Latest J-Drama',
      'Japanese Movies',
      'Trending Pakistani Drama',
      'All-Time Best Pakistani Drama',
      'Indian Dramas',
      'Thai Dramas',
      'Taiwanese Dramas',
      'Other Asian Dramas'
    ]) {
      expect(allRowTitles).toContain(shelf);
    }
  });

  it('anime: Trending, Latest, Top Rated, Animated Movies, and separate Cartoons (/animation) shelf', () => {
    const a = gatewayPlan('ANIME');
    const allTitles = a[0].rows.filter((r) => r.kind === 'list').map((r) => r.title);
    expect(allTitles).toEqual(['Trending Anime', 'Latest Anime', 'Top Rated Anime', 'Animated Movies', 'Cartoons']);
    const cartoons = a[0].rows.find((r) => r.kind === 'list' && r.title === 'Cartoons');
    expect(cartoons && cartoons.kind === 'list' ? cartoons.path : '').toBe('/animation');
  });

  it('extracts dub languages (Hindi, Bengali, Tamil, Telugu, English) onto item genres without false positives', () => {
    expect(DUB_LANGUAGES).toEqual(['Hindi', 'Bengali', 'Tamil', 'Telugu', 'English']);
    expect(extractDubLanguages({ name: 'Where is Home [Bengali]' })).toEqual(['Bengali']);
    expect(extractDubLanguages({ name: 'Neem Phuler Modhu', badge: 'Zee Bangla' })).toEqual(['Bengali']);
    expect(extractDubLanguages({ name: 'Pushpa 2 (Hindi Dubbed)', dubs: [{ language_name: 'Telugu' }, { language_name: 'Tamil' }] })).toEqual([
      'Telugu',
      'Tamil',
      'Hindi'
    ]);
    expect(extractDubLanguages({ name: 'Solo Leveling [English Dubbed]' })).toEqual(['English']);
    // Plain title words like "Johnny English" must not trigger a false English dub badge.
    expect(extractDubLanguages({ name: 'Johnny English Strikes Again' })).toEqual([]);

    const mapped = mapGwItem(
      {
        name: 'Jawan [Hindi]',
        poster_url: 'https://p/j.jpg',
        slug: 'jawan-1',
        subject_id: '55',
        genre: 'Action,Thriller',
        type: 'movie',
        has_resource: true,
        dubs: [{ subject_id: '56', slug: 'jawan-bn', language_name: 'Bengali' }]
      },
      'HOME'
    )!;
    expect(mapped.genres).toEqual(['Action', 'Thriller', 'Bengali', 'Hindi']);
  });

  it('only playable (has_resource === true) + not-dead titles are listed', () => {
    const raw: GwListItem = { name: 'X', poster_url: 'https://p/x.jpg', slug: 'x-1', subject_id: '1', type: 'movie', has_resource: false };
    expect(mapGwItem(raw, 'HOME')).toBeNull();
    expect(mapGwItem({ ...raw, has_resource: undefined }, 'HOME')).toBeNull();
    expect(mapGwItem({ ...raw, has_resource: null }, 'HOME')).toBeNull();
    expect(mapGwItem({ ...raw, has_resource: true }, 'HOME')?.id).toBe('gw:1');
    expect(mapGwItem({ ...raw, has_resource: true, genre: 'Adult,Erotic' }, 'HOME')).toBeNull();
    expect(mapGwItem({ ...raw, has_resource: true, genre: 'Adult,Erotic' }, 'ADULT')?.maturity).toBe('18+');
  });

  it('dead titles are hidden from lists', async () => {
    const dead = new DeadTitles(null);
    const first = sample('movies.json').items[0].subject_id as string;
    dead.add(first);
    const src = source({ '/movies': sample('movies.json'), '/tv-series': sample('tv-series.json') }, [], { isDead: (id) => dead.has(id) });
    const f = await src.file('HOME');
    expect(f.items.some((i) => i.id === `gw:${first}`)).toBe(false);
  });

  it('detail: movie (stills backdrop, cover hue, cast, lazy source)', () => {
    const d = sample('detail_inception-e1BOR6f19C7.json') as GwDetail;
    const it = mapGwDetail(d, 'HOME')!;
    expect(it.id).toBe('gw:6391474290696802080');
    expect(it.type).toBe('MOVIE');
    expect(it.durationSec).toBe(8880);
    expect(it.dominantColor).toBe('#324A56');
    expect(it.backdrop).not.toBe(it.poster);
    expect(it.cast?.length).toBeGreaterThan(0);
    expect(parseStreamRef(it.sources![0].url)).toEqual({ subjectId: '6391474290696802080', slug: 'inception-e1BOR6f19C7', se: 0, ep: 0 });
  });

  it('detail: tv seasons/episodes inline with per-episode lazy sources', () => {
    const d: GwDetail = {
      data: {
        subject: { subjectId: '42', subjectType: 2, title: 'Show', detailPath: 'show-42', cover: { url: 'https://p/c.jpg' }, genre: 'Drama' },
        resource: { seasons: [{ se: 2, maxEp: 2 }, { se: 1, maxEp: 3 }, { se: 0, maxEp: 0 }] }
      }
    };
    const it = mapGwDetail(d, 'DRAMA')!;
    expect(it.type).toBe('SERIES');
    expect(it.seasons?.map((s) => [s.n, s.episodes.length])).toEqual([
      [1, 3],
      [2, 2]
    ]);
    const ep = it.seasons![1].episodes[1];
    expect(ep.id).toBe('gw:42-s2e2');
    expect(parseStreamRef(ep.sources[0].url)).toMatchObject({ se: 2, ep: 2, slug: 'show-42' });
  });

  it('item(): /detail/{slug} via remembered slug', async () => {
    const calls: string[] = [];
    const src = source({ '/detail/inception-e1BOR6f19C7': sample('detail_inception-e1BOR6f19C7.json') }, calls);
    src.rememberSlug('gw:6391474290696802080', 'inception-e1BOR6f19C7');
    const it = await src.item('gw:6391474290696802080');
    expect(it?.title).toBe('Inception');
    expect(calls[0]).toContain('/detail/inception-e1BOR6f19C7');
  });

  it('search: classified into Home/Anime/Drama, 18+ dropped; suggest', async () => {
    const s = sample('search_q_inception.json');
    s.items.push({ name: 'Nope', poster_url: 'https://p/n.jpg', slug: 'n', subject_id: '9', genre: 'Erotic', type: 'movie', has_resource: true });
    const src = source({ '/search': s, '/search/suggest': sample('search_suggest_q_incep.json') });
    const hits = await src.search('inception');
    expect(hits.length).toBeGreaterThan(0);
    expect(hits.some((h) => h.id === 'gw:9')).toBe(false);
    expect(hits.every((h) => ['HOME', 'ANIME', 'DRAMA'].includes(h.space))).toBe(true);
    expect(await src.suggest('incep')).toContain('Inception');
    expect(classifyGw({ type: 'tv', country: 'Korea', genre: 'Romance' })).toBe('DRAMA');
    expect(classifyGw({ type: 'tv', country: 'Japan', genre: 'Anime,Action' })).toBe('ANIME');
  });

  it('catalog cache: second file() within 10 min is served from memory', async () => {
    const calls: string[] = [];
    const client = new GatewayClient({ base: () => BASE, fetcher: fakeGateway(homeRoutes, calls), persist: memKV() });
    await client.get('/movies', { page: 1 });
    await client.get('/movies', { page: 1 });
    expect(calls.length).toBe(1);
  });

  it('gateway images get resized srcset', () => {
    const p = 'https://pbcdnw.aoneroom.com/image/a.jpg';
    const a = imgAttrs(p, 'poster', 'MID', (x) => x);
    expect(a.src).toBe(gwImgUrl(p, 342));
    expect(a.srcset).toContain('w_200 200w');
  });
});

describe('thumbnail sizing (OSS + TMDB DPR & low-perf tier)', () => {
  it('selects TMDB width buckets (w342, w500, w780, w1280) based on CSS width, DPR, and LOW tier', () => {
    expect(tmdbBucketFor(160, 'MID', 1)).toBe('w342');
    expect(tmdbBucketFor(220, 'MID', 2)).toBe('w500');
    expect(tmdbBucketFor(360, 'HIGH', 2)).toBe('w780');
    expect(tmdbBucketFor(640, 'HIGH', 2)).toBe('w1280');
    // LOW tier caps DPR amplification so 2 GB RAM devices never decode oversized bitmaps.
    expect(tmdbBucketFor(220, 'LOW', 3)).toBe('w342');
    expect(tmdbBucketFor(900, 'LOW', 3)).toBe('w780');
  });

  it('computes OSS resize widths and replaces existing x-oss-process cleanly', () => {
    const raw = 'https://pbcdn.aoneroom.com/image/2026/poster.jpg';
    const already = 'https://pbcdn.aoneroom.com/image/2026/poster.jpg?x-oss-process=image/resize,w_1000&sig=1';
    expect(ossWidthFor(160, 'MID', 1)).toBe(342);
    expect(ossWidthFor(220, 'HIGH', 2)).toBe(500);
    expect(ossWidthFor(220, 'LOW', 3)).toBe(342);
    expect(gwImgUrl(raw, 500)).toBe(`${raw}?x-oss-process=image/resize,w_500`);
    expect(gwImgUrl(already, 342)).toBe('https://pbcdn.aoneroom.com/image/2026/poster.jpg?x-oss-process=image/resize,w_342&sig=1');
  });

  it('normalizes both tmdb:/ and full image.tmdb.org URLs and respects DPR in imgAttrs & thumbnailUrl', () => {
    const fullTmdb = 'https://image.tmdb.org/t/p/original/abc.jpg';
    const hiAttrs = imgAttrs(fullTmdb, 'poster', 'HIGH', (p) => p, 2);
    expect(hiAttrs.src).toBe('https://image.tmdb.org/t/p/w500/abc.jpg');
    expect(hiAttrs.srcset).toContain('w342/abc.jpg 342w');
    expect(hiAttrs.srcset).toContain('w500/abc.jpg 500w');
    expect(hiAttrs.srcset).toContain('w780/abc.jpg 780w');

    const lowAttrs = imgAttrs(fullTmdb, 'poster', 'LOW', (p) => p, 3);
    expect(lowAttrs.src).toBe('https://image.tmdb.org/t/p/w342/abc.jpg');
    expect(lowAttrs.srcset).not.toContain('w500');
    expect(lowAttrs.srcset).not.toContain('w780');

    const oss = 'https://pbcdnw.aoneroom.com/image/a.jpg';
    expect(thumbnailUrl(oss, 220, 'HIGH', 2)).toBe(`${oss}?x-oss-process=image/resize,w_500`);
    expect(thumbnailUrl(oss, 220, 'LOW', 3)).toBe(`${oss}?x-oss-process=image/resize,w_342`);
    expect(thumbnailUrl('tmdb:/z.jpg', 360, 'HIGH', 2)).toBe('https://image.tmdb.org/t/p/w780/z.jpg');
  });
});

describe('gateway playback URLs', () => {
  const st: GwStream = {
    direct_play_url: '/api/public/v1/stream/5/play?detail_path=s&se=0&ep=0',
    sources: [
      { format: 'DASH', resolution: '1080p', url: 'https://cdn/a.mpd', headers: { Cookie: 'c' } },
      { format: 'MP4', resolution: '480p', url: 'https://cdn/480.mp4?sign=1', headers: { Referer: 'https://r/' } },
      { format: 'MP4', resolution: '1080p', url: 'https://cdn/1080.mp4', proxy_url: '/api/public/v1/stream/5/play?url=x&res=1080p' },
      { format: 'MP4', resolution: '480p', url: 'https://cdn/dupe.mp4' }
    ]
  };

  it('MP4 only → proxy variants (highest first) with direct url + headers', () => {
    const s = streamToSources(BASE, '5', st);
    expect(s.map((x) => x.height)).toEqual([1080, 480]);
    expect(s[0].url).toBe(`${BASE}/api/public/v1/stream/5/play?url=x&res=1080p`);
    expect(s[1].url).toBe(proxyUrl(BASE, '5', 'https://cdn/480.mp4?sign=1', '480p'));
    expect(s[1].url).toContain('url=https%3A%2F%2Fcdn%2F480.mp4%3Fsign%3D1');
    expect(s[1].direct).toEqual({ url: 'https://cdn/480.mp4?sign=1', headers: { Referer: 'https://r/' } });
    expect(streamToSources(BASE, '5', sample('stream_6391474290696802080.json'))).toEqual([]);
  });

  it('direct play / subtitle proxy / captions', () => {
    expect(directPlayUrl(BASE, { subjectId: '5', slug: 's', se: 1, ep: 2 }, '720p')).toBe(`${BASE}/api/public/v1/stream/5/play?detail_path=s&se=1&ep=2&res=720p`);
    expect(subtitleProxy(BASE, 'https://c/x.srt')).toBe(`${BASE}/api/public/v1/subtitle?url=https%3A%2F%2Fc%2Fx.srt`);
    const subs = captionsToSubs(BASE, { captions: [{ lan: 'en', lanName: 'English', url: 'https://c/en.srt' }, { lan: 'bn', url: '' }] });
    expect(subs).toEqual([{ lang: 'en', label: 'English', url: subtitleProxy(BASE, 'https://c/en.srt'), format: 'VTT' }]);
  });

  it('pickVariant + applyResolved (noSource when empty)', () => {
    const s = streamToSources(BASE, '5', st);
    expect(pickVariant(s, 720)?.height).toBe(480);
    expect(pickVariant(s, 360)?.height).toBe(480);
    const item = mapGwDetail(sample('detail_inception-e1BOR6f19C7.json'), 'HOME')!;
    const m = buildMedia(item, undefined, () => true, (p) => p);
    const ok = applyResolved(m, { sources: s, subtitles: [] }, 1080);
    expect(ok.source?.height).toBe(1080);
    expect(ok.variants?.length).toBe(2);
    const none = applyResolved(m, { sources: [], subtitles: [] });
    expect(none.playable).toBe(false);
    expect(none.noSource).toBe(true);
  });

  it('resolver: stream (full=1 + captions), TMDB find via search, dead titles', async () => {
    const urls: string[] = [];
    const dead: string[] = [];
    const r = new StreamResolver({
      base: () => BASE,
      getJson: async <T,>(url: string) => {
        urls.push(url);
        if (url.includes('/api/v1/resolve')) return { ok: true, sources: [] } as T;
        if (url.includes('/captions')) return { captions: [] } as T;
        if (url.includes('/stream/5?')) return st as T;
        return { sources: [] } as T;
      },
      search: async () => [{ name: 'Inception', year: '2010', type: 'movie', subject_id: '5', slug: 's' }],
      onDead: (id) => dead.push(id)
    });
    const got = await r.resolve({ url: 'gw-stream:5?detail_path=s&se=0&ep=0' });
    expect(got.sources.length).toBe(2);
    expect(urls[0]).toContain('full=1');
    const viaFind = await r.resolve(findPlaceholder({ title: 'Inception', year: 2010, type: 'movie', tmdb: 27205, se: 0, ep: 0 }));
    expect(viaFind.sources.length).toBe(2);
    expect(urls.some((u) => u.includes('/api/v1/resolve?tmdb=27205'))).toBe(true);
    const empty = await r.resolve({ url: 'gw-stream:6?detail_path=z&se=0&ep=0' });
    expect(empty.sources).toEqual([]);
    expect(dead).toEqual(['6']);
  });

  it('find placeholder roundtrip + title matching', () => {
    const f = findPlaceholder({ title: 'Breaking Bad', year: 2008, type: 'tv', tmdb: 1396, se: 1, ep: 2 });
    expect(parseFindRef(f.url)).toEqual({ title: 'Breaking Bad', year: 2008, type: 'tv', tmdb: 1396, se: 1, ep: 2 });
    const hits: GwListItem[] = [
      { name: 'Breaking Bad [Hindi]', year: '2008', type: 'tv', subject_id: '1', slug: 'a' },
      { name: 'Breaking Bad', year: '2008', type: 'movie', subject_id: '2', slug: 'b' },
      { name: 'Breaking Bad', year: '2008', type: 'tv', subject_id: '3', slug: 'c' }
    ];
    expect(matchSearch(hits, { title: 'Breaking Bad', year: 2008, type: 'tv' })?.subject_id).toBe('3');
    expect(matchSearch(hits, { title: 'Nothing Here', year: 2008, type: 'tv' })).toBeUndefined();
  });

  it('normalizes YouTube stream & trailer URLs into https://www.youtube-nocookie.com/embed/<videoId> with kind MP4', () => {
    const vid = 'dQw4w9WgXcQ';
    for (const raw of [
      `https://www.youtube.com/watch?v=${vid}&feature=share`,
      `https://m.youtube.com/watch?app=desktop&v=${vid}`,
      `https://youtu.be/${vid}?si=123`,
      `https://www.youtube.com/shorts/${vid}`,
      `https://www.youtube.com/embed/${vid}?autoplay=1`,
      `https://www.youtube-nocookie.com/embed/${vid}`,
      `yt://${vid}`
    ]) {
      expect(extractYouTubeVideoId(raw)).toBe(vid);
      expect(toYouTubeEmbedUrl(raw)).toBe(`https://www.youtube-nocookie.com/embed/${vid}`);
    }
    expect(extractYouTubeVideoId('https://cdn.example/video.mp4')).toBeNull();

    const ytStream: GwStream = {
      trailer_url: 'https://youtu.be/abcDEF123_-',
      sources: [
        { source: 'Zee Bangla', format: 'YOUTUBE', resolution: '720p', url: `https://www.youtube.com/watch?v=${vid}` },
        { format: 'MP4', resolution: '1080p', url: 'https://cdn.example/ep1.mp4' }
      ]
    };
    const out = streamToSources(BASE, '88', ytStream);
    const ytSrc = out.find((x) => x.url.includes('youtube-nocookie.com'))!;
    expect(ytSrc).toMatchObject({
      url: `https://www.youtube-nocookie.com/embed/${vid}`,
      kind: 'MP4',
      label: 'YouTube · HD'
    });
    const trailer = streamTrailerSource(ytStream);
    expect(trailer).toMatchObject({
      url: 'https://www.youtube-nocookie.com/embed/abcDEF123_-',
      kind: 'MP4',
      label: 'YouTube · HD'
    });
  });

  it('produces clean provider-free quality & dual-audio labels', () => {
    expect(cleanStreamLabel({ height: 2160 })).toBe('4K UHD');
    expect(cleanStreamLabel({ height: 1080, dual: true, size: 1610612736 })).toBe('1080p FHD · Dual Audio · 1.5 GB');
    expect(cleanStreamLabel({ height: 720, language: 'Bengali' })).toBe('720p HD · Bengali');
    expect(cleanStreamLabel({ height: 720, youtube: true })).toBe('YouTube · HD');
    expect(isDualAudio({ label: '1080p', url: 'https://cdn.example/Movie.2026.Hindi-Eng.1080p.mp4' })).toBe(true);
    expect(isDualAudio({ label: '1080p', url: 'https://cdn.example/Movie.2026.1080p.mp4' })).toBe(false);
  });

  it('ranks BDIX first on Wi-Fi (unmetered) and Gateway Proxy MP4 first on mobile/cellular', () => {
    expect(detectNetworkKind({ type: 'wifi' })).toBe('wifi');
    expect(detectNetworkKind({ type: 'cellular' })).toBe('mobile');
    expect(detectNetworkKind({ type: 'wifi', saveData: true })).toBe('mobile');

    const mixed: GwStream = {
      sources: [
        { source: 'YouTube', format: 'YOUTUBE', resolution: '720p', url: 'https://youtu.be/dQw4w9WgXcQ' },
        { format: 'MP4', resolution: '720p', url: 'https://cdn.example/720.mp4' },
        { format: 'MP4', resolution: '1080p', url: 'https://cdn.example/1080.mp4' },
        { source: 'BDIX', isBdix: true, format: 'MP4', resolution: '1080p', url: 'http://bdix.local/movie-1080.mp4' }
      ]
    };

    const wifiRanked = streamToSources(BASE, '9', mixed, { network: 'wifi' });
    expect(wifiRanked[0].url).toBe('http://bdix.local/movie-1080.mp4');
    expect(wifiRanked[1].url).toContain('/api/public/v1/stream/9/play?');
    expect(wifiRanked[1].height).toBe(1080);
    expect(wifiRanked[wifiRanked.length - 1].url).toBe('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ');

    const mobileRanked = streamToSources(BASE, '9', mixed, { network: 'mobile' });
    // Mobile: gateway proxy MP4 first (720p mobileFit ahead of 1080p), BDIX last!
    expect(mobileRanked[0].url).toContain('/api/public/v1/stream/9/play?');
    expect(mobileRanked[0].height).toBe(720);
    expect(mobileRanked[1].url).toContain('/api/public/v1/stream/9/play?');
    expect(mobileRanked[1].height).toBe(1080);
    expect(mobileRanked[mobileRanked.length - 1].url).toBe('http://bdix.local/movie-1080.mp4');
  });

  it('tracks failed hosts & URLs in LinkHealth without poisoning the gateway proxy host itself', () => {
    let clock = 1_000;
    const health = new LinkHealth(() => clock);
    const badProxy = proxyUrl(BASE, '5', 'https://dead-cdn.example/a.mp4', '1080p');
    const siblingOnBadHost = proxyUrl(BASE, '5', 'https://dead-cdn.example/b.mp4', '720p');
    const healthyProxy = proxyUrl(BASE, '5', 'https://good-cdn.example/a.mp4', '720p');

    // 403 on proxied dead-cdn marks dead-cdn.example refused, leaving good-cdn.example on the same gateway healthy!
    health.failed(badProxy, 403);
    expect(health.refused(badProxy)).toBe(true);
    expect(health.refused(siblingOnBadHost)).toBe(true);
    expect(health.refused(healthyProxy)).toBe(false);

    // Ranking skips refused links, drops image links, and promotes dual-audio when preferred.
    expect(isImageLink('https://image.tmdb.org/t/p/w500/p.jpg')).toBe(true);
    health.preferDualAudio = true;
    const ranked = rankSources(
      [
        { url: 'https://cdn.example/poster.jpg', kind: 'MP4', height: 1080, label: '1080p FHD' },
        { url: badProxy, kind: 'MP4', height: 1080, label: '1080p FHD' },
        { url: healthyProxy, kind: 'MP4', height: 720, label: '720p HD' },
        { url: proxyUrl(BASE, '5', 'https://good-cdn.example/dual.mp4', '720p'), kind: 'MP4', height: 720, label: '720p HD · Dual Audio' }
      ],
      { health, network: 'wifi' }
    );
    expect(ranked.map((s) => s.label)).toEqual(['720p HD · Dual Audio', '720p HD']);

    // After 6 hours + 1ms, the cooldown expires.
    clock += 6 * 60 * 60_000 + 1;
    expect(health.refused(badProxy)).toBe(false);
  });
});

describe('catalog chain (gateway-only video spaces, isolated spaces untouched)', () => {
  const mock = () => new CatalogRepository({ baseUrl: '', fetcher: fileFetcher(), latency: NO_LATENCY, persist: null });

  it('Home/Anime/Drama come from the gateway; no mock fallback on failure', async () => {
    const ok = new Catalog({ mock: mock(), gateway: source(homeRoutes), online: () => true });
    await ok.file('HOME');
    expect(ok.originOf('HOME')).toBe('gateway');
    const broken = new Catalog({ mock: mock(), gateway: source({}), online: () => true });
    await expect(broken.file('ANIME')).rejects.toThrow();
    expect(broken.originOf('ANIME')).toBeUndefined();
  });

  it('TV / Music never call the gateway; Comics use MangaDex with the mock library as fallback', async () => {
    const calls: string[] = [];
    const c = new Catalog({ mock: mock(), gateway: source(homeRoutes, calls), online: () => true, adultUnlocked: () => true });
    for (const s of ['TV', 'MUSIC', 'COMICS'] as const) {
      await c.file(s);
      expect(c.originOf(s)).toBe('mock');
      expect(c.usesGateway(s)).toBe(false);
    }
    expect(calls).toEqual([]);

    // Live comics source wins when it answers; a failing one falls back to the bundled library.
    const md = new ComicsSource({
      web: async (url) => {
        calls.push(url);
        if (url.includes('/statistics/')) return { statistics: {} };
        return { data: [{ id: 'u1', attributes: { title: { en: 'Live Manga' }, originalLanguage: 'ja', year: 2020 }, relationships: [{ type: 'cover_art', attributes: { fileName: 'c.jpg' } }] }] };
      },
      gatewayBase: () => ''
    });
    const live = new Catalog({ mock: mock(), gateway: source(homeRoutes), comics: md, online: () => true });
    const f = await live.file('COMICS');
    expect(live.originOf('COMICS')).toBe('mangadex');
    expect(f.items[0].id).toBe('co-md:u1');
    expect(calls.some((u) => u.includes('api.mangadex.org'))).toBe(true);
    expect(calls.some((u) => u.includes('antiblock') || u.includes('gw.test'))).toBe(false);

    const offline = new Catalog({
      mock: mock(),
      comics: new ComicsSource({ web: async () => { throw new Error('offline'); }, gatewayBase: () => '' }),
      online: () => true
    });
    await offline.file('COMICS');
    expect(offline.originOf('COMICS')).toBe('mock');
  });

  it('18+: gateway only when unlocked; locked = no request, no metadata', async () => {
    const calls: string[] = [];
    let unlocked = false;
    const adultPage = { page: 1, has_more: false, items: [{ name: 'A', poster_url: 'https://p/a.jpg', slug: 'a-1', subject_id: '77', genre: 'Adult,Erotic', type: 'movie', has_resource: true }] };
    const c = new Catalog({ mock: mock(), gateway: source({ '/adult': adultPage }, calls), online: () => true, adultUnlocked: () => unlocked });
    await expect(c.file('ADULT')).rejects.toThrow();
    expect(await c.item('ad-gw:77')).toBeUndefined();
    expect(calls).toEqual([]);
    unlocked = true;
    const f = await c.file('ADULT');
    expect(c.originOf('ADULT')).toBe('gateway');
    expect(f.items[0].id).toBe('ad-gw:77');
    expect(calls.every((u) => u.includes('/adult'))).toBe(true);
  });

  it('global search never returns 18+ (gateway /search only, video spaces)', async () => {
    const s = sample('search_q_inception.json');
    s.items.push({ name: 'Inception X', poster_url: 'https://p/n.jpg', slug: 'n', subject_id: '9', genre: 'Adult', type: 'movie', has_resource: true });
    const calls: string[] = [];
    const c = new Catalog({ mock: mock(), gateway: source({ '/search': s }, calls), online: () => true, adultUnlocked: () => true });
    const hits = await c.remoteSearch('inception');
    expect(hits.length).toBeGreaterThan(0);
    expect(hits.some((h) => h.space === 'ADULT' || h.id.startsWith('ad-'))).toBe(false);
    expect(calls.some((u) => u.includes('/adult'))).toBe(false);
  });
});

describe('Me → Spaces', () => {
  it('Live TV & Sports, Music, Comics; 18+ only when enabled', () => {
    expect(meSpaceEntries(false).map((e) => e.label)).toEqual(['Live TV & Sports', 'Music', 'Comics']);
    const on = meSpaceEntries(true, '/cinetor-app');
    expect(on.map((e) => e.space)).toEqual(['TV', 'MUSIC', 'COMICS', 'ADULT']);
    expect(on.map((e) => e.href)).toEqual(['/cinetor-app/app/tv', '/cinetor-app/app/music', '/cinetor-app/app/comics', '/cinetor-app/app/adult']);
    expect(on.find((e) => e.space === 'COMICS')?.blurb).toBe('Manga · Manhwa · Manhua');
  });
});
