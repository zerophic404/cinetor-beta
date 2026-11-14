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

export const detailsHref = (item: Pick<Item, 'id'>): string => `${base}/app/title?id=${encodeURIComponent(item.id)}`;
export const watchHref = (id: string, episodeId?: string): string =>
  `${base}/app/watch?id=${encodeURIComponent(id)}${episodeId ? `&ep=${encodeURIComponent(episodeId)}` : ''}`;
