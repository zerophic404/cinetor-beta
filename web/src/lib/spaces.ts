// Isolated spaces listed under Me → Spaces (pure: no $app imports, vitest-friendly).
import { SPACE_SLUG, type Space } from './data/types';

export interface SpaceEntry {
  space: Space;
  label: string;
  icon: 'tv' | 'music' | 'comics' | 'adult';
  blurb: string;
}

export const ISOLATED_SPACE_ENTRIES: SpaceEntry[] = [
  { space: 'TV', label: 'Live TV & Sports', icon: 'tv', blurb: 'Channels, EPG, live matches' },
  { space: 'MUSIC', label: 'Music', icon: 'music', blurb: 'Albums, moods, tracks' },
  { space: 'COMICS', label: 'Comics', icon: 'comics', blurb: 'Manga · Manhwa · Manhua' },
  { space: 'ADULT', label: '18+', icon: 'adult', blurb: 'Adults only · PIN protected' }
];

/** Adult only once enabled (Settings → Content; never on Kids profiles — AdultGate.enabled is false there). */
export const visibleSpaceEntries = (adultEnabled: boolean): SpaceEntry[] => ISOLATED_SPACE_ENTRIES.filter((e) => e.space !== 'ADULT' || adultEnabled);

export interface MeSpace extends SpaceEntry {
  href: string;
}

export const meSpaceEntries = (adultEnabled: boolean, base = ''): MeSpace[] =>
  visibleSpaceEntries(adultEnabled).map((e) => ({ ...e, href: `${base}/app/${SPACE_SLUG[e.space]}` }));
