import { base } from '$app/paths';
import { SPACE_SLUG, type Space } from './data/types';

export interface NavTab {
  id: string;
  label: string;
  href: string;
  icon: IconName;
  /** Active when the current path matches exactly (vs prefix). */
  exact?: boolean;
}

export type IconName = 'home' | 'anime' | 'drama' | 'search' | 'me' | 'tv' | 'sports' | 'music' | 'comics' | 'adult' | 'spaces' | 'back' | 'grid';

/** Isolated space owning a path (/app/<slug>[/...]); HOME for everything else. */
export function spaceOfPath(pathname: string): Space {
  const rel = pathname.startsWith(base) ? pathname.slice(base.length) : pathname;
  const m = /^\/app\/(tv|music|comics|adult)(?:\/|$)/.exec(rel);
  if (!m) return 'HOME';
  return (Object.keys(SPACE_SLUG) as Space[]).find((s) => SPACE_SLUG[s] === m[1]) ?? 'HOME';
}

export const spaceHref = (s: Space) => (s === 'HOME' ? `${base}/app` : `${base}/app/${SPACE_SLUG[s]}`);

export const VIDEO_TABS: NavTab[] = [
  { id: 'home', label: 'Home', href: `${base}/app`, icon: 'home', exact: true },
  { id: 'anime', label: 'Anime', href: `${base}/app/anime`, icon: 'anime' },
  { id: 'drama', label: 'Drama', href: `${base}/app/drama`, icon: 'drama' },
  { id: 'search', label: 'Search', href: `${base}/app/search`, icon: 'search' },
  { id: 'me', label: 'Me', href: `${base}/app/me`, icon: 'me' }
];

export interface SpaceEntry {
  space: Space;
  label: string;
  icon: IconName;
  blurb: string;
}

/** Every isolated space (labels). Use visibleSpaceEntries() for the switcher. */
export const ISOLATED_SPACE_ENTRIES: SpaceEntry[] = (
  [
    { space: 'TV', label: 'Live TV & Sports', icon: 'tv', blurb: 'Channels, EPG, live matches' },
    { space: 'MUSIC', label: 'Music', icon: 'music', blurb: 'Albums, moods, tracks' },
    { space: 'COMICS', label: 'Comics', icon: 'comics', blurb: 'Manga · Manhwa · Manhua' },
    { space: 'ADULT', label: 'Adult', icon: 'adult', blurb: '18+ · PIN protected' }
  ] as SpaceEntry[]
);

/** Spaces shown in the switcher: Adult only once enabled (Settings → Content, not on Kids profiles). */
export const visibleSpaceEntries = (adultEnabled: boolean): SpaceEntry[] => ISOLATED_SPACE_ENTRIES.filter((e) => e.space !== 'ADULT' || adultEnabled);

/** Each isolated space gets its own tabs: its categories + in-space search + exit. */
export function isolatedTabs(space: Space): NavTab[] {
  const slug = SPACE_SLUG[space];
  const root = `${base}/app/${slug}`;
  const cats: Record<string, NavTab[]> = {
    TV: [
      { id: 'tv-live', label: 'Live TV', href: `${root}?cat=tv-live`, icon: 'tv' },
      { id: 'tv-sports', label: 'Sports', href: `${root}?cat=tv-sports`, icon: 'sports' }
    ],
    MUSIC: [
      { id: 'mu-albums', label: 'Albums', href: `${root}?cat=mu-albums`, icon: 'music' },
      { id: 'mu-tracks', label: 'Tracks', href: `${root}?cat=mu-tracks`, icon: 'grid' }
    ],
    COMICS: [
      { id: 'co-manga', label: 'Manga', href: `${root}?cat=co-manga`, icon: 'comics' },
      { id: 'co-manhwa', label: 'Manhwa', href: `${root}?cat=co-manhwa`, icon: 'comics' },
      { id: 'co-manhua', label: 'Manhua', href: `${root}?cat=co-manhua`, icon: 'comics' }
    ],
    ADULT: [{ id: 'ad-featured', label: 'Featured', href: `${root}?cat=ad-featured`, icon: 'adult' }]
  };
  return [
    ...(cats[space] ?? []),
    { id: 'search', label: 'Search', href: `${root}/search`, icon: 'search' },
    { id: 'exit', label: 'Exit', href: `${base}/app`, icon: 'back' }
  ];
}
