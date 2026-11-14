import { describe, expect, it } from 'vitest';
import { isTvUA, navKey, nearest, score, type Rect } from './spatial';

const R = (left: number, top: number, w = 100, h = 100): Rect => ({ left, top, right: left + w, bottom: top + h });
const grid = (rows: number, cols: number, gap = 20, size = 100) => {
  const out: { rect: Rect; value: string }[] = [];
  for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) out.push({ rect: R(c * (size + gap), r * (size + gap), size, size), value: `${r},${c}` });
  return out;
};

describe('spatial nearest-neighbour', () => {
  it('moves along a row', () => {
    const g = grid(1, 4);
    expect(nearest(R(120, 0), g, 'right')).toBe('0,2');
    expect(nearest(R(120, 0), g, 'left')).toBe('0,0');
    expect(nearest(R(0, 0), g, 'left')).toBeNull();
    expect(nearest(R(360, 0), g, 'right')).toBeNull();
  });
  it('moves straight down/up in a grid', () => {
    const g = grid(3, 3);
    expect(nearest(R(120, 0), g, 'down')).toBe('1,1');
    expect(nearest(R(120, 240), g, 'up')).toBe('1,1');
    expect(nearest(R(240, 120), g, 'down')).toBe('2,2');
    expect(nearest(R(0, 240), g, 'down')).toBeNull();
  });
  it('prefers an aligned element over a closer diagonal one', () => {
    const from = R(0, 0);
    const aligned = { rect: R(300, 0), value: 'aligned' };
    const diagonal = { rect: R(150, 150), value: 'diagonal' };
    expect(nearest(from, [diagonal, aligned], 'right')).toBe('aligned');
  });
  it('falls back to the nearest misaligned element when nothing is aligned (nav → content)', () => {
    const tab = R(800, 0, 60, 40);
    const cards = [{ rect: R(0, 200), value: 'far-left' }, { rect: R(600, 200), value: 'near' }];
    expect(nearest(tab, cards, 'down')).toBe('near');
  });
  it('picks the closest of several aligned candidates and ignores elements behind', () => {
    const from = R(200, 0);
    const c = [{ rect: R(320, 10), value: 'a' }, { rect: R(500, 0), value: 'b' }, { rect: R(0, 0), value: 'behind' }];
    expect(nearest(from, c, 'right')).toBe('a');
    expect(score(from, R(0, 0), 'right')).toBeNull();
  });
  it('partially overlapping rows: next row item with most overlap wins', () => {
    const from = R(100, 0, 200, 50);
    const row = [{ rect: R(0, 100, 150, 50), value: 'left' }, { rect: R(160, 100, 150, 50), value: 'center' }];
    expect(nearest(from, row, 'down')).toBe('center');
  });
});

describe('tv detection + keys', () => {
  it('detects TV user agents', () => {
    expect(isTvUA('Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36')).toBe(true);
    expect(isTvUA('Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/537.36')).toBe(true);
    expect(isTvUA('Mozilla/5.0 (Linux; Android 9; AFTMM Build/PS7233) AppleWebKit/537.36')).toBe(true);
    expect(isTvUA('Mozilla/5.0 (Linux; Android 12; BRAVIA 4K VH2 Build/STT1.211025.001; wv) Android TV')).toBe(true);
    expect(isTvUA('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36')).toBe(false);
    expect(isTvUA('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)')).toBe(false);
  });
  it('maps remote keys incl. webOS/Tizen back codes', () => {
    expect(navKey({ key: 'ArrowUp' })).toEqual({ type: 'move', dir: 'up' });
    expect(navKey({ key: 'Enter' })).toEqual({ type: 'enter' });
    expect(navKey({ key: 'Escape' })).toEqual({ type: 'back' });
    expect(navKey({ key: 'Backspace' })).toEqual({ type: 'back' });
    expect(navKey({ key: 'Unidentified', keyCode: 461 })).toEqual({ type: 'back' });
    expect(navKey({ key: 'XF86Back', keyCode: 10009 })).toEqual({ type: 'back' });
    expect(navKey({ key: 'a' })).toBeNull();
  });
});
