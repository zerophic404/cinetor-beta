// Browser download manager singleton: queue + runners + persistence + live stats (Svelte 5 runes).
import type { Episode, Item, Source, SourceKind } from '../data/types';
import type { Media } from '../player/sources';
import { removeAdaptive, storeAdaptive } from './adaptive';
import { backoffMs, clampConnections, eta, SpeedMeter } from './math';
import { assemble, removePieces, runProgressive, type PieceStore, type ProgressiveState } from './progressive';
import { DEFAULT_MAX_CONCURRENT, DownloadQueue, type QueueStatus } from './queue';
import { idbPieceStore, opfsPieceStore, quota, recordsKV, requestPersist, type Quota } from './store';
import { adultUnlocked, hostOf, visibleDownloads, type DownloadKind, type DownloadRecord, type TrackChoice } from './types';
import type { KV } from '../player/resume';
import { applyNativeProgress, chooseBackend, isTauriEnv, localPlaylist, nativeFileName } from '../desktop/env';

export const MAX_AUTO_RETRIES = 3;
const SETTINGS_KEY = 'cinetor.dl.settings';

export interface LiveStats {
  speed: number;
  avg: number;
  spark: number[];
  eta: number | null;
  elapsedMs: number;
  autoRetryAt?: number;
}

export interface StartRequest {
  media: Media;
  choice: TrackChoice;
  estimatedBytes: number;
  connections?: number;
  durationSec?: number;
}

export const downloadKindOf = (src: Source | undefined): DownloadKind | null => {
  if (!src) return null;
  if (src.kind === 'HLS' || src.kind === 'DASH') return 'adaptive';
  if (src.kind === 'MP4' || src.kind === 'WEBM' || src.kind === 'MP3' || src.kind === 'MKV') return 'progressive';
  return null;
};

/** Live channels / unsupported sources can't be downloaded. */
export const canDownload = (m: Pick<Media, 'item' | 'source'>): boolean => m.item.space !== 'TV' && m.item.type !== 'CHANNEL' && downloadKindOf(m.source) !== null;

const MIME_OF: Record<string, string> = { MP4: 'video/mp4', WEBM: 'video/webm', MP3: 'audio/mpeg', MKV: 'video/x-matroska', HLS: 'application/x-mpegurl', DASH: 'application/dash+xml' };

class Downloads {
  records = $state<Record<string, DownloadRecord>>({});
  live = $state<Record<string, LiveStats>>({});
  quota = $state<Quota>({ usage: 0, quota: 0, persisted: false });
  maxConcurrent = $state(DEFAULT_MAX_CONCURRENT);
  defaultConnections = $state(4);
  unlocked = $state(false);
  loaded = $state(false);

  private work = new Map<string, DownloadRecord>();
  private dirty = new Set<string>();
  private meters = new Map<string, SpeedMeter>();
  private runStart = new Map<string, number>();
  private retryTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private autoRetryAt = new Map<string, number>();
  private blobUrls = new Map<string, string>();
  private kv: KV<DownloadRecord> | null = null;
  private pieces: Promise<PieceStore> | null = null;
  private queue: DownloadQueue;
  private initP: Promise<void> | null = null;

  constructor() {
    this.queue = new DownloadQueue((id, signal) => this.run(id, signal), { onStatus: (id, s, err) => this.onStatus(id, s, err) }, DEFAULT_MAX_CONCURRENT);
  }

  /** Lazy browser init: load records, resume interrupted downloads, start the 1 s stats ticker. */
  init(): Promise<void> {
    if (typeof window === 'undefined') return Promise.resolve();
    return (this.initP ??= (async () => {
      try {
        const s = JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? '{}');
        if (s.maxConcurrent) this.maxConcurrent = Math.min(5, Math.max(1, s.maxConcurrent));
        if (s.connections) this.defaultConnections = clampConnections(s.connections);
      } catch {
        /* ignore */
      }
      this.queue.setMax(this.maxConcurrent);
      this.unlocked = adultUnlocked();
      this.kv = recordsKV();
      const all = (await this.kv?.values()) ?? [];
      const resumeIds: string[] = [];
      for (const r of all) {
        if (r.status === 'running' || r.status === 'queued') {
          r.status = 'paused';
          resumeIds.push(r.id);
        }
        this.work.set(r.id, r);
        this.records[r.id] = { ...r };
      }
      this.loaded = true;
      setInterval(() => this.tick(), 1000);
      void this.refreshQuota();
      // Downloads interrupted by a reload/crash resume from their persisted chunk offsets.
      for (const id of resumeIds.sort((a, b) => (this.work.get(a)!.createdAt ?? 0) - (this.work.get(b)!.createdAt ?? 0))) this.queue.enqueue(id);
    })());
  }

  private pieceStore(): Promise<PieceStore> {
    return (this.pieces ??= opfsPieceStore().then((s) => s ?? idbPieceStore()));
  }

  setMaxConcurrent(n: number): void {
    this.maxConcurrent = Math.min(5, Math.max(1, Math.round(n)));
    this.queue.setMax(this.maxConcurrent);
    this.saveSettings();
  }
  setDefaultConnections(n: number): void {
    this.defaultConnections = clampConnections(n);
    this.saveSettings();
  }
  private saveSettings(): void {
    try {
      localStorage.setItem(SETTINGS_KEY, JSON.stringify({ maxConcurrent: this.maxConcurrent, connections: this.defaultConnections }));
    } catch {
      /* ignore */
    }
  }

  get list(): DownloadRecord[] {
    return visibleDownloads(Object.values(this.records), this.unlocked).sort((a, b) => b.createdAt - a.createdAt);
  }

  get(id: string): DownloadRecord | undefined {
    const r = this.records[id];
    return r && (this.unlocked || r.space !== 'ADULT') ? r : undefined;
  }

  /** Bytes used by Cinetor downloads (completed + partial). */
  get usedBytes(): number {
    return Object.values(this.records).reduce((a, r) => a + (r.doneBytes || 0), 0);
  }

  async refreshQuota(): Promise<void> {
    this.quota = await quota();
  }

  async persistStorage(): Promise<boolean> {
    const ok = await requestPersist();
    await this.refreshQuota();
    return ok;
  }

  // ---------- actions ----------
  async start(req: StartRequest): Promise<void> {
    await this.init();
    const m = req.media;
    const src = m.source;
    const kind = downloadKindOf(src);
    if (!src || !kind) throw new Error('This source cannot be downloaded');
    const existing = this.work.get(m.key);
    if (existing && existing.status !== 'canceled' && existing.status !== 'failed') return;
    if (existing) await this.wipeData(existing);
    void requestPersist();
    const rec: DownloadRecord = {
      id: m.key,
      itemId: m.item.id,
      episodeId: m.episode?.id,
      space: m.item.space,
      title: m.title,
      subtitle: m.subtitle,
      poster: m.episode?.still || m.item.poster,
      kind,
      url: src.url,
      host: hostOf(src.url),
      container: src.kind,
      mime: MIME_OF[src.kind] ?? 'application/octet-stream',
      tracks: $state.snapshot(req.choice) as TrackChoice,
      status: 'queued',
      totalBytes: req.estimatedBytes || 0,
      doneBytes: 0,
      estimatedBytes: req.estimatedBytes || 0,
      durationSec: req.durationSec ?? m.durationSec,
      connections: kind === 'progressive' ? clampConnections(req.connections ?? this.defaultConnections) : 4,
      chunks: [],
      pieceEnds: [],
      backend: chooseBackend({ tauri: isTauriEnv(), kind, container: src.kind }),
      createdAt: Date.now(),
      elapsedMs: 0,
      retries: 0
    };
    this.put(rec, true);
    this.queue.enqueue(rec.id);
  }

  pause(id: string): void {
    this.clearRetry(id);
    this.queue.pause(id);
  }

  resume(id: string): void {
    this.clearRetry(id);
    this.queue.resume(id);
  }

  retry(id: string): void {
    const r = this.work.get(id);
    if (!r) return;
    this.clearRetry(id);
    this.patch(id, { retries: r.retries + 1, lastError: r.lastError });
    this.queue.enqueue(id);
  }

  async cancel(id: string): Promise<void> {
    this.clearRetry(id);
    // Queue reports 'canceled' (immediately, or once the runner stops) → onStatus wipes the data.
    this.queue.cancel(id);
  }

  async remove(id: string): Promise<void> {
    this.clearRetry(id);
    this.queue.cancel(id);
    const r = this.work.get(id);
    if (r) await this.wipeData(r);
    this.work.delete(id);
    delete this.records[id];
    delete this.live[id];
    await this.kv?.del(id);
    void this.refreshQuota();
  }

  prioritize(id: string): void {
    this.queue.prioritize(id);
  }

  /** Media with its source swapped to the offline copy when downloaded (shaka offline URI / OPFS blob URL).
   *  Distinct key so a warm network buffer for the same title is never reused instead of the local file. */
  async offlineMedia(m: Media): Promise<Media> {
    await this.init();
    const r = this.work.get(m.key);
    if (!r || r.status !== 'completed') return m;
    try {
      if (r.backend === 'native' && r.nativePath) {
        const { fileUrl } = await import('../desktop/tauri');
        const fileSrc = await fileUrl(r.nativePath);
        if (r.nativePlaylist) {
          // Native HLS: byte-range playlist over the single concatenated file.
          let url = this.blobUrls.get(r.id);
          if (!url) {
            const { readPlaylist } = await import('../desktop/native-dl');
            const text = localPlaylist(await readPlaylist(r.nativePlaylist), fileSrc);
            url = URL.createObjectURL(new Blob([text], { type: 'application/x-mpegurl' }));
            this.blobUrls.set(r.id, url);
          }
          return { ...m, key: `${m.key}@offline`, source: { url, kind: 'HLS', label: 'Downloaded' }, playable: true };
        }
        return { ...m, key: `${m.key}@offline`, source: { url: fileSrc, kind: (m.source?.kind ?? 'MP4') as Source['kind'], label: 'Downloaded' }, playable: true };
      }
      if (r.kind === 'adaptive' && r.offlineUri) {
        return { ...m, key: `${m.key}@offline`, source: { url: r.offlineUri, kind: r.container === 'DASH' ? 'DASH' : 'HLS', label: 'Downloaded' }, playable: true };
      }
      if (r.kind === 'progressive') {
        let url = this.blobUrls.get(r.id);
        if (!url) {
          const blob = await assemble(await this.pieceStore(), { id: r.id, pieceEnds: r.pieceEnds ?? [] }, r.mime);
          url = URL.createObjectURL(blob);
          this.blobUrls.set(r.id, url);
        }
        return { ...m, key: `${m.key}@offline`, source: { url, kind: (m.source?.kind ?? 'MP4') as Source['kind'], label: 'Downloaded' }, playable: true };
      }
    } catch (e) {
      this.patch(r.id, { lastError: `Offline copy unreadable: ${(e as Error).message}` });
    }
    return m;
  }

  /** Minimal item/episode from a completed download record (offline, when the catalog can't load). */
  async offlineStub(itemId: string, episodeId?: string): Promise<{ item: Item; episode?: Episode } | undefined> {
    await this.init();
    const r = [...this.work.values()].find((x) => x.itemId === itemId && x.status === 'completed' && (!episodeId || x.episodeId === episodeId));
    if (!r) return undefined;
    const src: Source = { url: r.url, kind: r.container as SourceKind };
    const item: Item = {
      id: r.itemId,
      space: r.space,
      categoryId: '',
      type: r.episodeId ? 'SERIES' : 'MOVIE',
      title: r.title,
      year: 0,
      rating: 0,
      maturity: '',
      genres: [],
      synopsis: '',
      poster: r.poster ?? '',
      backdrop: '',
      dominantColor: '#000000',
      accentColor: '#E50914',
      durationSec: r.durationSec,
      sources: r.episodeId ? [] : [src]
    };
    const episode: Episode | undefined = r.episodeId
      ? { id: r.episodeId, n: 0, title: r.subtitle ?? '', durationSec: r.durationSec ?? 0, still: r.poster ?? '', sources: [src], subtitles: [] }
      : undefined;
    return { item, episode };
  }

  isDownloaded(key: string): boolean {
    return this.records[key]?.status === 'completed';
  }

  // ---------- internals ----------
  private put(r: DownloadRecord, persist = false): void {
    this.work.set(r.id, r);
    this.records[r.id] = { ...r };
    if (persist) void this.kv?.set(r.id, $state.snapshot(r) as DownloadRecord);
  }

  private patch(id: string, p: Partial<DownloadRecord>, persist = true): void {
    const r = this.work.get(id);
    if (!r) return;
    Object.assign(r, p);
    this.records[id] = { ...r };
    if (persist) void this.kv?.set(id, { ...r, chunks: r.chunks.map((c) => ({ ...c })) });
  }

  private clearRetry(id: string): void {
    clearTimeout(this.retryTimers.get(id));
    this.retryTimers.delete(id);
    this.autoRetryAt.delete(id);
  }

  private async wipeData(r: DownloadRecord): Promise<void> {
    const url = this.blobUrls.get(r.id);
    if (url) URL.revokeObjectURL(url);
    this.blobUrls.delete(r.id);
    if (r.backend === 'native') {
      const { removeNative } = await import('../desktop/native-dl');
      await removeNative(r.id, nativeFileName(r), r.nativePath).catch(() => {});
      this.patch(r.id, { chunks: [], doneBytes: 0, nativePath: undefined, nativePlaylist: undefined, fraction: 0, segmentsDone: 0 });
      return;
    }
    if (r.kind === 'progressive') await removePieces(await this.pieceStore(), { id: r.id, pieceEnds: r.pieceEnds ?? [] });
    if (r.offlineUri) await removeAdaptive(r.offlineUri).catch(() => {});
    this.patch(r.id, { chunks: [], pieceEnds: [], doneBytes: 0, offlineUri: undefined, fraction: 0 });
  }

  private onStatus(id: string, s: QueueStatus, err?: unknown): void {
    const r = this.work.get(id);
    if (!r) return;
    const now = Date.now();
    const started = this.runStart.get(id);
    const p: Partial<DownloadRecord> = { status: s };
    if (s === 'running') {
      this.runStart.set(id, now);
      p.startedAt = r.startedAt ?? now;
      p.lastError = r.lastError;
    } else if (started !== undefined) {
      p.elapsedMs = r.elapsedMs + (now - started);
      this.runStart.delete(id);
    }
    if (s === 'completed') {
      p.finishedAt = now;
      p.lastError = undefined;
      this.autoRetryAt.delete(id);
      void this.refreshQuota();
    }
    if (s === 'failed') {
      p.lastError = err instanceof Error ? err.message : String(err ?? 'Unknown error');
      // Auto-retry with exponential backoff (2 s, 4 s, 8 s).
      const attempt = r.retries;
      if (attempt < MAX_AUTO_RETRIES) {
        const wait = backoffMs(attempt);
        this.autoRetryAt.set(id, now + wait);
        this.retryTimers.set(
          id,
          setTimeout(() => {
            this.retryTimers.delete(id);
            this.autoRetryAt.delete(id);
            const cur = this.work.get(id);
            if (cur?.status === 'failed') {
              this.patch(id, { retries: cur.retries + 1 });
              this.queue.enqueue(id);
            }
          }, wait)
        );
      }
    }
    if (s === 'canceled') void this.wipeData(r);
    this.patch(id, p);
    this.tick();
  }

  private meter(id: string): SpeedMeter {
    let m = this.meters.get(id);
    if (!m) this.meters.set(id, (m = new SpeedMeter()));
    return m;
  }

  private onBytes(id: string, n: number): void {
    this.meter(id).add(n, Date.now());
    this.dirty.add(id);
  }

  /** 1 s UI refresh: copy working progress into reactive state + live stats. */
  private tick(): void {
    const now = Date.now();
    const live: Record<string, LiveStats> = {};
    for (const [id, r] of this.work) {
      if (this.dirty.has(id)) this.records[id] = { ...r, chunks: r.chunks.map((c) => ({ ...c })) };
      const m = this.meters.get(id);
      const running = r.status === 'running';
      const speed = running && m ? m.current(now) : 0;
      const remaining = Math.max(0, (r.totalBytes || r.estimatedBytes) - r.doneBytes);
      const started = this.runStart.get(id);
      live[id] = {
        speed,
        avg: m?.average() ?? 0,
        spark: m?.sparkline(now) ?? [],
        eta: running ? eta(remaining, speed || m?.average() || 0) : null,
        elapsedMs: r.elapsedMs + (started !== undefined ? now - started : 0),
        autoRetryAt: this.autoRetryAt.get(id)
      };
    }
    this.dirty.clear();
    this.live = live;
  }

  private async run(id: string, signal: AbortSignal): Promise<void> {
    const r = this.work.get(id);
    if (!r) throw new Error('Unknown download');
    if (r.backend === 'native') return this.runNativeJob(r, signal);
    if (r.kind === 'progressive') return this.runProgressiveJob(r, signal);
    return this.runAdaptiveJob(r, signal);
  }

  private async runProgressiveJob(r: DownloadRecord, signal: AbortSignal): Promise<void> {
    const store = await this.pieceStore();
    const sync = (s: ProgressiveState) => {
      const doneBytes = s.chunks.reduce((a, c) => a + Math.max(0, c.pos - c.start), 0);
      r.chunks = s.chunks.map((c) => ({ ...c }));
      r.pieceEnds = s.pieceEnds.map((e) => [...e]);
      r.totalBytes = s.totalBytes || r.totalBytes;
      r.rangeSupported = s.rangeSupported;
      r.doneBytes = doneBytes;
      r.storage = store.kind === 'memory' ? undefined : store.kind;
      this.dirty.add(r.id);
    };
    const done = await runProgressive(
      { id: r.id, url: r.url, connections: r.connections, totalBytes: r.chunks.length ? r.totalBytes : 0, chunks: r.chunks, pieceEnds: r.pieceEnds ?? [], rangeSupported: r.rangeSupported },
      {
        fetch: globalThis.fetch.bind(globalThis),
        store,
        onBytes: (n) => this.onBytes(r.id, n),
        persist: async (s) => {
          sync(s);
          await this.kv?.set(r.id, { ...r, chunks: r.chunks.map((c) => ({ ...c })), pieceEnds: r.pieceEnds?.map((e) => [...e]) });
        }
      },
      signal
    );
    sync(done);
  }

  /** Desktop: Rust engine (multi-connection Range / HLS segments) → Downloads/Cinetor; resumes from its sidecar. */
  private async runNativeJob(r: DownloadRecord, signal: AbortSignal): Promise<void> {
    const { runNative } = await import('../desktop/native-dl');
    let lastPersist = Date.now();
    const done = await runNative(
      {
        id: r.id,
        url: r.url,
        kind: r.kind === 'progressive' ? 'progressive' : 'hls',
        connections: r.kind === 'progressive' ? r.connections : clampConnections(this.defaultConnections),
        fileName: nativeFileName(r),
        bandwidth: r.tracks.bandwidth,
        height: r.tracks.height
      },
      (p) => {
        const delta = applyNativeProgress(r, p);
        if (delta > 0) this.onBytes(r.id, delta);
        this.dirty.add(r.id);
        if (Date.now() - lastPersist > 5000) {
          lastPersist = Date.now();
          void this.kv?.set(r.id, { ...r, chunks: r.chunks.map((c) => ({ ...c })) });
        }
      },
      signal
    );
    this.patch(r.id, { nativePath: done.path, nativePlaylist: done.playlist ?? undefined, totalBytes: done.size || r.totalBytes, doneBytes: done.size || r.doneBytes, fraction: 1 });
  }

  private async runAdaptiveJob(r: DownloadRecord, signal: AbortSignal): Promise<void> {
    // shaka can't resume a partial store: a paused/failed HLS/DASH download restarts its segment pass.
    if (r.offlineUri) await removeAdaptive(r.offlineUri).catch(() => {});
    this.patch(r.id, { doneBytes: 0, fraction: 0, offlineUri: undefined, segmentsTotal: undefined, segmentsDone: 0 });
    const res = await storeAdaptive(
      r.url,
      r.container === 'DASH' ? 'application/dash+xml' : 'application/x-mpegurl',
      $state.snapshot(r.tracks) as TrackChoice,
      { id: r.id, title: r.title, subtitle: r.subtitle ?? '' },
      {
        onBytes: (n) => this.onBytes(r.id, n),
        onProgress: (fraction, bytes, segments) => {
          r.fraction = fraction;
          r.doneBytes = bytes;
          if (fraction > 0.05 && bytes > 0) r.totalBytes = Math.round(bytes / fraction);
          if (fraction > 0.02 && segments > 0) r.segmentsTotal = Math.round(segments / fraction);
          r.segmentsDone = segments;
          this.dirty.add(r.id);
        }
      },
      signal
    );
    this.patch(r.id, { offlineUri: res.offlineUri, totalBytes: res.size || r.doneBytes, doneBytes: res.size || r.doneBytes, fraction: 1 });
  }
}

export const downloads = new Downloads();
