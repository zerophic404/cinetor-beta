// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
// scrape or hack anything without Mythbrix's explicit permission.
// Music playback core (pure, DOM-free): queue + lazy gateway stream resolution + quality switch + media arbitration.
// The Svelte store (player.svelte.ts) mirrors its state; tests drive it with a fake audio element.
import type { Item } from '../data/types';
import type { MediaArbiter } from '../media/arbiter';
import { nextIndex, nextRepeat, prevIndex, reorder, reorderQueue, shuffleOrder, type QueueState, type Repeat } from './queue';

export type AudioQuality = 'flac' | '320kbps';

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
  rawId?: string;
  title: string;
  artist: string;
  album: string;
  albumId?: string;
  year?: string;
  art: string;
  color: string;
  accent: string;
  url: string;
  durationSec: number;
  durationLabel?: string;
  quality?: string;
  format?: string;
  hasFlac?: boolean;
}

export interface ResolvedTrackStream {
  url: string;
  qualityLabel?: string;
  format?: string;
}

export type TrackStreamResolver = (
  track: Track,
  quality: AudioQuality,
  signal: AbortSignal
) => Promise<ResolvedTrackStream | null>;

export const TRACK_UNAVAILABLE_MSG = 'Track unavailable right now. Try another song.';

export function trackOf(i: Item, asset: (p: string) => string = (p) => p): Track | null {
  const src = i.sources?.find((s) => s.kind === 'MP3') ?? i.sources?.[0];
  if (i.type !== 'TRACK' || !src) return null;
  return {
    id: i.id,
    rawId: i.id,
    title: i.title,
    artist: i.artist ?? '',
    album: i.album ?? '',
    albumId: i.albumId,
    year: i.year ? String(i.year) : undefined,
    art: asset(i.poster),
    color: i.dominantColor,
    accent: i.accentColor,
    url: asset(src.url),
    durationSec: i.durationSec ?? 0,
    hasFlac: false
  };
}

const isLazyUrl = (url: string): boolean => !url || url.startsWith('mu-stream:');

export class MusicCore {
  tracks: Track[] = [];
  index = -1;
  shuffle = false;
  repeat: Repeat = 'off';
  order: number[] = [];
  quality: AudioQuality = 'flac';
  loading = false;
  error: string | null = null;
  streamQualityLabel = 'FLAC Lossless';

  private audio: AudioLike | null;
  private resolver: TrackStreamResolver | null = null;
  private abort: AbortController | null = null;
  private seq = 0;

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

  setResolver(resolver: TrackStreamResolver | null): void {
    this.resolver = resolver;
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

  async playIndex(i: number, resumeSec = 0, autoPlay = true): Promise<void> {
    const t = this.tracks[i];
    if (!t) return;
    this.abort?.abort();
    const ctl = new AbortController();
    this.abort = ctl;
    const seq = ++this.seq;

    this.index = i;
    this.error = null;
    if (autoPlay) this.arbiter.claim('music');

    const effectiveQuality: AudioQuality = this.quality === 'flac' && t.hasFlac === false ? '320kbps' : this.quality;
    let playUrl = t.url;

    if (isLazyUrl(t.url)) {
      if (!this.resolver) {
        this.loading = false;
        this.error = TRACK_UNAVAILABLE_MSG;
        this.onChange();
        return;
      }
      this.loading = true;
      this.onChange();
      let resolved: ResolvedTrackStream | null = null;
      try {
        resolved = await this.resolver(t, effectiveQuality, ctl.signal);
      } catch {
        resolved = null;
      }
      if (seq !== this.seq || ctl.signal.aborted) return;
      this.loading = false;
      if (!resolved || !resolved.url) {
        if (this.audio && !this.audio.paused) this.audio.pause();
        this.error = TRACK_UNAVAILABLE_MSG;
        this.onChange();
        return;
      }
      playUrl = resolved.url;
      this.streamQualityLabel = resolved.qualityLabel ?? (effectiveQuality === 'flac' ? 'FLAC Lossless' : '320kbps Studio Master');
    } else {
      this.loading = false;
      this.streamQualityLabel = t.quality ?? (effectiveQuality === 'flac' ? 'FLAC Lossless' : '320kbps Studio Master');
    }

    const a = this.audio;
    if (a) {
      if (a.src !== playUrl) a.src = playUrl;
      a.currentTime = Math.max(0, resumeSec);
      this.onChange();
      if (autoPlay) {
        this.arbiter.claim('music');
        try {
          await a.play();
        } catch {
          /* autoplay blocked / network: UI shows paused */
        }
      }
    }
    this.onChange();
  }

  async setQuality(quality: AudioQuality): Promise<void> {
    if (this.quality === quality) return;
    this.quality = quality;
    const cur = this.current;
    if (cur && isLazyUrl(cur.url) && this.resolver) {
      const pos = this.audio?.currentTime ?? 0;
      const shouldPlay = this.playing || !this.audio?.src;
      await this.playIndex(this.index, pos, shouldPlay);
      return;
    }
    this.streamQualityLabel = quality === 'flac' && cur?.hasFlac !== false ? 'FLAC Lossless' : '320kbps Studio Master';
    this.onChange();
  }

  async toggle(): Promise<void> {
    const a = this.audio;
    const cur = this.current;
    if (!a || this.index < 0 || !cur) return;
    if (isLazyUrl(cur.url) && (!a.src || this.error)) {
      await this.playIndex(this.index);
      return;
    }
    if (a.paused) {
      this.arbiter.claim('music');
      try {
        await a.play();
      } catch {
        /* ignore */
      }
    } else {
      a.pause();
    }
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
    this.abort?.abort();
    this.loading = false;
    this.error = null;
    this.audio?.pause();
    this.tracks = [];
    this.index = -1;
    this.order = [];
    this.arbiter.release('music');
    this.onChange();
  }
}
