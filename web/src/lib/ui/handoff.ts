// Card → details/inline-player handoff: preserves `slug` across every route helper so deep links,
// reloads, Hero Play, Continue Watching, Downloads, and MiniPlayer never hit "Title not found".
//
// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
// scrape or hack anything without Mythbrix's explicit permission.
import { base } from '$app/paths';
import { catalog } from '$lib/data/app-catalog';
import { spaceForId } from '$lib/data/catalog';
import type { Item } from '$lib/data/types';

export const SHARED_NAME = 'cinetor-poster';
const SLUG_STORAGE_KEY = 'cinetor.slugHints';

const recent = new Map<string, Item>();
const slugMemory = new Map<string, string>();
let slugsHydrated = false;
let marked: HTMLElement | null = null;

function hydrateSlugs(): void {
  if (slugsHydrated || typeof sessionStorage === 'undefined') return;
  slugsHydrated = true;
  try {
    const raw = sessionStorage.getItem(SLUG_STORAGE_KEY) ?? localStorage.getItem(SLUG_STORAGE_KEY);
    if (!raw) return;
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    for (const [k, v] of Object.entries(parsed)) {
      if (typeof v === 'string' && v) slugMemory.set(k, v);
    }
  } catch {
    /* ignore storage errors */
  }
}

export function rememberHandoffSlug(id: string, slug?: string | null): void {
  const clean = slug?.trim();
  if (!id || !clean) return;
  hydrateSlugs();
  slugMemory.set(id, clean);
  if (slugMemory.size > 300) {
    const oldest = slugMemory.keys().next().value;
    if (oldest !== undefined) slugMemory.delete(oldest);
  }
  try {
    const serialized = JSON.stringify(Object.fromEntries(slugMemory));
    sessionStorage.setItem(SLUG_STORAGE_KEY, serialized);
    localStorage.setItem(SLUG_STORAGE_KEY, serialized);
  } catch {
    /* ignore quota errors */
  }
}

export function resolveHandoffSlug(id: string, explicitSlug?: string | null): string | undefined {
  if (!id) return undefined;
  const direct = explicitSlug?.trim();
  if (direct) {
    rememberHandoffSlug(id, direct);
    return direct;
  }
  hydrateSlugs();
  const cached = recent.get(id)?.slug || slugMemory.get(id) || catalog.peek(id)?.slug;
  if (cached) {
    rememberHandoffSlug(id, cached);
    return cached;
  }
  const tmdbSub = /^(?:ad-gw:|gw:)(tmdb-(?:tv|movie)-\d+)$/i.exec(id);
  if (tmdbSub) return tmdbSub[1];
  return undefined;
}

export function handoff(item: Item, el?: HTMLElement | null): void {
  recent.set(item.id, item);
  if (recent.size > 60) recent.delete(recent.keys().next().value as string);
  if (item.slug) rememberHandoffSlug(item.id, item.slug);
  if (marked && marked !== el) marked.style.removeProperty('view-transition-name');
  marked = el ?? null;
  if (el) el.style.setProperty('view-transition-name', SHARED_NAME);
}

export const handedOff = (id: string): Item | undefined => recent.get(id);

export type HrefItem = Pick<Item, 'id'> & Partial<Pick<Item, 'space' | 'type' | 'albumId' | 'slug' | 'live'>>;

export interface HrefOpts {
  slug?: string | null;
  ep?: string | null;
  episodeId?: string | null;
  s?: number | null;
  season?: number | null;
  e?: number | null;
  episode?: number | null;
  play?: boolean;
  live?: boolean;
}

const enc = encodeURIComponent;

function parseEpisodeCode(epId?: string | null): { s?: number; e?: number } {
  if (!epId) return {};
  const m = /[-:]s(\d+)e(\d+)$/i.exec(epId);
  if (!m) return {};
  return { s: Number(m[1]) || undefined, e: Number(m[2]) || undefined };
}

/** Build `/app/title` URL preserving `slug`, season/episode pointers, and optional inline `play=1`. */
export function titleHref(itemOrId: HrefItem | string, opts?: HrefOpts): string {
  const id = typeof itemOrId === 'string' ? itemOrId : itemOrId.id;
  const itemSlug = typeof itemOrId === 'string' ? undefined : itemOrId.slug;
  const slug = resolveHandoffSlug(id, opts?.slug ?? itemSlug);
  const ep = opts?.ep ?? opts?.episodeId ?? undefined;
  const parsed = parseEpisodeCode(ep);
  const s = opts?.s ?? opts?.season ?? parsed.s;
  const e = opts?.e ?? opts?.episode ?? parsed.e;

  const params: string[] = [`id=${enc(id)}`];
  if (slug) params.push(`slug=${enc(slug)}`);
  if (ep) params.push(`ep=${enc(ep)}`);
  if (s !== undefined && s !== null && s > 0) params.push(`s=${Math.trunc(s)}`);
  if (e !== undefined && e !== null && e > 0) params.push(`e=${Math.trunc(e)}`);
  if (opts?.play) params.push('play=1');
  return `${base}/app/title?${params.join('&')}`;
}

/** Where a card leads: channels play live, music → album page, comics → comic details, else title details. */
export function detailsHref(item: HrefItem, opts?: HrefOpts): string {
  if (item.type === 'CHANNEL' || item.live) return watchHref(item, { ...opts, live: true });
  if (item.space === 'MUSIC') {
    if (item.type === 'TRACK' && item.albumId) return `${base}/app/music/album?id=${enc(item.albumId)}&track=${enc(item.id)}`;
    return `${base}/app/music/album?id=${enc(item.id)}`;
  }
  if (item.space === 'COMICS') return `${base}/app/comics/title?id=${enc(item.id)}`;
  return titleHref(item, opts);
}

/**
 * Play target href:
 *  • VOD movies & series route directly to `/app/title?id=...&slug=...&play=1` for instant inline playback;
 *  • Live TV channels & live sports stay on `/app/watch?id=...&live=1`.
 */
export function watchHref(itemOrId: HrefItem | string, epOrOpts?: string | HrefOpts | null): string {
  const id = typeof itemOrId === 'string' ? itemOrId : itemOrId.id;
  const opts: HrefOpts =
    typeof epOrOpts === 'string' ? { ep: epOrOpts } : epOrOpts ? { ...epOrOpts } : {};
  const isLive =
    opts.live === true ||
    (typeof itemOrId !== 'string' && (itemOrId.type === 'CHANNEL' || itemOrId.live === true || itemOrId.space === 'TV')) ||
    spaceForId(id) === 'TV';

  if (isLive) {
    return `${base}/app/watch?id=${enc(id)}&live=1`;
  }
  return titleHref(itemOrId, { ...opts, play: true });
}
