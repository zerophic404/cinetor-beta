import { describe, expect, it, vi } from 'vitest';
import { CatalogRepository, NO_LATENCY } from './catalog';
import { SPACES, SPACE_FILE } from './types';
import { fileFetcher } from './test-utils';

describe('CatalogRepository', () => {
  it('fetches ${base}/mock/<space>.json once and caches it', async () => {
    const calls: string[] = [];
    const repo = new CatalogRepository({ baseUrl: '/cinetor', fetcher: fileFetcher(calls), latency: NO_LATENCY });
    const [a, b] = await Promise.all([repo.categories('HOME'), repo.categories('HOME')]);
    await repo.genres('HOME');
    expect(a).toBe(b);
    expect(calls).toEqual(['/cinetor/mock/home.json']);
    expect(repo.asset('images/posters/x.webp')).toBe('/cinetor/mock/images/posters/x.webp');
  });

  it('loads every space file with categories + items', async () => {
    const repo = new CatalogRepository({ baseUrl: '', fetcher: fileFetcher(), latency: NO_LATENCY });
    for (const s of SPACES) {
      const f = await repo.file(s);
      expect(f.space, SPACE_FILE[s]).toBe(s);
      expect(f.categories.length).toBeGreaterThan(0);
      expect(f.items.every((i) => i.space === s)).toBe(true);
    }
  });

  it('item(id) / row(rowId) resolve by id prefix without loading other spaces', async () => {
    const calls: string[] = [];
    const repo = new CatalogRepository({ baseUrl: '', fetcher: fileFetcher(calls), latency: NO_LATENCY });
    const item = await repo.item('dr-k-001');
    expect(item?.space).toBe('DRAMA');
    const row = await repo.row('an-trending-r1');
    expect(row?.items.length).toBe(row?.row.itemIds.length);
    expect(calls).toEqual(['/mock/drama.json', '/mock/anime.json']);
  });

  it('byGenre and genres are scoped to the space', async () => {
    const repo = new CatalogRepository({ baseUrl: '', fetcher: fileFetcher(), latency: NO_LATENCY });
    const genres = await repo.genres('ANIME');
    expect(genres).toContain('Isekai');
    const isekai = await repo.byGenre('ANIME', 'isekai');
    expect(isekai.length).toBeGreaterThan(0);
    expect(isekai.every((i) => i.space === 'ANIME' && i.genres.includes('Isekai'))).toBe(true);
  });

  it('simulated latency is applied on cache miss within [min,max]', async () => {
    const sleep = vi.fn(() => Promise.resolve());
    const repo = new CatalogRepository({
      baseUrl: '',
      fetcher: fileFetcher(),
      latency: { enabled: true, minMs: 300, maxMs: 1200 },
      random: () => 0.5,
      sleep
    });
    await repo.categories('HOME');
    await repo.categories('HOME');
    expect(sleep).toHaveBeenCalledTimes(1);
    expect(sleep).toHaveBeenCalledWith(750);
  });

  it('persists to IndexedDB-like cache and serves later sessions from it without latency', async () => {
    const store = new Map<string, unknown>();
    const persist = {
      get: async (k: string) => store.get(k),
      set: async (k: string, v: unknown) => {
        store.set(k, v);
      }
    };
    const first = new CatalogRepository({ baseUrl: '', fetcher: fileFetcher(), latency: NO_LATENCY, persist });
    await first.categories('HOME');
    expect(store.has('/mock/home.json')).toBe(true);

    const sleep = vi.fn(() => Promise.resolve());
    const second = new CatalogRepository({
      baseUrl: '',
      fetcher: fileFetcher(),
      latency: { enabled: true, minMs: 300, maxMs: 1200 },
      sleep,
      persist
    });
    expect((await second.categories('HOME')).length).toBeGreaterThan(0);
    expect(sleep).not.toHaveBeenCalled();
  });

  it('failed loads are not cached', async () => {
    let fail = true;
    const ok = fileFetcher();
    const repo = new CatalogRepository({
      baseUrl: '',
      latency: NO_LATENCY,
      fetcher: async (u) => (fail ? { ok: false, status: 503, json: async () => ({}) } : ok(u))
    });
    await expect(repo.categories('HOME')).rejects.toThrow(/503/);
    fail = false;
    expect((await repo.categories('HOME')).length).toBeGreaterThan(0);
  });
});
