// Hero carousel timer & 3D peeking poster deck math (pure, framework-free).
// Auto-advances every `intervalMs` while not paused; touch interactions apply a 6 s cooldown
// before resuming rotation so cards never move right after a user swipe or tap.

export type PauseReason = 'hover' | 'focus' | 'hidden' | 'offscreen' | 'manual' | 'touch' | 'modal' | 'reduced';

export interface CarouselOptions {
  count: number;
  intervalMs?: number;
  touchCooldownMs?: number;
  onChange?: (index: number) => void;
  startIndex?: number;
  now?: () => number;
}

export const DEFAULT_INTERVAL_MS = 5000;
export const DEFAULT_TOUCH_COOLDOWN_MS = 6000;

export interface DeckCardTransform {
  visible: boolean;
  scale: number;
  translatePct: number;
  zIndex: number;
  dimAlpha: number;
  blurPx: number;
}

/** Shortest signed offset from `current` in a deck of `count` items, shifted by live `dragFraction` (-1..1). */
export function deckOffset(index: number, current: number, count: number, dragFraction = 0): number {
  if (count <= 0) return 0;
  const clampedDrag = Math.max(-1, Math.min(1, dragFraction));
  let diff = index - current;
  if (count > 2) {
    const half = count / 2;
    while (diff > half) diff -= count;
    while (diff <= -half) diff += count;
  }
  return diff + clampedDrag;
}

/** Movira-style 3D peeking deck transform for a card at signed `offset` from center. */
export function deckCardTransform(offset: number, lowTier = false): DeckCardTransform {
  const abs = Math.abs(offset);
  if (abs > 2.2) {
    return { visible: false, scale: 0.76, translatePct: Math.sign(offset) * 112, zIndex: 0, dimAlpha: 0.8, blurPx: 0 };
  }
  const far = Math.min(abs, 2);
  const scale = Math.round((1 - 0.12 * far) * 10000) / 10000;
  const translatePct = Math.round(offset * 56 * 100) / 100;
  const zIndex = 30 - Math.round(far * 10);
  const dimAlpha = abs < 0.01 ? 0 : Math.min(0.8, Math.round(0.45 * far * 1000) / 1000);
  const blurPx = lowTier || abs < 0.35 ? 0 : Math.round(Math.min(3, abs * 2.5) * 10) / 10;
  return { visible: true, scale, translatePct, zIndex, dimAlpha, blurPx };
}

/** Returns +1 for next slide (swipe left), -1 for prev slide (swipe right), or 0 when below threshold. */
export function resolveSwipe(dx: number, dy: number, containerWidth = 320): -1 | 0 | 1 {
  const threshold = Math.max(40, Math.min(72, containerWidth * 0.14));
  if (Math.abs(dx) > threshold && Math.abs(dx) > Math.abs(dy) * 1.2) {
    return dx < 0 ? 1 : -1;
  }
  return 0;
}

export class CarouselController {
  index: number;
  readonly intervalMs: number;
  readonly touchCooldownMs: number;
  private count: number;
  private onChange?: (index: number) => void;
  private reasons = new Set<PauseReason>();
  private timer: ReturnType<typeof setTimeout> | null = null;
  private running = false;
  private touchedAt: number | null = null;
  private activeDelay: number;
  private now: () => number;

  constructor(opts: CarouselOptions) {
    this.count = Math.max(0, opts.count);
    this.intervalMs = opts.intervalMs ?? DEFAULT_INTERVAL_MS;
    this.touchCooldownMs = opts.touchCooldownMs ?? DEFAULT_TOUCH_COOLDOWN_MS;
    this.activeDelay = this.intervalMs;
    this.onChange = opts.onChange;
    this.now = opts.now ?? Date.now;
    this.index = this.count ? Math.min(Math.max(0, opts.startIndex ?? 0), this.count - 1) : 0;
  }

  get size(): number {
    return this.count;
  }

  get paused(): boolean {
    return this.reasons.size > 0;
  }

  /** Delay (ms) of the currently scheduled auto-advance (6 s right after touch, 5 s normally). */
  get currentDelayMs(): number {
    return this.activeDelay;
  }

  isPausedBy(r: PauseReason): boolean {
    return this.reasons.has(r);
  }

  start(): void {
    this.running = true;
    this.schedule();
  }

  stop(): void {
    this.running = false;
    this.clear();
  }

  destroy(): void {
    this.stop();
    this.onChange = undefined;
  }

  private clear(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }

  private nextDelayMs(): number {
    if (this.touchedAt === null) return this.intervalMs;
    const sinceTouch = Math.max(0, this.now() - this.touchedAt);
    const cooldownLeft = this.touchCooldownMs - sinceTouch;
    if (cooldownLeft <= 0) {
      this.touchedAt = null;
      return this.intervalMs;
    }
    return Math.max(this.intervalMs, cooldownLeft);
  }

  private schedule(): void {
    this.clear();
    const delay = this.nextDelayMs();
    this.activeDelay = delay;
    if (!this.running || this.count < 2 || this.reasons.size) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.set(this.index + 1);
    }, delay);
  }

  private set(i: number): void {
    if (!this.count) return;
    const next = ((i % this.count) + this.count) % this.count;
    const changed = next !== this.index;
    this.index = next;
    if (changed) this.onChange?.(next);
    this.schedule();
  }

  goTo(i: number): void {
    this.set(i);
  }

  next(): void {
    this.set(this.index + 1);
  }

  prev(): void {
    this.set(this.index - 1);
  }

  /** Finger touched the deck: pause immediately and stamp touch timestamp. */
  touchStart(): void {
    this.touchedAt = this.now();
    this.pause('touch');
  }

  /** Finger lifted: stamp release timestamp and schedule with 6 s cooldown. */
  touchEnd(): void {
    this.touchedAt = this.now();
    if (!this.reasons.delete('touch')) {
      this.schedule();
      return;
    }
    this.schedule();
  }

  /** Record a discrete touch tap (e.g. tapping a peeking card or dot) before calling goTo(). */
  noteTouch(): void {
    this.touchedAt = this.now();
    this.schedule();
  }

  /** New slide set (e.g. category tab change): back to slide 0, timer restarted. */
  reset(count: number): void {
    this.count = Math.max(0, count);
    this.touchedAt = null;
    const changed = this.index !== 0;
    this.index = 0;
    if (changed) this.onChange?.(0);
    this.schedule();
  }

  pause(r: PauseReason): void {
    this.reasons.add(r);
    this.clear();
  }

  resume(r: PauseReason): void {
    if (!this.reasons.delete(r)) return;
    this.schedule();
  }

  setPaused(r: PauseReason, on: boolean): void {
    if (on) this.pause(r);
    else this.resume(r);
  }
}
