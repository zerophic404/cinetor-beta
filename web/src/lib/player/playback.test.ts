import { get } from 'svelte/store';
import { describe, expect, it } from 'vitest';
import { Preloader } from './Preloader';
import { Session } from './session';
import { ResumeStore, memoryKV, resumable, shouldSave, type ResumeEntry } from './resume';
import { buildMedia, nextEpisode, pickSource, playbackOf, externalPlayerHref, type Media } from './sources';
import {
  clampMiniOffset,
  fromMiniPosFrac,
  isSwipeOff,
  miniHeight,
  parseMiniPosFrac,
  toMiniPosFrac
} from './mini-math';
import {
  buildYouTubePlayerVars,
  dubOfSource,
  extractYouTubeVideoId,
  isYouTubeSource,
  mapYouTubeStateToClock,
  pickAlternateSource,
  pickPreferredSource,
  YOUTUBE_CLOCK0
} from './youtube';
import type { EngineLike, LoadOpts } from './types';
import type { Item } from '../data/types';

/** Fake single-element engine: records loads, simulates async buffering. */
class FakeEngine implements EngineLike {
  static instances = 0;
  loadedKey: string | null = null;
  loads: string[] = [];
  aborted: string[] = [];
  playing: string | null = null;
  unloads = 0;
  private resolvers: (() => void)[] = [];
  constructor(public auto = true) {
    FakeEngine.instances++;
  }
  load(media: Media, _start: number, opts: LoadOpts): Promise<void> {
    this.loads.push(media.key);
    this.playing = null;
    this.loadedKey = null;
    opts.signal?.addEventListener('abort', () => this.aborted.push(media.key));
    return new Promise((resolve) => {
      const done = () => {
        // A newer load replaces the element's source.
        if (this.loads[this.loads.length - 1] === media.key) this.loadedKey = media.key;
        resolve();
      };
      if (this.auto) queueMicrotask(done);
      else this.resolvers.push(done);
    });
  }
  flush() {
    const r = this.resolvers.splice(0);
    r.forEach((f) => f());
  }
  async unload() {
    this.unloads++;
    this.loadedKey = null;
    this.playing = null;
  }
  async play() {
    this.playing = this.loadedKey;
  }
  pause() {
    this.playing = null;
  }
}

const item = (id: string, extra: Partial<Item> = {}): Item => ({
  id,
  space: 'HOME',
  categoryId: 'c',
  type: 'MOVIE',
  title: id,
  year: 2020,
  rating: 7,
  maturity: 'PG',
  genres: [],
  synopsis: '',
  poster: '',
  backdrop: '',
  dominantColor: '#000',
  accentColor: '#f00',
  sources: [{ kind: 'HLS', url: `https://x/${id}.m3u8` }],
  subtitles: [{ lang: 'en', label: 'English', url: 'subs/sample-en.vtt', format: 'VTT' }],
  ...extra
});
const canPlay = () => false;
const media = (id: string) => buildMedia(item(id), undefined, canPlay, (p) => `/mock/${p}`);
const tick = () => new Promise((r) => setTimeout(r, 0));

function rig(opts: { auto?: boolean; dataSaver?: boolean } = {}) {
  const engine = new FakeEngine(opts.auto ?? true);
  let session: Session | null = null;
  const preloader = new Preloader({
    engine,
    startFor: async () => 0,
    dataSaver: () => !!opts.dataSaver,
    sessionBusy: () => !!session?.busy
  });
  session = new Session({ engine, preloader, startFor: async () => 0 });
  return { engine, preloader, session };
}

describe('preloader', () => {
  it('rapidSwitch_keepsSinglePlayer_andLatestItem', async () => {
    FakeEngine.instances = 0;
    const { engine, preloader, session } = rig({ auto: false });
    const a = preloader.preload(media('A'));
    await tick();
    const b = preloader.preload(media('B'));
    await tick();
    engine.flush();
    await Promise.all([a, b]);
    expect(FakeEngine.instances).toBe(1);
    expect(engine.loadedKey).toBe('B');
    expect(engine.aborted).toContain('A');
    expect(get(preloader.readiness)).toEqual({ B: 'ready' });
    expect(await session.play(media('B'))).toBe(true); // instant: same element, no reload
    expect(engine.loads).toEqual(['A', 'B']);
    expect(engine.playing).toBe('B');
  });

  it('cancel_releasesBuffer and clears readiness', async () => {
    const { engine, preloader } = rig();
    await preloader.preload(media('A'));
    expect(get(preloader.readiness).A).toBe('ready');
    preloader.cancel('A');
    expect(engine.unloads).toBe(1);
    expect(get(preloader.readiness)).toEqual({});
  });

  it('data saver resolves without buffering', async () => {
    const { engine, preloader } = rig({ dataSaver: true });
    await preloader.preload(media('A'));
    expect(engine.loads).toEqual([]);
    expect(get(preloader.readiness).A).toBe('resolved');
  });

  it('wrong preload never plays: play(B) after preload(A) loads B', async () => {
    const { engine, preloader, session } = rig();
    await preloader.preload(media('A'));
    expect(await session.play(media('B'))).toBe(false);
    expect(engine.playing).toBe('B');
  });
});

describe('session', () => {
  it('miniPlayer_replacedByNewPlay_singleAudio', async () => {
    FakeEngine.instances = 0;
    const left: string[] = [];
    const engine = new FakeEngine();
    const preloader = new Preloader({ engine, startFor: async () => 0, dataSaver: () => false, sessionBusy: () => session.busy });
    const session: Session = new Session({ engine, preloader, startFor: async () => 0, onLeave: (m) => left.push(m.key) });
    await session.play(media('A'));
    session.minimize();
    expect(session.snapshot.mode).toBe('mini');
    // Details of B opened while mini plays A: preload must not hijack the element.
    await preloader.preload(media('B'));
    expect(engine.playing).toBe('A');
    expect(get(preloader.readiness).B).toBe('resolved');
    await session.play(media('B'));
    expect(FakeEngine.instances).toBe(1);
    expect(engine.playing).toBe('B');
    expect(session.snapshot).toMatchObject({ mode: 'full', media: { key: 'B' } });
    expect(left).toEqual(['A']);
  });

  it('rewarm after mini closes buffers the resolved details target', async () => {
    const engine = new FakeEngine();
    const preloader = new Preloader({ engine, startFor: async () => 0, dataSaver: () => false, sessionBusy: () => session.busy });
    const session: Session = new Session({ engine, preloader, startFor: async () => 0 });
    await session.play(media('A'));
    session.minimize();
    await preloader.preload(media('B'));
    expect(get(preloader.readiness).B).toBe('resolved');
    await session.stop();
    await preloader.rewarm();
    expect(engine.loadedKey).toBe('B');
    expect(get(preloader.readiness).B).toBe('ready');
  });

  it('expand same media keeps playback, stop unloads', async () => {
    const { engine, session } = rig();
    await session.play(media('A'));
    session.minimize();
    await session.play(media('A'));
    expect(engine.loads).toEqual(['A']);
    expect(session.snapshot.mode).toBe('full');
    await session.stop();
    expect(session.snapshot.mode).toBe('hidden');
    expect(engine.loadedKey).toBeNull();
  });

  it('unsupported media → error, nothing loaded', async () => {
    const { engine, session } = rig();
    const m = buildMedia(item('M', { sources: [{ kind: 'MKV', url: 'https://x/a.mkv' }] }), undefined, canPlay, (p) => p);
    await session.play(m);
    expect(session.snapshot.error).toBe('unsupported');
    expect(engine.loads).toEqual([]);
  });
});

describe('sources', () => {
  it('picks adaptive first, flags MKV unsupported', () => {
    expect(pickSource([{ kind: 'MP4', url: 'a' }, { kind: 'DASH', url: 'b' }], canPlay).source?.kind).toBe('DASH');
    expect(pickSource([{ kind: 'MKV', url: 'a' }], canPlay)).toMatchObject({ playable: false });
    expect(playbackOf({ kind: 'MKV', url: 'a' }, (m) => m === 'video/webm')).toBe('native');
    expect(media('A').subtitles[0].url).toBe('/mock/subs/sample-en.vtt');
    expect(externalPlayerHref('https://h/a.mkv', 'Android 13')).toBe('intent://h/a.mkv#Intent;scheme=https;type=video/*;end');
  });
  it('next episode crosses seasons', () => {
    const ep = (id: string, n: number) => ({ id, n, title: id, durationSec: 1, still: '', sources: [], subtitles: [] });
    const seasons = [
      { n: 2, episodes: [ep('s2e1', 1)] },
      { n: 1, episodes: [ep('s1e1', 1), ep('s1e2', 2)] }
    ];
    expect(nextEpisode(seasons, 's1e1')?.id).toBe('s1e2');
    expect(nextEpisode(seasons, 's1e2')?.id).toBe('s2e1');
    expect(nextEpisode(seasons, 's2e1')).toBeUndefined();
  });
});

describe('resume store', () => {
  it('resume_restoresPosition per profile, clears finished, remembers last episode', async () => {
    let now = 1000;
    const kv = memoryKV<ResumeEntry & { profile: string }>();
    const r = new ResumeStore(kv, () => now);
    await r.save('p1', { itemId: 'S', episodeId: 'e2', space: 'HOME', pos: 120, dur: 1400 });
    expect(await r.startFor('p1', 'S', 'e2')).toBe(120);
    expect(await r.startFor('p2', 'S', 'e2')).toBe(0);
    expect(await r.lastEpisode('p1', 'S')).toBe('e2');
    await r.save('p1', { itemId: 'S', episodeId: 'e2', space: 'HOME', pos: 1395, dur: 1400 });
    expect(await r.startFor('p1', 'S', 'e2')).toBe(0);
    expect(resumable(3, 100)).toBe(false);
    expect(shouldSave(6000, 1000)).toBe(true);
    expect(shouldSave(5999, 1000)).toBe(false);
  });

  it('continueWatching_onlyVideoSpaces, newest first, one per item', async () => {
    let now = 0;
    const r = new ResumeStore(memoryKV(), () => ++now);
    await r.save('p', { itemId: 'A', space: 'HOME', pos: 50, dur: 1000 });
    await r.save('p', { itemId: 'T', space: 'TV', pos: 50, dur: 1000 });
    await r.save('p', { itemId: 'X', space: 'ADULT', pos: 50, dur: 1000 });
    await r.save('p', { itemId: 'B', episodeId: 'b1', space: 'ANIME', pos: 60, dur: 1000 });
    const cw = await r.continueWatching('p');
    expect(cw.map((e) => e.itemId)).toEqual(['B', 'A']);
  });
});

describe('chromeless YouTube contract, state transitions, fallback & dub persistence', () => {
  it('extractYouTubeVideoId parses watch, short, embed, nocookie, shorts, live and yt:// URLs', () => {
    expect(extractYouTubeVideoId('https://www.youtube.com/watch?v=dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
    expect(extractYouTubeVideoId('https://youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=42s')).toBe('dQw4w9WgXcQ');
    expect(extractYouTubeVideoId('https://m.youtube.com/watch?v=dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
    expect(extractYouTubeVideoId('https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RD1')).toBe('dQw4w9WgXcQ');
    expect(extractYouTubeVideoId('https://youtu.be/dQw4w9WgXcQ?si=abc_123')).toBe('dQw4w9WgXcQ');
    expect(extractYouTubeVideoId('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ?autoplay=1')).toBe('dQw4w9WgXcQ');
    expect(extractYouTubeVideoId('https://youtube-nocookie.com/embed/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
    expect(extractYouTubeVideoId('https://www.youtube.com/embed/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
    expect(extractYouTubeVideoId('https://www.youtube.com/shorts/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
    expect(extractYouTubeVideoId('https://www.youtube.com/live/dQw4w9WgXcQ?feature=share')).toBe('dQw4w9WgXcQ');
    expect(extractYouTubeVideoId('yt://dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
    expect(extractYouTubeVideoId('yt://dQw4w9WgXcQ?t=15')).toBe('dQw4w9WgXcQ');
    expect(extractYouTubeVideoId('yt:vid_123-XYZ')).toBe('vid_123-XYZ');
  });

  it('extractYouTubeVideoId rejects non-YouTube hosts and malformed/unsafe inputs', () => {
    expect(extractYouTubeVideoId(null)).toBeNull();
    expect(extractYouTubeVideoId(undefined)).toBeNull();
    expect(extractYouTubeVideoId('')).toBeNull();
    expect(extractYouTubeVideoId('https://cdn.example.com/watch?v=dQw4w9WgXcQ')).toBeNull();
    expect(extractYouTubeVideoId('https://evil-youtube.com/watch?v=dQw4w9WgXcQ')).toBeNull();
    expect(extractYouTubeVideoId('https://www.youtube.com/watch?list=PL1234567890')).toBeNull();
    expect(extractYouTubeVideoId('https://www.youtube-nocookie.com/embed/ab"onload="alert(1)')).toBeNull();
    expect(extractYouTubeVideoId('gw-stream:123?detail_path=abc')).toBeNull();
  });

  it('marks YouTube sources playable with playback mode youtube', () => {
    const ytSrc = { kind: 'MP4' as const, url: 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ', label: 'YouTube · HD' };
    expect(isYouTubeSource(ytSrc)).toBe(true);
    expect(playbackOf(ytSrc, () => false)).toBe('youtube');
    const picked = pickSource([ytSrc], () => false);
    expect(picked.playable).toBe(true);
    expect(picked.source?.url).toBe(ytSrc.url);
    const m = buildMedia(item('YT1', { sources: [ytSrc] }), undefined, () => false, (p) => p);
    expect(m.playable).toBe(true);
  });

  it('builds chromeless playerVars and omits custom scheme origins like tauri:// on Linux desktop', () => {
    const webVars = buildYouTubePlayerVars({ autoplay: true, startSec: 45.8, origin: 'https://ashrix404.github.io' });
    expect(webVars).toMatchObject({
      autoplay: 1,
      controls: 0,
      modestbranding: 1,
      rel: 0,
      iv_load_policy: 3,
      playsinline: 1,
      disablekb: 1,
      fs: 0,
      enablejsapi: 1,
      start: 45,
      origin: 'https://ashrix404.github.io'
    });
    const linuxTauriVars = buildYouTubePlayerVars({ autoplay: false, startSec: 0, origin: 'tauri://localhost' });
    expect(linuxTauriVars.autoplay).toBe(0);
    expect('origin' in linuxTauriVars).toBe(false);
    expect('start' in linuxTauriVars).toBe(false);
  });

  it('maps YouTube IFrame API states to Cinetor clock transitions including ended=0', () => {
    let st = { ...YOUTUBE_CLOCK0 };
    st = mapYouTubeStateToClock(st, { ytState: 1, t: 12.5, dur: 180, rate: 1.25, volume0to100: 80, muted: false });
    expect(st).toMatchObject({ t: 12.5, dur: 180, paused: false, waiting: false, ended: false, rate: 1.25, volume: 0.8, muted: false });

    st = mapYouTubeStateToClock(st, { ytState: 3 });
    expect(st.waiting).toBe(true);
    expect(st.ended).toBe(false);

    st = mapYouTubeStateToClock(st, { ytState: 2, t: 40 });
    expect(st).toMatchObject({ t: 40, paused: true, waiting: false, ended: false });

    st = mapYouTubeStateToClock(st, { ytState: 0, t: 180 });
    expect(st).toMatchObject({ t: 180, paused: true, waiting: false, ended: true });
  });

  it('falls back to alternate non-failed gateway stream when YouTube errors', () => {
    const yt = { kind: 'MP4' as const, url: 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ', label: 'YouTube · HD' };
    const alt1 = { kind: 'MP4' as const, url: 'https://cdn.test/720.mp4', label: '720p · MP4', height: 720 };
    const alt2 = { kind: 'MP4' as const, url: 'https://cdn.test/480.mp4', label: '480p · MP4', height: 480 };
    const failed = new Set([yt.url]);
    expect(pickAlternateSource([yt, alt1, alt2], failed)?.url).toBe(alt1.url);
    failed.add(alt1.url);
    expect(pickAlternateSource([yt, alt1, alt2], failed)?.url).toBe(alt2.url);
    failed.add(alt2.url);
    expect(pickAlternateSource([yt, alt1, alt2], failed)).toBeUndefined();
  });

  it('persists dub and closest quality preference across episodes', () => {
    expect(dubOfSource({ kind: 'MP4', url: 'a', label: '1080p · Hindi', height: 1080 })).toBe('Hindi');
    expect(dubOfSource({ kind: 'MP4', url: 'b', label: '720p · MP4 · Bengali · 1.1 GB', height: 720 })).toBe('Bengali');
    expect(dubOfSource({ kind: 'MP4', url: 'c', label: 'YouTube · [Tamil]' })).toBe('Tamil');
    expect(dubOfSource({ kind: 'MP4', url: 'd', label: '1080p · MP4 · 2.0 GB', height: 1080 })).toBeNull();

    const ep2Sources = [
      { kind: 'MP4' as const, url: 'en-1080', label: '1080p · English', height: 1080 },
      { kind: 'MP4' as const, url: 'hi-1080', label: '1080p · Hindi', height: 1080 },
      { kind: 'MP4' as const, url: 'hi-480', label: '480p · Hindi', height: 480 }
    ];
    expect(pickPreferredSource(ep2Sources, { dub: 'Hindi', height: 720 })?.url).toBe('hi-480');
    expect(pickPreferredSource(ep2Sources, { dub: 'Hindi', height: 1080 })?.url).toBe('hi-1080');
  });
});

describe('free-position floating mini-player math', () => {
  const insets = { top: 16, right: 12, bottom: 84, left: 12 };

  it('keeps exact dropped (x, y) inside safe viewport margins without corner-snapping', () => {
    const vw = 1280;
    const vh = 720;
    const w = 420;
    const h = miniHeight(w);

    const mid = clampMiniOffset(360, 250, w, h, vw, vh, insets);
    expect(mid).toEqual({ x: 360, y: 250 });

    const topClamped = clampMiniOffset(410, -20, w, h, vw, vh, insets);
    expect(topClamped).toEqual({ x: 410, y: insets.top });

    const bottomClamped = clampMiniOffset(500, 999, w, h, vw, vh, insets);
    expect(bottomClamped).toEqual({ x: 500, y: vh - h - insets.bottom });

    expect(isSwipeOff({ x: -w * 0.6, y: 200, w, h }, vw)).toBe(true);
    expect(isSwipeOff({ x: 360, y: 250, w, h }, vw)).toBe(false);
  });

  it('persists normalized (xFrac, yFrac) round-trip and defaults cleanly when unset', () => {
    const vw = 1280;
    const vh = 720;
    const w = 420;
    const h = miniHeight(w);

    const def = fromMiniPosFrac(null, w, h, vw, vh, insets);
    expect(def).toEqual({ x: vw - w - insets.right, y: vh - h - insets.bottom });

    const frac = toMiniPosFrac(360, 250, w, h, vw, vh, insets);
    const json = JSON.stringify(frac);
    const parsed = parseMiniPosFrac(json);
    expect(parsed).not.toBeNull();
    const restored = fromMiniPosFrac(parsed, w, h, vw, vh, insets);
    expect(restored.x).toBeCloseTo(360, 0);
    expect(restored.y).toBeCloseTo(250, 0);
    expect(parseMiniPosFrac('not-json')).toBeNull();
  });
});
