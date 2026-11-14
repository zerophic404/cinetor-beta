// Hero carousel timer (pure, framework-free). Auto-advances every `intervalMs` while not paused.
// Pause reasons stack: hover, focus, document hidden, off-screen, manual. Any manual navigation
// restarts the full interval so the user never gets yanked away right after choosing a slide.

export type PauseReason = 'hover' | 'focus' | 'hidden' | 'offscreen' | 'manual';

export interface CarouselOptions {
  count: number;
  intervalMs?: number;
  onChange?: (index: number) => void;
  startIndex?: number;
}

export const DEFAULT_INTERVAL_MS = 5000;

export class CarouselController {
  index: number;
  readonly intervalMs: number;
  private count: number;
  private onChange?: (index: number) => void;
  private reasons = new Set<PauseReason>();
  private timer: ReturnType<typeof setTimeout> | null = null;
  private running = false;

  constructor(opts: CarouselOptions) {
    this.count = Math.max(0, opts.count);
    this.intervalMs = opts.intervalMs ?? DEFAULT_INTERVAL_MS;
    this.onChange = opts.onChange;
    this.index = this.count ? Math.min(Math.max(0, opts.startIndex ?? 0), this.count - 1) : 0;
  }

  get size(): number {
    return this.count;
  }

  get paused(): boolean {
    return this.reasons.size > 0;
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

  private schedule(): void {
    this.clear();
    if (!this.running || this.count < 2 || this.reasons.size) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.set(this.index + 1);
    }, this.intervalMs);
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

  /** New slide set (e.g. category tab change): back to slide 0, timer restarted. */
  reset(count: number): void {
    this.count = Math.max(0, count);
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
