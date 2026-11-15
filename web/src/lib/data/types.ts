// Mirrors shared/mock/catalog.schema.json. Image/subtitle paths are relative to the mock root.

export const SPACES = ['HOME', 'ANIME', 'DRAMA', 'TV', 'MUSIC', 'COMICS', 'ADULT'] as const;
export type Space = (typeof SPACES)[number];

/** Spaces that participate in global search / recents / continue-watching. */
export const VIDEO_SPACES = ['HOME', 'ANIME', 'DRAMA'] as const satisfies readonly Space[];
/** Isolated spaces: own nav, own in-space search, never leak into global surfaces. */
export const ISOLATED_SPACES = ['TV', 'MUSIC', 'COMICS', 'ADULT'] as const satisfies readonly Space[];

export const isVideoSpace = (s: Space): boolean => (VIDEO_SPACES as readonly Space[]).includes(s);

export type RowStyle = 'POSTER' | 'LANDSCAPE' | 'TOP10' | 'CONTINUE' | 'CIRCLE' | 'CHANNEL';
export type ItemType = 'MOVIE' | 'SERIES' | 'CHANNEL' | 'EVENT' | 'ALBUM' | 'TRACK' | 'COMIC' | 'VIDEO';
export type SourceKind = 'HLS' | 'DASH' | 'MP4' | 'MKV' | 'WEBM' | 'RTSP' | 'SS' | 'MP3';

export interface Row {
  id: string;
  title: string;
  style: RowStyle;
  itemIds: string[];
  /** Remote paged row (gateway): next page is fetched when the row scrolls to its end. */
  more?: RowMore;
}

export interface RowMore {
  /** Gateway list path, e.g. '/movies'. */
  path: string;
  params: Record<string, string>;
  /** Last loaded page. */
  page: number;
  hasMore: boolean;
}

export interface Category {
  id: string;
  space: Space;
  title: string;
  heroIds: string[];
  rows: Row[];
}

export interface Source {
  url: string;
  kind: SourceKind;
  label?: string;
  headers?: Record<string, string>;
  /** Vertical resolution (gateway variants) — drives the quality menu. */
  height?: number;
  /** Original CDN URL + required headers (desktop native downloader can send them; browsers use `url` = proxy). */
  direct?: { url: string; headers: Record<string, string> };
}

export interface Sub {
  lang: string;
  label: string;
  url: string;
  format: 'VTT' | 'SRT' | 'ASS';
}

export interface Episode {
  id: string;
  n: number;
  title: string;
  synopsis?: string;
  durationSec: number;
  still: string;
  sources: Source[];
  subtitles: Sub[];
  introStartSec?: number;
  introEndSec?: number;
}

export interface Season {
  n: number;
  title?: string;
  episodes: Episode[];
  /** Remote (TMDB) seasons load episodes lazily; this is the known count before they arrive. */
  episodeCount?: number;
}

export interface Chapter {
  id: string;
  n: number;
  title: string;
  pages: string[];
  /** Remote chapters (MangaDex) know the page count before the pages are resolved. */
  pageCount?: number;
}

export interface Program {
  id: string;
  title: string;
  genre?: string;
  synopsis?: string;
  startOffsetSec: number;
  durationSec: number;
}

export interface Team {
  name: string;
  short: string;
  color: string;
  score?: number | null;
}

export interface Item {
  id: string;
  space: Space;
  categoryId: string;
  type: ItemType;
  title: string;
  altTitle?: string;
  year: number;
  rating: number;
  maturity: string;
  genres: string[];
  synopsis: string;
  poster: string;
  backdrop: string;
  logo?: string;
  dominantColor: string;
  accentColor: string;
  durationSec?: number;
  seasons?: Season[];
  sources?: Source[];
  subtitles?: Sub[];
  introStartSec?: number;
  introEndSec?: number;
  /** COMIC: chapters, each with ordered page images. */
  pages?: Chapter[];
  format?: 'PAGED' | 'LONGSTRIP';
  readingDirection?: 'RTL' | 'LTR' | 'TTB';
  live?: boolean;
  channelNumber?: number;
  channelGroup?: string;
  programs?: Program[];
  league?: string;
  status?: 'LIVE' | 'UPCOMING' | 'FINISHED';
  startOffsetSec?: number;
  homeTeam?: Team;
  awayTeam?: Team;
  artist?: string;
  album?: string;
  albumId?: string;
  trackNumber?: number;
  trackIds?: string[];
  cast?: string[];
  studio?: string;
  /** Gateway detail path (`/detail/{slug}`, `detail_path=` on stream calls). */
  slug?: string;
  country?: string;
}

export interface CatalogFile {
  space: Space;
  version?: number;
  genres?: string[];
  epgAnchor?: number;
  assets?: { avatars?: string[] };
  categories: Category[];
  items: Item[];
}

export const SPACE_FILE: Record<Space, string> = {
  HOME: 'home',
  ANIME: 'anime',
  DRAMA: 'drama',
  TV: 'tv',
  MUSIC: 'music',
  COMICS: 'comics',
  ADULT: 'adult'
};

/** URL slug used in routes (/app/<slug>). HOME lives at /app. */
export const SPACE_SLUG = SPACE_FILE;

export function spaceFromSlug(slug: string): Space | undefined {
  return (Object.keys(SPACE_FILE) as Space[]).find((s) => SPACE_FILE[s] === slug);
}
