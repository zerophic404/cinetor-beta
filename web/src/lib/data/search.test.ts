import { describe, expect, it } from 'vitest';
import { CatalogRepository, NO_LATENCY } from './catalog';
import { fileFetcher } from './test-utils';
import { normalize, search, searchInSpace, searchItems } from './search';
import { ISOLATED_SPACES, SPACES, type CatalogFile, type Item, type Space } from './types';

function item(space: Space, id: string, title: string, altTitle?: string): Item {
  return {
    id, space, categoryId: 'c', type: 'MOVIE', title, altTitle, year: 2020, rating: 7, maturity: 'PG',
    genres: ['Drama'], synopsis: '', poster: 'images/p.webp', backdrop: 'images/b.webp',
    dominantColor: '#111111', accentColor: '#E50914'
  };
}

/** Every space contains a title matching "night" so leaks are detectable. */
function fakeRepo(calls: string[] = []) {
  const files: Record<string, CatalogFile> = {};
  for (const s of SPACES) {
    files[s.toLowerCase()] = {
      space: s,
      categories: [{ id: 'c', space: s, title: 'C', heroIds: [], rows: [] }],
      items: [item(s, `${s.toLowerCase()}-1`, `Night ${s}`), item(s, `${s.toLowerCase()}-2`, 'Other')]
    };
  }
  return new CatalogRepository({
    baseUrl: '',
    latency: NO_LATENCY,
    fetcher: async (url) => {
      calls.push(url);
      const name = url.split('/mock/')[1].replace('.json', '');
      return { ok: true, status: 200, json: async () => files[name] };
    }
  });
}

describe('globalSearch', () => {
  it('globalSearch excludes isolated spaces', async () => {
    const calls: string[] = [];
    const repo = fakeRepo(calls);
    // Even with isolated files already in memory they must not leak.
    for (const s of ISOLATED_SPACES) await repo.items(s);
    const r = await search(repo, 'night');
    const spaces = r.groups.map((g) => g.space).sort();
    expect(spaces).toEqual(['ANIME', 'DRAMA', 'HOME']);
    const all = r.groups.flatMap((g) => g.items);
    expect(all.some((i) => (ISOLATED_SPACES as readonly string[]).includes(i.space))).toBe(false);
  });

  it('never fetches isolated space files', async () => {
    const calls: string[] = [];
    await search(fakeRepo(calls), 'night');
    expect(calls.sort()).toEqual(['/mock/anime.json', '/mock/drama.json', '/mock/home.json']);
  });

  it('real mock catalog: global results only from Home/Anime/Drama', async () => {
    const repo = new CatalogRepository({ baseUrl: '', fetcher: fileFetcher(), latency: NO_LATENCY });
    for (const q of ['a', 'e', 'the', 'night', 'cafe', 'live', 'chapter']) {
      const r = await search(repo, q, 1000);
      for (const g of r.groups) for (const i of g.items) expect(['HOME', 'ANIME', 'DRAMA']).toContain(i.space);
    }
  });
});

describe('searchInSpace', () => {
  it('searchInSpace returns only that space', async () => {
    const repo = fakeRepo();
    for (const s of SPACES) {
      const r = await searchInSpace(repo, s, 'night');
      expect(r.map((i) => i.space)).toEqual([s]);
    }
  });

  it('real mock: TV search finds channels, never other spaces', async () => {
    const repo = new CatalogRepository({ baseUrl: '', fetcher: fileFetcher(), latency: NO_LATENCY });
    const r = await searchInSpace(repo, 'TV', 'news');
    expect(r.length).toBeGreaterThan(0);
    expect(r.every((i) => i.space === 'TV')).toBe(true);
  });
});

describe('normalization & matching', () => {
  it('is diacritic- and case-insensitive', () => {
    expect(normalize('Spirit CAFÉ  Àfter-Dark')).toBe('spirit cafe after dark');
    const items = [item('ANIME', 'a', 'Sorabito: Spirit Café After Dark'), item('HOME', 'b', 'Unrelated')];
    expect(searchItems(items, 'CAFE').map((i) => i.id)).toEqual(['a']);
    expect(searchItems(items, 'café').map((i) => i.id)).toEqual(['a']);
    expect(searchItems(items, 'SPIRIT cafe').map((i) => i.id)).toEqual(['a']);
  });

  it('ranks title prefix before word prefix before substring; matches altTitle', () => {
    const items = [
      item('HOME', 'sub', 'Alpha Bravonight'),
      item('HOME', 'word', 'The Night Train'),
      item('HOME', 'pre', 'Nightfall'),
      item('HOME', 'alt', 'Zeta', 'Night Shift')
    ];
    expect(searchItems(items, 'night').map((i) => i.id)).toEqual(['pre', 'word', 'alt', 'sub']);
  });

  it('empty query returns nothing', () => {
    expect(searchItems([item('HOME', 'a', 'A')], '   ')).toEqual([]);
  });

  it('real mock: diacritic title found via plain ASCII query', async () => {
    const repo = new CatalogRepository({ baseUrl: '', fetcher: fileFetcher(), latency: NO_LATENCY });
    const r = await search(repo, 'cafe nocturne');
    expect(r.groups.flatMap((g) => g.items).some((i) => i.title.includes('Café'))).toBe(true);
  });
});
