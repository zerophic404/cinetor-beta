// Reactive dominant colors: TMDB items start with a hashed fallback; the real color is sampled
// from a small thumbnail (ColorCache: memory LRU + IndexedDB) and swapped in when ready.
import { untrack } from 'svelte';
import { SvelteMap } from 'svelte/reactivity';
import { colors } from './app-catalog';
import { colorSampleUrl, isTmdbPath } from './images';
import { repo } from './repo';
import type { Item } from './types';

const live = new SvelteMap<string, string>();

export function colorOf(item: Item): string {
  return live.get(item.id) ?? item.dominantColor;
}

/** Call from effects / event handlers only (writes state). Mock items already carry real colors. */
export function ensureColor(item: Item | undefined): void {
  if (!item || !isTmdbPath(item.poster)) return;
  untrack(() => {
    if (live.has(item.id)) return;
    const url = colorSampleUrl(item.poster, (p) => repo.asset(p));
    const hit = colors.peek(url);
    if (hit) {
      live.set(item.id, hit);
      return;
    }
    void colors.get(url).then((c) => {
      if (c) live.set(item.id, c);
    });
  });
}
