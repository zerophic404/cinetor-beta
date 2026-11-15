import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { CatalogRepository, NO_LATENCY } from './catalog';
import {
  DeadTitles,
  GatewayCatalogSource,
  GatewayClient,
  classifyGw,
  gatewayBase,
  gatewayPlan,
  gwItemId,
  isGatewayAdultId,
  mapGwDetail,
  mapGwItem,
  normalizeBase,
  subjectIdOf,
  type GwDetail,
  type GwFetcher,
  type GwListItem
} from './gateway';
import {
  StreamResolver,
  applyResolved,
  captionsToSubs,
  directPlayUrl,
  findPlaceholder,
  isLazySource,
  matchSearch,
  parseFindRef,
  parseStreamRef,
  pickVariant,
  proxyUrl,
  streamToSources,
  subtitleProxy,
  type GwStream
} from './gateway-play';
import { gwImgUrl, imgAttrs } from './images';
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
  '/animation': sample('animation.json')
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
  it('home: Movies/Series categories, Banner hero, paged rows, sections', async () => {
    const calls: string[] = [];
    const src = source(homeRoutes, calls);
    const f = await src.file('HOME');
    expect(f.categories.map((c) => c.id)).toEqual(['gw-movies', 'gw-series']);
    const banner = sample('home.json').sections[0].items.map((i: GwListItem) => `gw:${i.subject_id}`);
    expect(f.categories[0].heroIds.every((id) => banner.includes(id))).toBe(true);
    const latest = f.categories[0].rows[0];
    expect(latest.id).toBe('gw-movies-latest');
    expect(latest.more).toEqual({ path: '/movies', params: {}, page: 1, hasMore: true });
    expect(f.categories[0].rows.length).toBeGreaterThan(1);
    expect(f.items.every((i) => i.id.startsWith('gw:') && /^https:\/\//.test(i.poster) && !!i.slug)).toBe(true);
    const movie = f.items.find((i) => i.type === 'MOVIE')!;
    expect(isLazySource(movie.sources?.[0])).toBe(true);
    // Isolated endpoints never touched for HOME.
    expect(calls.some((u) => u.includes('/adult'))).toBe(false);
  });

  it('paged rows: next page + genre param', async () => {
    const calls: string[] = [];
    const src = source({ '/movies': (u: URL) => ({ ...sample('movies.json'), page: Number(u.searchParams.get('page')), has_more: false }) }, calls);
    const r = await src.morePage('HOME', { path: '/movies', params: {}, page: 1, hasMore: true }, 'gw-movies');
    expect(new URL(calls[0]).searchParams.get('page')).toBe('2');
    expect(r.more).toMatchObject({ page: 2, hasMore: false });
    expect(r.items.length).toBeGreaterThan(0);
    const g = await src.genrePage('HOME', 'gw-movies', 'Action');
    expect(new URL(calls[1]).searchParams.get('genre')).toBe('Action');
    expect(g?.items.length).toBeGreaterThan(0);
  });

  it('drama: K/C/J use country=, anime uses /animation', () => {
    const d = gatewayPlan('DRAMA');
    expect(d.map((c) => c.title)).toEqual(['K-Drama', 'C-Drama', 'J-Drama']);
    expect(JSON.stringify(d[0].rows)).toContain('"country":"Korea"');
    expect(JSON.stringify(gatewayPlan('ANIME'))).toContain('/animation');
    expect(JSON.stringify(gatewayPlan('ADULT'))).toContain('/adult');
  });

  it('only playable (has_resource) + not-dead titles are listed', () => {
    const raw: GwListItem = { name: 'X', poster_url: 'https://p/x.jpg', slug: 'x-1', subject_id: '1', type: 'movie', has_resource: false };
    expect(mapGwItem(raw, 'HOME')).toBeNull();
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

  it('TV / Music / Comics never call the gateway', async () => {
    const calls: string[] = [];
    const c = new Catalog({ mock: mock(), gateway: source(homeRoutes, calls), online: () => true, adultUnlocked: () => true });
    for (const s of ['TV', 'MUSIC', 'COMICS'] as const) {
      await c.file(s);
      expect(c.originOf(s)).toBe('mock');
      expect(c.usesGateway(s)).toBe(false);
    }
    expect(calls).toEqual([]);
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
