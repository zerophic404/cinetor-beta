// HLS/DASH downloads via shaka.offline.Storage (IndexedDB). Browser only.
import { loadShaka } from '../player/engine';
import { estimateSize } from './math';
import type { TrackChoice } from './types';

export interface ShakaTrackLite {
  id: number;
  type: string;
  bandwidth: number;
  height?: number | null;
  width?: number | null;
  language: string;
  label?: string | null;
  codecs?: string | null;
  videoCodec?: string | null;
  audioCodec?: string | null;
  active?: boolean;
}

interface StoredContent {
  offlineUri: string | null;
  size: number;
  duration: number;
}
interface AbortableOp<T> {
  promise: Promise<T>;
  abort(): Promise<void>;
}
interface NetEngine {
  registerResponseFilter(f: (type: number, res: { data: ArrayBuffer | ArrayBufferView }) => void): void;
}
interface ShakaStorage {
  configure(c: object): boolean;
  store(uri: string, meta?: object, mime?: string): AbortableOp<StoredContent>;
  remove(uri: string): Promise<void>;
  destroy(): Promise<void>;
  getNetworkingEngine?(): NetEngine | null;
}
interface ProbePlayer {
  attach(v: HTMLMediaElement): Promise<void>;
  load(uri: string, start?: number | null, mime?: string): Promise<void>;
  configure(c: object): boolean;
  getVariantTracks(): ShakaTrackLite[];
  getTextTracks(): ShakaTrackLite[];
  seekRange(): { start: number; end: number };
  isLive(): boolean;
  destroy(): Promise<void>;
}
interface OfflineNS {
  Player: new () => ProbePlayer;
  offline: { Storage: { new (): ShakaStorage; support(): boolean } };
}

async function ns(): Promise<OfflineNS | null> {
  const sh = (await loadShaka()) as unknown as OfflineNS | null;
  return sh?.offline?.Storage ? sh : null;
}

export async function adaptiveSupported(): Promise<boolean> {
  const sh = await ns();
  try {
    return !!sh && sh.offline.Storage.support();
  } catch {
    return false;
  }
}

export interface QualityOption {
  label: string;
  height: number;
  bandwidth: number;
  codecs: string;
  estimatedBytes: number;
}
export interface AdaptiveProbe {
  durationSec: number;
  live: boolean;
  qualities: QualityOption[];
  audios: { language: string; label: string }[];
  texts: { language: string; label: string }[];
}

/** Tracks + duration from a throwaway shaka player on a detached <video> (not in the DOM). */
export async function probeAdaptive(uri: string, mime: string | undefined, fallbackDuration = 0): Promise<AdaptiveProbe> {
  const sh = await ns();
  if (!sh) throw new Error('Offline storage is not supported in this browser');
  const p = new sh.Player();
  const v = document.createElement('video');
  v.muted = true;
  try {
    await p.attach(v);
    p.configure({ streaming: { bufferingGoal: 1, rebufferingGoal: 0.5, bufferBehind: 0 } });
    await p.load(uri, null, mime);
    const range = p.seekRange();
    const durationSec = Number.isFinite(v.duration) && v.duration > 0 ? v.duration : Math.max(0, range.end - range.start) || fallbackDuration;
    return summarize(p.getVariantTracks(), p.getTextTracks(), durationSec, p.isLive());
  } finally {
    await p.destroy().catch(() => {});
  }
}

export function langLabel(code: string, label?: string | null): string {
  if (label) return label;
  if (!code || code === 'und') return 'Default';
  try {
    return new Intl.DisplayNames(undefined, { type: 'language' }).of(code) ?? code;
  } catch {
    return code;
  }
}

/** Pure: one quality per height (best bandwidth), audio + text language lists, size estimates. */
export function summarize(variants: ShakaTrackLite[], texts: ShakaTrackLite[], durationSec: number, live = false): AdaptiveProbe {
  const byH = new Map<number, ShakaTrackLite>();
  for (const t of variants) {
    const h = t.height ?? 0;
    const cur = byH.get(h);
    if (!cur || t.bandwidth > cur.bandwidth) byH.set(h, t);
  }
  const qualities = [...byH.values()]
    .sort((a, b) => (b.height ?? 0) - (a.height ?? 0) || b.bandwidth - a.bandwidth)
    .map((t) => ({
      label: t.height ? `${t.height}p` : 'Audio only',
      height: t.height ?? 0,
      bandwidth: t.bandwidth,
      codecs: t.codecs ?? [t.videoCodec, t.audioCodec].filter(Boolean).join(', '),
      estimatedBytes: estimateSize(t.bandwidth, durationSec)
    }));
  const audios = new Map<string, { language: string; label: string }>();
  for (const t of variants) if (!audios.has(t.language)) audios.set(t.language, { language: t.language, label: langLabel(t.language, t.label) });
  const tx = new Map<string, { language: string; label: string }>();
  for (const t of texts) if (!tx.has(t.language)) tx.set(t.language, { language: t.language, label: langLabel(t.language, t.label) });
  return { durationSec, live, qualities, audios: [...audios.values()], texts: [...tx.values()] };
}

/** Pure: choose tracks offered by Storage that match the user's choice (ids differ between instances). */
export function selectTracks(offered: ShakaTrackLite[], choice: TrackChoice): ShakaTrackLite[] {
  const variants = offered.filter((t) => t.type === 'variant');
  const texts = offered.filter((t) => t.type === 'text');
  let pool = variants.filter((t) => (choice.height ?? 0) === (t.height ?? 0));
  if (!pool.length) pool = variants;
  if (choice.audio) {
    const a = pool.filter((t) => t.language === choice.audio);
    if (a.length) pool = a;
  }
  const target = choice.bandwidth ?? Infinity;
  pool.sort((a, b) => Math.abs(a.bandwidth - target) - Math.abs(b.bandwidth - target) || b.bandwidth - a.bandwidth);
  const v = pool[0];
  const pickedTexts = texts.filter((t) => choice.texts.includes(t.language));
  return v ? [v, ...pickedTexts] : pickedTexts;
}

export interface AdaptiveRun {
  /** Fraction 0..1 + bytes so far + segments done. */
  onProgress(fraction: number, bytes: number, segments: number): void;
  onBytes(n: number): void;
}

const SEGMENT = 1; // shaka.net.NetworkingEngine.RequestType.SEGMENT

/** Download into shaka's IndexedDB. Resolves with the offline URI. Abort (pause/cancel) restarts next time. */
export async function storeAdaptive(uri: string, mime: string | undefined, choice: TrackChoice, meta: object, run: AdaptiveRun, signal: AbortSignal): Promise<{ offlineUri: string; size: number }> {
  const sh = await ns();
  if (!sh) throw new Error('Offline storage is not supported in this browser');
  const storage = new sh.offline.Storage();
  let bytes = 0;
  let segments = 0;
  let fraction = 0;
  storage.configure({
    offline: {
      numberOfParallelDownloads: 4,
      trackSelectionCallback: async (tracks: ShakaTrackLite[]) => selectTracks(tracks, choice),
      downloadSizeCallback: async () => true,
      progressCallback: (_c: StoredContent, p: number) => {
        fraction = p;
        run.onProgress(fraction, bytes, segments);
      }
    }
  });
  storage.getNetworkingEngine?.()?.registerResponseFilter((type, res) => {
    if (type !== SEGMENT) return;
    const n = res.data instanceof ArrayBuffer ? res.data.byteLength : (res.data?.byteLength ?? 0);
    segments++;
    bytes += n;
    run.onBytes(n);
    run.onProgress(fraction, bytes, segments);
  });
  const op = storage.store(uri, meta, mime);
  const onAbort = () => void op.abort().catch(() => {});
  signal.addEventListener('abort', onAbort);
  try {
    const content = await op.promise;
    if (!content.offlineUri) throw new Error('Storage returned no offline URI');
    return { offlineUri: content.offlineUri, size: content.size || bytes };
  } catch (e) {
    if (signal.aborted) throw new DOMException('aborted', 'AbortError');
    const d = e as { code?: number; message?: string };
    throw new Error(d.code ? `Shaka offline error ${d.code}${d.code === 9001 || d.code === 9002 ? ' (storage quota)' : ''}` : (d.message ?? String(e)));
  } finally {
    signal.removeEventListener('abort', onAbort);
    await storage.destroy().catch(() => {});
  }
}

export async function removeAdaptive(offlineUri: string): Promise<void> {
  const sh = await ns();
  if (!sh) return;
  const storage = new sh.offline.Storage();
  try {
    await storage.remove(offlineUri);
  } finally {
    await storage.destroy().catch(() => {});
  }
}
