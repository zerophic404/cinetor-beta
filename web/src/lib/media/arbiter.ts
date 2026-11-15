// One-audible-thing-at-a-time: starting music stops the video player and vice versa.
export type MediaKind = 'video' | 'music';

export class MediaArbiter {
  private stops = new Map<MediaKind, () => void>();
  active: MediaKind | null = null;

  /** Register how to silence a media kind. Returns unregister. */
  register(kind: MediaKind, stop: () => void): () => void {
    this.stops.set(kind, stop);
    return () => {
      if (this.stops.get(kind) === stop) this.stops.delete(kind);
    };
  }

  /** `kind` starts playing: every other kind is stopped. */
  claim(kind: MediaKind): void {
    for (const [k, stop] of this.stops) if (k !== kind) stop();
    this.active = kind;
  }

  release(kind: MediaKind): void {
    if (this.active === kind) this.active = null;
  }
}

/** App-wide instance (player controller + music player register here). */
export const arbiter = new MediaArbiter();
