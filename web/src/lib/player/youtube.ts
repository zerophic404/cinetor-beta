// Pure chromeless YouTube contract, URL detection, state mapping, fallback & dub/quality matching.
// Relative imports only so Vitest runs this module directly without SvelteKit aliases.
import type { Source } from '../data/types';

const YT_SCHEME_RE = /^yt:(?:\/\/)?([A-Za-z0-9_-]{6,20})(?=[/?&#]|$)/i;
const YT_SHORT_HOST_RE = /^https?:\/\/(?:www\.)?youtu\.be\/([A-Za-z0-9_-]{6,20})(?=[/?&#]|$)/i;
const YT_PATH_ID_RE = /^https?:\/\/(?:(?:www|m|music)\.)?youtube(?:-nocookie)?\.com\/(?:embed|shorts|live|v)\/([A-Za-z0-9_-]{6,20})(?=[/?&#]|$)/i;
const YT_WATCH_HOST_RE = /^https?:\/\/(?:(?:www|m|music)\.)?youtube(?:-nocookie)?\.com\/watch\?(.+)$/i;
const YT_VALID_ID_RE = /^[A-Za-z0-9_-]{6,20}$/;

/**
 * Pure YouTube video ID extractor across `youtube.com/watch?v=...`, `youtu.be/...`,
 * `youtube-nocookie.com/embed/...`, `youtube.com/shorts|live|v/...`, and `yt://<videoId>`.
 */
export function extractYouTubeVideoId(url: string | null | undefined): string | null {
  const raw = url?.trim();
  if (!raw) return null;
  const schemeMatch = YT_SCHEME_RE.exec(raw);
  if (schemeMatch?.[1]) return schemeMatch[1];
  const shortMatch = YT_SHORT_HOST_RE.exec(raw);
  if (shortMatch?.[1]) return shortMatch[1];
  const pathMatch = YT_PATH_ID_RE.exec(raw);
  if (pathMatch?.[1]) return pathMatch[1];
  const watchMatch = YT_WATCH_HOST_RE.exec(raw);
  if (!watchMatch?.[1]) return null;
  const query = watchMatch[1].split('#', 1)[0];
  for (const part of query.split('&')) {
    const eq = part.indexOf('=');
    if (eq <= 0) continue;
    if (part.slice(0, eq) === 'v') {
      const candidate = part.slice(eq + 1);
      return YT_VALID_ID_RE.test(candidate) ? candidate : null;
    }
  }
  return null;
}

export function isYouTubeSource(src: Partial<Source> | null | undefined): boolean {
  return extractYouTubeVideoId(src?.url) !== null;
}

export interface YouTubeClock {
  t: number;
  dur: number;
  paused: boolean;
  waiting: boolean;
  ended: boolean;
  rate: number;
  volume: number;
  muted: boolean;
}

export const YOUTUBE_CLOCK0: YouTubeClock = {
  t: 0,
  dur: 0,
  paused: true,
  waiting: false,
  ended: false,
  rate: 1,
  volume: 1,
  muted: false
};

export interface YouTubeClockPatch {
  ytState?: number;
  t?: number;
  dur?: number;
  rate?: number;
  volume0to100?: number;
  muted?: boolean;
}

/**
 * Pure mapper from YouTube IFrame API state (`-1` unstarted, `0` ended, `1` playing,
 * `2` paused, `3` buffering, `5` cued) + telemetry into Cinetor's unified player clock.
 */
export function mapYouTubeStateToClock(prev: YouTubeClock, patch: YouTubeClockPatch): YouTubeClock {
  const t = typeof patch.t === 'number' && Number.isFinite(patch.t) && patch.t >= 0 ? patch.t : prev.t;
  const dur = typeof patch.dur === 'number' && Number.isFinite(patch.dur) && patch.dur > 0 ? patch.dur : prev.dur;
  const rate = typeof patch.rate === 'number' && Number.isFinite(patch.rate) && patch.rate > 0 ? patch.rate : prev.rate;
  const volume =
    typeof patch.volume0to100 === 'number' && Number.isFinite(patch.volume0to100)
      ? Math.min(1, Math.max(0, patch.volume0to100 / 100))
      : prev.volume;
  const muted = typeof patch.muted === 'boolean' ? patch.muted : prev.muted;

  let { paused, waiting, ended } = prev;
  switch (patch.ytState) {
    case 1: // PLAYING
      paused = false;
      waiting = false;
      ended = false;
      break;
    case 2: // PAUSED
      paused = true;
      waiting = false;
      ended = false;
      break;
    case 3: // BUFFERING
      waiting = true;
      ended = false;
      break;
    case 0: // ENDED
      paused = true;
      waiting = false;
      ended = true;
      break;
    case 5: // CUED
    case -1: // UNSTARTED
      waiting = false;
      ended = false;
      break;
  }

  return { t, dur, paused, waiting, ended, rate, volume, muted };
}

export function buildYouTubePlayerVars(opts: {
  autoplay: boolean;
  startSec?: number;
  origin?: string;
}): Record<string, string | number> {
  const vars: Record<string, string | number> = {
    autoplay: opts.autoplay ? 1 : 0,
    controls: 0,
    modestbranding: 1,
    rel: 0,
    iv_load_policy: 3,
    cc_load_policy: 0,
    disablekb: 1,
    fs: 0,
    playsinline: 1,
    enablejsapi: 1
  };
  const start = Math.floor(opts.startSec ?? 0);
  if (start > 0) vars.start = start;
  // Only pass HTTP(S) origins; Linux Tauri uses `tauri://localhost` which YouTube rejects if passed as `origin`.
  if (opts.origin && /^https?:\/\//i.test(opts.origin)) {
    vars.origin = opts.origin;
  }
  return vars;
}

/** Picks the first alternate source whose URL has not failed yet. */
export function pickAlternateSource(sources: Source[], failedUrls: ReadonlySet<string>): Source | undefined {
  return sources.find((s) => !!s.url && !failedUrls.has(s.url));
}

// ------------------------------------------------ Dub & Quality Preference Persistence

export interface PlaybackPref {
  dub?: string | null;
  height?: number | null;
}

const SIZE_TOKEN_RE = /^[\d.]+\s*[kmgt]?b$/i;
const QUALITY_TOKEN_RE = /^(?:\d{3,4}p(?:\s*(?:hd|fhd|qhd|uhd))?|4k(?:\s*uhd)?|2k(?:\s*qhd)?|auto|hd|sd|fhd|qhd|uhd)$/i;
const FORMAT_TOKEN_RE = /^(?:mp4|mkv|m4v|avi|webm|mov|ts|hls|dash|h264|h265|hevc|av1|aac|flac|mp3)$/i;
const PROVIDER_TOKEN_RE = /^(?:youtube|direct|proxy|gateway(?:\s*proxy)?|dual\s*audio|multi\s*audio|server\s*\d*|source\s*\d*|bdix|moviebox|zee\s*bangla)$/i;
const BRACKET_DUB_RE = /\[([A-Za-z][A-Za-z\s-]{1,20})]/;

function isNonDubToken(token: string): boolean {
  return SIZE_TOKEN_RE.test(token) || QUALITY_TOKEN_RE.test(token) || FORMAT_TOKEN_RE.test(token) || PROVIDER_TOKEN_RE.test(token);
}

/** Extracts a dub/audio language label (e.g. `'Hindi'`, `'Bengali'`, `'Tamil'`) from `source.label`, or `null`. */
export function dubOfSource(source: Partial<Source> | null | undefined): string | null {
  const label = source?.label?.trim();
  if (!label) return null;
  const bracket = BRACKET_DUB_RE.exec(label)?.[1]?.trim();
  if (bracket && !isNonDubToken(bracket)) return bracket;
  for (const raw of label.split(/[·|/]/)) {
    const cleaned = raw.replace(/^[\s[\]()]+|[\s[\]()]+$/g, '');
    if (cleaned && !isNonDubToken(cleaned)) return cleaned;
  }
  return null;
}

export function seriesBaseId(id: string): string {
  const base = id.split('#', 1)[0];
  const stripped = base.replace(/[-:/]s?\d+[-:/]e?\d+$/i, '').replace(/[-:/]e(?:pisode)?[-:]?\d+$/i, '');
  return stripped || base;
}

/**
 * Picks the best source matching saved dub & height preferences across episodes:
 * 1. Exact dub + exact height
 * 2. Exact dub + closest height
 * 3. Any source with target dub
 * 4. Closest height when height preference exists
 */
export function pickPreferredSource(
  sources: Source[],
  titlePref?: PlaybackPref | null,
  globalPref?: PlaybackPref | null
): Source | undefined {
  if (!sources.length) return undefined;
  const targetDub = titlePref?.dub?.trim() || globalPref?.dub?.trim() || null;
  const targetHeight = titlePref?.height ?? globalPref?.height ?? null;

  if (targetDub) {
    const dubMatches = sources.filter((s) => dubOfSource(s)?.toLowerCase() === targetDub.toLowerCase());
    if (dubMatches.length) {
      if (targetHeight && targetHeight > 0) {
        const exact = dubMatches.find((s) => s.height === targetHeight);
        if (exact) return exact;
        const withHeight = dubMatches.filter((s) => (s.height ?? 0) > 0);
        if (withHeight.length) {
          return [...withHeight].sort((a, b) => Math.abs((a.height ?? 0) - targetHeight) - Math.abs((b.height ?? 0) - targetHeight))[0];
        }
      }
      return dubMatches[0];
    }
  }

  if (targetHeight && targetHeight > 0) {
    const withHeight = sources.filter((s) => (s.height ?? 0) > 0);
    if (withHeight.length) {
      return [...withHeight].sort((a, b) => Math.abs((a.height ?? 0) - targetHeight) - Math.abs((b.height ?? 0) - targetHeight))[0];
    }
  }

  return sources[0];
}

// ------------------------------------------------ Lazy YouTube IFrame API Loader (Browser)

export interface YTPlayerInstance {
  playVideo(): void;
  pauseVideo(): void;
  stopVideo(): void;
  seekTo(seconds: number, allowSeekAhead?: boolean): void;
  getCurrentTime(): number;
  getDuration(): number;
  getPlayerState(): number;
  getPlaybackRate(): number;
  setPlaybackRate(suggestedRate: number): void;
  getVolume(): number;
  setVolume(volume: number): void;
  mute(): void;
  unMute(): void;
  isMuted(): boolean;
  loadVideoById(opts: { videoId: string; startSeconds?: number }): void;
  cueVideoById(opts: { videoId: string; startSeconds?: number }): void;
  destroy(): void;
}

export interface YTPlayerOptions {
  host?: string;
  videoId: string;
  width?: string | number;
  height?: string | number;
  playerVars?: Record<string, string | number>;
  events?: {
    onReady?: (ev: { target: YTPlayerInstance }) => void;
    onStateChange?: (ev: { data: number; target: YTPlayerInstance }) => void;
    onPlaybackRateChange?: (ev: { data: number; target: YTPlayerInstance }) => void;
    onError?: (ev: { data: number; target: YTPlayerInstance }) => void;
  };
}

export interface YTNamespace {
  Player: new (element: HTMLElement | string, options: YTPlayerOptions) => YTPlayerInstance;
}

interface YTWindow extends Window {
  YT?: YTNamespace;
  onYouTubeIframeAPIReady?: () => void;
}

const YT_NOCOOKIE_API = 'https://www.youtube-nocookie.com/iframe_api';
const YT_FALLBACK_API = 'https://www.youtube.com/iframe_api';

let ytApiPromise: Promise<YTNamespace | null> | null = null;

/** Lazily loads the YouTube IFrame API once across the entire session. */
export function loadYouTubeIframeApi(): Promise<YTNamespace | null> {
  if (typeof window === 'undefined' || typeof document === 'undefined') return Promise.resolve(null);
  const w = window as YTWindow;
  if (w.YT?.Player) return Promise.resolve(w.YT);
  if (ytApiPromise) return ytApiPromise;

  ytApiPromise = new Promise<YTNamespace | null>((resolve) => {
    let settled = false;
    const finish = (ns: YTNamespace | null) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      if (!ns) ytApiPromise = null;
      resolve(ns);
    };

    const timeout = setTimeout(() => finish(w.YT?.Player ? w.YT : null), 12_000);
    const prevReady = w.onYouTubeIframeAPIReady;
    w.onYouTubeIframeAPIReady = () => {
      prevReady?.();
      finish(w.YT?.Player ? w.YT : null);
    };

    const inject = (src: string, fallback: string | null) => {
      const script = document.createElement('script');
      script.src = src;
      script.async = true;
      script.onerror = () => {
        if (fallback) inject(fallback, null);
        else finish(null);
      };
      document.head.appendChild(script);
    };

    inject(YT_NOCOOKIE_API, YT_FALLBACK_API);
  });

  return ytApiPromise;
}
