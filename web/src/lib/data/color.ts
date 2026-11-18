// Dominant color per image: computed once (64px canvas average, off the critical path),
// cached in a memory LRU + persistent store (IndexedDB in browsers).
import type { PersistentCache } from './idb';
import { Lru } from './lru';

/** Average of RGBA bytes, skipping near-transparent pixels. Returns '#RRGGBB' (or null if empty). */
export function averageColor(data: ArrayLike<number>): string | null {
  let r = 0;
  let g = 0;
  let b = 0;
  let n = 0;
  for (let i = 0; i + 3 < data.length; i += 4) {
    if (data[i + 3] < 128) continue;
    r += data[i];
    g += data[i + 1];
    b += data[i + 2];
    n++;
  }
  if (!n) return null;
  const h = (v: number) => Math.round(v / n).toString(16).padStart(2, '0');
  return `#${h(r)}${h(g)}${h(b)}`.toUpperCase();
}

/** Deterministic dark fallback color from a string (used until the real color is computed). */
export function fallbackColor(seed: string): string {
  let x = 2166136261;
  for (let i = 0; i < seed.length; i++) x = Math.imul(x ^ seed.charCodeAt(i), 16777619);
  const hue = Math.abs(x) % 360;
  // HSL(hue, 45%, 18%) → hex
  const s = 0.45;
  const l = 0.18;
  const k = (n: number) => (n + hue / 30) % 12;
  const a = s * Math.min(l, 1 - l);
  const f = (n: number) => l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)));
  const h = (v: number) => Math.round(v * 255).toString(16).padStart(2, '0');
  return `#${h(f(0))}${h(f(8))}${h(f(4))}`.toUpperCase();
}

export type ColorCompute = (url: string) => Promise<string | null>;

export interface ColorCacheOptions {
  persist?: PersistentCache | null;
  compute?: ColorCompute;
  max?: number;
}

const KEY = (url: string) => `color:${url}`;

export class ColorCache {
  private mem: Lru<string, string>;
  private inflight = new Map<string, Promise<string | null>>();
  private persist: PersistentCache | null;
  private compute: ColorCompute;

  constructor(opts: ColorCacheOptions = {}) {
    this.mem = new Lru(opts.max ?? 400);
    this.persist = opts.persist ?? null;
    this.compute = opts.compute ?? canvasColor;
  }

  /** Synchronous memory hit only. */
  peek(url: string): string | undefined {
    return this.mem.get(url);
  }

  get(url: string): Promise<string | null> {
    const hit = this.mem.get(url);
    if (hit) return Promise.resolve(hit);
    let p = this.inflight.get(url);
    if (!p) {
      p = (async () => {
        const stored = this.persist ? ((await this.persist.get(KEY(url))) as string | undefined) : undefined;
        if (typeof stored === 'string' && /^#[0-9A-F]{6}$/i.test(stored)) {
          this.mem.set(url, stored);
          return stored;
        }
        const c = await this.compute(url).catch(() => null);
        if (c) {
          this.mem.set(url, c);
          if (this.persist) void this.persist.set(KEY(url), c);
        }
        return c;
      })();
      p.finally(() => this.inflight.delete(url)).catch(() => undefined);
      this.inflight.set(url, p);
    }
    return p;
  }
}

function idle(): Promise<void> {
  return new Promise((r) => {
    const ric = (globalThis as { requestIdleCallback?: (cb: () => void, o?: { timeout: number }) => number }).requestIdleCallback;
    if (ric) ric(() => r(), { timeout: 1500 });
    else setTimeout(r, 60);
  });
}

/** Browser-only: draw a CORS image into a 64px canvas and average it. Runs at idle time. */
export async function canvasColor(url: string): Promise<string | null> {
  if (!url || typeof document === 'undefined' || typeof Image === 'undefined') return null;
  await idle();
  try {
    const imgEl = new Image();
    imgEl.referrerPolicy = 'no-referrer';
    imgEl.crossOrigin = 'anonymous';
    imgEl.decoding = 'async';
    imgEl.src = url;
    await imgEl.decode();
    const size = 64;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    if (!ctx) return null;
    ctx.drawImage(imgEl, 0, 0, size, size);
    return averageColor(ctx.getImageData(0, 0, size, size).data);
  } catch {
    return null;
  }
}
