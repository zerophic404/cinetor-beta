// Pointer-event gesture recognizer for the player surface (touch + pen + mouse). Math in gesture-math.ts.
import {
  BRIGHTNESS_MAX,
  BRIGHTNESS_MIN,
  DOUBLE_TAP_MS,
  DoubleTap,
  HOLD_MS,
  axisOf,
  dragLevel,
  dragSeekTarget,
  gesturesAllowed,
  sideOf,
  verticalTarget,
  type Axis,
  type Side
} from './gesture-math';

export type LevelKind = 'brightness' | 'volume';

export interface GestureHandlers {
  locked(): boolean;
  /** Single tap (touch) / click (mouse). */
  tap(pointerType: string): void;
  dblclick(): void;
  doubleTapSeek(side: Side, total: number, step: number): void;
  holdStart(): void;
  holdEnd(): void;
  time(): number;
  duration(): number;
  seekPreview(target: number, delta: number): void;
  seekCommit(target: number): void;
  levelGet(kind: LevelKind): number;
  levelSet(kind: LevelKind, value: number): void;
  levelEnd(kind: LevelKind): void;
  /** Optional: vertical swipe in the centre third (live TV zapping) instead of a level drag. dir +1 = up. */
  swipeV?(dir: 1 | -1): void;
}

export const LEVEL_RANGE: Record<LevelKind, [number, number]> = { brightness: [BRIGHTNESS_MIN, BRIGHTNESS_MAX], volume: [0, 1] };

/** Attach to the stage element. Returns detach. Ignores events that start on buttons/inputs. */
export function attachGestures(el: HTMLElement, h: GestureHandlers, step = 10): () => void {
  const dt = new DoubleTap(step);
  let id: number | null = null;
  let type = 'touch';
  let x0 = 0;
  let y0 = 0;
  let axis: Axis | null = null;
  let moved = false;
  let holding = false;
  let holdTimer: ReturnType<typeof setTimeout> | undefined;
  let tapTimer: ReturnType<typeof setTimeout> | undefined;
  let lastClick = 0;
  let seekStart = 0;
  let seekTarget = 0;
  let level: LevelKind = 'volume';
  let levelStart = 0;
  let swipe = false;
  let lastDy = 0;

  const ignore = (t: EventTarget | null) => t instanceof Element && !!t.closest('button, a, input, select, textarea, [role="slider"], [data-no-gesture]');

  function down(e: PointerEvent) {
    if (id !== null || !e.isPrimary || (e.pointerType === 'mouse' && e.button !== 0) || ignore(e.target)) return;
    id = e.pointerId;
    type = e.pointerType;
    x0 = e.clientX;
    y0 = e.clientY;
    axis = null;
    moved = false;
    holding = false;
    swipe = false;
    if (!gesturesAllowed(h.locked())) return;
    holdTimer = setTimeout(() => {
      if (axis || moved) return;
      holding = true;
      h.holdStart();
    }, HOLD_MS);
    try {
      el.setPointerCapture(e.pointerId);
    } catch {
      /* ignore */
    }
  }

  function move(e: PointerEvent) {
    if (e.pointerId !== id || holding || !gesturesAllowed(h.locked())) return;
    const dx = e.clientX - x0;
    const dy = e.clientY - y0;
    if (!axis) {
      const a = axisOf(dx, dy);
      if (!a) return;
      moved = true;
      clearTimeout(holdTimer);
      if (type === 'mouse') return; // mouse drags don't seek/level (use the seek bar / wheel)
      axis = a;
      const r = el.getBoundingClientRect();
      if (a === 'h') seekStart = h.time();
      else if (h.swipeV && sideOf(x0 - r.left, r.width) === 'center') swipe = true;
      else {
        level = verticalTarget(x0 - r.left, r.width);
        levelStart = h.levelGet(level);
      }
    }
    if (!axis) return;
    const r = el.getBoundingClientRect();
    if (swipe) {
      lastDy = dy;
    } else if (axis === 'h') {
      seekTarget = dragSeekTarget(seekStart, dx, r.width, h.duration());
      h.seekPreview(seekTarget, seekTarget - seekStart);
    } else {
      const [lo, hi] = LEVEL_RANGE[level];
      h.levelSet(level, dragLevel(levelStart, dy, r.height, lo, hi));
    }
    e.preventDefault();
  }

  function up(e: PointerEvent) {
    if (e.pointerId !== id) return;
    id = null;
    clearTimeout(holdTimer);
    if (holding) {
      holding = false;
      h.holdEnd();
      return;
    }
    if (swipe) {
      swipe = false;
      if (Math.abs(lastDy) > 60 && e.type !== 'pointercancel') h.swipeV?.(lastDy < 0 ? 1 : -1);
      lastDy = 0;
      return;
    }
    if (axis === 'h') return void h.seekCommit(seekTarget);
    if (axis === 'v') return void h.levelEnd(level);
    if (moved && e.type !== 'pointercancel') return;
    if (e.type === 'pointercancel') return;
    if (!gesturesAllowed(h.locked())) return void h.tap(type);
    const now = performance.now();
    if (type === 'mouse') {
      if (now - lastClick < DOUBLE_TAP_MS) {
        lastClick = 0;
        h.dblclick();
      } else lastClick = now;
      h.tap('mouse');
      return;
    }
    const r = el.getBoundingClientRect();
    const side = sideOf(e.clientX - r.left, r.width);
    const total = dt.tap(side, now);
    if (total !== null) {
      clearTimeout(tapTimer);
      h.doubleTapSeek(side, total, step);
      return;
    }
    clearTimeout(tapTimer);
    // Center taps toggle immediately; side taps wait to see if a double tap follows.
    if (side === 'center') h.tap(type);
    else tapTimer = setTimeout(() => h.tap(type), DOUBLE_TAP_MS);
  }

  const ctx = (e: Event) => e.preventDefault();
  el.addEventListener('pointerdown', down);
  el.addEventListener('pointermove', move);
  el.addEventListener('pointerup', up);
  el.addEventListener('pointercancel', up);
  el.addEventListener('contextmenu', ctx);
  return () => {
    clearTimeout(holdTimer);
    clearTimeout(tapTimer);
    el.removeEventListener('pointerdown', down);
    el.removeEventListener('pointermove', move);
    el.removeEventListener('pointerup', up);
    el.removeEventListener('pointercancel', up);
    el.removeEventListener('contextmenu', ctx);
  };
}

/** iOS (incl. iPadOS desktop UA): media volume is read-only. */
export function volumeReadOnly(): boolean {
  if (typeof navigator === 'undefined') return false;
  const ua = navigator.userAgent;
  return /iP(hone|ad|od)/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
}
