import { base } from '$app/paths';
import { SPACE_SLUG, type Space } from './data/types';
import { meSpaceEntries, type MeSpace } from './spaces';

export { ISOLATED_SPACE_ENTRIES, visibleSpaceEntries, type SpaceEntry } from './spaces';

export interface NavTab {
  id: string;
  label: string;
  href: string;
  icon: IconName;
  /** Active when the current path matches exactly (vs prefix). */
  exact?: boolean;
}

export type IconName = 'home' | 'anime' | 'drama' | 'search' | 'me' | 'tv' | 'sports' | 'music' | 'comics' | 'adult' | 'spaces' | 'back' | 'grid';

/** Space owning a path (/app/<slug>[/...]); HOME for /app, /app/me, /app/search, etc. */
export function spaceOfPath(pathname: string): Space {
  const rel = pathname.startsWith(base) ? pathname.slice(base.length) : pathname;
  const m = /^\/app\/(anime|drama|tv|music|comics|adult)(?:\/|$)/.exec(rel);
  if (!m) return 'HOME';
  return (Object.keys(SPACE_SLUG) as Space[]).find((s) => SPACE_SLUG[s] === m[1]) ?? 'HOME';
}

export const spaceHref = (s: Space) => (s === 'HOME' ? `${base}/app` : `${base}/app/${SPACE_SLUG[s]}`);

/** Top-right Search icon destination: in-space search for MUSIC/COMICS/ADULT; global search otherwise. */
export function searchHrefForSpace(space: Space): string {
  if (space === 'MUSIC' || space === 'COMICS' || space === 'ADULT') {
    return `${base}/app/${SPACE_SLUG[space]}/search`;
  }
  return `${base}/app/search`;
}

export const VIDEO_TABS: NavTab[] = [
  { id: 'home', label: 'Home', href: `${base}/app`, icon: 'home', exact: true },
  { id: 'anime', label: 'Anime', href: `${base}/app/anime`, icon: 'anime' },
  { id: 'drama', label: 'Drama', href: `${base}/app/drama`, icon: 'drama' },
  { id: 'tv', label: 'TV', href: `${base}/app/tv`, icon: 'tv' },
  { id: 'me', label: 'Me', href: `${base}/app/me`, icon: 'me' }
];

/** Isolated spaces under Me → Spaces (Music, Comics, 18+): category tabs + return to Me. */
export function isolatedTabs(space: Space): NavTab[] {
  const slug = SPACE_SLUG[space];
  const root = `${base}/app/${slug}`;
  const cats: Record<string, NavTab[]> = {
    MUSIC: [
      { id: 'mu-albums', label: 'Albums', href: `${root}?cat=mu-albums`, icon: 'music' },
      { id: 'mu-tracks', label: 'Tracks', href: `${root}?cat=mu-tracks`, icon: 'grid' }
    ],
    COMICS: [
      { id: 'co-manga', label: 'Manga', href: `${root}?cat=co-manga`, icon: 'comics' },
      { id: 'co-manhwa', label: 'Manhwa', href: `${root}?cat=co-manhwa`, icon: 'comics' },
      { id: 'co-manhua', label: 'Manhua', href: `${root}?cat=co-manhua`, icon: 'comics' }
    ],
    ADULT: [
      { id: 'ad-featured', label: 'Movies', href: `${root}?cat=ad-featured`, icon: 'adult' },
      { id: 'ad-series', label: 'Series', href: `${root}?cat=ad-series`, icon: 'grid' }
    ]
  };
  return [
    ...(cats[space] ?? []),
    // Isolated spaces live under Me → back returns there.
    { id: 'exit', label: 'Me', href: `${base}/app/me`, icon: 'back' }
  ];
}

/** Me → Spaces entries (Music, Comics, and 18+ only once enabled; PIN asked inside). */
export const meSpaces = (adultEnabled: boolean): MeSpace[] => meSpaceEntries(adultEnabled, base);
