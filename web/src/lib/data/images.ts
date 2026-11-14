// Image URL helpers. Mock items use mock-relative paths ('images/posters/x.webp');
// TMDB items use 'tmdb:/abc.jpg' so the size can be picked per tier + card size.
import type { Tier } from '../perf/tier';

export const TMDB_IMG = 'https://image.tmdb.org/t/p';
export const TMDB_PREFIX = 'tmdb:';

export type TmdbSize = 'w92' | 'w185' | 'w342' | 'w500' | 'w780' | 'w1280' | 'original';
export type ImageKind = 'poster' | 'backdrop' | 'still' | 'hero' | 'logo' | 'avatar';

export const isTmdbPath = (p: string | null | undefined): p is string => !!p && p.startsWith(TMDB_PREFIX);
export const isAbsolute = (p: string) => /^(https?:|data:|blob:)/.test(p);

export function tmdbUrl(path: string, size: TmdbSize): string {
  const p = path.startsWith(TMDB_PREFIX) ? path.slice(TMDB_PREFIX.length) : path;
  return `${TMDB_IMG}/${size}${p.startsWith('/') ? p : '/' + p}`;
}

/** Candidate widths per kind and tier (LOW tier never downloads the large variants). */
export function sizesFor(kind: ImageKind, tier: Tier): TmdbSize[] {
  switch (kind) {
    case 'poster':
    case 'avatar':
    case 'logo':
      return tier === 'LOW' ? ['w185', 'w342'] : ['w185', 'w342', 'w780'];
    case 'still':
      return tier === 'LOW' ? ['w185', 'w342'] : ['w342', 'w780'];
    case 'backdrop':
      return tier === 'LOW' ? ['w342', 'w780'] : ['w780', 'w1280'];
    case 'hero':
      return tier === 'HIGH' ? ['w780', 'w1280'] : tier === 'MID' ? ['w780', 'w1280'] : ['w780'];
  }
}

/** Layout `sizes` attribute per kind (approximate rendered width). */
export const SIZES_ATTR: Record<ImageKind, string> = {
  poster: '(min-width: 900px) 168px, 28vw',
  avatar: '120px',
  logo: '160px',
  still: '(min-width: 900px) 200px, 36vw',
  backdrop: '(min-width: 900px) 320px, 60vw',
  hero: '100vw'
};

export interface ImgAttrs {
  src: string;
  srcset?: string;
  sizes?: string;
}

/**
 * Build <img> attributes. `asset` resolves mock-relative paths (adds `${base}/mock/`).
 * TMDB paths get a responsive srcset; mock files are single-resolution.
 */
export function imgAttrs(path: string, kind: ImageKind, tier: Tier, asset: (p: string) => string): ImgAttrs {
  if (!path) return { src: '' };
  if (isTmdbPath(path)) {
    const sizes = sizesFor(kind, tier);
    const srcset = sizes.map((s) => `${tmdbUrl(path, s)} ${s.slice(1)}w`).join(', ');
    return { src: tmdbUrl(path, sizes[Math.min(1, sizes.length - 1)]), srcset, sizes: SIZES_ATTR[kind] };
  }
  if (isAbsolute(path)) return { src: path };
  return { src: asset(path) };
}

/** Small image URL used for dominant-color sampling (64px canvas). */
export function colorSampleUrl(path: string, asset: (p: string) => string): string {
  if (isTmdbPath(path)) return tmdbUrl(path, 'w185');
  return isAbsolute(path) ? path : asset(path);
}
