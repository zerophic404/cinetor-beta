// Contracts shared by engine / preloader / session (lets the logic be unit tested with fakes).
import type { Media } from './sources';

export interface LoadOpts {
  /** false = data saver: resolve only, don't buffer ahead. */
  buffer: boolean;
  signal?: AbortSignal;
}

export interface EngineLike {
  /** Media key currently loaded into the single <video>, or null. */
  readonly loadedKey: string | null;
  load(media: Media, startSec: number, opts: LoadOpts): Promise<void>;
  unload(): Promise<void>;
  play(): Promise<void>;
  pause(): void;
}

export type Readiness = 'resolving' | 'buffering' | 'ready' | 'resolved' | 'error';
