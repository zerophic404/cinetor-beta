// Browser wiring of the player singletons: engine + preloader + session + resume + Media Session + sleep timer.
import { get, writable } from 'svelte/store';
import { activeProfileId } from '$lib/data/library.svelte';
import { repo } from '$lib/data/repo';
import type { Episode, Item } from '$lib/data/types';
import { engine } from './engine';
import { arbiter } from '$lib/media/arbiter';
import { Preloader } from './Preloader';
import { ResumeStore, idbKV, memoryKV, shouldSave, type ResumeEntry } from './resume';
import { Session } from './session';
import { buildMedia, type Media } from './sources';
import { pickPreferredSource, seriesBaseId, type PlaybackPref } from './youtube';
import { streams } from '$lib/data/app-catalog';
import { applyResolved, isLazySource } from '$lib/data/gateway-play';
import { downloads } from '$lib/download/manager.svelte';

export interface Clock {
  t: number;
  dur: number;
  buffered: [number, number][];
  paused: boolean;
  waiting: boolean;
  ended: boolean;
  rate: number;
  volume: number;
  muted: boolean;
}

const CLOCK0: Clock = { t: 0, dur: 0, buffered: [], paused: true, waiting: false, ended: false, rate: 1, volume: 1, muted: false };
export const clock = writable<Clock>(CLOCK0);

export const resume = new ResumeStore(
  typeof indexedDB !== 'undefined' ? idbKV<ResumeEntry & { profile: string }>('cinetor-player', 'resume') : memoryKV<ResumeEntry & { profile: string }>()
);

const startFor = (m: Media) => resume.startFor(activeProfileId(), m.item.id, m.episode?.id);

export const DATA_SAVER_KEY = 'cinetor.dataSaver';
export function dataSaver(): boolean {
  try {
    if (localStorage.getItem(DATA_SAVER_KEY) === 'on') return true;
  } catch {
    /* ignore */
  }
  const c = (navigator as Navigator & { connection?: { saveData?: boolean } }).connection;
  return !!c?.saveData;
}

function saveResume(m: Media): void {
  const dur = engine.duration;
  if (engine.loadedKey !== m.key || !Number.isFinite(dur) || dur <= 0) return;
  void resume.save(activeProfileId(), {
    itemId: m.item.id,
    episodeId: m.episode?.id,
    space: m.item.space,
    pos: engine.currentTime,
    dur,
    title: m.title
  });
}

export const preloader = new Preloader({ engine, startFor, dataSaver: () => dataSaver(), sessionBusy: () => session.busy });
export const session: Session = new Session({ engine, preloader, startFor, onLeave: saveResume });

export const QUALITY_KEY = 'cinetor.gwQuality';
export const DUB_KEY = 'cinetor.gwDub';

function titlePrefKey(itemId: string): string {
  return `cinetor.pref.${activeProfileId()}.${seriesBaseId(itemId)}`;
}

function readTitlePref(itemId?: string): PlaybackPref | null {
  if (!itemId) return null;
  try {
    const raw = localStorage.getItem(titlePrefKey(itemId));
    return raw ? (JSON.parse(raw) as PlaybackPref) : null;
  } catch {
    return null;
  }
}

function writeTitlePref(itemId: string | undefined, patch: PlaybackPref): void {
  if (!itemId) return;
  try {
    const cur = readTitlePref(itemId) ?? {};
    localStorage.setItem(titlePrefKey(itemId), JSON.stringify({ ...cur, ...patch }));
  } catch {
    /* ignore */
  }
}

function readGlobalPref(): PlaybackPref {
  try {
    const h = Number(localStorage.getItem(QUALITY_KEY)) || null;
    const dub = localStorage.getItem(DUB_KEY) || null;
    return { dub, height: h };
  } catch {
    return {};
  }
}

/** Preferred gateway variant height (per-series pick first, else last global pick; default 1080). */
export function preferredHeight(itemId?: string): number {
  const t = readTitlePref(itemId);
  if (t?.height && t.height > 0) return t.height;
  try {
    return Number(localStorage.getItem(QUALITY_KEY)) || 1080;
  } catch {
    return 1080;
  }
}

export function preferredDub(itemId?: string): string | null {
  const t = readTitlePref(itemId);
  if (t?.dub) return t.dub;
  try {
    return localStorage.getItem(DUB_KEY) || null;
  } catch {
    return null;
  }
}

export const mediaFor = (item: Item, ep?: Episode): Media =>
  buildMedia(
    item,
    ep,
    (mime) => engine.canPlay(mime),
    (p) => repo.asset(p),
    readTitlePref(item.id),
    readGlobalPref()
  );

/** Gateway placeholder → proxy MP4 / YouTube variants + captions (noSource when nothing playable). */
export async function resolveMedia(m: Media, signal?: AbortSignal): Promise<Media> {
  if (!isLazySource(m.source)) return m;
  const prefH = preferredHeight(m.item.id);
  const r = await streams.resolve(m.source!, signal).catch(() => ({ sources: [], subtitles: [] }));
  const resolved = applyResolved(m, r, prefH);
  const prefDub = preferredDub(m.item.id);
  if (prefDub && resolved.variants && resolved.variants.length > 1) {
    const chosen = pickPreferredSource(resolved.variants, { dub: prefDub, height: prefH });
    if (chosen) return { ...resolved, source: chosen };
  }
  return resolved;
}

/** Play/preload target: offline copy when downloaded, else the resolved gateway stream. */
export async function prepareMedia(item: Item, ep?: Episode, signal?: AbortSignal): Promise<Media> {
  const m = await downloads.offlineMedia(mediaFor(item, ep));
  return resolveMedia(m, signal);
}

/** Expired / failing link: drop the cached resolution and resolve again. */
export async function reResolve(m: Media): Promise<Media | null> {
  const lazy = mediaFor(m.item, m.episode);
  if (!isLazySource(lazy.source)) return null;
  streams.invalidate(lazy.source!.url);
  const fresh = await resolveMedia(lazy);
  return fresh.noSource ? null : fresh;
}

engine.recover = reResolve;
engine.onQualityPick = (h, itemId) => {
  writeTitlePref(itemId, { height: h });
  try {
    if (h) localStorage.setItem(QUALITY_KEY, String(h));
    else localStorage.removeItem(QUALITY_KEY);
  } catch {
    /* ignore */
  }
};
engine.onDubPick = (dub, itemId) => {
  writeTitlePref(itemId, { dub });
  try {
    if (dub) localStorage.setItem(DUB_KEY, dub);
  } catch {
    /* ignore */
  }
};

/** Next-episode action for the current media (set by the watch page; used by N key, Media Session, autoplay). */
export const nextUp = writable<{ title: string; go: () => void } | null>(null);

// Sleep timer: minutes or end of episode.
export const sleep = writable<{ mode: number | 'end' | null; until: number | null }>({ mode: null, until: null });
let sleepTimer: ReturnType<typeof setTimeout> | undefined;
export function setSleep(mode: number | 'end' | null): void {
  clearTimeout(sleepTimer);
  if (typeof mode === 'number') {
    const ms = mode * 60_000;
    sleep.set({ mode, until: Date.now() + ms });
    sleepTimer = setTimeout(() => {
      engine.pause();
      sleep.set({ mode: null, until: null });
    }, ms);
  } else sleep.set({ mode, until: null });
}

let wired = false;
let lastSave = 0;
let lastPos = 0;

/** Wire once (called by the /app layout). */
export function wirePlayer(): void {
  if (wired || typeof document === 'undefined') return;
  wired = true;
  // Single audible source: music starting stops the video session; video playing pauses music.
  arbiter.register('video', () => {
    if (session.snapshot.media) void session.stop();
    else engine.pause();
  });
  engine.onVideo((v) => {
    v.addEventListener('play', () => {
      if (!engine.youtubeActive) arbiter.claim('video');
    });
    const sync = () => {
      if (engine.youtubeActive) return;
      const b: [number, number][] = [];
      for (let i = 0; i < v.buffered.length; i++) b.push([v.buffered.start(i), v.buffered.end(i)]);
      clock.set({
        t: v.currentTime,
        dur: Number.isFinite(v.duration) ? v.duration : 0,
        buffered: b,
        paused: v.paused,
        waiting: get(clock).waiting && !v.paused && v.readyState < 3,
        ended: v.ended,
        rate: v.playbackRate,
        volume: v.volume,
        muted: v.muted
      });
    };
    for (const ev of ['timeupdate', 'progress', 'durationchange', 'play', 'pause', 'ratechange', 'volumechange', 'ended', 'loadedmetadata', 'seeked', 'emptied']) v.addEventListener(ev, sync);
    v.addEventListener('waiting', () => {
      if (!engine.youtubeActive) clock.update((c) => ({ ...c, waiting: true }));
    });
    v.addEventListener('playing', () => {
      if (!engine.youtubeActive) clock.update((c) => ({ ...c, waiting: false, paused: false, ended: false }));
    });
    v.addEventListener('timeupdate', () => {
      if (engine.youtubeActive) return;
      const m = session.snapshot.media;
      const now = Date.now();
      if (m && !v.paused && shouldSave(now, lastSave)) {
        lastSave = now;
        saveResume(m);
      }
      if (now - lastPos > 1000) {
        lastPos = now;
        positionState(v.currentTime, v.duration, v.playbackRate);
      }
    });
    v.addEventListener('pause', () => {
      if (engine.youtubeActive) return;
      const m = session.snapshot.media;
      if (m) saveResume(m);
    });
    v.addEventListener('ended', () => {
      if (engine.youtubeActive) return;
      if (get(sleep).mode === 'end') setSleep(null);
    });
  });
  engine.onYouTubeClock((yc, ev) => {
    clock.set({
      t: yc.t,
      dur: yc.dur,
      buffered: yc.dur > 0 && yc.t > 0 ? [[0, yc.t]] : [],
      paused: yc.paused,
      waiting: yc.waiting,
      ended: yc.ended,
      rate: yc.rate,
      volume: yc.volume,
      muted: yc.muted
    });
    const m = session.snapshot.media;
    const now = Date.now();
    if (ev === 'play') arbiter.claim('video');
    if (m && !yc.paused && shouldSave(now, lastSave)) {
      lastSave = now;
      saveResume(m);
    }
    if (now - lastPos > 1000 && yc.dur > 0) {
      lastPos = now;
      positionState(yc.t, yc.dur, yc.rate);
    }
    if (ev === 'pause' && m) saveResume(m);
    if (ev === 'ended') {
      if (m) saveResume(m);
      if (get(sleep).mode === 'end') setSleep(null);
    }
  });
  const flush = () => {
    const m = session.snapshot.media;
    if (m) saveResume(m);
  };
  document.addEventListener('visibilitychange', () => document.visibilityState === 'hidden' && flush());
  window.addEventListener('pagehide', flush);
  wireMediaSession();
  // Mini closed while a details page is open → warm its Play target now.
  let prevMode = session.snapshot.mode;
  session.state.subscribe((s) => {
    if (s.mode === 'hidden' && prevMode !== 'hidden') void preloader.rewarm();
    prevMode = s.mode;
  });
}

function positionState(pos: number, dur: number, rate: number): void {
  const ms = navigator.mediaSession;
  if (!ms?.setPositionState || !Number.isFinite(dur) || dur <= 0) return;
  try {
    ms.setPositionState({ duration: dur, position: Math.min(pos, dur), playbackRate: rate || 1 });
  } catch {
    /* ignore */
  }
}

function wireMediaSession(): void {
  if (!('mediaSession' in navigator)) return;
  const ms = navigator.mediaSession;
  const set = (a: MediaSessionAction, f: MediaSessionActionHandler | null) => {
    try {
      ms.setActionHandler(a, f);
    } catch {
      /* unsupported action */
    }
  };
  set('play', () => void engine.play().catch(() => {}));
  set('pause', () => engine.pause());
  set('stop', () => void session.stop());
  set('seekbackward', (d) => {
    engine.seekTo(Math.max(0, engine.currentTime - (d.seekOffset ?? 10)));
  });
  set('seekforward', (d) => {
    const dur = engine.duration || Infinity;
    engine.seekTo(Math.min(dur, engine.currentTime + (d.seekOffset ?? 10)));
  });
  set('seekto', (d) => {
    if (d.seekTime !== undefined) engine.seekTo(d.seekTime);
  });
  nextUp.subscribe((n) => set('nexttrack', n ? () => n.go() : null));
  let lastKey: string | null = null;
  session.state.subscribe((s) => {
    const m = s.media;
    if ((m?.key ?? null) === lastKey) return;
    lastKey = m?.key ?? null;
    if (!m) {
      ms.metadata = null;
      ms.playbackState = 'none';
      return;
    }
    const art = m.item.poster ? (/^https?:/.test(m.item.poster) ? m.item.poster : repo.asset(m.item.poster)) : '';
    ms.metadata = new MediaMetadata({
      title: m.episode ? m.episode.title : m.title,
      artist: m.episode ? `${m.title}${m.subtitle ? ` · ${m.subtitle.split(' · ')[0]}` : ''}` : 'Cinetor',
      album: 'Cinetor',
      artwork: art ? [{ src: art, sizes: '500x750', type: 'image/jpeg' }] : []
    });
  });
  clock.subscribe((c) => {
    if (session.snapshot.media) ms.playbackState = c.paused ? 'paused' : 'playing';
  });
}

// ---- small helpers used by UI ----
export async function togglePiP(): Promise<void> {
  if (engine.youtubeActive) return;
  const v = engine.video as (HTMLVideoElement & { webkitSetPresentationMode?: (m: string) => void; webkitPresentationMode?: string }) | null;
  if (!v) return;
  try {
    if (document.pictureInPictureElement) await document.exitPictureInPicture();
    else if (document.pictureInPictureEnabled && v.requestPictureInPicture) await v.requestPictureInPicture();
    else if (v.webkitSetPresentationMode) v.webkitSetPresentationMode(v.webkitPresentationMode === 'picture-in-picture' ? 'inline' : 'picture-in-picture');
  } catch {
    /* user gesture / unsupported */
  }
}

export const pipSupported = (): boolean =>
  typeof document !== 'undefined' &&
  !engine.youtubeActive &&
  (!!document.pictureInPictureEnabled || 'webkitSetPresentationMode' in HTMLVideoElement.prototype);
