// Playback session: one media at a time, modes FULL | MINI | HIDDEN (Android PlayerHost equivalent).
// A new play() always replaces the current media on the single element (Review Focus 2: never two audios).
import { get, writable, type Readable } from 'svelte/store';
import { LatestWins } from './latest';
import type { Media } from './sources';
import type { EngineLike } from './types';

export type Mode = 'hidden' | 'full' | 'mini';

export interface SessionState {
  mode: Mode;
  media: Media | null;
  /** 'unsupported' → external player fallback; 'gesture' → autoplay blocked, needs a tap; else message. */
  error: string | null;
  loading: boolean;
}

export interface SessionDeps {
  engine: EngineLike;
  preloader: { take(key: string): boolean; startOf?(key: string): number | undefined };
  startFor(media: Media): Promise<number>;
  /** Called before the current media is replaced or stopped (resume save). */
  onLeave?(media: Media): void;
}

const IDLE: SessionState = { mode: 'hidden', media: null, error: null, loading: false };

export class Session {
  private readonly st = writable<SessionState>(IDLE);
  readonly state: Readable<SessionState> = { subscribe: this.st.subscribe };
  private latest = new LatestWins();

  constructor(private deps: SessionDeps) {}

  get snapshot(): SessionState {
    return get(this.st);
  }

  get busy(): boolean {
    const s = this.snapshot;
    return s.mode !== 'hidden' && !!s.media;
  }

  /** Start (or resume) `media` in full mode. Returns true if it started from a warmed element. */
  async play(media: Media, opts: { start?: number } = {}): Promise<boolean> {
    const { engine } = this.deps;
    const cur = this.snapshot.media;
    if (cur?.key === media.key && engine.loadedKey === media.key) {
      this.st.update((s) => ({ ...s, mode: 'full', media, error: null }));
      if (opts.start === undefined) return true;
    }
    if (cur && cur.key !== media.key) this.deps.onLeave?.(cur);
    this.st.set({ mode: 'full', media, error: null, loading: true });
    if (!media.playable || !media.source) {
      this.latest.cancel();
      engine.pause();
      await engine.unload();
      this.st.update((s) => ({ ...s, error: 'unsupported', loading: false }));
      return false;
    }
    let instant = false;
    const ok = await this.latest
      .run(media.key, async (signal, isCurrent) => {
        instant = opts.start === undefined && this.deps.preloader.take(media.key);
        if (!instant) {
          engine.pause();
          const start = opts.start ?? this.deps.preloader.startOf?.(media.key) ?? (await this.deps.startFor(media));
          if (!isCurrent()) return false;
          await engine.load(media, start, { buffer: true, signal });
          if (!isCurrent()) return false;
        }
        return true;
      })
      .catch((e: unknown) => {
        this.st.update((s) => (s.media?.key === media.key ? { ...s, error: errMsg(e), loading: false } : s));
        return false;
      });
    if (!ok) return false;
    this.st.update((s) => ({ ...s, loading: false }));
    try {
      await engine.play();
    } catch (e) {
      if (this.snapshot.media?.key === media.key) this.st.update((s) => ({ ...s, error: isGestureError(e) ? 'gesture' : errMsg(e) }));
    }
    return instant;
  }

  minimize(): void {
    if (this.snapshot.media && !this.snapshot.error) this.st.update((s) => ({ ...s, mode: 'mini' }));
    else void this.stop();
  }

  expand(): void {
    if (this.snapshot.media) this.st.update((s) => ({ ...s, mode: 'full' }));
  }

  clearError(): void {
    this.st.update((s) => ({ ...s, error: null }));
  }

  async stop(): Promise<void> {
    const cur = this.snapshot.media;
    if (cur) this.deps.onLeave?.(cur);
    this.latest.cancel();
    this.st.set(IDLE);
    this.deps.engine.pause();
    await this.deps.engine.unload();
  }
}

const isGestureError = (e: unknown) => e instanceof Error && e.name === 'NotAllowedError';
function errMsg(e: unknown): string {
  if (e && typeof e === 'object' && 'message' in e && typeof (e as Error).message === 'string' && (e as Error).message) return (e as Error).message;
  return 'Playback failed';
}
