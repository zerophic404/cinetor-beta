// Pure gesture math (no DOM) — unit tested. Mirrors Android GestureMath.

export const DOUBLE_TAP_MS = 320;
/** Window in which further taps on the same side keep accumulating after a double tap. */
export const ACCUMULATE_MS = 700;
export const HOLD_MS = 450;
export const DRAG_SLOP_PX = 12;
export const HOLD_RATE = 2;
export const BRIGHTNESS_MIN = 0.25;
export const BRIGHTNESS_MAX = 1.6;

export type Side = 'left' | 'center' | 'right';
export type Axis = 'h' | 'v';

export const clamp = (v: number, lo: number, hi: number): number => (Number.isNaN(v) ? lo : Math.min(hi, Math.max(lo, v)));

/** Thirds of the surface: left / center / right. */
export function sideOf(x: number, width: number): Side {
  if (width <= 0) return 'center';
  const r = x / width;
  return r < 1 / 3 ? 'left' : r > 2 / 3 ? 'right' : 'center';
}

/** Vertical drag on left half → brightness, right half → volume. */
export const verticalTarget = (x: number, width: number): 'brightness' | 'volume' => (x < width / 2 ? 'brightness' : 'volume');

/** Dominant axis once the pointer moved past the slop, else null (still a tap / hold candidate). */
export function axisOf(dx: number, dy: number, slop = DRAG_SLOP_PX): Axis | null {
  if (Math.hypot(dx, dy) < slop) return null;
  return Math.abs(dx) >= Math.abs(dy) ? 'h' : 'v';
}

/**
 * Double-tap ±step seconds, consecutive taps on the same side accumulate (10 → 20 → 30).
 * `tap()` returns the accumulated signed delta for this burst, or null for a lone first tap.
 */
export class DoubleTap {
  private side: Side | null = null;
  private last = -Infinity;
  private streak = 0;
  constructor(public step = 10) {}

  tap(side: Side, now: number): number | null {
    if (side === 'center') {
      this.reset();
      return null;
    }
    const window = this.streak > 0 ? ACCUMULATE_MS : DOUBLE_TAP_MS;
    if (side === this.side && now - this.last <= window) this.streak++;
    else this.streak = 0;
    this.side = side;
    this.last = now;
    if (this.streak === 0) return null;
    return (side === 'left' ? -1 : 1) * this.streak * this.step;
  }

  /** True while a burst is active (further taps on `side` should seek instead of toggling controls). */
  active(side: Side, now: number): boolean {
    return this.streak > 0 && side === this.side && now - this.last <= ACCUMULATE_MS;
  }

  reset(): void {
    this.side = null;
    this.last = -Infinity;
    this.streak = 0;
  }
}

/** Long-press → 2× while held; release restores the previous rate. */
export class HoldSpeed {
  private prev: number | null = null;
  get holding(): boolean {
    return this.prev !== null;
  }
  start(currentRate: number): number {
    if (this.prev === null) this.prev = currentRate;
    return HOLD_RATE;
  }
  /** Rate to restore, or null when no hold was active. */
  end(): number | null {
    const p = this.prev;
    this.prev = null;
    return p;
  }
}

/** Horizontal drag → seek. Full surface width spans min(duration, 120 s). */
export function dragSeekTarget(startSec: number, dx: number, width: number, duration: number): number {
  if (!(duration > 0) || width <= 0) return Math.max(0, startSec);
  const span = Math.min(duration, 120);
  return clamp(startSec + (dx / width) * span, 0, duration);
}

/** Vertical drag (up = increase) → level in [min,max]; full height spans the whole range. */
export function dragLevel(start: number, dy: number, height: number, min = 0, max = 1): number {
  if (height <= 0) return clamp(start, min, max);
  return clamp(start - (dy / height) * (max - min), min, max);
}

/** Seek with clamping to [0, duration] (duration unknown → only clamp at 0). */
export function seekBy(cur: number, delta: number, duration: number): number {
  return clamp(cur + delta, 0, duration > 0 ? duration : Number.MAX_SAFE_INTEGER);
}

export const signedTime = (sec: number): string => `${sec < 0 ? '−' : '+'}${fmtTime(Math.abs(sec))}`;

export function fmtTime(sec: number): string {
  if (!Number.isFinite(sec) || sec < 0) sec = 0;
  const s = Math.floor(sec);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = String(s % 60).padStart(2, '0');
  return h ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}

/** Locked player ignores every gesture (only the unlock button works). */
export const gesturesAllowed = (locked: boolean): boolean => !locked;
