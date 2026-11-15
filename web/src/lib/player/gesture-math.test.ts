import { describe, expect, it } from 'vitest';
import {
  ACCUMULATE_MS,
  BRIGHTNESS_MAX,
  BRIGHTNESS_MIN,
  DoubleTap,
  HoldSpeed,
  axisOf,
  clamp,
  dragLevel,
  dragSeekTarget,
  fmtTime,
  gesturesAllowed,
  seekBy,
  sideOf,
  verticalTarget
} from './gesture-math';
import { MINI_MAX_FRAC, MINI_MIN_FRAC, clampMiniWidth, cornerPos, isSwipeOff, miniFrac, miniHeight, resizeWidth, snapCorner } from './mini-math';
import { keyAction, stepSpeed } from './keys';

describe('gesture math', () => {
  it('doubleTap_accumulates 10 → 20 → 30 on the same side', () => {
    const d = new DoubleTap(10);
    expect(d.tap('right', 0)).toBeNull();
    expect(d.tap('right', 200)).toBe(10);
    expect(d.tap('right', 600)).toBe(20);
    expect(d.tap('right', 1000)).toBe(30);
    expect(d.active('right', 1100)).toBe(true);
  });

  it('doubleTap: left is negative, side switch / timeout restarts the burst', () => {
    const d = new DoubleTap(10);
    d.tap('left', 0);
    expect(d.tap('left', 100)).toBe(-10);
    expect(d.tap('right', 200)).toBeNull();
    expect(d.tap('right', 300)).toBe(10);
    expect(d.tap('right', 300 + ACCUMULATE_MS + 1)).toBeNull();
    expect(d.tap('center', 0)).toBeNull();
  });

  it('first double tap needs a quick second tap', () => {
    const d = new DoubleTap(10);
    d.tap('left', 0);
    expect(d.tap('left', 500)).toBeNull();
  });

  it('hold_sets2x_releaseRestores previous rate', () => {
    const h = new HoldSpeed();
    expect(h.end()).toBeNull();
    expect(h.start(1.25)).toBe(2);
    expect(h.holding).toBe(true);
    expect(h.start(2)).toBe(2); // repeated start keeps the original
    expect(h.end()).toBe(1.25);
    expect(h.holding).toBe(false);
  });

  it('verticalDrag_leftIsBrightness_rightIsVolume', () => {
    expect(verticalTarget(10, 400)).toBe('brightness');
    expect(verticalTarget(390, 400)).toBe('volume');
    expect(dragLevel(0.5, -100, 200)).toBe(1); // up half the height = +0.5
    expect(dragLevel(0.5, 100, 400)).toBe(0.25);
    expect(dragLevel(1, -50, 100, BRIGHTNESS_MIN, BRIGHTNESS_MAX)).toBe(BRIGHTNESS_MAX);
  });

  it('horizontalDrag_clampsToDuration', () => {
    expect(dragSeekTarget(50, 200, 400, 600)).toBe(110); // half width of 120 s span
    expect(dragSeekTarget(590, 400, 400, 600)).toBe(600);
    expect(dragSeekTarget(5, -400, 400, 600)).toBe(0);
    expect(dragSeekTarget(10, 400, 400, 60)).toBe(60); // short clip: span = duration
    expect(seekBy(5, -10, 100)).toBe(0);
    expect(seekBy(95, 10, 100)).toBe(100);
  });

  it('axis + sides + clamp + locked', () => {
    expect(axisOf(3, 4)).toBeNull();
    expect(axisOf(30, 4)).toBe('h');
    expect(axisOf(3, -40)).toBe('v');
    expect(sideOf(10, 300)).toBe('left');
    expect(sideOf(150, 300)).toBe('center');
    expect(sideOf(290, 300)).toBe('right');
    expect(clamp(NaN, 0, 1)).toBe(0);
    expect(gesturesAllowed(true)).toBe(false);
    expect(fmtTime(3725)).toBe('1:02:05');
    expect(fmtTime(65)).toBe('1:05');
  });
});

describe('mini player geometry', () => {
  const m = { top: 10, right: 10, bottom: 10, left: 10 };
  it('snaps to nearest corner, fling nudges', () => {
    expect(snapCorner({ x: 20, y: 20, w: 100, h: 56 }, 400, 800)).toBe('tl');
    expect(snapCorner({ x: 280, y: 700, w: 100, h: 56 }, 400, 800)).toBe('br');
    expect(snapCorner({ x: 100, y: 700, w: 100, h: 56 }, 400, 800, 2, 0)).toBe('br');
    expect(cornerPos('br', 100, 56, 400, 800, m)).toEqual({ x: 290, y: 734 });
  });
  it('swipe off stops', () => {
    expect(isSwipeOff({ x: -70, y: 0, w: 100, h: 56 }, 400)).toBe(true);
    expect(isSwipeOff({ x: 10, y: 0, w: 100, h: 56 }, 400)).toBe(false);
    expect(isSwipeOff({ x: 330, y: 0, w: 100, h: 56 }, 400, 2)).toBe(true);
  });
});

describe('keyboard', () => {
  it('maps shortcuts', () => {
    expect(keyAction({ key: ' ' })).toEqual({ type: 'toggle' });
    expect(keyAction({ key: 'j' })).toEqual({ type: 'seek', by: -10 });
    expect(keyAction({ key: 'L' })).toEqual({ type: 'seek', by: 10 });
    expect(keyAction({ key: 'ArrowUp' })).toEqual({ type: 'volume', by: 0.05 });
    expect(keyAction({ key: '>' })).toEqual({ type: 'speed', dir: 1 });
    expect(keyAction({ key: 'n' })).toEqual({ type: 'next' });
    expect(keyAction({ key: 'f', ctrlKey: true })).toBeNull();
  });
  it('steps speed within 0.25–3', () => {
    expect(stepSpeed(1, 1)).toBe(1.25);
    expect(stepSpeed(1, -1)).toBe(0.75);
    expect(stepSpeed(3, 1)).toBe(3);
    expect(stepSpeed(0.25, -1)).toBe(0.25);
    expect(stepSpeed(1.1, 1)).toBe(1.25);
  });
});

describe('mini player resize (corner handle)', () => {
  it('clamps width to 25–80 % of the viewport and keeps 16:9', () => {
    expect(clampMiniWidth(10, 1000)).toBe(250);
    expect(clampMiniWidth(5000, 1000)).toBe(800);
    expect(clampMiniWidth(400, 1000)).toBe(400);
    // Never below 160 px on tiny viewports.
    expect(clampMiniWidth(10, 400)).toBe(160);
    expect(miniHeight(320)).toBe(180);
    expect(miniFrac(400, 1000)).toBeCloseTo(0.4);
    expect(miniFrac(10, 1000)).toBeCloseTo(MINI_MIN_FRAC);
    expect(miniFrac(9999, 1000)).toBeCloseTo(MINI_MAX_FRAC);
  });

  it('right-anchored corners grow when dragged leftwards', () => {
    expect(resizeWidth(400, -100, 'br', 1000)).toBe(500);
    expect(resizeWidth(400, 100, 'br', 1000)).toBe(300);
    expect(resizeWidth(400, 100, 'bl', 1000)).toBe(500);
    expect(resizeWidth(400, -100, 'tl', 1000)).toBe(300);
    // Clamped at both ends.
    expect(resizeWidth(400, -5000, 'br', 1000)).toBe(800);
    expect(resizeWidth(400, 5000, 'br', 1000)).toBe(250);
  });
});
