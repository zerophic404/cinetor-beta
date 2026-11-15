import { describe, expect, it } from 'vitest';
import { memoryKV } from '../data/kv';
import { spaceForId } from '../data/catalog';
import type { Chapter } from '../data/types';
import { COMIC_CATS, ComicsSource, mapMdChapters, mapMdManga, mdChapterUuid, mdListUrl, mdPageUrls, type JsonFetch } from './mangadex';
import { defaultMode, effectiveMode, keyStep, nextChapter, preloadRange, ReadProgress, tapZone } from './reader';

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

describe('navigation', () => {
  it('tap zones mirror in RTL', () => {
    expect(tapZone(10, 100, 'rtl')).toBe('next');
    expect(tapZone(90, 100, 'rtl')).toBe('prev');
    expect(tapZone(10, 100, 'ltr')).toBe('prev');
    expect(tapZone(50, 100, 'ltr')).toBe('menu');
  });
  it('arrow keys follow reading direction', () => {
    expect(keyStep('ArrowLeft', 'rtl')).toBe(1);
    expect(keyStep('ArrowRight', 'ltr')).toBe(1);
    expect(keyStep('ArrowDown', 'vertical')).toBe(0);
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
});

describe('progress', () => {
  it('is saved per chapter', () => {
    const kv = memoryKV();
    const p = new ReadProgress(kv, 'p1');
    p.save('co-1', 'ch1', 4, 12);
    p.save('co-1', 'ch2', 1, 10);
    expect(p.get('co-1', 'ch1')?.page).toBe(4);
    expect(p.get('co-1', 'ch2')?.page).toBe(1);
    expect(p.last('co-1')).toEqual({ chapterId: 'ch2', page: 1 });
    expect(p.fraction('co-1', 'ch1')).toBeCloseTo(5 / 12);
    // another profile is isolated
    expect(new ReadProgress(kv, 'p2').get('co-1', 'ch1')).toBeUndefined();
  });
  it('remembers the mode override per title', () => {
    const p = new ReadProgress(memoryKV(), 'p1');
    expect(p.getMode('co-1')).toBeNull();
    p.setMode('co-1', 'ltr');
    expect(p.getMode('co-1')).toBe('ltr');
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

  it('chapters: external / empty chapters dropped, duplicates collapsed, page count kept', () => {
    const chs = mapMdChapters({
      data: [
        { id: 'a', attributes: { chapter: '1', title: 'Start', pages: 20 } },
        { id: 'b', attributes: { chapter: '1', title: 'Dup group', pages: 20 } },
        { id: 'c', attributes: { chapter: '2', title: '', pages: 0 } },
        { id: 'd', attributes: { chapter: '3', title: 'Linked', pages: 12, externalUrl: 'https://else.where' } },
        { id: 'e', attributes: { chapter: '4', title: null, pages: 7 } }
      ]
    });
    expect(chs.map((c) => c.id)).toEqual(['ch:a', 'ch:e']);
    expect(chs[0]).toMatchObject({ n: 1, title: 'Ch. 1 · Start', pageCount: 20, pages: [] });
    expect(chs[1].title).toBe('Chapter 4');
    expect(mdChapterUuid(chs[0].id)).toBe('a');
  });

  it('page urls built from at-home payload; empty payload = no pages', () => {
    const at = { baseUrl: 'https://md.network', chapter: { hash: 'h1', data: ['1.png', '2.png'], dataSaver: ['1s.jpg'] } };
    expect(mdPageUrls(at)).toEqual(['https://md.network/data/h1/1.png', 'https://md.network/data/h1/2.png']);
    expect(mdPageUrls(at, true)).toEqual(['https://md.network/data-saver/h1/1s.jpg']);
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

  it('builds Manga/Manhwa/Manhua categories with real covers', async () => {
    const list = { data: [mdManga('u1', 'ja'), mdManga('u2', 'ko'), mdManga('u3', 'zh')] };
    const src = new ComicsSource({
      web: routes({ 'api.mangadex.org/manga?': list, 'api.mangadex.org/statistics': { statistics: { u1: { rating: { bayesian: 9.1 } } } } }),
      gatewayBase: () => 'https://gw.test'
    });
    const f = await src.file();
    expect(f.space).toBe('COMICS');
    expect(f.categories.map((c) => c.id)).toEqual(['co-manga', 'co-manhwa', 'co-manhua']);
    expect(f.categories[0].rows.map((r) => r.id)).toEqual(['co-manga-popular', 'co-manga-latest']);
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

  it('chapter pages: gateway proxy first, direct MangaDex fallback, then unavailable', async () => {
    const at = { baseUrl: 'https://md.network', chapter: { hash: 'h', data: ['1.png'] } };
    // Gateway returns the known-empty payload → direct at-home wins.
    const calls: string[] = [];
    const src = new ComicsSource({
      web: routes({ 'gw.test/api/public/v1/manga?action=c2': { chapter: { data: [] } } }, calls),
      native: routes({ 'at-home/server': at }, calls),
      gatewayBase: () => 'https://gw.test'
    });
    expect(await src.chapterPages('ch:a')).toEqual(['https://md.network/data/h/1.png']);
    expect(calls[0]).toContain('action=c2&id=a');
    expect(calls.some((u) => u.includes('at-home/server/a'))).toBe(true);

    // Gateway serves pages → no direct call at all.
    const c2: string[] = [];
    const gwOk = new ComicsSource({ web: routes({ 'action=c2': at }, c2), gatewayBase: () => 'https://gw.test' });
    expect((await gwOk.chapterPages('ch:b')).length).toBe(1);
    expect(c2.some((u) => u.includes('at-home'))).toBe(false);

    // Everything fails → [] ("chapter unavailable"), never throws.
    const dead = new ComicsSource({ web: routes({}), gatewayBase: () => 'https://gw.test' });
    await expect(dead.chapterPages('ch:c')).resolves.toEqual([]);
  });
});
