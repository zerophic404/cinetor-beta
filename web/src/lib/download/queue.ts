// Download queue: FIFO with max concurrent runners, pause/resume/cancel. Pure (runner injected) → unit tested.

export type QueueStatus = 'queued' | 'running' | 'paused' | 'completed' | 'failed' | 'canceled';

export type Runner = (id: string, signal: AbortSignal) => Promise<void>;

export interface QueueEvents {
  onStatus(id: string, status: QueueStatus, error?: unknown): void;
}

export const DEFAULT_MAX_CONCURRENT = 2;

export class DownloadQueue {
  private waiting: string[] = [];
  private running = new Map<string, AbortController>();
  private stopping = new Map<string, 'paused' | 'canceled'>();
  readonly status = new Map<string, QueueStatus>();

  constructor(
    private runner: Runner,
    private events: QueueEvents,
    private max = DEFAULT_MAX_CONCURRENT
  ) {}

  get maxConcurrent(): number {
    return this.max;
  }
  setMax(n: number): void {
    this.max = Math.max(1, Math.round(n));
    this.pump();
  }

  get runningIds(): string[] {
    return [...this.running.keys()];
  }
  get waitingIds(): string[] {
    return [...this.waiting];
  }

  private set(id: string, s: QueueStatus, err?: unknown): void {
    this.status.set(id, s);
    this.events.onStatus(id, s, err);
  }

  /** Add (or re-add after pause/fail). No-op when already queued/running. */
  enqueue(id: string): void {
    if (this.running.has(id) || this.waiting.includes(id)) return;
    this.waiting.push(id);
    this.set(id, 'queued');
    this.pump();
  }

  resume(id: string): void {
    this.enqueue(id);
  }

  pause(id: string): void {
    this.stop(id, 'paused');
  }

  cancel(id: string): void {
    this.stop(id, 'canceled');
  }

  private stop(id: string, as: 'paused' | 'canceled'): void {
    const wi = this.waiting.indexOf(id);
    if (wi >= 0) {
      this.waiting.splice(wi, 1);
      this.set(id, as);
      return;
    }
    const ctl = this.running.get(id);
    if (ctl) {
      this.stopping.set(id, as);
      ctl.abort();
      return;
    }
    if (as === 'canceled' || this.status.get(id) !== 'completed') this.set(id, as);
  }

  /** Move a waiting item to the front (priority). */
  prioritize(id: string): void {
    const i = this.waiting.indexOf(id);
    if (i > 0) {
      this.waiting.splice(i, 1);
      this.waiting.unshift(id);
    }
  }

  private pump(): void {
    while (this.running.size < this.max && this.waiting.length) {
      const id = this.waiting.shift()!;
      const ctl = new AbortController();
      this.running.set(id, ctl);
      this.set(id, 'running');
      let p: Promise<void>;
      try {
        p = this.runner(id, ctl.signal);
      } catch (e) {
        p = Promise.reject(e);
      }
      p.then(
        () => this.finish(id, 'completed'),
        (e) => this.finish(id, 'failed', e)
      );
    }
  }

  private finish(id: string, s: 'completed' | 'failed', err?: unknown): void {
    this.running.delete(id);
    const stop = this.stopping.get(id);
    this.stopping.delete(id);
    if (stop) this.set(id, stop);
    else this.set(id, s, err);
    this.pump();
  }
}
