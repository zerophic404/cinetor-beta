import { get } from 'svelte/store';
import { describe, expect, it } from 'vitest';
import { Preloader } from './Preloader';
import { Session } from './session';
import { ResumeStore, memoryKV, resumable, shouldSave, type ResumeEntry } from './resume';
import { buildMedia, nextEpisode, pickSource, playbackOf, externalPlayerHref, type Media } from './sources';
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
