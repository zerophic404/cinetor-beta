// Desktop (Tauri) detection + download backend selection — pure, unit-tested (no Tauri imports here).
import type { DownloadKind } from '../download/types';

export type DownloadBackend = 'native' | 'browser';

/** Tauri 2 injects `__TAURI_INTERNALS__` into every webview it hosts. */
export function isTauriEnv(w: unknown = typeof window !== 'undefined' ? window : undefined): boolean {
  return !!w && typeof w === 'object' && '__TAURI_INTERNALS__' in (w as object);
}

/**
 * Where a new download runs:
 *  • desktop app → native Rust engine for progressive files (multi-connection Range) and HLS (segments → one file);
 *  • DASH (no native support) and every browser/PWA → OPFS / shaka offline storage.
 */
export function chooseBackend(o: { tauri: boolean; kind: DownloadKind; container: string }): DownloadBackend {
  if (!o.tauri) return 'browser';
  if (o.kind === 'progressive') return 'native';
  return o.container.toUpperCase() === 'HLS' ? 'native' : 'browser';
}

const EXT: Record<string, string> = { MP4: 'mp4', WEBM: 'webm', MP3: 'mp3', MKV: 'mkv', HLS: 'ts', DASH: 'mp4' };

/** File name of a native download inside Downloads/Cinetor (the Rust side sanitizes again). */
export function nativeFileName(r: { title: string; subtitle?: string; container: string; tracks?: { quality?: string } }): string {
  const q = r.tracks?.quality && r.tracks.quality !== 'Original' ? ` [${r.tracks.quality}]` : '';
  const base = `${r.title}${r.subtitle ? ` - ${r.subtitle}` : ''}${q}`
    .replace(/[\\/:*?"<>|\u0000-\u001f]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 110) || 'download';
  return `${base}.${EXT[r.container.toUpperCase()] ?? 'bin'}`;
}

export const MEDIA_TOKEN = '__CINETOR_MEDIA__';

/** Native HLS downloads ship a byte-range playlist with a placeholder URI → point it at the local file URL. */
export function localPlaylist(text: string, mediaUrl: string): string {
  return text.split(MEDIA_TOKEN).join(mediaUrl);
}

/** Map a native progress event onto the record fields the Downloads UI reads. */
export interface NativeProgress {
  id: string;
  doneBytes: number;
  totalBytes: number;
  speed: number;
  chunks: { start: number; end: number; pos: number }[];
  rangeSupported: boolean;
  segmentsDone: number;
  segmentsTotal: number;
}

export function applyNativeProgress<R extends { doneBytes: number; totalBytes: number; chunks: { start: number; end: number; pos: number }[]; rangeSupported?: boolean; segmentsDone?: number; segmentsTotal?: number; fraction?: number }>(
  r: R,
  p: NativeProgress
): number {
  const delta = Math.max(0, p.doneBytes - r.doneBytes);
  r.doneBytes = p.doneBytes;
  if (p.totalBytes > 0) r.totalBytes = p.totalBytes;
  if (p.chunks.length) r.chunks = p.chunks.map((c) => ({ ...c }));
  r.rangeSupported = p.rangeSupported;
  if (p.segmentsTotal > 0) {
    r.segmentsDone = p.segmentsDone;
    r.segmentsTotal = p.segmentsTotal;
    r.fraction = p.segmentsDone / p.segmentsTotal;
  } else if (r.totalBytes > 0) r.fraction = r.doneBytes / r.totalBytes;
  return delta;
}
