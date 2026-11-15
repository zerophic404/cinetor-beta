// Bridge to the Rust download engine (desktop only): start/pause/remove + progress events.
import { invoke, listen } from './tauri';
import type { NativeProgress } from './env';

export interface NativeStart {
  id: string;
  url: string;
  kind: 'progressive' | 'hls';
  connections: number;
  fileName: string;
  bandwidth?: number;
  height?: number;
}

export interface NativeDone {
  id: string;
  path: string;
  playlist?: string | null;
  size: number;
}

const abortError = () => new DOMException('aborted', 'AbortError');

/** Resolves when the file is complete; rejects with AbortError on pause/cancel (Rust keeps the partial data). */
export function runNative(req: NativeStart, onProgress: (p: NativeProgress) => void, signal: AbortSignal): Promise<NativeDone> {
  if (signal.aborted) return Promise.reject(abortError());
  return new Promise<NativeDone>((resolve, reject) => {
    let settled = false;
    let unsubs: (() => void)[] = [];
    const finish = (fn: () => void) => {
      if (settled) return;
      settled = true;
      signal.removeEventListener('abort', onAbort);
      for (const u of unsubs) u();
      unsubs = [];
      fn();
    };
    const onAbort = () => {
      void invoke('dl_pause', { id: req.id }).catch(() => {});
      finish(() => reject(abortError()));
    };
    signal.addEventListener('abort', onAbort);
    Promise.all([
      listen<NativeProgress>('dl://progress', (p) => {
        if (p.id === req.id && !settled) onProgress(p);
      }),
      listen<NativeDone>('dl://done', (p) => {
        if (p.id === req.id) finish(() => resolve(p));
      }),
      listen<{ id: string; message: string }>('dl://error', (p) => {
        if (p.id === req.id) finish(() => reject(new Error(p.message)));
      })
    ])
      .then((fns) => {
        if (settled) return fns.forEach((f) => f());
        unsubs = fns;
        return invoke('dl_start', { req });
      })
      .catch((e) => finish(() => reject(e instanceof Error ? e : new Error(String(e)))));
  });
}

export function removeNative(id: string, fileName: string, path?: string): Promise<void> {
  return invoke<void>('dl_remove', { id, fileName, path: path ?? null });
}

export function readPlaylist(path: string): Promise<string> {
  return invoke<string>('dl_read_playlist', { path });
}

export function downloadsDir(): Promise<string> {
  return invoke<string>('dl_dir');
}
