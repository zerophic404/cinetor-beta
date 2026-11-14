import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CarouselController } from './carousel';
import { buildSpaceModel, filterRows, heroItems, pickCategory } from './space-model';
import type { CatalogFile, Item } from '../data/types';

describe('CarouselController', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('hero auto-advances every 5 s and wraps', () => {
    const seen: number[] = [];
    const c = new CarouselController({ count: 3, intervalMs: 5000, onChange: (i) => seen.push(i) });
    c.start();
    vi.advanceTimersByTime(4999);
    expect(seen).toEqual([]);
    vi.advanceTimersByTime(1);
    vi.advanceTimersByTime(5000);
    vi.advanceTimersByTime(5000);
    expect(seen).toEqual([1, 2, 0]);
    c.destroy();
  });

  it('pauses while hovered / dragged / hidden; reasons stack', () => {
    const c = new CarouselController({ count: 3, intervalMs: 5000 });
    c.start();
    c.pause('manual');
    c.pause('hidden');
    vi.advanceTimersByTime(20000);
    expect(c.index).toBe(0);
    c.resume('manual');
    vi.advanceTimersByTime(20000);
    expect(c.index).toBe(0);
    c.resume('hidden');
    vi.advanceTimersByTime(5000);
    expect(c.index).toBe(1);
    c.destroy();
  });

  it('manual navigation restarts the full interval', () => {
    const c = new CarouselController({ count: 4, intervalMs: 5000 });
    c.start();
    vi.advanceTimersByTime(4000);
    c.goTo(2);
    vi.advanceTimersByTime(4000);
    expect(c.index).toBe(2);
    vi.advanceTimersByTime(1000);
    expect(c.index).toBe(3);
    c.prev();
    expect(c.index).toBe(2);
    c.destroy();
  });
});

const it2 = (id: string, genres: string[]): Item => ({
  id, space: 'HOME', categoryId: 'c', type: 'MOVIE', title: id, year: 2020, rating: 7, maturity: 'PG', genres,
  synopsis: '', poster: 'p', backdrop: 'b', dominantColor: '#111111', accentColor: '#E50914'
});

describe('space model', () => {
  const file: CatalogFile = {
    space: 'HOME',
    categories: [
      { id: 'movies', space: 'HOME', title: 'Movies', heroIds: ['a', 'b'], rows: [{ id: 'r1', title: 'R1', style: 'POSTER', itemIds: ['a', 'b', 'c'] }] },
      { id: 'series', space: 'HOME', title: 'Series', heroIds: ['d'], rows: [{ id: 'r2', title: 'R2', style: 'POSTER', itemIds: ['d'] }] }
    ],
    items: [it2('a', ['Action']), it2('b', ['Drama']), it2('c', ['Action']), it2('d', ['Comedy'])]
  };

  it('genre chip filters rows', () => {
    const m = buildSpaceModel(file);
    const rows = filterRows(m, pickCategory(m, 'movies'), 'Action');
    expect(rows[0].items.map((i) => i.id)).toEqual(['a', 'c']);
    expect(filterRows(m, pickCategory(m, 'movies'), 'Comedy')).toEqual([]);
  });

  it('category tab swaps hero', () => {
    const m = buildSpaceModel(file);
    expect(heroItems(m, pickCategory(m, 'movies')).map((i) => i.id)).toEqual(['a', 'b']);
    expect(heroItems(m, pickCategory(m, 'series')).map((i) => i.id)).toEqual(['d']);
    expect(m.tabs.map((t) => t.label)).toEqual(['Movies', 'Series']);
  });
});
