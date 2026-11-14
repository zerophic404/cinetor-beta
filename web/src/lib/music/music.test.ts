import { describe, expect, it } from 'vitest';
import { MediaArbiter } from '../media/arbiter';
import { MusicCore, type AudioLike, type Track } from './core';
import { nextIndex, prevIndex, reorder, reorderQueue, shuffleOrder, type QueueState } from './queue';

const q = (o: Partial<QueueState>): QueueState => ({ length: 4, index: 0, shuffle: false, repeat: 'off', order: [], ...o });

describe('queue reorder', () => {
  it('moves an item and keeps the current track current', () => {
    expect(reorder(['a', 'b', 'c', 'd'], 0, 3)).toEqual(['b', 'c', 'd', 'a']);
    expect(reorder(['a', 'b', 'c', 'd'], 3, 1)).toEqual(['a', 'd', 'b', 'c']);
    // current = c (index 2)
    expect(reorderQueue(q({ index: 2 }), 0, 3).index).toBe(1);
    expect(reorderQueue(q({ index: 2 }), 3, 0).index).toBe(3);
    expect(reorderQueue(q({ index: 2 }), 2, 0).index).toBe(0);
    expect(reorderQueue(q({ index: 2 }), 0, 1).index).toBe(2);
  });
  it('keeps the shuffle order pointing at the same tracks', () => {
    const before = q({ index: 1, shuffle: true, order: [1, 3, 0, 2] });
    const after = reorderQueue(before, 3, 0); // d moves to front: a b c d -> d a b c
    expect(after.index).toBe(2);
    expect(after.order).toEqual([2, 0, 1, 3]);
  });
});

describe('shuffle / repeat next index', () => {
  it('sequential with repeat off stops at the end, repeat all wraps', () => {
    expect(nextIndex(q({ index: 1 }))).toBe(2);
    expect(nextIndex(q({ index: 3 }))).toBeNull();
    expect(nextIndex(q({ index: 3, repeat: 'all' }))).toBe(0);
    expect(prevIndex(q({ index: 0 }))).toBeNull();
    expect(prevIndex(q({ index: 0, repeat: 'all' }))).toBe(3);
  });
  it('repeat one replays on auto-advance but a user next still advances', () => {
    expect(nextIndex(q({ index: 2, repeat: 'one' }), true)).toBe(2);
    expect(nextIndex(q({ index: 2, repeat: 'one' }))).toBe(3);
  });
  it('shuffle follows the shuffled order, current first', () => {
    const order = shuffleOrder(4, 2, () => 0.5);
    expect(order[0]).toBe(2);
    expect([...order].sort()).toEqual([0, 1, 2, 3]);
    const s = q({ index: 2, shuffle: true, order });
    expect(nextIndex(s)).toBe(order[1]);
    expect(nextIndex(q({ index: order[3], shuffle: true, order }))).toBeNull();
    expect(nextIndex(q({ index: order[3], shuffle: true, order, repeat: 'all' }))).toBe(order[0]);
    expect(prevIndex(q({ index: order[1], shuffle: true, order }))).toBe(order[0]);
  });
});

function fakeAudio(): AudioLike & { plays: number } {
  return {
    src: '',
    currentTime: 0,
    duration: 100,
    paused: true,
    plays: 0,
    play() {
      (this as { paused: boolean }).paused = false;
      this.plays++;
      return Promise.resolve();
    },
    pause() {
      (this as { paused: boolean }).paused = true;
    }
  };
}

const track = (id: string): Track => ({ id, title: id, artist: 'A', album: 'B', art: '', color: '#000', accent: '#f00', url: `https://x/${id}.mp3`, durationSec: 100 });

describe('music + video exclusive', () => {
  it('starting music stops the video player', async () => {
    const arb = new MediaArbiter();
    let videoStops = 0;
    arb.register('video', () => videoStops++);
    const audio = fakeAudio();
    const core = new MusicCore(audio, arb);
    await core.playQueue([track('t1'), track('t2')], 0);
    expect(videoStops).toBe(1);
    expect(audio.paused).toBe(false);
    expect(audio.src).toContain('t1');
    expect(arb.active).toBe('music');
  });
  it('starting video pauses music (single audio)', async () => {
    const arb = new MediaArbiter();
    const audio = fakeAudio();
    const core = new MusicCore(audio, arb);
    await core.playQueue([track('t1')], 0);
    arb.claim('video');
    expect(audio.paused).toBe(true);
    expect(core.playing).toBe(false);
    expect(arb.active).toBe('video');
  });
  it('auto-advances and stops at the end with repeat off', async () => {
    const audio = fakeAudio();
    const core = new MusicCore(audio, new MediaArbiter());
    await core.playQueue([track('t1'), track('t2')], 0);
    await core.ended();
    expect(core.current?.id).toBe('t2');
    await core.ended();
    expect(audio.paused).toBe(true);
    core.move(1, 0);
    expect(core.current?.id).toBe('t2');
    expect(core.tracks.map((t) => t.id)).toEqual(['t2', 't1']);
  });
});
