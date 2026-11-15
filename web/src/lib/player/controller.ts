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
  const v = engine.video;
  if (!v || engine.loadedKey !== m.key || !Number.isFinite(v.duration)) return;
  void resume.save(activeProfileId(), {
    itemId: m.item.id,
    episodeId: m.episode?.id,
    space: m.item.space,
    pos: v.currentTime,
    dur: v.duration,
    title: m.title
  });
}

export const preloader = new Preloader({ engine, startFor, dataSaver: () => dataSaver(), sessionBusy: () => session.busy });
export const session: Session = new Session({ engine, preloader, startFor, onLeave: saveResume });

export const mediaFor = (item: Item, ep?: Episode): Media =>
  buildMedia(
    item,
    ep,
    (mime) => engine.canPlay(mime),
    (p) => repo.asset(p)
  );

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
    v.addEventListener('play', () => arbiter.claim('video'));
    const sync = () => {
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
    v.addEventListener('waiting', () => clock.update((c) => ({ ...c, waiting: true })));
    v.addEventListener('playing', () => clock.update((c) => ({ ...c, waiting: false, paused: false, ended: false })));
    v.addEventListener('timeupdate', () => {
      const m = session.snapshot.media;
      const now = Date.now();
      if (m && !v.paused && shouldSave(now, lastSave)) {
        lastSave = now;
        saveResume(m);
      }
      if (now - lastPos > 1000) {
        lastPos = now;
        positionState(v);
      }
    });
    v.addEventListener('pause', () => {
      const m = session.snapshot.media;
      if (m) saveResume(m);
    });
    v.addEventListener('ended', () => {
      if (get(sleep).mode === 'end') setSleep(null);
    });
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

function positionState(v: HTMLVideoElement): void {
  const ms = navigator.mediaSession;
  if (!ms?.setPositionState || !Number.isFinite(v.duration) || v.duration <= 0) return;
  try {
    ms.setPositionState({ duration: v.duration, position: Math.min(v.currentTime, v.duration), playbackRate: v.playbackRate || 1 });
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
  const v = () => engine.video;
  set('play', () => void v()?.play().catch(() => {}));
  set('pause', () => v()?.pause());
  set('stop', () => void session.stop());
  set('seekbackward', (d) => {
    const el = v();
    if (el) el.currentTime = Math.max(0, el.currentTime - (d.seekOffset ?? 10));
  });
  set('seekforward', (d) => {
    const el = v();
    if (el) el.currentTime = Math.min(el.duration || Infinity, el.currentTime + (d.seekOffset ?? 10));
  });
  set('seekto', (d) => {
    const el = v();
    if (el && d.seekTime !== undefined) el.currentTime = d.seekTime;
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
  typeof document !== 'undefined' && (!!document.pictureInPictureEnabled || 'webkitSetPresentationMode' in HTMLVideoElement.prototype);
