// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
// scrape or hack anything without Mythbrix's explicit permission.
// Shaka-player + chromeless YouTube IFrame API singleton bound to persistent stage elements
// (owned by the /app layout's park slot and moved between park → player → mini → desktop pop-out).
import { writable, type Readable } from 'svelte/store';
import { MIME, playbackOf, type Media } from './sources';
import {
  buildYouTubePlayerVars,
  dubOfSource,
  extractYouTubeVideoId,
  loadYouTubeIframeApi,
  mapYouTubeStateToClock,
  pickAlternateSource,
  pickPreferredSource,
  YOUTUBE_CLOCK0,
  type YouTubeClock,
  type YTPlayerInstance
} from './youtube';
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

export type YouTubeClockEvent = 'tick' | 'play' | 'pause' | 'ended';

const NO_TRACKS: TrackInfo = { qualities: [], auto: true, activeHeight: null, audios: [], audio: null, texts: [], text: null };
const YT_NOCOOKIE_HOST = 'https://www.youtube-nocookie.com';

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
    .catch(() => {
      void healStaleApp();
      return null;
    }));
}

/** A stale service-worker shell can reference lazy chunks that no longer exist → drop caches + SW and reload once. */
async function healStaleApp(): Promise<void> {
  try {
    if (typeof window === 'undefined' || sessionStorage.getItem('cinetor.healed')) return;
    sessionStorage.setItem('cinetor.healed', '1');
    const regs = (await navigator.serviceWorker?.getRegistrations?.()) ?? [];
    await Promise.all(regs.map((r) => r.unregister()));
    if ('caches' in window) await Promise.all((await caches.keys()).map((k) => caches.delete(k)));
    location.reload();
  } catch {
    /* ignore */
  }
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
  private rawVideoPlay: (() => Promise<void>) | null = null;
  private rawVideoPause: (() => void) | null = null;
  private park: HTMLElement | null = null;
  private hosts: HTMLElement[] = [];
  private player: ShakaPlayer | null = null;
  private headers: Record<string, string> | undefined;
  private _tracks = writable<TrackInfo>(NO_TRACKS);
  readonly tracks: Readable<TrackInfo> = { subscribe: this._tracks.subscribe };
  private _error = writable<{ msg: string; unsupported: boolean } | null>(null);
  readonly error: Readable<{ msg: string; unsupported: boolean } | null> = { subscribe: this._error.subscribe };
  private _isYouTube = writable<boolean>(false);
  readonly isYouTube: Readable<boolean> = { subscribe: this._isYouTube.subscribe };

  private videoListeners: ((v: HTMLVideoElement) => void)[] = [];
  private ytClockListeners: ((c: YouTubeClock, ev: YouTubeClockEvent) => void)[] = [];

  private ytWrap: HTMLDivElement | null = null;
  private ytSlot: HTMLDivElement | null = null;
  private ytShield: HTMLDivElement | null = null;
  private ytPlayer: YTPlayerInstance | null = null;
  private ytTimer: ReturnType<typeof setInterval> | null = null;
  private ytActive = false;
  private ytVideoId: string | null = null;
  private ytWantPlay = false;
  private ytGeneration = 0;
  private ytClock: YouTubeClock = { ...YOUTUBE_CLOCK0 };
  private failedUrls = new Set<string>();

  /** Gateway variants of the loaded media (quality menu = reload another proxy MP4). */
  private variants: Source[] = [];
  private current: Source | null = null;
  private media: Media | null = null;
  private recovering = false;
  /** Re-resolve a failing gateway stream (expired signed link) — wired by the controller. */
  recover: ((m: Media) => Promise<Media | null>) | null = null;
  /** Manual quality picks are remembered here (controller sets localStorage). */
  onQualityPick: ((height: number | null, itemId?: string) => void) | null = null;
  /** Manual dub picks are remembered across episodes (controller sets localStorage). */
  onDubPick: ((dub: string, itemId?: string) => void) | null = null;

  get youtubeActive(): boolean {
    return this.ytActive;
  }

  get currentTime(): number {
    return this.ytActive ? this.ytClock.t : (this.video?.currentTime ?? 0);
  }

  get duration(): number {
    if (this.ytActive) return this.ytClock.dur;
    const d = this.video?.duration ?? 0;
    return Number.isFinite(d) && d > 0 ? d : 0;
  }

  get isPaused(): boolean {
    return this.ytActive ? this.ytClock.paused : (this.video?.paused ?? true);
  }

  get isEnded(): boolean {
    return this.ytActive ? this.ytClock.ended : (this.video?.ended ?? false);
  }

  get playbackRate(): number {
    return this.ytActive ? this.ytClock.rate : (this.video?.playbackRate ?? 1);
  }

  get volume(): number {
    return this.ytActive ? this.ytClock.volume : (this.video?.volume ?? 1);
  }

  get muted(): boolean {
    return this.ytActive ? this.ytClock.muted : (this.video?.muted ?? false);
  }

  ensureVideo(): HTMLVideoElement {
    if (this.video) return this.video;
    const v = document.createElement('video');
    v.playsInline = true;
    v.setAttribute('playsinline', '');
    v.setAttribute('webkit-playsinline', '');
    v.preload = 'auto';
    v.className = 'cinetor-video';
    this.rawVideoPlay = v.play.bind(v);
    this.rawVideoPause = v.pause.bind(v);
    const pausedDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'paused');
    v.play = () => (this.ytActive ? this.play() : this.rawVideoPlay!());
    v.pause = () => {
      if (this.ytActive) this.pause();
      else this.rawVideoPause!();
    };
    if (pausedDesc?.get) {
      Object.defineProperty(v, 'paused', {
        configurable: true,
        get: () => (this.ytActive ? this.ytClock.paused : pausedDesc.get!.call(v))
      });
    }
    v.addEventListener('error', () => {
      if (this.ytActive) return;
      if (this.tryRecover()) return;
      const code = v.error?.code;
      if (code === 4 || code === 3) void this.explainMediaError(v.currentSrc, code);
      else if (code === 2) this._error.set({ msg: 'Network error — check your connection and retry.', unsupported: false });
    });
    this.video = v;
    this.place();
    for (const f of this.videoListeners) f(v);
    return v;
  }

  private ensureYtWrap(): HTMLDivElement {
    if (this.ytWrap) return this.ytWrap;
    const wrap = document.createElement('div');
    wrap.className = 'cinetor-yt-wrap';
    wrap.setAttribute('aria-hidden', 'true');
    wrap.style.cssText =
      'position:absolute;inset:0;width:100%;height:100%;overflow:hidden;background:#000;pointer-events:none;user-select:none;-webkit-user-select:none;display:none;';

    const slot = document.createElement('div');
    slot.className = 'cinetor-yt-slot';
    slot.style.cssText =
      'position:absolute;top:-60px;left:0;width:100%;height:calc(100% + 120px);pointer-events:none;transform-origin:center center;';

    const shield = document.createElement('div');
    shield.className = 'cinetor-yt-shield';
    shield.style.cssText = 'position:absolute;inset:0;background:#000;pointer-events:none;z-index:1;';

    wrap.appendChild(slot);
    wrap.appendChild(shield);
    this.ytWrap = wrap;
    this.ytSlot = slot;
    this.ytShield = shield;
    this.place();
    return wrap;
  }

  /** Subscribe to the element's creation (controller wires events once). */
  onVideo(f: (v: HTMLVideoElement) => void): void {
    if (this.video) f(this.video);
    else this.videoListeners.push(f);
  }

  /** Subscribe to chromeless YouTube clock updates (controller wires resume, mediaSession & arbiter). */
  onYouTubeClock(f: (c: YouTubeClock, ev: YouTubeClockEvent) => void): void {
    this.ytClockListeners.push(f);
  }

  private emitYtClock(ev: YouTubeClockEvent): void {
    if (!this.ytActive) return;
    for (const f of this.ytClockListeners) f(this.ytClock, ev);
  }

  setPark(el: HTMLElement | null): void {
    this.park = el;
    if (el) this.ensureVideo();
    this.place();
  }

  /** MEDIA_ERR_SRC_NOT_SUPPORTED is also what browsers report for HTTP 403/404/429 — tell the two apart. */
  private async explainMediaError(src: string, code: number): Promise<void> {
    const unsupported = { msg: "This format or codec isn't supported by your browser.", unsupported: true };
    if (!/^https?:/i.test(src)) return void this._error.set(unsupported);
    try {
      const r = await fetch(src, { headers: { Range: 'bytes=0-0' }, cache: 'no-store' });
      void r.body?.cancel();
      if (r.status >= 400) {
        this._error.set({ msg: `The stream isn't available right now (HTTP ${r.status}). Retry in a moment or pick another quality.`, unsupported: false });
        return;
      }
    } catch {
      /* cross-origin without CORS / offline → fall through */
    }
    this._error.set(code === 3 ? { msg: 'Playback was interrupted — retry.', unsupported: false } : unsupported);
  }

  /** Move the persistent element(s) into `host` (latest host wins). Returns detach. */
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
    const host = this.hosts[this.hosts.length - 1] ?? this.park;
    if (!host) return;
    const h = host as HTMLElement & { moveBefore?: (n: Node, ref: Node | null) => void };

    const v = this.video;
    if (v && v.parentElement !== host) {
      const wasPlaying = !this.ytActive && !v.paused && !v.ended;
      try {
        if (typeof h.moveBefore === 'function' && v.isConnected && host.isConnected) h.moveBefore(v, null);
        else host.appendChild(v);
      } catch {
        host.appendChild(v);
      }
      if (wasPlaying && v.paused) this.rawVideoPlay?.().catch(() => {});
    }

    const yw = this.ytWrap;
    if (yw && yw.parentElement !== host) {
      const wasConnected = yw.isConnected;
      let movedAtomically = false;
      try {
        if (typeof h.moveBefore === 'function' && wasConnected && host.isConnected) {
          h.moveBefore(yw, null);
          movedAtomically = true;
        } else {
          host.appendChild(yw);
        }
      } catch {
        host.appendChild(yw);
      }
      // Browsers without `moveBefore` reload iframes on `appendChild` → re-bind YT.Player at current time.
      if (!movedAtomically && wasConnected && this.ytActive && this.ytVideoId && this.ytPlayer) {
        void this.remountYouTubeAfterMove();
      }
    }
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
      if (this.ytActive) return;
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

  private allSourcesOf(media: Media): Source[] {
    if (media.variants?.length) return media.variants;
    const list = media.episode?.sources?.length ? media.episode.sources : (media.item.sources ?? []);
    return list.filter((s) => playbackOf(s, (m) => this.canPlay(m)) !== 'unsupported');
  }

  async load(media: Media, startSec: number, opts: LoadOpts): Promise<void> {
    const src = media.source;
    if (!src || isLazySource(src)) throw new Error('No source available');
    const v = this.ensureVideo();
    this.loadedKey = null;
    this._error.set(null);
    this._tracks.set(NO_TRACKS);
    this.failedUrls.clear();
    this.media = media;
    this.variants = this.allSourcesOf(media);
    this.current = src;
    v.preload = opts.buffer ? 'auto' : 'metadata';
    try {
      await this.loadSrc(src, startSec, opts);
    } catch (e) {
      if (opts.signal?.aborted || (e as Error)?.name === 'AbortError' || (!this.variants.length && !this.recover)) throw e;
      this.failedUrls.add(src.url);
      // Gateway / YouTube fallback: try alternate streams, then fresh resolution before giving up.
      const ok = await this.fallback(media, src, startSec, opts);
      if (!ok) throw e;
    }
    if (opts.signal?.aborted) throw new DOMException('aborted', 'AbortError');
    this.loadedKey = media.key;
    this.refreshTracks();
  }

  private async fallback(media: Media, failed: Source, startSec: number, opts: LoadOpts): Promise<boolean> {
    this.failedUrls.add(failed.url);
    while (true) {
      const alt = pickAlternateSource(this.variants, this.failedUrls);
      if (!alt) break;
      try {
        await this.loadSrc(alt, startSec, opts);
        this.current = alt;
        this._error.set(null);
        return true;
      } catch {
        this.failedUrls.add(alt.url);
        if (opts.signal?.aborted) return false;
      }
    }
    const fresh = this.recover ? await this.recover(media).catch(() => null) : null;
    if (!fresh?.source || opts.signal?.aborted) return false;
    const freshList = fresh.variants?.length ? fresh.variants : [fresh.source];
    const candidate = pickAlternateSource(freshList, this.failedUrls) ?? fresh.source;
    try {
      await this.loadSrc(candidate, startSec, opts);
      this.variants = fresh.variants ?? [];
      this.current = candidate;
      this._error.set(null);
      return true;
    } catch {
      return false;
    }
  }

  /** Mid-playback failure of a gateway or YouTube stream: switch alternate or re-resolve at current position. */
  private tryRecover(): boolean {
    const m = this.media;
    if (!m || !this.loadedKey || (!this.variants.length && !this.recover) || this.recovering) return false;
    this.recovering = true;
    const t = this.currentTime;
    const wasPlaying = !this.isPaused || this.ytWantPlay;
    const key = this.loadedKey;
    if (this.current?.url) this.failedUrls.add(this.current.url);
    void (async () => {
      try {
        const alt = pickAlternateSource(this.variants, this.failedUrls);
        if (alt) {
          await this.loadSrc(alt, t, { buffer: true });
          if (this.loadedKey !== key) return;
          this.current = alt;
          this._error.set(null);
          this.refreshTracks();
          if (wasPlaying) await this.play().catch(() => {});
          return;
        }
        if (!this.recover) throw new Error('No source available');
        const fresh = await this.recover(m);
        if (!fresh?.source || this.loadedKey !== key) throw new Error('No source available');
        const h = this.current?.height;
        const freshList = fresh.variants ?? [fresh.source];
        const src = freshList.find((x) => !this.failedUrls.has(x.url) && x.height === h) ?? pickAlternateSource(freshList, this.failedUrls) ?? fresh.source;
        await this.loadSrc(src, t, { buffer: true });
        this.variants = fresh.variants ?? [];
        this.current = src;
        this._error.set(null);
        this.refreshTracks();
        if (wasPlaying) await this.play().catch(() => {});
      } catch {
        this._error.set({ msg: 'This stream is unavailable right now — retry or pick another quality.', unsupported: false });
      } finally {
        setTimeout(() => (this.recovering = false), 8_000);
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
    if (kind === 'youtube') {
      const videoId = extractYouTubeVideoId(src.url);
      if (!videoId) throw new Error('Invalid stream');
      await this.loadYouTubeSrc(videoId, startSec, opts);
      return;
    }

    this.destroyYouTube();
    v.style.display = '';
    const sh = await loadShaka();
    if (opts.signal?.aborted) throw new DOMException('aborted', 'AbortError');
    this.headers = src.headers;
    const nativeHls = src.kind === 'HLS' && this.canPlay('application/vnd.apple.mpegurl');
    const canFallBack = !src.url.startsWith('offline:') && (kind === 'native' || nativeHls);
    if (sh) {
      try {
        const p = await this.getPlayer(sh);
        p.configure({
          streaming: { bufferingGoal: opts.buffer ? 30 : 4, rebufferingGoal: 0.5, bufferBehind: 30 },
          abr: { enabled: true }
        });
        // offline: URIs (downloaded HLS/DASH) are resolved by shaka's offline scheme — no MIME hint.
        await p.load(src.url, startSec > 0 ? startSec : null, src.url.startsWith('offline:') ? undefined : MIME[src.kind]);
        return;
      } catch (e) {
        if (opts.signal?.aborted || !canFallBack) throw e;
        // Progressive file / Safari HLS: the browser's own decoder is the most compatible path.
        this._error.set(null);
        await this.player?.unload().catch(() => {});
      }
    }
    // No shaka (no MediaSource, e.g. old iOS) or shaka failed on a natively playable source.
    if (kind === 'shaka' && !nativeHls) {
      this._error.set({ msg: "This browser can't play adaptive streams (no MediaSource). Update it, or try Chrome, Edge, Firefox or Safari 17.1+.", unsupported: true });
      throw new Error('unsupported');
    }
    await nativeLoad(v, src.url, startSec, opts.signal);
  }

  private async loadYouTubeSrc(videoId: string, startSec: number, opts: LoadOpts): Promise<void> {
    const v = this.ensureVideo();
    this.rawVideoPause?.();
    if (this.player) await this.player.unload().catch(() => {});
    v.removeAttribute('src');
    try {
      v.load();
    } catch {
      /* ignore */
    }
    v.style.display = 'none';

    const wrap = this.ensureYtWrap();
    wrap.style.display = 'block';
    if (this.ytShield) this.ytShield.style.display = 'block';

    const prevRate = this.ytClock.rate;
    const prevVol = this.ytClock.volume;
    const prevMuted = this.ytClock.muted;
    this.ytActive = true;
    this._isYouTube.set(true);
    this.ytVideoId = videoId;
    this.ytWantPlay = false;
    this.ytClock = {
      ...YOUTUBE_CLOCK0,
      t: Math.max(0, startSec),
      dur: this.media?.durationSec ?? 0,
      rate: prevRate,
      volume: prevVol,
      muted: prevMuted
    };
    this.emitYtClock('tick');

    if (!opts.buffer) {
      // Data-saver preload: keep resolved metadata without creating the iframe until play().
      return;
    }
    await this.mountYouTubeInstance(videoId, startSec, false, opts.signal);
  }

  private async mountYouTubeInstance(
    videoId: string,
    startSec: number,
    autoplay: boolean,
    signal?: AbortSignal
  ): Promise<void> {
    const yt = await loadYouTubeIframeApi();
    if (signal?.aborted) throw new DOMException('aborted', 'AbortError');
    if (!yt?.Player) throw new Error('Player unavailable');

    this.clearYtInstanceOnly();
    const gen = ++this.ytGeneration;
    const slot = this.ytSlot;
    if (!slot) throw new Error('Stage unavailable');
    slot.innerHTML = '';
    const mountEl = document.createElement('div');
    mountEl.style.cssText = 'width:100%;height:100%;pointer-events:none;';
    slot.appendChild(mountEl);

    const origin = typeof window !== 'undefined' ? window.location?.origin : undefined;

    await new Promise<void>((resolve, reject) => {
      let settled = false;
      const onAbort = () => {
        if (settled) return;
        settled = true;
        reject(new DOMException('aborted', 'AbortError'));
      };
      signal?.addEventListener('abort', onAbort, { once: true });

      const playerInstance = new yt.Player(mountEl, {
        host: YT_NOCOOKIE_HOST,
        videoId,
        width: '100%',
        height: '100%',
        playerVars: buildYouTubePlayerVars({ autoplay, startSec, origin }),
        events: {
          onReady: (ev) => {
            if (gen !== this.ytGeneration) return;
            const target = ev.target;
            this.ytPlayer = target;
            const iframe = slot.querySelector('iframe');
            if (iframe) {
              iframe.style.cssText = 'width:100% !important;height:100% !important;border:0 !important;pointer-events:none !important;';
              iframe.setAttribute('tabindex', '-1');
            }
            const dur = safeNum(() => target.getDuration(), this.ytClock.dur);
            if (this.ytClock.rate !== 1) runSafe(() => target.setPlaybackRate(this.ytClock.rate));
            runSafe(() => target.setVolume(Math.round(this.ytClock.volume * 100)));
            if (this.ytClock.muted) runSafe(() => target.mute());
            else runSafe(() => target.unMute());
            if (startSec > 0.5) runSafe(() => target.seekTo(startSec, true));
            this.ytClock = mapYouTubeStateToClock(this.ytClock, { dur, t: Math.max(0, startSec) });
            this.startYtProgressLoop(gen);
            this.emitYtClock('tick');
            if (this.ytWantPlay || autoplay) {
              runSafe(() => target.playVideo());
            }
            if (!settled) {
              settled = true;
              signal?.removeEventListener('abort', onAbort);
              resolve();
            }
          },
          onStateChange: (ev) => {
            if (gen !== this.ytGeneration || !this.ytActive) return;
            const st = ev.data;
            if ((st === 1 || st === 2) && this.ytShield) this.ytShield.style.display = 'none';
            const t = safeNum(() => ev.target.getCurrentTime(), this.ytClock.t);
            const dur = safeNum(() => ev.target.getDuration(), this.ytClock.dur);
            const rate = safeNum(() => ev.target.getPlaybackRate(), this.ytClock.rate);
            this.ytClock = mapYouTubeStateToClock(this.ytClock, { ytState: st, t, dur, rate });
            if (st === 1) this.emitYtClock('play');
            else if (st === 2) this.emitYtClock('pause');
            else if (st === 0) this.emitYtClock('ended');
            else this.emitYtClock('tick');
          },
          onPlaybackRateChange: (ev) => {
            if (gen !== this.ytGeneration || !this.ytActive) return;
            this.ytClock = mapYouTubeStateToClock(this.ytClock, { rate: ev.data });
            this.emitYtClock('tick');
          },
          onError: () => {
            if (gen !== this.ytGeneration) return;
            if (this.ytShield) this.ytShield.style.display = 'none';
            if (!settled) {
              settled = true;
              signal?.removeEventListener('abort', onAbort);
              reject(new Error('Stream unavailable'));
              return;
            }
            if (!this.tryRecover()) {
              this._error.set({ msg: "This stream isn't available right now — retry or pick another quality.", unsupported: false });
            }
          }
        }
      });
      this.ytPlayer = playerInstance;
    });
  }

  private async remountYouTubeAfterMove(): Promise<void> {
    const id = this.ytVideoId;
    if (!id || !this.ytActive) return;
    const resumeAt = this.ytClock.t;
    const auto = !this.ytClock.paused || this.ytWantPlay;
    try {
      await this.mountYouTubeInstance(id, resumeAt, auto);
    } catch {
      /* handled by onError / tryRecover */
    }
  }

  private startYtProgressLoop(gen: number): void {
    if (this.ytTimer) clearInterval(this.ytTimer);
    this.ytTimer = setInterval(() => {
      if (gen !== this.ytGeneration || !this.ytActive || !this.ytPlayer) return;
      const p = this.ytPlayer;
      const st = safeNum(() => p.getPlayerState(), -1);
      const t = safeNum(() => p.getCurrentTime(), this.ytClock.t);
      const dur = safeNum(() => p.getDuration(), this.ytClock.dur);
      const rate = safeNum(() => p.getPlaybackRate(), this.ytClock.rate);
      const vol = safeNum(() => p.getVolume(), this.ytClock.volume * 100);
      const muted = safeBool(() => p.isMuted(), this.ytClock.muted);
      this.ytClock = mapYouTubeStateToClock(this.ytClock, {
        ytState: st,
        t,
        dur,
        rate,
        volume0to100: vol,
        muted
      });
      this.emitYtClock('tick');
    }, 250);
  }

  private clearYtInstanceOnly(): void {
    this.ytGeneration++;
    if (this.ytTimer) {
      clearInterval(this.ytTimer);
      this.ytTimer = null;
    }
    const prev = this.ytPlayer;
    this.ytPlayer = null;
    if (prev) runSafe(() => prev.destroy());
    if (this.ytSlot) this.ytSlot.innerHTML = '';
  }

  private destroyYouTube(): void {
    this.clearYtInstanceOnly();
    this.ytActive = false;
    this.ytVideoId = null;
    this.ytWantPlay = false;
    this._isYouTube.set(false);
    this.ytClock = { ...YOUTUBE_CLOCK0 };
    if (this.ytWrap) this.ytWrap.style.display = 'none';
  }

  async unload(): Promise<void> {
    this.loadedKey = null;
    this.media = null;
    this.variants = [];
    this.current = null;
    this.failedUrls.clear();
    this._tracks.set(NO_TRACKS);
    this.destroyYouTube();
    const v = this.video;
    if (!v) return;
    v.style.display = '';
    this.rawVideoPause?.();
    if (this.player) await this.player.unload().catch(() => {});
    v.removeAttribute('src');
    try {
      v.load();
    } catch {
      /* ignore */
    }
  }

  async play(): Promise<void> {
    if (this.ytActive) {
      this.ytWantPlay = true;
      if (this.ytClock.ended) {
        this.ytClock = { ...this.ytClock, t: 0, ended: false, paused: false, waiting: true };
      } else {
        this.ytClock = { ...this.ytClock, paused: false };
      }
      this.emitYtClock('play');
      if (!this.ytPlayer && this.ytVideoId) {
        await this.mountYouTubeInstance(this.ytVideoId, this.ytClock.t, true);
        return;
      }
      runSafe(() => {
        if (this.ytClock.t === 0 && this.ytPlayer?.getPlayerState?.() === 0) this.ytPlayer.seekTo(0, true);
        this.ytPlayer?.playVideo();
      });
      return;
    }
    const v = this.ensureVideo();
    return this.rawVideoPlay ? this.rawVideoPlay() : v.play();
  }

  pause(): void {
    if (this.ytActive) {
      this.ytWantPlay = false;
      this.ytClock = { ...this.ytClock, paused: true, waiting: false };
      runSafe(() => this.ytPlayer?.pauseVideo());
      this.emitYtClock('pause');
      return;
    }
    if (this.rawVideoPause) this.rawVideoPause();
    else this.video?.pause();
  }

  togglePlay(): void {
    if (this.isPaused || this.isEnded) void this.play().catch(() => {});
    else this.pause();
  }

  seekTo(sec: number): void {
    const max = this.duration > 0 ? this.duration : Math.max(0, sec);
    const clamped = Math.min(max, Math.max(0, sec));
    if (this.ytActive) {
      this.ytClock = { ...this.ytClock, t: clamped, ended: false };
      runSafe(() => this.ytPlayer?.seekTo(clamped, true));
      this.emitYtClock('tick');
      return;
    }
    const v = this.video;
    if (v) v.currentTime = clamped;
  }

  setPlaybackRate(rate: number): void {
    if (this.ytActive) {
      this.ytClock = { ...this.ytClock, rate };
      runSafe(() => this.ytPlayer?.setPlaybackRate(rate));
      this.emitYtClock('tick');
      return;
    }
    if (this.video) this.video.playbackRate = rate;
  }

  setVolume(vol: number): void {
    const clamped = Math.min(1, Math.max(0, vol));
    if (this.ytActive) {
      const muted = clamped === 0;
      this.ytClock = { ...this.ytClock, volume: clamped, muted };
      runSafe(() => {
        this.ytPlayer?.setVolume(Math.round(clamped * 100));
        if (muted) this.ytPlayer?.mute();
        else this.ytPlayer?.unMute();
      });
      this.emitYtClock('tick');
      return;
    }
    const v = this.video;
    if (v) {
      v.volume = clamped;
      v.muted = clamped === 0;
    }
  }

  setMuted(muted: boolean): void {
    if (this.ytActive) {
      this.ytClock = { ...this.ytClock, muted };
      runSafe(() => (muted ? this.ytPlayer?.mute() : this.ytPlayer?.unMute()));
      this.emitYtClock('tick');
      return;
    }
    if (this.video) this.video.muted = muted;
  }

  refreshTracks(): void {
    const p = this.player;
    if (!this.loadedKey) return;
    if (this.variants.length) {
      const activeDub = dubOfSource(this.current);
      const dubSet = new Map<string, AudioOpt>();
      for (const x of this.variants) {
        const d = dubOfSource(x);
        if (d && !dubSet.has(d.toLowerCase())) {
          dubSet.set(d.toLowerCase(), { language: `dub:${d}`, label: d });
        }
      }
      const sameDubVariants =
        activeDub && dubSet.size > 1
          ? this.variants.filter((x) => dubOfSource(x)?.toLowerCase() === activeDub.toLowerCase())
          : this.variants;
      const byH = new Set<number>();
      const qualities: Quality[] = [];
      for (const x of sameDubVariants) {
        if (x.height && !byH.has(x.height)) {
          byH.add(x.height);
          qualities.push({ height: x.height, bandwidth: 0 });
        }
      }
      qualities.sort((a, b) => b.height - a.height);
      const texts = !this.ytActive && p ? p.getTextTracks().map((t) => ({ id: t.id, language: t.language, label: t.label || langName(t.language) })) : [];
      this._tracks.update((cur) => ({
        ...cur,
        qualities,
        auto: cur.auto && !this.current?.height ? true : cur.auto,
        activeHeight: this.current?.height ?? null,
        audios: [...dubSet.values()],
        audio: activeDub ? `dub:${activeDub}` : null,
        texts
      }));
      return;
    }
    if (this.ytActive || !p) return;
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
    if (this.ytActive || !p) return;
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

  /** Reload another gateway variant at the current position (keeps play/pause + dub). */
  private async switchVariant(height: number | null): Promise<void> {
    const key = this.loadedKey;
    if (!key) return;
    this.onQualityPick?.(height, this.media?.item.id);
    const activeDub = dubOfSource(this.current);
    const pool =
      activeDub && this.variants.some((x) => dubOfSource(x)?.toLowerCase() === activeDub.toLowerCase())
        ? this.variants.filter((x) => dubOfSource(x)?.toLowerCase() === activeDub.toLowerCase())
        : this.variants;
    const target =
      height === null
        ? pool.find((x) => (x.height ?? 0) <= 1080) ?? pool[0]
        : pool.find((x) => x.height === height) ?? pickPreferredSource(pool, { dub: activeDub, height });
    if (!target || target.url === this.current?.url) {
      this._tracks.update((t) => ({ ...t, auto: height === null }));
      return;
    }
    await this.reloadTarget(target, key, height === null);
  }

  private async switchDubVariant(dub: string): Promise<void> {
    const key = this.loadedKey;
    if (!key || !this.variants.length) return;
    this.onDubPick?.(dub, this.media?.item.id);
    const target = pickPreferredSource(this.variants, { dub, height: this.current?.height ?? null });
    if (!target || target.url === this.current?.url) return;
    await this.reloadTarget(target, key, undefined);
  }

  private async reloadTarget(target: Source, key: string, autoOverride: boolean | undefined): Promise<void> {
    const t = this.currentTime;
    const wasPlaying = !this.isPaused;
    try {
      await this.loadSrc(target, t, { buffer: true });
      if (this.loadedKey !== key) return;
      this.current = target;
      if (autoOverride !== undefined) this._tracks.update((x) => ({ ...x, auto: autoOverride }));
      this.refreshTracks();
      if (wasPlaying) await this.play().catch(() => {});
    } catch {
      this._error.set({ msg: `Couldn't switch to ${target.label ?? 'that stream'}.`, unsupported: false });
    }
  }

  selectAudio(language: string): void {
    if (language.startsWith('dub:')) {
      void this.switchDubVariant(language.slice(4));
      return;
    }
    if (this.ytActive) return;
    this.player?.selectAudioLanguage(language);
    this.refreshTracks();
  }

  /** Embedded (manifest) text track; null hides. */
  selectText(id: number | null): void {
    const p = this.player;
    if (this.ytActive || !p) return;
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

function safeNum(fn: () => number, fallback: number): number {
  try {
    const v = fn();
    return typeof v === 'number' && Number.isFinite(v) ? v : fallback;
  } catch {
    return fallback;
  }
}

function safeBool(fn: () => boolean, fallback: boolean): boolean {
  try {
    return !!fn();
  } catch {
    return fallback;
  }
}

function runSafe(fn: () => void): void {
  try {
    fn();
  } catch {
    /* ignore transient iframe state errors */
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
