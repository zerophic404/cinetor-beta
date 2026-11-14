// Reactive music player singleton. The single <audio> element lives in the /app layout and is
// handed over with attach(); playback survives navigation. Video ↔ music exclusivity via arbiter.
import { arbiter } from '$lib/media/arbiter';
import { MusicCore, type Track } from './core';
import type { Repeat } from './queue';

class MusicPlayer {
  tracks = $state.raw<Track[]>([]);
  index = $state(-1);
  playing = $state(false);
  shuffle = $state(false);
  repeat = $state<Repeat>('off');
  t = $state(0);
  dur = $state(0);
  expanded = $state(false);

  readonly core = new MusicCore(null, arbiter, () => this.sync());
  private el: HTMLAudioElement | null = null;

  get current(): Track | null {
    return this.tracks[this.index] ?? null;
  }

  private sync(): void {
    const c = this.core;
    this.tracks = c.tracks;
    this.index = c.index;
    this.shuffle = c.shuffle;
    this.repeat = c.repeat;
    this.playing = c.playing;
    if (this.el) {
      this.t = this.el.currentTime || 0;
      this.dur = Number.isFinite(this.el.duration) ? this.el.duration : (c.current?.durationSec ?? 0);
    }
  }

  /** Svelte action for the layout's <audio>. */
  attach = (el: HTMLAudioElement) => {
    this.el = el;
    this.core.setAudio(el);
    const sync = () => this.sync();
    const evs = ['play', 'pause', 'timeupdate', 'durationchange', 'loadedmetadata', 'seeked', 'emptied'] as const;
    for (const e of evs) el.addEventListener(e, sync);
    const onPlay = () => arbiter.claim('music');
    const onEnded = () => void this.core.ended();
    el.addEventListener('play', onPlay);
    el.addEventListener('ended', onEnded);
    this.mediaSession();
    return {
      destroy: () => {
        for (const e of evs) el.removeEventListener(e, sync);
        el.removeEventListener('play', onPlay);
        el.removeEventListener('ended', onEnded);
        this.core.setAudio(null);
        this.el = null;
      }
    };
  };

  playQueue(tracks: Track[], start = 0): void {
    void this.core.playQueue(tracks, start);
    this.updateMeta();
  }
  playIndex(i: number): void {
    void this.core.playIndex(i).then(() => this.updateMeta());
  }
  toggle(): void {
    void this.core.toggle();
  }
  next(): void {
    void this.core.next().then(() => this.updateMeta());
  }
  prev(): void {
    void this.core.prev().then(() => this.updateMeta());
  }
  seek(sec: number): void {
    this.core.seek(sec);
  }
  setShuffle(on: boolean): void {
    this.core.setShuffle(on);
  }
  cycleRepeat(): void {
    this.core.cycleRepeat();
  }
  move(from: number, to: number): void {
    this.core.move(from, to);
  }
  remove(i: number): void {
    this.core.remove(i);
  }
  stop(): void {
    this.expanded = false;
    this.core.stop();
  }

  private updateMeta(): void {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
    const t = this.core.current;
    if (!t) return;
    try {
      navigator.mediaSession.metadata = new MediaMetadata({ title: t.title, artist: t.artist, album: t.album, artwork: t.art ? [{ src: t.art, sizes: '512x512' }] : [] });
    } catch {
      /* ignore */
    }
  }

  private mediaSession(): void {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
    // Video wiring owns the handlers while video plays; music re-claims them on play.
    this.el?.addEventListener('play', () => {
      const ms = navigator.mediaSession;
      const set = (a: MediaSessionAction, f: MediaSessionActionHandler | null) => {
        try {
          ms.setActionHandler(a, f);
        } catch {
          /* unsupported */
        }
      };
      set('play', () => this.toggle());
      set('pause', () => this.toggle());
      set('nexttrack', () => this.next());
      set('previoustrack', () => this.prev());
      set('seekto', (d) => d.seekTime !== undefined && this.seek(d.seekTime));
      this.updateMeta();
    });
  }
}

export const music = new MusicPlayer();
