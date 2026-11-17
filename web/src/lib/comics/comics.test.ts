import { describe, expect, it } from 'vitest';
import { memoryKV } from '../data/kv';
import { spaceForId } from '../data/catalog';
import type { Chapter } from '../data/types';
import {
  COMIC_CATS,
  ComicsSource,
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
  it('falls back from primary page URL to data-saver backup URL on image error before giving up', () => {
    const full = 'https://md.network/data/h1/1.png';
    const saver = 'https://md.network/data-saver/h1/1s.jpg';
    expect(resolvePageSrc(full, saver, 0)).toBe(full);
    expect(resolvePageSrc(full, saver, 1)).toBe(saver);
    expect(resolvePageSrc(full, saver, 2)).toContain('1s.jpg?');
    expect(resolvePageSrc(full, saver, 3)).toBeNull();
    // Data-saver preference starts directly with backup URL
    expect(resolvePageSrc(full, saver, 0, true)).toBe(saver);
    expect(resolvePageSrc(full, saver, 1, true)).toBe(full);
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
    // another profile is isolated
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

// ---------------------------------------------------------------- MangaDex catalogue

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

describe('mangadex urls + mapping', () => {
  it('browse url uses original language per kind and safe/suggestive ratings', () => {
    const u = new URL(mdListUrl({ cat: COMIC_CATS[1], limit: 24 }));
    expect(u.origin + u.pathname).toBe('https://api.mangadex.org/manga');
    expect(u.searchParams.getAll('originalLanguage[]')).toEqual(['ko']);
    expect(u.searchParams.getAll('contentRating[]')).toEqual(['safe', 'suggestive']);
    expect(u.searchParams.getAll('availableTranslatedLanguage[]')).toEqual(['en']);
    expect(u.searchParams.get('order[followedCount]')).toBe('desc');
    expect(u.searchParams.getAll('includes[]')).toContain('cover_art');
    expect(new URL(mdListUrl({ cat: COMIC_CATS[2] })).searchParams.getAll('originalLanguage[]')).toEqual(['zh', 'zh-hk']);
    expect(new URL(mdListUrl({ title: 'naruto' })).searchParams.get('title')).toBe('naruto');
    expect(mdGatewayPagesUrl('https://gw.test/', 'c-1', 'c2')).toBe('https://gw.test/api/public/v1/manga?action=c2&id=c-1');
    expect(mdGatewayPagesUrl('https://gw.test/', 'c-1', 'chapter')).toBe('https://gw.test/api/public/v1/manga?action=chapter&id=c-1');
  });

  it('manga maps RTL paged, manhwa/manhua vertical, with a real cover', () => {
    const m = mapMdManga(mdManga('u1', 'ja'), COMIC_CATS[0], 8.74)!;
    expect(m.id).toBe('co-md:u1');
    expect(spaceForId(m.id)).toBe('COMICS');
    expect(m.space).toBe('COMICS');
    expect(m.type).toBe('COMIC');
    expect(m.poster).toBe('https://uploads.mangadex.org/covers/u1/cover.jpg.512.jpg');
    expect(m.readingDirection).toBe('RTL');
    expect(m.format).toBe('PAGED');
    expect(defaultMode(m)).toBe('rtl');
    expect(m.rating).toBe(8.7);
    expect(m.genres).toEqual(['Action']);
    expect(m.synopsis).toBe('A great story with a link.');
    for (const [i, dir] of [[1, 'TTB'], [2, 'TTB']] as const) {
      const v = mapMdManga(mdManga(`u${i}`, i === 1 ? 'ko' : 'zh'), COMIC_CATS[i])!;
      expect(v.readingDirection).toBe(dir);
      expect(defaultMode(v)).toBe('vertical');
    }
    expect(mapMdManga({ id: 'x' })).toBeNull();
  });

  it('chapters: external / empty chapters dropped, duplicates collapsed, sorted numerically', () => {
    const chs = mapMdChapters({
      data: [
        { id: 'e', attributes: { chapter: '10', title: null, pages: 7 } },
        { id: 'a', attributes: { chapter: '1', title: 'Start', pages: 20 } },
        { id: 'b', attributes: { chapter: '1', title: 'Dup group', pages: 20 } },
        { id: 'c', attributes: { chapter: '2', title: '', pages: 0 } },
        { id: 'd', attributes: { chapter: '3', title: 'Linked', pages: 12, externalUrl: 'https://else.where' } },
        { id: 'f', attributes: { chapter: '2.5', title: 'Bonus', pages: 9 } }
      ]
    });
    expect(chs.map((c) => c.id)).toEqual(['ch:a', 'ch:f', 'ch:e']);
    expect(chs[0]).toMatchObject({ n: 1, title: 'Ch. 1 · Start', pageCount: 20, pages: [] });
    expect(chs[1].title).toBe('Ch. 2.5 · Bonus');
    expect(chs[2].title).toBe('Chapter 10');
    expect(mdChapterUuid(chs[0].id)).toBe('a');
  });

  it('page urls built from at-home or direct gateway pages payload; empty payload = no pages', () => {
    const at = { baseUrl: 'https://md.network', chapter: { hash: 'h1', data: ['1.png', '2.png'], dataSaver: ['1s.jpg', '2s.jpg'] } };
    expect(mdPageUrls(at)).toEqual(['https://md.network/data/h1/1.png', 'https://md.network/data/h1/2.png']);
    expect(mdPageUrls(at, true)).toEqual(['https://md.network/data-saver/h1/1s.jpg', 'https://md.network/data-saver/h1/2s.jpg']);
    expect(mdPageSet(at)).toEqual({
      primary: ['https://md.network/data/h1/1.png', 'https://md.network/data/h1/2.png'],
      backup: ['https://md.network/data-saver/h1/1s.jpg', 'https://md.network/data-saver/h1/2s.jpg']
    });
    expect(mdPageSet({ pages: ['https://cdn.example/p1.webp', 'https://cdn.example/p2.webp'] })).toEqual({
      primary: ['https://cdn.example/p1.webp', 'https://cdn.example/p2.webp'],
      backup: ['https://cdn.example/p1.webp', 'https://cdn.example/p2.webp']
    });
    expect(mdPageUrls({ baseUrl: 'https://md.network', chapter: { hash: '', data: [] } })).toEqual([]);
    expect(mdPageUrls(null)).toEqual([]);
  });
});

describe('ComicsSource', () => {
  const routes = (map: Record<string, unknown>, calls: string[] = []): JsonFetch => async (url) => {
    calls.push(url);
    const hit = Object.entries(map).find(([k]) => url.startsWith(k) || url.includes(k));
    if (!hit) throw new Error('HTTP 404');
    return hit[1];
  };

  it('builds Manga/Manhwa/Manhua categories with Popular, Top Rated (TOP10), Latest & New shelves', async () => {
    const list = { data: [mdManga('u1', 'ja'), mdManga('u2', 'ko'), mdManga('u3', 'zh')] };
    const src = new ComicsSource({
      web: routes({ 'api.mangadex.org/manga?': list, 'api.mangadex.org/statistics': { statistics: { u1: { rating: { bayesian: 9.1 } } } } }),
      gatewayBase: () => 'https://gw.test'
    });
    const f = await src.file();
    expect(f.space).toBe('COMICS');
    expect(f.categories.map((c) => c.id)).toEqual(['co-manga', 'co-manhwa', 'co-manhua']);
    expect(f.categories[0].rows.map((r) => r.id)).toEqual(['co-manga-popular', 'co-manga-top', 'co-manga-latest', 'co-manga-new']);
    expect(f.categories[0].rows[1].style).toBe('TOP10');
    expect(f.items.every((i) => i.space === 'COMICS' && i.poster.startsWith('https://uploads.mangadex.org/'))).toBe(true);
    expect(f.categories[0].heroIds.length).toBeGreaterThan(0);
  });

  it('item() attaches the chapter list, pages stay lazy', async () => {
    const src = new ComicsSource({
      web: routes({ '/manga/u1/feed': { data: [{ id: 'a', attributes: { chapter: '1', pages: 5 } }] }, '/manga/u1?': { data: mdManga('u1', 'ja') } }),
      gatewayBase: () => ''
    });
    const it = (await src.item('co-md:u1'))!;
    expect(it.title).toBe('Test Title');
    expect(it.pages?.map((c) => c.id)).toEqual(['ch:a']);
    expect(it.pages?.[0].pages).toEqual([]);
  });

  it('live search queries MangaDex by title + optional category and attaches ratings', async () => {
    const calls: string[] = [];
    const src = new ComicsSource({
      web: routes(
        {
          'api.mangadex.org/manga?': { data: [mdManga('s1', 'ko', 'Solo Leveling')] },
          'api.mangadex.org/statistics': { statistics: { s1: { rating: { bayesian: 9.35 } } } }
        },
        calls
      ),
      gatewayBase: () => ''
    });
    const hits = await src.search('Solo Leveling', undefined, 'co-manhwa');
    expect(hits.length).toBe(1);
    expect(hits[0].title).toBe('Solo Leveling');
    expect(hits[0].rating).toBe(9.4);
    expect(hits[0].categoryId).toBe('co-manhwa');
    expect(calls[0]).toContain('originalLanguage%5B%5D=ko');
  });

  it('chapter pages: gateway c2 → gateway chapter → MangaDex at-home (with data-saver backup) → unavailable', async () => {
    const at = { baseUrl: 'https://md.network', chapter: { hash: 'h', data: ['1.png'], dataSaver: ['1s.jpg'] } };
    // Gateway c2 returns empty, action=chapter fails → direct at-home via native bridge wins and stores backup.
    const calls: string[] = [];
    const src = new ComicsSource({
      web: routes({ 'gw.test/api/public/v1/manga?action=c2': { chapter: { data: [] } } }, calls),
      native: routes({ 'at-home/server': at }, calls),
      gatewayBase: () => 'https://gw.test'
    });
    expect(await src.chapterPages('ch:a')).toEqual(['https://md.network/data/h/1.png']);
    expect(src.backupPages('ch:a')).toEqual(['https://md.network/data-saver/h/1s.jpg']);
    expect(await src.chapterPages('ch:a', undefined, { dataSaver: true })).toEqual(['https://md.network/data-saver/h/1s.jpg']);
    expect(calls[0]).toContain('action=c2&id=a');
    expect(calls.some((u) => u.includes('action=chapter&id=a'))).toBe(true);
    expect(calls.some((u) => u.includes('at-home/server/a'))).toBe(true);

    // Gateway action=chapter serves pages when action=c2 fails → no direct at-home call needed.
    const chCalls: string[] = [];
    const gwChapterOk = new ComicsSource({
      web: routes({ 'action=chapter': { pages: ['https://cdn.example/1.jpg'] } }, chCalls),
      gatewayBase: () => 'https://gw.test'
    });
    expect(await gwChapterOk.chapterPages('ch:b2')).toEqual(['https://cdn.example/1.jpg']);
    expect(chCalls.some((u) => u.includes('at-home'))).toBe(false);

    // Gateway c2 serves pages → no action=chapter or direct call at all.
    const c2: string[] = [];
    const gwOk = new ComicsSource({ web: routes({ 'action=c2': at }, c2), gatewayBase: () => 'https://gw.test' });
    expect((await gwOk.chapterPages('ch:b')).length).toBe(1);
    expect(c2.some((u) => u.includes('at-home'))).toBe(false);

    // Everything fails → [] ("chapter unavailable"), never throws.
    const dead = new ComicsSource({ web: routes({}), gatewayBase: () => 'https://gw.test' });
    await expect(dead.chapterPages('ch:c')).resolves.toEqual([]);
  });
});
