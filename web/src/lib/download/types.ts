// Download records (persisted in IndexedDB) + options.
import type { Space } from '../data/types';
import type { Chunk } from './math';
import type { QueueStatus } from './queue';

export type DownloadKind = 'progressive' | 'adaptive';

export interface TrackChoice {
  /** Variant / quality label e.g. "720p" or "Original". */
  quality: string;
  height?: number;
  bandwidth?: number;
  codecs?: string;
  audio?: string;
  texts: string[];
}

export interface DownloadRecord {
  /** = media key (`itemId` or `itemId#episodeId`). */
  id: string;
  itemId: string;
  episodeId?: string;
  space: Space;
  title: string;
  subtitle?: string;
  poster?: string;
  kind: DownloadKind;
  url: string;
  host: string;
  /** Container / MIME label (MP4, WebM, MP3, HLS, DASH). */
  container: string;
  mime: string;
  tracks: TrackChoice;
  status: QueueStatus;
  totalBytes: number;
  doneBytes: number;
  /** Size shown in the options sheet before start. */
  estimatedBytes: number;
  durationSec?: number;
  // progressive
  connections: number;
  chunks: Chunk[];
  rangeSupported?: boolean;
  /** 'opfs' part files or 'idb' blob pieces (Safari fallback). */
  storage?: 'opfs' | 'idb';
  /** idb fallback: committed piece end offsets per chunk. */
  pieceEnds?: number[][];
  // adaptive
  offlineUri?: string;
  fraction?: number;
  segmentsTotal?: number;
  segmentsDone?: number;
  // gateway (signed links expire → re-resolved before each run when older than 15 min)
  /** Lazy placeholder ('gw-stream:…') the URL was resolved from. */
  lazyUrl?: string;
  resolvedAt?: number;
  /** CDN URL + headers (desktop native downloader sends them; falls back to `url` = proxy). */
  directUrl?: string;
  directHeaders?: Record<string, string>;
  // desktop (Tauri) native engine
  /** 'native' = Rust downloader into Downloads/Cinetor; undefined/'browser' = OPFS / shaka offline. */
  backend?: 'native' | 'browser';
  /** Finished native file (absolute path). */
  nativePath?: string;
  /** Native HLS: local byte-range playlist next to the file. */
  nativePlaylist?: string;
  // bookkeeping
  createdAt: number;
  startedAt?: number;
  finishedAt?: number;
  /** Active time accumulated over sessions (ms). */
  elapsedMs: number;
  retries: number;
  lastError?: string;
}

export interface ProgressiveOptions {
  connections: number;
}

export const isAdultRecord = (r: Pick<DownloadRecord, 'space'>): boolean => r.space === 'ADULT';

export const ADULT_UNLOCK_KEY = 'cinetor.adultUnlocked';
export function adultUnlocked(): boolean {
  try {
    return typeof localStorage !== 'undefined' && localStorage.getItem(ADULT_UNLOCK_KEY) === '1';
  } catch {
    return false;
  }
}

/** Adult downloads stay hidden unless the adult space is unlocked. */
export const visibleDownloads = <T extends Pick<DownloadRecord, 'space'>>(list: T[], unlocked: boolean): T[] => (unlocked ? list : list.filter((r) => !isAdultRecord(r)));

export type Tab = 'active' | 'completed' | 'failed';
export function tabOf(s: QueueStatus): Tab {
  if (s === 'completed') return 'completed';
  if (s === 'failed' || s === 'canceled') return 'failed';
  return 'active';
}

export function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return '';
  }
}
