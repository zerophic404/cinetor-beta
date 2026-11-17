import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CarouselController, DEFAULT_TOUCH_COOLDOWN_MS, deckCardTransform, deckOffset, resolveSwipe } from './carousel';
import {
  ART_PALETTES,
  buildSpaceModel,
  canSeeAll,
  dedupeItems,
  detectDubBadge,
  displayGenres,
  filterRows,
  formatRating,
  generatedArtSpec,
  has4kBadge,
  heroItems,
  mergeUniqueItems,
  pickCategory,
  safeSpaceError,
  visibleWatchProgress
} from './space-model';
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

  it('waits 6 s touch cooldown after swipe before resuming 5 s rotation', () => {
    expect(DEFAULT_TOUCH_COOLDOWN_MS).toBe(6000);
    const c = new CarouselController({ count: 4, intervalMs: 5000, touchCooldownMs: 6000 });
    c.start();
    vi.advanceTimersByTime(2000);
    c.touchStart();
    vi.advanceTimersByTime(10000);
    expect(c.index).toBe(0);
    c.next();
    c.touchEnd();
    expect(c.index).toBe(1);
    expect(c.currentDelayMs).toBe(6000);
    vi.advanceTimersByTime(5999);
    expect(c.index).toBe(1);
    vi.advanceTimersByTime(1);
    expect(c.index).toBe(2);
    expect(c.currentDelayMs).toBe(5000);
    vi.advanceTimersByTime(5000);
    expect(c.index).toBe(3);
    c.destroy();
  });

  it('applies 6 s cooldown when tapping a peeking card via noteTouch', () => {
    const c = new CarouselController({ count: 5, intervalMs: 5000, touchCooldownMs: 6000 });
    c.start();
    c.noteTouch();
    c.goTo(3);
    expect(c.index).toBe(3);
    vi.advanceTimersByTime(5500);
    expect(c.index).toBe(3);
    vi.advanceTimersByTime(500);
    expect(c.index).toBe(4);
    c.destroy();
  });
});

describe('3D peeking deck geometry & swipe', () => {
  it('computes shortest signed circular offset in a looping deck', () => {
    expect(deckOffset(0, 0, 5)).toBe(0);
    expect(deckOffset(1, 0, 5)).toBe(1);
    expect(deckOffset(4, 0, 5)).toBe(-1);
    expect(deckOffset(3, 0, 5)).toBe(-2);
    expect(deckOffset(0, 4, 5)).toBe(1);
    // Two-item deck does not wrap ambiguously
    expect(deckOffset(1, 0, 2)).toBe(1);
    expect(deckOffset(0, 1, 2)).toBe(-1);
  });

  it('scales, dims, and blurs side cards while skipping blur on low tier', () => {
    const front = deckCardTransform(0, false);
    expect(front).toEqual({ visible: true, scale: 1, translatePct: 0, zIndex: 30, dimAlpha: 0, blurPx: 0 });

    const rightNeighbor = deckCardTransform(1, false);
    expect(rightNeighbor.visible).toBe(true);
    expect(rightNeighbor.scale).toBe(0.88);
    expect(rightNeighbor.translatePct).toBeGreaterThan(40);
    expect(rightNeighbor.zIndex).toBeLessThan(front.zIndex);
    expect(rightNeighbor.dimAlpha).toBeCloseTo(0.45, 2);
    expect(rightNeighbor.blurPx).toBeGreaterThan(0);

    const lowTierNeighbor = deckCardTransform(-1, true);
    expect(lowTierNeighbor.blurPx).toBe(0);
    expect(lowTierNeighbor.scale).toBe(0.88);
    expect(lowTierNeighbor.translatePct).toBe(-rightNeighbor.translatePct);

    expect(deckCardTransform(2.5, false).visible).toBe(false);
  });

  it('resolves horizontal swipe gestures and ignores vertical scroll', () => {
    expect(resolveSwipe(-72, 10, 320)).toBe(1);
    expect(resolveSwipe(72, 12, 320)).toBe(-1);
    expect(resolveSwipe(-20, 4, 320)).toBe(0);
    expect(resolveSwipe(-80, 90, 320)).toBe(0);
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

  it('detects Hindi, Bengali, Tamil, Telugu, and explicit English dub badges', () => {
    expect(detectDubBadge({ title: 'Pushpa 2 [Hindi]', genres: ['Action'] })).toBe('Hindi');
    expect(detectDubBadge({ title: 'Byomkesh', genres: ['Mystery', 'Bengali'] })).toBe('Bengali');
    expect(detectDubBadge({ title: 'Vikram (Tamil Dubbed)', genres: ['Thriller'] })).toBe('Tamil');
    expect(detectDubBadge({ title: 'Kalki', genres: ['Sci-Fi'], tags: ['Telugu'] })).toBe('Telugu');
    expect(detectDubBadge({ title: 'Solo Leveling [English]', genres: ['Anime'] })).toBe('English');
    expect(detectDubBadge({ title: 'Solo Leveling', genres: ['Anime', 'English'] })).toBe('English');
    // Ordinary English words in titles must not trigger false English dub badges
    expect(detectDubBadge({ title: 'Johnny English Strikes Again', genres: ['Comedy'] })).toBeNull();
    expect(detectDubBadge({ title: 'Dune: Part Two', genres: ['Sci-Fi', 'Adventure'] })).toBeNull();
  });

  it('detects 4K UHD from title, genres/tags, or 2160p sources and cleans displayGenres', () => {
    expect(has4kBadge({ title: 'Oppenheimer 4K', genres: ['Drama'] })).toBe(true);
    expect(has4kBadge({ title: 'Blade Runner', genres: ['Sci-Fi', '4K UHD'] })).toBe(true);
    expect(
      has4kBadge({
        title: 'Interstellar',
        genres: ['Sci-Fi'],
        sources: [{ url: 'https://x.example/v.mp4', kind: 'MP4', height: 2160 }]
      })
    ).toBe(true);
    expect(has4kBadge({ title: 'Past Lives', genres: ['Romance', 'Drama'] })).toBe(false);

    expect(displayGenres({ genres: ['Action', '[Hindi]', '4K UHD', 'Thriller', 'Hindi', 'Crime', 'Drama'] }, 3)).toEqual([
      'Action',
      'Thriller',
      'Crime'
    ]);
  });

  it('formats positive ratings and clamps watch progress strictly to 2%..95%', () => {
    expect(formatRating(7.84)).toBe('7.8');
    expect(formatRating(8)).toBe('8.0');
    expect(formatRating(0)).toBeNull();
    expect(formatRating(-1)).toBeNull();

    expect(visibleWatchProgress(undefined)).toBeNull();
    expect(visibleWatchProgress(0.01)).toBeNull();
    expect(visibleWatchProgress(0.02)).toBe(0.02);
    expect(visibleWatchProgress(0.48)).toBe(0.48);
    expect(visibleWatchProgress(0.95)).toBe(0.95);
    expect(visibleWatchProgress(0.96)).toBeNull();
  });

  it('builds deterministic crimson/graphite GeneratedArt specs without off-brand hues', () => {
    const a = generatedArtSpec('gw:10042');
    const b = generatedArtSpec('gw:10042');
    const c = generatedArtSpec('gw:99999');
    expect(a).toEqual(b);
    expect(ART_PALETTES.some(([l, d]) => l === a.light && d === a.dark)).toBe(true);
    expect(a.orbs).toHaveLength(3);
    expect(c.orbs).toHaveLength(3);
  });

  it('deduplicates row items across pages and gates See all appropriately', () => {
    const base = [it2('a', ['Action']), it2('b', ['Action']), it2('a', ['Action'])];
    expect(dedupeItems(base).map((i) => i.id)).toEqual(['a', 'b']);
    const merged = mergeUniqueItems(dedupeItems(base), [it2('b', ['Action']), it2('c', ['Drama'])]);
    expect(merged.map((i) => i.id)).toEqual(['a', 'b', 'c']);

    expect(canSeeAll({ id: 'r1', title: 'Trending', style: 'POSTER', itemIds: [] }, 6)).toBe(true);
    expect(canSeeAll({ id: 'r2', title: 'Top 10', style: 'TOP10', itemIds: [] }, 10)).toBe(false);
    expect(canSeeAll({ id: 'r3', title: 'Continue', style: 'CONTINUE', itemIds: [] }, 5)).toBe(false);
  });

  it('scrubs gateway URLs from user-visible error copy while keeping safe messages', () => {
    expect(safeSpaceError('Failed to fetch https://upstream.invalid/api/v1/home')).toBe(
      'Check your connection and try again.'
    );
    expect(safeSpaceError('18+ is locked')).toBe('18+ is locked');
  });
});
