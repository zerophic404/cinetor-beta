// Isolated spaces listed under Me → Spaces (pure: no $app imports, vitest-friendly).
// TV is now a primary main-navigation tab, so Me → Spaces lists ONLY Music, Comics, and 18+ (when enabled).
import { SPACE_SLUG, type Space } from './data/types';

export interface SpaceEntry {
  space: Space;
  label: string;
  icon: 'tv' | 'music' | 'comics' | 'adult';
  blurb: string;
}

export const ISOLATED_SPACE_ENTRIES: SpaceEntry[] = [
  { space: 'MUSIC', label: 'Music', icon: 'music', blurb: 'Albums, moods, tracks' },
  { space: 'COMICS', label: 'Comics', icon: 'comics', blurb: 'Manga · Manhwa · Manhua' },
  { space: 'ADULT', label: '18+', icon: 'adult', blurb: 'Adults only · PIN protected' }
];

/** Adult only once enabled (Settings → Content; never on Kids profiles). TV never shown in Me → Spaces. */
export const visibleSpaceEntries = (adultEnabled: boolean): SpaceEntry[] =>
  ISOLATED_SPACE_ENTRIES.filter((e) => e.space !== 'TV' && (e.space !== 'ADULT' || Boolean(adultEnabled)));

export interface MeSpace extends SpaceEntry {
  href: string;
}

export const meSpaceEntries = (adultEnabled: boolean, base = ''): MeSpace[] =>
  visibleSpaceEntries(adultEnabled).map((e) => ({ ...e, href: `${base}/app/${SPACE_SLUG[e.space]}` }));
