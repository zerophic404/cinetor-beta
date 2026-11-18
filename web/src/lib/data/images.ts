// Image URL helpers. Mock items use mock-relative paths ('images/posters/x.webp');
// TMDB items use 'tmdb:/abc.jpg' (or full image.tmdb.org URLs) and gateway items use OSS resize
// (`?x-oss-process=image/resize,w_N`), both aware of DPR and low-perf tier.
import type { Tier } from '../perf/tier';

export const TMDB_IMG = 'https://image.tmdb.org/t/p';
export const TMDB_PREFIX = 'tmdb:';

export type TmdbSize = 'w92' | 'w185' | 'w342' | 'w500' | 'w780' | 'w1280' | 'original';
export type ImageKind = 'poster' | 'backdrop' | 'still' | 'hero' | 'logo' | 'avatar';

const TMDB_FULL_RE = /^https?:\/\/image\.tmdb\.org\/t\/p\/[^/?#]+(\/[^?#]+)/i;
const GW_OSS_HOST_RE = /^https:\/\/[^/]*(?:aoneroom\.com|aliyuncs\.com)\//i;

export const isTmdbPath = (p: string | null | undefined): p is string =>
  !!p && (p.startsWith(TMDB_PREFIX) || TMDB_FULL_RE.test(p));

export const isAbsolute = (p: string): boolean => /^(https?:|data:|blob:)/.test(p);

/** Gateway OSS CDN images support on-the-fly resize: `?x-oss-process=image/resize,w_N`. */
export const isGwImage = (p: string | null | undefined): p is string =>
  !!p && (GW_OSS_HOST_RE.test(p) || (/^https:\/\//i.test(p) && /[?&]x-oss-process=image\//i.test(p)));

export function gwImgUrl(p: string, w: number): string {
  const width = Math.max(64, Math.round(w));
  const proc = `x-oss-process=image/resize,w_${width}`;
  if (/([?&])x-oss-process=[^&#]*/i.test(p)) {
    return p.replace(/([?&])x-oss-process=[^&#]*/i, `$1${proc}`);
  }
  return `${p}${p.includes('?') ? '&' : '?'}${proc}`;
}

/** Remove `x-oss-process` query parameter cleanly while preserving any other query params and hash. */
export function stripOssProcess(url: string): string {
  if (!url || !/x-oss-process=/i.test(url)) return url;
  const hashIdx = url.indexOf('#');
  const beforeHash = hashIdx >= 0 ? url.slice(0, hashIdx) : url;
  const hash = hashIdx >= 0 ? url.slice(hashIdx) : '';
  const qIdx = beforeHash.indexOf('?');
  if (qIdx < 0) return url;
  const base = beforeHash.slice(0, qIdx);
  const rest = beforeHash
    .slice(qIdx + 1)
    .split('&')
    .filter((part) => part.length > 0 && !/^x-oss-process=/i.test(part));
  return rest.length ? `${base}?${rest.join('&')}${hash}` : `${base}${hash}`;
}

function defaultDpr(): number {
  if (typeof devicePixelRatio === 'number' && Number.isFinite(devicePixelRatio) && devicePixelRatio > 0) {
    return devicePixelRatio;
  }
  return 1;
}

function scaledPixels(cssWidthPx: number, tier: Tier, dpr = defaultDpr()): number {
  const base = Math.max(48, cssWidthPx);
  const maxDpr = tier === 'LOW' ? 1.25 : tier === 'MID' ? 2 : 2.5;
  const clampedDpr = Math.min(Math.max(Number.isFinite(dpr) && dpr > 0 ? dpr : 1, 1), maxDpr);
  const px = Math.round(base * clampedDpr);
  if (tier === 'LOW') {
    return base <= 260 ? Math.min(px, 342) : Math.min(px, 780);
  }
  return Math.min(px, 1280);
}

/** Pick TMDB width bucket (`w342` / `w500` / `w780` / `w1280`) for a CSS width, perf tier, and DPR. */
export function tmdbBucketFor(cssWidthPx: number, tier: Tier = 'MID', dpr = defaultDpr()): TmdbSize {
  const px = scaledPixels(cssWidthPx, tier, dpr);
  if (px <= 342) return 'w342';
  if (px <= 500) return 'w500';
  if (px <= 780) return 'w780';
  return 'w1280';
}

/** Pick OSS resize width (`200` / `342` / `500` / `780` / `1280`) for a CSS width, perf tier, and DPR. */
export function ossWidthFor(cssWidthPx: number, tier: Tier = 'MID', dpr = defaultDpr()): number {
  if (cssWidthPx < 120 && (dpr ?? 1) <= 1.25) return 200;
  const px = scaledPixels(cssWidthPx, tier, dpr);
  if (px <= 342) return 342;
  if (px <= 500) return 500;
  if (px <= 780) return 780;
  return 1280;
}

/** Widths per kind and tier for gateway images. */
export function gwWidths(kind: ImageKind, tier: Tier): number[] {
  switch (kind) {
    case 'poster':
    case 'avatar':
    case 'logo':
      return tier === 'LOW' ? [200, 342] : tier === 'MID' ? [200, 342, 500] : [200, 342, 500, 780];
    case 'still':
      return tier === 'LOW' ? [342] : [342, 500, 780];
    case 'backdrop':
      return tier === 'LOW' ? [500, 780] : [500, 780, 1280];
    case 'hero':
      return tier === 'LOW' ? [500, 780] : [780, 1280];
  }
}

function extractTmdbRelPath(path: string): string {
  if (path.startsWith(TMDB_PREFIX)) {
    const rel = path.slice(TMDB_PREFIX.length);
    return rel.startsWith('/') ? rel : `/${rel}`;
  }
  const m = TMDB_FULL_RE.exec(path);
  if (m) return m[1];
  return path.startsWith('/') ? path : `/${path}`;
}

export function tmdbUrl(path: string, size: TmdbSize): string {
  return `${TMDB_IMG}/${size}${extractTmdbRelPath(path)}`;
}

/** Candidate widths per kind and tier (LOW tier never downloads the large variants). */
export function sizesFor(kind: ImageKind, tier: Tier): TmdbSize[] {
  switch (kind) {
    case 'poster':
    case 'avatar':
    case 'logo':
      return tier === 'LOW' ? ['w185', 'w342'] : tier === 'MID' ? ['w185', 'w342', 'w500'] : ['w342', 'w500', 'w780'];
    case 'still':
      return tier === 'LOW' ? ['w185', 'w342'] : ['w342', 'w500', 'w780'];
    case 'backdrop':
      return tier === 'LOW' ? ['w342', 'w500', 'w780'] : ['w500', 'w780', 'w1280'];
    case 'hero':
      return tier === 'LOW' ? ['w500', 'w780'] : ['w780', 'w1280'];
  }
}

const NOMINAL_CSS_WIDTH: Record<ImageKind, number> = {
  poster: 180,
  avatar: 120,
  logo: 160,
  still: 240,
  backdrop: 400,
  hero: 720
};

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

const sizeToPx = (s: TmdbSize): number => (s === 'original' ? 1920 : parseInt(s.slice(1), 10));

/** Single DPR + tier-aware thumbnail URL for OSS (`?x-oss-process=image/resize,w_N`) or TMDB buckets. */
export function thumbnailUrl(
  path: string,
  cssWidthPx: number,
  tier: Tier = 'MID',
  dpr = defaultDpr(),
  asset: (p: string) => string = (p) => p
): string {
  if (!path) return '';
  if (isTmdbPath(path)) return tmdbUrl(path, tmdbBucketFor(cssWidthPx, tier, dpr));
  if (isGwImage(path)) return gwImgUrl(path, ossWidthFor(cssWidthPx, tier, dpr));
  return isAbsolute(path) ? path : asset(path);
}

/**
 * Build <img> attributes. `asset` resolves mock-relative paths (adds `${base}/mock/`).
 * TMDB and OSS gateway paths get a tier-capped responsive srcset and DPR-aware fallback src.
 */
export function imgAttrs(
  path: string,
  kind: ImageKind,
  tier: Tier,
  asset: (p: string) => string,
  dpr = defaultDpr()
): ImgAttrs {
  if (!path) return { src: '' };
  const wantPx = ossWidthFor(NOMINAL_CSS_WIDTH[kind], tier, dpr);
  if (isTmdbPath(path)) {
    const sizes = sizesFor(kind, tier);
    const srcset = sizes.map((s) => `${tmdbUrl(path, s)} ${s.slice(1)}w`).join(', ');
    const chosen = sizes.find((s) => sizeToPx(s) >= wantPx) ?? sizes[sizes.length - 1];
    return { sizes: SIZES_ATTR[kind], srcset, src: tmdbUrl(path, chosen) };
  }
  if (isGwImage(path)) {
    const ws = gwWidths(kind, tier);
    const srcset = ws.map((w) => `${gwImgUrl(path, w)} ${w}w`).join(', ');
    const chosen = ws.find((w) => w >= wantPx) ?? ws[ws.length - 1];
    return { sizes: SIZES_ATTR[kind], srcset, src: gwImgUrl(path, chosen) };
  }
  if (isAbsolute(path)) return { src: path };
  return { src: asset(path) };
}

/** Small image URL used for dominant-color sampling (64px canvas — distinct size from Poster src/srcset). */
export function colorSampleUrl(path: string, asset: (p: string) => string): string {
  if (isTmdbPath(path)) return tmdbUrl(path, 'w92');
  if (isGwImage(path)) return gwImgUrl(path, 100);
  return isAbsolute(path) ? path : asset(path);
}
