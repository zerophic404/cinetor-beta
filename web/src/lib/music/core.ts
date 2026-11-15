// Music playback core (pure, DOM-free): queue + one audio element + media arbitration.
// The Svelte store (player.svelte.ts) mirrors its state; tests drive it with a fake audio element.
import type { Item } from '../data/types';
import type { MediaArbiter } from '../media/arbiter';
import { nextIndex, nextRepeat, prevIndex, reorder, reorderQueue, shuffleOrder, type QueueState, type Repeat } from './queue';

export interface AudioLike {
  src: string;
  currentTime: number;
  readonly duration: number;
  readonly paused: boolean;
  play(): Promise<void>;
  pause(): void;
}

export interface Track {
  id: string;
  title: string;
  artist: string;
  album: string;
  albumId?: string;
  art: string;
  color: string;
  accent: string;
  url: string;
  durationSec: number;
}

export function trackOf(i: Item, asset: (p: string) => string = (p) => p): Track | null {
  const src = i.sources?.find((s) => s.kind === 'MP3') ?? i.sources?.[0];
  if (i.type !== 'TRACK' || !src) return null;
  return {
    id: i.id,
    title: i.title,
    artist: i.artist ?? '',
    album: i.album ?? '',
    albumId: i.albumId,
    art: asset(i.poster),
    color: i.dominantColor,
    accent: i.accentColor,
    url: src.url,
    durationSec: i.durationSec ?? 0
  };
}

export class MusicCore {
  tracks: Track[] = [];
  index = -1;
  shuffle = false;
  repeat: Repeat = 'off';
  order: number[] = [];
  private audio: AudioLike | null;

  constructor(
    audio: AudioLike | null,
    private arbiter: MediaArbiter,
    private onChange: () => void = () => {},
    private rand: () => number = Math.random
  ) {
    this.audio = audio;
    arbiter.register('music', () => this.pause());
  }

  setAudio(a: AudioLike | null): void {
    this.audio = a;
  }

  get current(): Track | null {
    return this.tracks[this.index] ?? null;
  }

  get queue(): QueueState {
    return { length: this.tracks.length, index: this.index, shuffle: this.shuffle, repeat: this.repeat, order: this.order };
  }

  get playing(): boolean {
    return !!this.audio && !this.audio.paused && this.index >= 0;
  }

  async playQueue(tracks: Track[], start = 0): Promise<void> {
    this.tracks = tracks.slice();
    this.index = -1;
    if (this.shuffle) this.order = shuffleOrder(this.tracks.length, start, this.rand);
    await this.playIndex(start);
  }

  async playIndex(i: number): Promise<void> {
    const t = this.tracks[i];
    if (!t) return;
    this.index = i;
    const a = this.audio;
    this.arbiter.claim('music');
    if (a) {
      if (a.src !== t.url) a.src = t.url;
      a.currentTime = 0;
      this.onChange();
      try {
        await a.play();
      } catch {
        /* autoplay blocked / network: UI shows paused */
      }
    }
    this.onChange();
  }

  async toggle(): Promise<void> {
    const a = this.audio;
    if (!a || this.index < 0) return;
    if (a.paused) {
      this.arbiter.claim('music');
      try {
        await a.play();
      } catch {
        /* ignore */
      }
    } else a.pause();
    this.onChange();
  }

  pause(): void {
    if (this.audio && !this.audio.paused) this.audio.pause();
    this.onChange();
  }

  async next(auto = false): Promise<void> {
    const n = nextIndex(this.queue, auto);
    if (n === null) {
      this.pause();
      if (auto && this.audio) this.audio.currentTime = 0;
      return;
    }
    await this.playIndex(n);
  }

  async prev(): Promise<void> {
    const a = this.audio;
    if (a && a.currentTime > 3) {
      a.currentTime = 0;
      this.onChange();
      return;
    }
    const p = prevIndex(this.queue);
    if (p !== null) await this.playIndex(p);
  }

  seek(sec: number): void {
    if (this.audio) this.audio.currentTime = Math.max(0, sec);
    this.onChange();
  }

  setShuffle(on: boolean): void {
    this.shuffle = on;
    this.order = on ? shuffleOrder(this.tracks.length, this.index, this.rand) : [];
    this.onChange();
  }

  cycleRepeat(): void {
    this.repeat = nextRepeat(this.repeat);
    this.onChange();
  }

  move(from: number, to: number): void {
    if (from === to || from < 0 || from >= this.tracks.length) return;
    const q = reorderQueue(this.queue, from, to);
    this.tracks = reorder(this.tracks, from, to);
    this.index = q.index;
    this.order = this.shuffle ? q.order : [];
    this.onChange();
  }

  remove(i: number): void {
    if (i < 0 || i >= this.tracks.length) return;
    const wasCurrent = i === this.index;
    this.tracks = this.tracks.filter((_, j) => j !== i);
    this.order = this.order.filter((j) => j !== i).map((j) => (j > i ? j - 1 : j));
    if (i < this.index) this.index--;
    if (wasCurrent) {
      if (!this.tracks.length) return this.stop();
      this.index = Math.min(i, this.tracks.length - 1);
      void this.playIndex(this.index);
      return;
    }
    this.onChange();
  }

  /** Audio element ended. */
  ended(): Promise<void> {
    return this.next(true);
  }

  stop(): void {
    this.audio?.pause();
    this.tracks = [];
    this.index = -1;
    this.order = [];
    this.arbiter.release('music');
    this.onChange();
  }
}
