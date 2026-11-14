import { describe, expect, it } from 'vitest';
import { memoryKV } from '../data/kv';
import type { Chapter } from '../data/types';
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
