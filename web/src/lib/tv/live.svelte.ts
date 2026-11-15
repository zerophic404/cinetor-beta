// Live TV runtime state: guide base moment, ticking clock, favourite channels (per profile).
import { activeProfileId } from '$lib/data/library.svelte';
import { localKV, readJSON, writeJSON } from '$lib/data/kv';
import { toggleFavorite } from './epg';

const favKey = () => `cinetor.tvfav.${activeProfileId()}`;

class LiveStore {
  /** Mock programme offsets are relative to this moment (first open of the TV space this session). */
  readonly base = Math.floor(Date.now() / 1000);
  now = $state(Math.floor(Date.now() / 1000));
  favorites = $state<string[]>(readJSON<string[]>(localKV(), favKey(), []));
  private timer: ReturnType<typeof setInterval> | undefined;
  private users = 0;

  /** Start the 15 s clock while a live surface is mounted. Returns a release fn. */
  tick(): () => void {
    this.users++;
    this.now = Math.floor(Date.now() / 1000);
    if (!this.timer) this.timer = setInterval(() => (this.now = Math.floor(Date.now() / 1000)), 15_000);
    return () => {
      this.users--;
      if (this.users <= 0 && this.timer) {
        clearInterval(this.timer);
        this.timer = undefined;
      }
    };
  }

  isFav(id: string): boolean {
    return this.favorites.includes(id);
  }

  toggleFav(id: string): void {
    this.favorites = toggleFavorite(this.favorites, id);
    writeJSON(localKV(), favKey(), this.favorites);
  }
}

export const live = new LiveStore();
