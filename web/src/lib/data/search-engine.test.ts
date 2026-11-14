import { describe, expect, it, vi } from 'vitest';
import { SearchEngine, InlineBackend, mergeResults } from './search-engine';
import { historyScope, memoryStore, SearchHistory } from './search-history';
import { PrefixIndex, toDoc, withinOne } from './search-index';
import { VIDEO_SPACES, type Item, type Space } from './types';

function item(space: Space, id: string, title: string, rating = 7, altTitle?: string): Item {
  return {
    id, space, categoryId: 'c', type: 'MOVIE', title, altTitle, year: 2020, rating, maturity: 'PG',
    genres: [], synopsis: '', poster: 'p', backdrop: 'b', dominantColor: '#111111', accentColor: '#E50914'
  };
}

const flush = (ms = 5) => new Promise((r) => setTimeout(r, ms));

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => ((resolve = res), (reject = rej)));
  return { promise, resolve, reject };
}

describe('PrefixIndex', () => {
  it('ranks title prefix > word prefix > substring and tolerates one typo', () => {
    const idx = new PrefixIndex();
    idx.add(
      [
        item('HOME', 'a', 'Night Train', 6),
        item('HOME', 'b', 'The Long Night', 9),
        item('HOME', 'c', 'Midnight Run', 8),
        item('HOME', 'd', 'Interstellar', 8)
      ].map(toDoc)
    );
    expect(idx.query('night', ['HOME'])).toEqual(['a', 'b', 'c']);
    expect(idx.query('intersteller', ['HOME'])).toEqual(['d']);
    expect(withinOne('kitten', 'sitten')).toBe(true);
    expect(withinOne('kitten', 'sitting')).toBe(false);
  });

  it('returns first results from a 1,000-item index in < 50 ms', () => {
    const idx = new PrefixIndex();
    idx.add(Array.from({ length: 1000 }, (_, i) => toDoc(item('HOME', `h${i}`, `Title number ${i} alpha${i % 37}`))));
    const t0 = performance.now();
    const ids = idx.query('alpha1', ['HOME'], 60);
    expect(performance.now() - t0).toBeLessThan(50);
    expect(ids.length).toBeGreaterThan(0);
  });
});

describe('SearchEngine', () => {
  it('global search excludes adult and isolated spaces (local + remote)', async () => {
    const remote = async () => [item('HOME', 'r1', 'Night Remote'), item('ADULT', 'r2', 'Night Adult'), item('TV', 'r3', 'Night TV')];
    const e = new SearchEngine({ scope: VIDEO_SPACES, remote, debounceMs: 0 });
    e.index(['HOME', 'ANIME', 'DRAMA', 'TV', 'MUSIC', 'COMICS', 'ADULT'].map((s) => item(s as Space, `${s}-1`, `Night ${s}`)));
    let last: Item[] = [];
    await e.search('night', (u) => (last = u.results));
    const spaces = new Set(last.map((i) => i.space));
    expect([...spaces].sort()).toEqual(['ANIME', 'DRAMA', 'HOME']);
    expect(last.map((i) => i.id)).toContain('r1');
    expect(e.all().every((i) => (VIDEO_SPACES as readonly Space[]).includes(i.space))).toBe(true);
  });

  it('local results arrive before remote; remote merges without reordering local', async () => {
    const d = deferred<Item[]>();
    const e = new SearchEngine({ scope: ['HOME'], remote: () => d.promise, debounceMs: 0 });
    e.index([item('HOME', 'l1', 'Dune'), item('HOME', 'l2', 'Dune Part Two')]);
    const updates: string[][] = [];
    const done = e.search('dune', (u) => updates.push(u.results.map((i) => i.id)));
    await flush();
    expect(updates[0]).toEqual(['l1', 'l2']);
    d.resolve([item('HOME', 'l2', 'Dune Part Two'), item('HOME', 'r9', 'Dune (1984)')]);
    await done;
    expect(updates.at(-1)).toEqual(['l1', 'l2', 'r9']);
    expect(mergeResults([item('HOME', 'x', 'X')], [item('HOME', 'x', 'X')]).length).toBe(1);
  });

  it('stale remote result is discarded and its request aborted', async () => {
    const calls: { q: string; signal: AbortSignal; d: ReturnType<typeof deferred<Item[]>> }[] = [];
    const e = new SearchEngine({
      scope: ['HOME'],
      debounceMs: 0,
      remote: (q, signal) => {
        const d = deferred<Item[]>();
        calls.push({ q, signal, d });
        return d.promise;
      }
    });
    const seen: string[] = [];
    void e.search('fr', (u) => seen.push(`${u.query}:${u.remote.map((i) => i.id).join(',')}`));
    await flush();
    const second = e.search('frieren', (u) => seen.push(`${u.query}:${u.remote.map((i) => i.id).join(',')}`));
    await flush();
    expect(calls.map((c) => c.q)).toEqual(['fr', 'frieren']);
    expect(calls[0].signal.aborted).toBe(true);
    calls[1].d.resolve([item('HOME', 'new', 'Frieren')]);
    calls[0].d.resolve([item('HOME', 'old', 'Free Guy')]);
    await second;
    await flush();
    expect(seen.some((s) => s.includes('old'))).toBe(false);
    expect(seen.at(-1)).toBe('frieren:new');
  });

  it('typing fast inside the debounce window makes only one remote call', async () => {
    const remote = vi.fn(async () => [] as Item[]);
    const e = new SearchEngine({ scope: ['HOME'], remote, debounceMs: 30 });
    void e.search('d', () => {});
    void e.search('du', () => {});
    await e.search('dun', () => {});
    expect(remote).toHaveBeenCalledTimes(1);
    expect(remote.mock.calls[0][0]).toBe('dun');
  });

  it('query cache hit skips the network', async () => {
    const remote = vi.fn(async () => [item('HOME', 'r', 'Frieren')]);
    const e = new SearchEngine({ scope: ['HOME'], remote, debounceMs: 0 });
    await e.search('Frieren', () => {});
    await e.search('other', () => {});
    let hit: Item[] = [];
    let pending = true;
    await e.search('  frieren ', (u) => ((hit = u.remote), (pending = u.remotePending)));
    expect(remote).toHaveBeenCalledTimes(2);
    expect(hit.map((i) => i.id)).toEqual(['r']);
    expect(pending).toBe(false);
  });

  it('in-space engine only returns that space', async () => {
    const e = new SearchEngine({ scope: ['COMICS'], local: new InlineBackend() });
    e.index([item('COMICS', 'c1', 'Night Manga'), item('HOME', 'h1', 'Night Movie')]);
    let ids: string[] = [];
    await e.search('night', (u) => (ids = u.results.map((i) => i.id)));
    expect(ids).toEqual(['c1']);
  });
});

describe('SearchHistory', () => {
  it('is local per profile, deduped, newest first, capped', async () => {
    const store = memoryStore();
    const h = new SearchHistory(store, 3);
    await h.add('p1', 'global', 'Dune');
    await h.add('p1', 'global', 'Frieren');
    await h.add('p1', 'global', 'dune');
    await h.add('p2', 'global', 'Kids show');
    expect(await h.list('p1', 'global')).toEqual(['dune', 'Frieren']);
    expect(await h.list('p2', 'global')).toEqual(['Kids show']);
    await h.add('p1', 'global', 'a');
    await h.add('p1', 'global', 'b');
    expect((await h.list('p1', 'global')).length).toBe(3);
    await h.clear('p1', 'global');
    expect(await h.list('p1', 'global')).toEqual([]);
    expect(await h.list('p2', 'global')).toEqual(['Kids show']);
  });

  it('isolated spaces never write to the global history', async () => {
    const store = memoryStore();
    const h = new SearchHistory(store);
    expect(historyScope()).toBe('global');
    expect(historyScope('ANIME')).toBe('global');
    for (const s of ['TV', 'MUSIC', 'COMICS', 'ADULT'] as const) {
      expect(historyScope(s)).not.toBe('global');
      await h.add('p1', historyScope(s), `secret ${s}`);
    }
    expect(await h.list('p1', 'global')).toEqual([]);
    expect(await h.list('p1', 'adult')).toEqual(['secret ADULT']);
  });

  it('pause stops recording', async () => {
    const h = new SearchHistory(memoryStore());
    await h.setPaused('p1', true);
    await h.add('p1', 'global', 'x');
    expect(await h.list('p1', 'global')).toEqual([]);
  });
});
