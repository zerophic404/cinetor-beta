// Instant play: warm the single shared <video> while the user reads details (Task 8.3 / Android 3.2).
// Latest preload wins; previous aborted (Review Focus 1). Never hijacks media that is already playing.
import { writable, type Readable } from 'svelte/store';
import { LatestWins } from './latest';
import type { Media } from './sources';
import type { EngineLike, Readiness } from './types';

export interface PreloaderDeps {
  engine: EngineLike;
  startFor(media: Media): Promise<number>;
  dataSaver(): boolean;
  /** True while a session (full or mini) owns the video → preload only resolves. */
  sessionBusy(): boolean;
}

export class Preloader {
  private readonly ready = writable<Record<string, Readiness>>({});
  readonly readiness: Readable<Record<string, Readiness>> = { subscribe: this.ready.subscribe };
  private latest = new LatestWins();
  private prepared: string | null = null;
  private starts = new Map<string, number>();
  private last: Media | null = null;
  private lastState: Readiness | null = null;

  constructor(private deps: PreloaderDeps) {}

  private set(key: string, r: Readiness | null) {
    if (this.last?.key === key) this.lastState = r;
    this.ready.update((m) => {
      if (!r) {
        if (!(key in m)) return m;
        const rest = { ...m };
        delete rest[key];
        return rest;
      }
      // Only the latest key keeps a readiness entry.
      return { [key]: r };
    });
  }

  /** Resume position resolved during preload (instant Play reuses it). */
  startOf(key: string): number | undefined {
    return this.starts.get(key);
  }

  preload(media: Media): Promise<void> {
    const { engine } = this.deps;
    this.last = media;
    return this.latest
      .run(media.key, async (signal, isCurrent) => {
        this.set(media.key, 'resolving');
        if (this.prepared && this.prepared !== media.key) this.prepared = null;
        const start = await this.deps.startFor(media);
        if (!isCurrent()) return;
        this.starts.set(media.key, start);
        if (!media.playable || !media.source) return this.set(media.key, 'error');
        if (this.deps.sessionBusy() || this.deps.dataSaver()) return this.set(media.key, 'resolved');
        if (engine.loadedKey === media.key) {
          this.prepared = media.key;
          return this.set(media.key, 'ready');
        }
        this.set(media.key, 'buffering');
        await engine.load(media, start, { buffer: true, signal });
        if (!isCurrent()) return;
        this.prepared = media.key;
        this.set(media.key, 'ready');
      })
      .catch(() => {
        if (this.latest.currentKey === media.key) this.set(media.key, 'error');
      });
  }

  /** Session ended (mini closed): warm the latest details target that was only resolved while busy. */
  rewarm(): Promise<void> | undefined {
    if (this.last && this.lastState === 'resolved' && !this.deps.sessionBusy() && !this.deps.dataSaver()) return this.preload(this.last);
    return undefined;
  }

  /** Play pressed: true when the shared element already holds this media (instant start). */
  take(key: string): boolean {
    const hit = this.prepared === key && this.deps.engine.loadedKey === key;
    this.prepared = null;
    if (this.latest.currentKey === key) this.latest.cancel(key);
    return hit;
  }

  /** Details closed without playing: abort + free the buffer (unless a session owns the element). */
  cancel(key?: string): void {
    const k = key ?? this.latest.currentKey;
    if (!k) return;
    this.latest.cancel(k);
    if (this.last?.key === k) this.last = null;
    if (this.prepared === k) this.prepared = null;
    this.set(k, null);
    if (this.deps.engine.loadedKey === k && !this.deps.sessionBusy()) void this.deps.engine.unload();
  }
}
