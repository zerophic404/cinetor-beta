import { describe, expect, it } from 'vitest';
import { memoryKV } from '../data/kv';
import { spaceForId } from '../data/catalog';
import type { PersistentCache } from '../data/idb';
import type { Chapter } from '../data/types';
import {
  AL_PREFIX,
  COMIC_CATS,
  ComicsSource,
  cleanAnilistHtml,
  gwAnilistUrl,
  gwMangaImgUrl,
  isMangadexId,
  mapAnilistMedia,
  mapC1Chapters,
  mapMdChapters,
  mapMdManga,
  mdChapterUuid,
  mdGatewayPagesUrl,
  mdListUrl,
  mdPageSet,
  mdPageUrls,
  type JsonFetch
} from './mangadex';
import {
  defaultMode,
  effectiveMode,
  filterChapters,
  keyStep,
  nextChapter,
  preloadRange,
  ReadProgress,
  resolvePageSrc,
  tapZone,
  verticalScrollStep
} from './reader';

const manga = { categoryId: 'co-manga', format: 'PAGED', readingDirection: 'RTL' } as const;
const manhwa = { categoryId: 'co-manhwa', format: 'LONGSTRIP', readingDirection: 'TTB' } as const;
const manhua = { categoryId: 'co-manhua', format: 'LONGSTRIP', readingDirection: 'TTB' } as const;

function memPersist(): PersistentCache {
  const store = new Map<string, unknown>();
  return {
    get: async (k) => store.get(k),
    set: async (k, v) => {
      store.set(k, v);
    }
  };
}

describe('reading mode defaults', () => {
  it('manga defaults to paged right-to-left', () => {
    expect(defaultMode(manga)).toBe('rtl');
  });
  it('manhwa and manhua default to vertical', () => {
    expect(defaultMode(manhwa)).toBe('vertical');
    expect(defaultMode(manhua)).toBe('vertical');
  });
  it('user override wins', () => {
    expect(effectiveMode(manga, 'vertical')).toBe('vertical');
    expect(effectiveMode(manhwa, null)).toBe('vertical');
  });
});

describe('navigation & chapter filtering', () => {
  it('tap zones mirror in RTL', () => {
    expect(tapZone(10, 100, 'rtl')).toBe('next');
    expect(tapZone(90, 100, 'rtl')).toBe('prev');
    expect(tapZone(10, 100, 'ltr')).toBe('prev');
    expect(tapZone(50, 100, 'ltr')).toBe('menu');
  });
  it('arrow keys follow reading direction in paged mode and verticalScrollStep handles webtoon keys', () => {
    expect(keyStep('ArrowLeft', 'rtl')).toBe(1);
    expect(keyStep('ArrowRight', 'ltr')).toBe(1);
    expect(keyStep('ArrowDown', 'vertical')).toBe(0);
    expect(verticalScrollStep('ArrowDown')).toBe(1);
    expect(verticalScrollStep('PageDown')).toBe(1);
    expect(verticalScrollStep(' ', false)).toBe(1);
    expect(verticalScrollStep(' ', true)).toBe(-1);
    expect(verticalScrollStep('ArrowUp')).toBe(-1);
    expect(verticalScrollStep('ArrowLeft')).toBe(0);
  });
  it('preloads next 3 pages, 1 on LOW tier, clipped at the end', () => {
    expect(preloadRange(0, 10, 'HIGH')).toEqual([1, 2, 3]);
    expect(preloadRange(0, 10, 'LOW')).toEqual([1]);
    expect(preloadRange(8, 10, 'MID')).toEqual([9]);
  });
  it('next chapter', () => {
    const chs: Chapter[] = [
      { id: 'c1', n: 1, title: '1', pages: [] },
      { id: 'c2', n: 2, title: '2', pages: [] }
    ];
    expect(nextChapter(chs, 'c1')?.id).toBe('c2');
    expect(nextChapter(chs, 'c2')).toBeUndefined();
  });
  it('filters chapters by query, unread state, and asc/desc sort order', () => {
    const chs: Chapter[] = [
      { id: 'c1', n: 1, title: 'Ch. 1 · Prologue', pages: [] },
      { id: 'c2', n: 2, title: 'Ch. 2 · Awakening', pages: [] },
      { id: 'c3', n: 12, title: 'Chapter 12', pages: [] }
    ];
    expect(filterChapters(chs, { order: 'desc' }).map((c) => c.id)).toEqual(['c3', 'c2', 'c1']);
    expect(filterChapters(chs, { query: 'awake' }).map((c) => c.id)).toEqual(['c2']);
    expect(filterChapters(chs, { query: '12' }).map((c) => c.id)).toEqual(['c3']);
    expect(filterChapters(chs, { unreadOnly: true, isRead: (id) => id === 'c1' }).map((c) => c.id)).toEqual(['c2', 'c3']);
  });
  it('falls back from proxied primary page URL to backup URL on image error before giving up', () => {
    const proxied = 'https://gw.test/api/public/v1/manga/img?u=https%3A%2F%2Fcdn.example%2F1.jpg&r=https%3A%2F%2Freader.example%2F';
    const raw = 'https://cdn.example/1.jpg';
    expect(resolvePageSrc(proxied, raw, 0)).toBe(proxied);
    expect(resolvePageSrc(proxied, raw, 1)).toBe(raw);
    expect(resolvePageSrc(proxied, raw, 2)).toContain('1.jpg?');
    expect(resolvePageSrc(proxied, raw, 3)).toBeNull();
  });
});

describe('progress', () => {
  it('is saved per chapter and lists recent items sorted by timestamp', () => {
    let clock = 100;
    const kv = memoryKV();
    const p = new ReadProgress(kv, 'p1', () => clock);
    p.save('co-1', 'ch1', 4, 12);
    clock = 250;
    p.save('co-2', 'ch9', 9, 10);
    clock = 180;
    p.save('co-1', 'ch2', 1, 10);
    expect(p.get('co-1', 'ch1')?.page).toBe(4);
    expect(p.get('co-1', 'ch2')?.page).toBe(1);
    expect(p.last('co-1')).toEqual({ chapterId: 'ch2', page: 1 });
    expect(p.fraction('co-1', 'ch1')).toBeCloseTo(5 / 12);
    expect(p.isRead('co-2', 'ch9')).toBe(true);
    expect(p.isRead('co-1', 'ch2')).toBe(false);
    expect(p.recentItems().map((r) => r.itemId)).toEqual(['co-2', 'co-1']);
    expect(new ReadProgress(kv, 'p2').get('co-1', 'ch1')).toBeUndefined();
  });
  it('remembers the mode override and data-saver preference per profile', () => {
    const p = new ReadProgress(memoryKV(), 'p1');
    expect(p.getMode('co-1')).toBeNull();
    p.setMode('co-1', 'ltr');
    expect(p.getMode('co-1')).toBe('ltr');
    expect(p.getDataSaver()).toBe(false);
    p.setDataSaver(true);
    expect(p.getDataSaver()).toBe(true);
  });
});

// ---------------------------------------------------------------- Gateway AniList + Reader pipeline

const alMedia = (id: number, country: 'JP' | 'KR' | 'CN', title = 'Solo Leveling') => ({
  id,
  countryOfOrigin: country,
  status: 'FINISHED',
  averageScore: 87,
  genres: ['Action', 'Fantasy'],
  chapters: 200,
  synonyms: ['Na 혼자만 레벨업'],
  bannerImage: 'https://s4.anilist.co/file/anilistcdn/media/manga/banner/105398.jpg',
  description: 'Ten years ago, <b>the Gate</b> appeared.<br><br><i>Source: Official</i>',
  startDate: { year: 2018 },
  title: { english: title, romaji: 'Ore Dake Level Up na Ken', native: '나 혼자만 레벨업' },
  coverImage: {
    extraLarge: `https://s4.anilist.co/file/anilistcdn/media/manga/cover/large/bx${id}.jpg`,
    large: `https://s4.anilist.co/file/anilistcdn/media/manga/cover/medium/bx${id}.jpg`
  }
});

const mdManga = (id: string, lang: string, title = 'Test Title') => ({
  id,
  attributes: {
    title: { en: title },
    altTitles: [{ ja: 'テスト' }],
    description: { en: 'A **great** story with [a link](https://x.test).' },
    year: 2019,
    contentRating: 'safe',
    originalLanguage: lang,
    tags: [{ attributes: { name: { en: 'Action' }, group: 'genre' } }, { attributes: { name: { en: 'Oneshot' }, group: 'format' } }]
  },
  relationships: [
    { type: 'cover_art', attributes: { fileName: 'cover.jpg' } },
    { type: 'author', attributes: { name: 'Someone' } }
  ]
});

describe('gateway AniList + MangaDex mapping & urls', () => {
  it('maps AniList media with s4.anilist.co covers, clean synopsis, score and origin format', () => {
    expect(cleanAnilistHtml('Line 1<br/>Line 2 &amp; <b>bold</b> &quot;q&quot;')).toBe('Line 1\nLine 2 & bold "q"');
    const kr = mapAnilistMedia(alMedia(105398, 'KR', 'Solo Leveling'));
    expect(kr).not.toBeNull();
    expect(kr!.item.id).toBe(`${AL_PREFIX}105398`);
    expect(isMangadexId(kr!.item.id)).toBe(true);
    expect(spaceForId(kr!.item.id)).toBe('COMICS');
    expect(kr!.item.categoryId).toBe('co-manhwa');
    expect(kr!.item.format).toBe('LONGSTRIP');
    expect(kr!.item.readingDirection).toBe('TTB');
    expect(kr!.item.poster).toContain('https://s4.anilist.co/');
    expect(kr!.item.backdrop).toContain('banner/105398.jpg');
    expect(kr!.item.rating).toBe(8.7);
    expect(kr!.item.year).toBe(2018);
    expect(kr!.item.synopsis).toBe('Ten years ago, the Gate appeared.\nSource: Official');
    expect(kr!.altTitles).toContain('Ore Dake Level Up na Ken');

    const jp = mapAnilistMedia(alMedia(30002, 'JP', 'Berserk'))!;
    expect(jp.item.categoryId).toBe('co-manga');
    expect(jp.item.format).toBe('PAGED');
    expect(jp.item.readingDirection).toBe('RTL');

    const cn = mapAnilistMedia(alMedia(101234, 'CN', 'Tales of Demons and Gods'))!;
    expect(cn.item.categoryId).toBe('co-manhua');
    expect(cn.item.format).toBe('LONGSTRIP');
  });

  it('builds gateway /manga/gql and /manga/img proxy URLs cleanly', () => {
    const gql = gwAnilistUrl('https://gw.test/', 'query { Page { media { id } } }', { page: 1 });
    expect(gql.startsWith('https://gw.test/api/public/v1/manga/gql?body=')).toBe(true);
    const imgProxied = gwMangaImgUrl('https://gw.test/', 'https://cdn.reader.org/p/01.jpg', 'https://reader.org/');
    expect(imgProxied).toBe(
      'https://gw.test/api/public/v1/manga/img?u=https%3A%2F%2Fcdn.reader.org%2Fp%2F01.jpg&r=https%3A%2F%2Freader.org%2F'
    );
  });

  it('maps c1 reader chapters sorted numerically in ascending reading order', () => {
    const chs = mapC1Chapters([
      { id: '01JAAA10', n: '10', t: '' },
      { id: '01JAAA01', n: '1', t: 'The Weakest Hunter' },
      { id: '01JAAA01DUP', n: '1', t: 'Duplicate' },
      { id: '01JAAA025', n: '2.5', t: 'Side Story' }
    ]);
    expect(chs.map((c) => c.id)).toEqual(['ch:ck-01JAAA01', 'ch:ck-01JAAA025', 'ch:ck-01JAAA10']);
    expect(chs[0].title).toBe('Ch. 1 · The Weakest Hunter');
    expect(chs[1].title).toBe('Ch. 2.5 · Side Story');
    expect(chs[2].title).toBe('Chapter 10');
  });

  it('preserves MangaDex helpers & URL builders for fallback parity', () => {
    const u = new URL(mdListUrl({ cat: COMIC_CATS[1], limit: 24 }));
    expect(u.origin + u.pathname).toBe('https://api.mangadex.org/manga');
    expect(mdGatewayPagesUrl('https://gw.test/', 'c-1', 'c2')).toBe('https://gw.test/api/public/v1/manga?action=c2&id=c-1');
    const m = mapMdManga(mdManga('u1', 'ja'), COMIC_CATS[0], 8.74)!;
    expect(m.id).toBe('co-md:u1');
    const chs = mapMdChapters({
      data: [
        { id: 'e', attributes: { chapter: '10', title: null, pages: 7 } },
        { id: 'a', attributes: { chapter: '1', title: 'Start', pages: 20 } }
      ]
    });
    expect(chs.map((c) => c.id)).toEqual(['ch:a', 'ch:e']);
    expect(mdChapterUuid(chs[0].id)).toBe('a');
    const at = { baseUrl: 'https://md.network', chapter: { hash: 'h1', data: ['1.png'], dataSaver: ['1s.jpg'] } };
    expect(mdPageUrls(at)).toEqual(['https://md.network/data/h1/1.png']);
    expect(mdPageSet(at).backup).toEqual(['https://md.network/data-saver/h1/1s.jpg']);
  });
});

describe('ComicsSource (Gateway AniList + c1/c2/dex/img pipeline)', () => {
  it('fetches browse shelves & search strictly via gateway /manga/gql (zero direct api.mangadex.org CORS calls)', async () => {
    const calls: string[] = [];
    const web: JsonFetch = async (url) => {
      calls.push(url);
      const u = new URL(url);
      if (u.pathname === '/api/public/v1/manga/gql') {
        const parsed = JSON.parse(u.searchParams.get('body') ?? '{}') as { variables?: { country?: string; search?: string } };
        const c = parsed.variables?.country;
        if (parsed.variables?.search) {
          return { data: { Page: { media: [alMedia(105398, 'KR', 'Solo Leveling')] } } };
        }
        if (c === 'JP') return { data: { Page: { media: [alMedia(30002, 'JP', 'Berserk')] } } };
        if (c === 'KR') return { data: { Page: { media: [alMedia(105398, 'KR', 'Solo Leveling')] } } };
        if (c === 'CN') return { data: { Page: { media: [alMedia(101234, 'CN', 'Martial Peak')] } } };
        return {
          data: {
            Page: {
              media: [alMedia(105398, 'KR', 'Solo Leveling'), alMedia(30002, 'JP', 'Berserk'), alMedia(101234, 'CN', 'Martial Peak')]
            }
          }
        };
      }
      throw new Error(`Unexpected URL: ${url}`);
    };

    const src = new ComicsSource({ web, gatewayBase: () => 'https://gw.test', persist: memPersist() });
    const f = await src.file();
    expect(f.space).toBe('COMICS');
    expect(calls.length).toBeGreaterThan(0);
    expect(calls.every((u) => u.startsWith('https://gw.test/api/public/v1/manga/gql'))).toBe(true);
    expect(calls.some((u) => u.includes('api.mangadex.org'))).toBe(false);
    expect(f.categories.map((c) => c.id)).toEqual(['co-all', 'co-manga', 'co-manhwa', 'co-manhua']);
    expect(f.categories[0].rows.map((r) => r.title)).toEqual([
      'Trending',
      'Popular Manhwa',
      'Popular Manga',
      'Popular Manhua',
      'Top Rated'
    ]);
    expect(f.items.every((i) => i.poster.startsWith('https://s4.anilist.co/'))).toBe(true);

    const hits = await src.search('Solo Leveling', undefined, 'co-manhwa');
    expect(hits.length).toBe(1);
    expect(hits[0].id).toBe('co-al:105398');
    expect(hits[0].title).toBe('Solo Leveling');
  });

  it('resolves chapters via /manga/c1 with fallback to /manga/dex, persists metadata across reloads, and proxies /manga/c2 pages with Referer via /manga/img', async () => {
    const calls: string[] = [];
    const persist = memPersist();
    const web: JsonFetch = async (url) => {
      calls.push(url);
      const u = new URL(url);
      if (u.pathname === '/api/public/v1/manga/gql') {
        return { data: { Media: alMedia(105398, 'KR', 'Solo Leveling') } };
      }
      if (u.pathname === '/api/public/v1/manga/c1') {
        return { chapters: [{ id: '01JXYZ001', n: '1', t: 'Prologue' }, { id: '01JXYZ002', n: '2', t: '' }] };
      }
      if (u.pathname === '/api/public/v1/manga/c2') {
        return {
          pages: ['https://planeptune.us/manga/solo/001.jpg', 'https://planeptune.us/manga/solo/002.jpg'],
          ref: 'https://weebcentral.com/'
        };
      }
      throw new Error(`Unexpected ${url}`);
    };

    const src = new ComicsSource({ web, gatewayBase: () => 'https://gw.test', persist });
    const full = (await src.item('co-al:105398'))!;
    expect(full.title).toBe('Solo Leveling');
    expect(full.pages?.map((c) => c.id)).toEqual(['ch:ck-01JXYZ001', 'ch:ck-01JXYZ002']);

    // Simulate reload: brand-new ComicsSource instance with offline network recovers full item + chapters from persist!
    const reloaded = new ComicsSource({
      web: async () => {
        throw new Error('offline');
      },
      gatewayBase: () => 'https://gw.test',
      persist
    });
    const recovered = (await reloaded.item('co-al:105398'))!;
    expect(recovered.title).toBe('Solo Leveling');
    expect(recovered.poster).toContain('s4.anilist.co');
    expect(recovered.pages?.length).toBe(2);

    // Chapter pages wrap external hotlink-protected CDN images through /api/public/v1/manga/img?u=...&r=...
    const pages = await src.chapterPages('ch:ck-01JXYZ001');
    expect(pages).toEqual([
      'https://gw.test/api/public/v1/manga/img?u=https%3A%2F%2Fplaneptune.us%2Fmanga%2Fsolo%2F001.jpg&r=https%3A%2F%2Fweebcentral.com%2F',
      'https://gw.test/api/public/v1/manga/img?u=https%3A%2F%2Fplaneptune.us%2Fmanga%2Fsolo%2F002.jpg&r=https%3A%2F%2Fweebcentral.com%2F'
    ]);
    expect(src.backupPages('ch:ck-01JXYZ001')).toEqual([
      'https://planeptune.us/manga/solo/001.jpg',
      'https://planeptune.us/manga/solo/002.jpg'
    ]);
  });

  it('falls back to /api/public/v1/manga/dex/.../feed + at-home when /manga/c1 has no chapters', async () => {
    const web: JsonFetch = async (url) => {
      const u = new URL(url);
      if (u.pathname === '/api/public/v1/manga/gql') {
        return { data: { Media: alMedia(30002, 'JP', 'Berserk') } };
      }
      if (u.pathname === '/api/public/v1/manga/c1') return { chapters: [] };
      if (u.pathname === '/api/public/v1/manga/dex/manga') {
        return { data: [{ id: 'dex-berserk', attributes: { title: { en: 'Berserk' }, links: { al: '30002' } } }] };
      }
      if (u.pathname === '/api/public/v1/manga/dex/manga/dex-berserk/feed') {
        return { data: [{ id: 'dex-ch-1', attributes: { chapter: '1', title: 'The Black Swordsman', pages: 45 } }] };
      }
      if (u.pathname === '/api/public/v1/manga/dex/at-home/server/dex-ch-1') {
        return { baseUrl: 'https://cmdxd98sb0x3yprd.mangadex.network', chapter: { hash: 'h9', data: ['p1.png'], dataSaver: ['p1s.jpg'] } };
      }
      throw new Error(`404 ${url}`);
    };

    const src = new ComicsSource({ web, gatewayBase: () => 'https://gw.test', persist: memPersist() });
    const item = (await src.item('co-al:30002'))!;
    expect(item.pages?.map((c) => c.id)).toEqual(['ch:dex-ch-1']);
    const pages = await src.chapterPages('ch:dex-ch-1');
    expect(pages).toEqual([
      'https://gw.test/api/public/v1/manga/img?u=https%3A%2F%2Fcmdxd98sb0x3yprd.mangadex.network%2Fdata%2Fh9%2Fp1.png&r=https%3A%2F%2Fmangadex.org%2F'
    ]);
    expect(src.backupPages('ch:dex-ch-1')).toEqual(['https://cmdxd98sb0x3yprd.mangadex.network/data-saver/h9/p1s.jpg']);
  });

  it('rescues c1 chapters via /manga/dex at-home when /manga/c2 returns empty pages', async () => {
    const web: JsonFetch = async (url) => {
      const u = new URL(url);
      if (u.pathname === '/api/public/v1/manga/gql') {
        return { data: { Media: alMedia(30002, 'JP', 'Berserk') } };
      }
      if (u.pathname === '/api/public/v1/manga/c1') {
        return { chapters: [{ id: '01JEMPTY01', n: '1', t: 'The Black Swordsman' }] };
      }
      if (u.pathname === '/api/public/v1/manga/c2') return { pages: [] };
      if (u.pathname === '/api/public/v1/manga/dex/manga') {
        return { data: [{ id: 'dex-berserk', attributes: { title: { en: 'Berserk' }, links: { al: '30002' } } }] };
      }
      if (u.pathname === '/api/public/v1/manga/dex/manga/dex-berserk/feed') {
        return { data: [{ id: 'dex-ch-1', attributes: { chapter: '1', title: 'The Black Swordsman', pages: 45 } }] };
      }
      if (u.pathname === '/api/public/v1/manga/dex/at-home/server/dex-ch-1') {
        return { baseUrl: 'https://cmdxd98sb0x3yprd.mangadex.network', chapter: { hash: 'h9', data: ['rescued.png'], dataSaver: [] } };
      }
      throw new Error(`404 ${url}`);
    };

    const src = new ComicsSource({ web, gatewayBase: () => 'https://gw.test', persist: memPersist() });
    const item = (await src.item('co-al:30002'))!;
    expect(item.pages?.map((c) => c.id)).toEqual(['ch:ck-01JEMPTY01']);
    const pages = await src.chapterPages('ch:ck-01JEMPTY01');
    expect(pages).toEqual([
      'https://gw.test/api/public/v1/manga/img?u=https%3A%2F%2Fcmdxd98sb0x3yprd.mangadex.network%2Fdata%2Fh9%2Frescued.png&r=https%3A%2F%2Fmangadex.org%2F'
    ]);
  });

  it('keeps MangaDex fallback when gatewayBase is empty ("") for gateway.test.ts compatibility', async () => {
    const list = { data: [mdManga('u1', 'ja'), mdManga('u2', 'ko'), mdManga('u3', 'zh')] };
    const calls: string[] = [];
    const src = new ComicsSource({
      web: async (url) => {
        calls.push(url);
        if (url.includes('/statistics/')) return { statistics: { u1: { rating: { bayesian: 9.1 } } } };
        return list;
      },
      gatewayBase: () => ''
    });
    const f = await src.file();
    expect(f.items[0].id).toBe('co-md:u1');
    expect(calls.some((u) => u.includes('api.mangadex.org'))).toBe(true);
  });
});
