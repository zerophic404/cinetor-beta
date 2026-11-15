// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
// scrape or hack anything without Mythbrix's explicit permission.
// Shaka-player singleton bound to ONE persistent <video> (owned by the /app layout's park slot).
// The element is MOVED between hosts (park → player → mini), never recreated → buffer + state kept.
import { writable, type Readable } from 'svelte/store';
import { MIME, playbackOf, type Media } from './sources';
import type { EngineLike, LoadOpts } from './types';
import type { Source } from '../data/types';
import { isLazySource } from '../data/gateway-play';

interface ShakaTrack {
  id: number;
  active: boolean;
  type: string;
  height?: number | null;
  bandwidth: number;
  language: string;
  label?: string | null;
  roles?: string[];
}
interface ShakaError {
  code: number;
  category: number;
  severity?: number;
  message?: string;
}
interface ShakaPlayer {
  attach(v: HTMLMediaElement): Promise<void>;
  load(uri: string, start?: number | null, mime?: string): Promise<void>;
  unload(): Promise<void>;
  configure(c: object): boolean;
  getVariantTracks(): ShakaTrack[];
  getTextTracks(): ShakaTrack[];
  selectVariantTrack(t: ShakaTrack, clearBuffer?: boolean): void;
  selectAudioLanguage(lang: string, role?: string): void;
  selectTextTrack(t: ShakaTrack): void;
  setTextTrackVisibility(on: boolean): void;
  addEventListener(type: string, fn: (e: Event & { detail?: ShakaError }) => void): void;
  getNetworkingEngine(): { registerRequestFilter(f: (type: number, req: { headers: Record<string, string> }) => void): void } | null;
}
interface ShakaNS {
  Player: { new (): ShakaPlayer; isBrowserSupported(): boolean };
  polyfill: { installAll(): void };
}

export interface Quality {
  height: number;
  bandwidth: number;
}
export interface AudioOpt {
  language: string;
  label: string;
}
export interface TextOpt {
  id: number;
  language: string;
  label: string;
}
export interface TrackInfo {
  qualities: Quality[];
  /** Auto (ABR) on. */
  auto: boolean;
  activeHeight: number | null;
  audios: AudioOpt[];
  audio: string | null;
  texts: TextOpt[];
  text: number | null;
}

const NO_TRACKS: TrackInfo = { qualities: [], auto: true, activeHeight: null, audios: [], audio: null, texts: [], text: null };

let shakaP: Promise<ShakaNS | null> | null = null;
/** Lazy chunk: shaka is only downloaded the first time something is loaded. */
export function loadShaka(): Promise<ShakaNS | null> {
  return (shakaP ??= import('shaka-player')
    .then((m) => {
      const mod = m as unknown as { default?: ShakaNS } & ShakaNS;
      const sh = (mod.default?.Player ? mod.default : mod) as ShakaNS;
      sh.polyfill.installAll();
      return sh.Player.isBrowserSupported() ? sh : null;
    })
    .catch(() => null));
}

/** Human message for shaka / media errors. Unsupported formats → caller shows external-player link. */
export function describeError(code: number | undefined, category?: number): { msg: string; unsupported: boolean } {
  if (code === undefined) return { msg: 'Playback failed.', unsupported: false };
  // shaka categories: 1 NETWORK · 3 MEDIA · 4 MANIFEST · 6 DRM
  if (category === 1) return { msg: 'Network error — check your connection and retry.', unsupported: false };
  if (code === 4032 || code === 3016 || code === 3014 || code === 3015 || code === 4040 || code === 4000 || code === 4001)
    return { msg: "This format or codec isn't supported by your browser.", unsupported: true };
  if (category === 6) return { msg: 'Protected content is not supported here.', unsupported: true };
  return { msg: `Playback error (${code}).`, unsupported: false };
}

class Engine implements EngineLike {
  video: HTMLVideoElement | null = null;
  loadedKey: string | null = null;
  private park: HTMLElement | null = null;
  private hosts: HTMLElement[] = [];
  private player: ShakaPlayer | null = null;
  private headers: Record<string, string> | undefined;
  private _tracks = writable<TrackInfo>(NO_TRACKS);
  readonly tracks: Readable<TrackInfo> = { subscribe: this._tracks.subscribe };
  private _error = writable<{ msg: string; unsupported: boolean } | null>(null);
  readonly error: Readable<{ msg: string; unsupported: boolean } | null> = { subscribe: this._error.subscribe };
  private videoListeners: ((v: HTMLVideoElement) => void)[] = [];
  /** Gateway variants of the loaded media (quality menu = reload another proxy MP4). */
  private variants: Source[] = [];
  private current: Source | null = null;
  private media: Media | null = null;
  private recovering = false;
  /** Re-resolve a failing gateway stream (expired signed link) — wired by the controller. */
  recover: ((m: Media) => Promise<Media | null>) | null = null;
  /** Manual quality picks are remembered here (controller sets localStorage). */
  onQualityPick: ((height: number | null) => void) | null = null;

  ensureVideo(): HTMLVideoElement {
    if (this.video) return this.video;
    const v = document.createElement('video');
    v.playsInline = true;
    v.setAttribute('playsinline', '');
    v.setAttribute('webkit-playsinline', '');
    v.preload = 'auto';
    v.className = 'cinetor-video';
    v.addEventListener('error', () => {
      if (this.tryRecover()) return;
      const code = v.error?.code;
      if (code === 4 || code === 3) this._error.set({ msg: "This format or codec isn't supported by your browser.", unsupported: true });
      else if (code === 2) this._error.set({ msg: 'Network error — check your connection and retry.', unsupported: false });
    });
    this.video = v;
    this.place();
    for (const f of this.videoListeners) f(v);
    return v;
  }

  /** Subscribe to the element's creation (controller wires events once). */
  onVideo(f: (v: HTMLVideoElement) => void): void {
    if (this.video) f(this.video);
    else this.videoListeners.push(f);
  }

  setPark(el: HTMLElement | null): void {
    this.park = el;
    if (el) this.ensureVideo();
    this.place();
  }

  /** Move the persistent element into `host` (latest host wins). Returns detach. */
  attach(host: HTMLElement): () => void {
    this.hosts = this.hosts.filter((h) => h !== host);
    this.hosts.push(host);
    this.ensureVideo();
    this.place();
    return () => {
      this.hosts = this.hosts.filter((h) => h !== host);
      this.place();
    };
  }

  private place(): void {
    const v = this.video;
    if (!v) return;
    const host = this.hosts[this.hosts.length - 1] ?? this.park;
    if (!host || v.parentElement === host) return;
    const wasPlaying = !v.paused && !v.ended;
    const h = host as HTMLElement & { moveBefore?: (n: Node, ref: Node | null) => void };
    // moveBefore (Chromium 133+) keeps media state atomically; appendChild is a same-task move.
    try {
      if (typeof h.moveBefore === 'function' && v.isConnected && host.isConnected) h.moveBefore(v, null);
      else host.appendChild(v);
    } catch {
      host.appendChild(v);
    }
    if (wasPlaying && v.paused) v.play().catch(() => {});
  }

  canPlay(mime: string): boolean {
    if (typeof document === 'undefined') return false;
    return !!this.ensureVideo().canPlayType(mime);
  }

  private async getPlayer(sh: ShakaNS): Promise<ShakaPlayer> {
    if (this.player) return this.player;
    const p = new sh.Player();
    await p.attach(this.ensureVideo());
    p.addEventListener('error', (e) => {
      const d = e.detail;
      if (d?.code === 7000) return; // LOAD_INTERRUPTED: newer load won
      if (this.tryRecover()) return;
      this._error.set(describeError(d?.code, d?.category));
    });
    const refresh = () => this.refreshTracks();
    for (const t of ['trackschanged', 'variantchanged', 'adaptation', 'textchanged', 'abrstatuschanged']) p.addEventListener(t, refresh);
    p.getNetworkingEngine()?.registerRequestFilter((_type, req) => {
      if (this.headers) Object.assign(req.headers, this.headers);
    });
    this.player = p;
    return p;
  }

  async load(media: Media, startSec: number, opts: LoadOpts): Promise<void> {
    const src = media.source;
    if (!src || isLazySource(src)) throw new Error('No source available');
    const v = this.ensureVideo();
    this.loadedKey = null;
    this._error.set(null);
    this._tracks.set(NO_TRACKS);
    this.media = media;
    this.variants = media.variants ?? [];
    this.current = src;
    v.preload = opts.buffer ? 'auto' : 'metadata';
    try {
      await this.loadSrc(src, startSec, opts);
    } catch (e) {
      if (opts.signal?.aborted || (e as Error)?.name === 'AbortError' || !this.variants.length) throw e;
      // Gateway: other variants, then a fresh resolution (expired link), before giving up.
      const ok = await this.fallback(media, src, startSec, opts);
      if (!ok) throw e;
    }
    if (opts.signal?.aborted) throw new DOMException('aborted', 'AbortError');
    this.loadedKey = media.key;
    this.refreshTracks();
  }

  private async fallback(media: Media, failed: Source, startSec: number, opts: LoadOpts): Promise<boolean> {
    for (const alt of this.variants) {
      if (alt.url === failed.url) continue;
      try {
        await this.loadSrc(alt, startSec, opts);
        this.current = alt;
        this._error.set(null);
        return true;
      } catch {
        if (opts.signal?.aborted) return false;
      }
    }
    const fresh = this.recover ? await this.recover(media).catch(() => null) : null;
    if (!fresh?.source || opts.signal?.aborted) return false;
    try {
      await this.loadSrc(fresh.source, startSec, opts);
      this.variants = fresh.variants ?? [];
      this.current = fresh.source;
      this._error.set(null);
      return true;
    } catch {
      return false;
    }
  }

  /** Mid-playback failure of a gateway stream: re-resolve once and continue at the same position. */
  private tryRecover(): boolean {
    const m = this.media;
    const v = this.video;
    if (!m || !v || !this.loadedKey || !this.variants.length || this.recovering || !this.recover) return false;
    this.recovering = true;
    const t = v.currentTime;
    const wasPlaying = !v.paused;
    const key = this.loadedKey;
    void (async () => {
      try {
        const fresh = await this.recover!(m);
        if (!fresh?.source || this.loadedKey !== key) throw new Error('No source available');
        const h = this.current?.height;
        const src = fresh.variants?.find((x) => x.height === h) ?? fresh.source;
        await this.loadSrc(src, t, { buffer: true });
        this.variants = fresh.variants ?? [];
        this.current = src;
        this._error.set(null);
        this.refreshTracks();
        if (wasPlaying) await v.play().catch(() => {});
      } catch {
        this._error.set({ msg: 'Stream link expired or failed — retry.', unsupported: false });
      } finally {
        setTimeout(() => (this.recovering = false), 10_000);
      }
    })();
    return true;
  }

  private async loadSrc(src: Source, startSec: number, opts: LoadOpts): Promise<void> {
    const v = this.ensureVideo();
    const kind = playbackOf(src, (m) => this.canPlay(m));
    if (kind === 'unsupported') {
      this._error.set({ msg: "This format isn't supported by your browser.", unsupported: true });
      throw new Error('unsupported');
    }
    const sh = await loadShaka();
    if (opts.signal?.aborted) throw new DOMException('aborted', 'AbortError');
    this.headers = src.headers;
    if (sh) {
      const p = await this.getPlayer(sh);
      p.configure({
        streaming: { bufferingGoal: opts.buffer ? 30 : 4, rebufferingGoal: 0.5, bufferBehind: 30 },
        abr: { enabled: true }
      });
      // offline: URIs (downloaded HLS/DASH) are resolved by shaka's offline scheme — no MIME hint.
      await p.load(src.url, startSec > 0 ? startSec : null, src.url.startsWith('offline:') ? undefined : MIME[src.kind]);
    } else {
      // No MSE (old iOS): native element (Safari plays HLS natively).
      if (kind === 'shaka' && !(src.kind === 'HLS' && this.canPlay('application/vnd.apple.mpegurl'))) {
        this._error.set({ msg: "Adaptive streaming isn't supported by this browser.", unsupported: true });
        throw new Error('unsupported');
      }
      await nativeLoad(v, src.url, startSec, opts.signal);
    }
  }

  async unload(): Promise<void> {
    this.loadedKey = null;
    this.media = null;
    this.variants = [];
    this.current = null;
    this._tracks.set(NO_TRACKS);
    const v = this.video;
    if (!v) return;
    v.pause();
    if (this.player) await this.player.unload().catch(() => {});
    v.removeAttribute('src');
    try {
      v.load();
    } catch {
      /* ignore */
    }
  }

  play(): Promise<void> {
    return this.ensureVideo().play();
  }

  pause(): void {
    this.video?.pause();
  }

  refreshTracks(): void {
    const p = this.player;
    if (!this.loadedKey) return;
    if (this.variants.length) {
      // Progressive gateway variants: one quality per proxy MP4.
      const texts = p ? p.getTextTracks().map((t) => ({ id: t.id, language: t.language, label: t.label || langName(t.language) })) : [];
      this._tracks.update((cur) => ({
        ...cur,
        qualities: this.variants.filter((x) => x.height).map((x) => ({ height: x.height!, bandwidth: 0 })),
        auto: cur.auto && !this.current?.height ? true : cur.auto,
        activeHeight: this.current?.height ?? null,
        texts
      }));
      return;
    }
    if (!p) return;
    const variants = p.getVariantTracks();
    const byH = new Map<number, number>();
    for (const t of variants) if (t.height) byH.set(t.height, Math.max(byH.get(t.height) ?? 0, t.bandwidth));
    const active = variants.find((t) => t.active);
    const audios = new Map<string, AudioOpt>();
    for (const t of variants) if (t.language && !audios.has(t.language)) audios.set(t.language, { language: t.language, label: t.label || langName(t.language) });
    const texts = p.getTextTracks().map((t) => ({ id: t.id, language: t.language, label: t.label || langName(t.language) }));
    this._tracks.update((cur) => ({
      qualities: [...byH].map(([height, bandwidth]) => ({ height, bandwidth })).sort((a, b) => b.height - a.height),
      auto: cur.auto,
      activeHeight: active?.height ?? null,
      audios: [...audios.values()],
      audio: active?.language ?? null,
      texts,
      text: p.getTextTracks().find((t) => t.active)?.id ?? null
    }));
  }

  selectQuality(height: number | null): void {
    if (this.variants.length) {
      void this.switchVariant(height);
      return;
    }
    const p = this.player;
    if (!p) return;
    if (height === null) {
      p.configure({ abr: { enabled: true } });
      this._tracks.update((t) => ({ ...t, auto: true }));
      return;
    }
    const variants = p.getVariantTracks();
    const lang = variants.find((t) => t.active)?.language;
    const pick = variants.filter((t) => t.height === height && (!lang || t.language === lang)).sort((a, b) => b.bandwidth - a.bandwidth)[0];
    if (!pick) return;
    p.configure({ abr: { enabled: false } });
    p.selectVariantTrack(pick, true);
    this._tracks.update((t) => ({ ...t, auto: false }));
    this.refreshTracks();
  }

  /** Reload another gateway variant at the current position (keeps play/pause state). */
  private async switchVariant(height: number | null): Promise<void> {
    const v = this.video;
    const key = this.loadedKey;
    if (!v || !key) return;
    this.onQualityPick?.(height);
    const target = height === null ? this.variants.find((x) => (x.height ?? 0) <= 1080) ?? this.variants[0] : this.variants.find((x) => x.height === height);
    if (!target || target.url === this.current?.url) {
      this._tracks.update((t) => ({ ...t, auto: height === null }));
      return;
    }
    const t = v.currentTime;
    const wasPlaying = !v.paused;
    try {
      await this.loadSrc(target, t, { buffer: true });
      if (this.loadedKey !== key) return;
      this.current = target;
      this._tracks.update((x) => ({ ...x, auto: height === null }));
      this.refreshTracks();
      if (wasPlaying) await v.play().catch(() => {});
    } catch {
      this._error.set({ msg: `Couldn't switch to ${target.label ?? 'that quality'}.`, unsupported: false });
    }
  }

  selectAudio(language: string): void {
    this.player?.selectAudioLanguage(language);
    this.refreshTracks();
  }

  /** Embedded (manifest) text track; null hides. */
  selectText(id: number | null): void {
    const p = this.player;
    if (!p) return;
    if (id === null) p.setTextTrackVisibility(false);
    else {
      const t = p.getTextTracks().find((x) => x.id === id);
      if (t) {
        p.selectTextTrack(t);
        p.setTextTrackVisibility(true);
      }
    }
    this._tracks.update((x) => ({ ...x, text: id }));
  }
}

function nativeLoad(v: HTMLVideoElement, url: string, start: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const done = () => {
      v.removeEventListener('loadedmetadata', ok);
      v.removeEventListener('error', bad);
    };
    const ok = () => {
      done();
      if (start > 0) v.currentTime = start;
      resolve();
    };
    const bad = () => {
      done();
      reject(new Error('Media failed to load'));
    };
    v.addEventListener('loadedmetadata', ok);
    v.addEventListener('error', bad);
    signal?.addEventListener('abort', () => {
      done();
      reject(new DOMException('aborted', 'AbortError'));
    });
    v.src = url;
    v.load();
  });
}

function langName(code: string): string {
  if (!code || code === 'und') return 'Default';
  try {
    return new Intl.DisplayNames(undefined, { type: 'language' }).of(code) ?? code;
  } catch {
    return code;
  }
}

export const engine = new Engine();
