// Card → details handoff: the clicked item is kept so details paints its header instantly,
// and exactly one element carries the shared view-transition-name.
import { base } from '$app/paths';
import type { Item } from '$lib/data/types';

export const SHARED_NAME = 'cinetor-poster';

const recent = new Map<string, Item>();
let marked: HTMLElement | null = null;

export function handoff(item: Item, el?: HTMLElement | null): void {
  recent.set(item.id, item);
  if (recent.size > 40) recent.delete(recent.keys().next().value as string);
  if (marked && marked !== el) marked.style.removeProperty('view-transition-name');
  marked = el ?? null;
  if (el) el.style.setProperty('view-transition-name', SHARED_NAME);
}

export const handedOff = (id: string): Item | undefined => recent.get(id);

export type HrefItem = Pick<Item, 'id'> & Partial<Pick<Item, 'space' | 'type' | 'albumId'>>;

const enc = encodeURIComponent;

/** Where a card leads: channels play directly, music → album page, comics → comic details, else title details. */
export function detailsHref(item: HrefItem): string {
  if (item.type === 'CHANNEL') return watchHref(item.id);
  if (item.space === 'MUSIC') {
    if (item.type === 'TRACK' && item.albumId) return `${base}/app/music/album?id=${enc(item.albumId)}&track=${enc(item.id)}`;
    return `${base}/app/music/album?id=${enc(item.id)}`;
  }
  if (item.space === 'COMICS') return `${base}/app/comics/title?id=${enc(item.id)}`;
  return `${base}/app/title?id=${enc(item.id)}`;
}

export const watchHref = (id: string, episodeId?: string): string => `${base}/app/watch?id=${enc(id)}${episodeId ? `&ep=${enc(episodeId)}` : ''}`;
