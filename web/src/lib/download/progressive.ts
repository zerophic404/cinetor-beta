// Progressive (MP4/WebM/MP3) downloader: multi-connection HTTP Range fetch → committed pieces
// (OPFS files or IndexedDB blobs) with resume from persisted chunk offsets. Deps injected → unit tested.
import { allDone, doneBytes, rangeHeader, resumeFrom, splitRanges, type Chunk } from './math';

/** Write-once blob pieces: key = (download id, chunk index, piece seq). */
export interface PieceStore {
  readonly kind: 'opfs' | 'idb' | 'memory';
  put(id: string, chunk: number, seq: number, data: Blob): Promise<void>;
  get(id: string, chunk: number, seq: number): Promise<Blob | undefined>;
  del(id: string, chunk: number, seq: number): Promise<void>;
}

export interface ProgressiveState {
  id: string;
  url: string;
  connections: number;
  totalBytes: number;
  chunks: Chunk[];
  pieceEnds: number[][];
  rangeSupported?: boolean;
  headers?: Record<string, string>;
}

export interface ProgressiveDeps {
  fetch: typeof fetch;
  store: PieceStore;
  /** Persist chunk offsets + piece list (called after every committed piece). */
  persist(s: ProgressiveState): Promise<void>;
  /** Raw bytes received (for speed meter). */
  onBytes(n: number): void;
  /** Bytes buffered per connection before committing a piece. */
  commitEvery?: number;
}

export class HttpError extends Error {
  constructor(
    public status: number,
    msg: string
  ) {
    super(msg);
  }
}

export const DEFAULT_COMMIT = 4 * 1024 * 1024;

/** HEAD for length, then a 1-byte Range GET: 206 ⇒ ranges supported. CORS-safe (status + Content-Length only). */
export async function probe(f: typeof fetch, url: string, signal?: AbortSignal, headers?: Record<string, string>): Promise<{ total: number; ranges: boolean; mime: string }> {
  let total = 0;
  let mime = '';
  try {
    const h = await f(url, { method: 'HEAD', signal, headers });
    if (h.ok) {
      total = Number(h.headers.get('content-length') ?? 0) || 0;
      mime = h.headers.get('content-type') ?? '';
    }
  } catch (e) {
    if (signal?.aborted) throw e;
  }
  let ranges = false;
  try {
    const r = await f(url, { headers: { ...headers, Range: 'bytes=0-0' }, signal });
    ranges = r.status === 206;
    if (ranges && !total) {
      const cr = r.headers.get('content-range');
      const m = cr?.match(/\/(\d+)\s*$/);
      if (m) total = Number(m[1]);
    }
    if (!mime) mime = r.headers.get('content-type') ?? '';
    if (!ranges && !total) total = Number(r.headers.get('content-length') ?? 0) || 0;
    await r.body?.cancel().catch(() => {});
  } catch (e) {
    if (signal?.aborted) throw e;
    throw new Error(`Can't reach source (network or CORS): ${(e as Error).message ?? e}`);
  }
  return { total, ranges: ranges && total > 0, mime };
}

/** Prepare chunk plan (first start) or reconcile persisted offsets with committed pieces (resume). */
export function plan(s: ProgressiveState, total: number, ranges: boolean): ProgressiveState {
  const chunks = splitRanges(total, ranges ? s.connections : 1);
  return { ...s, totalBytes: total, rangeSupported: ranges, chunks, pieceEnds: chunks.map(() => []) };
}

export function reconcile(s: ProgressiveState): ProgressiveState {
  const pieceEnds = s.chunks.map((_, i) => [...(s.pieceEnds[i] ?? [])]);
  const chunks = resumeFrom(s.chunks, (i) => {
    const ends = pieceEnds[i];
    return ends.length ? ends[ends.length - 1] - s.chunks[i].start : 0;
  });
  // Drop pieces beyond the reconciled offset (written but never persisted).
  chunks.forEach((c, i) => {
    pieceEnds[i] = pieceEnds[i].filter((e) => e <= c.pos);
  });
  return { ...s, chunks, pieceEnds };
}

/** Run (or resume) a progressive download until every chunk is complete. Rejects with AbortError on pause/cancel. */
export async function runProgressive(initial: ProgressiveState, deps: ProgressiveDeps, signal: AbortSignal): Promise<ProgressiveState> {
  let s = initial;
  if (!s.chunks.length) {
    const p = await probe(deps.fetch, s.url, signal, s.headers);
    s = plan(s, p.total, p.ranges);
    await deps.persist(s);
  } else {
    s = reconcile(s);
    // A server without Range support can't resume mid-file: restart from 0.
    if (s.rangeSupported === false && s.chunks.some((c) => c.pos > c.start && !(c.end >= 0 && c.pos > c.end))) {
      s = plan(s, s.totalBytes, false);
    }
  }
  const commitEvery = deps.commitEvery ?? DEFAULT_COMMIT;
  // One shared state object mutated by all connections; persist snapshots are serialized.
  const live = s;
  let persistChain = Promise.resolve();
  const persist = () => (persistChain = persistChain.then(() => deps.persist({ ...live, chunks: live.chunks.map((c) => ({ ...c })), pieceEnds: live.pieceEnds.map((e) => [...e]) })));

  const runChunk = async (i: number): Promise<void> => {
    const c = live.chunks[i];
    if (c.end >= 0 && c.pos > c.end) return;
    const headers: Record<string, string> = { ...live.headers };
    const ranged = live.rangeSupported !== false && (live.chunks.length > 1 || c.pos > 0);
    if (ranged) headers.Range = rangeHeader(c);
    const res = await deps.fetch(live.url, { headers, signal });
    if (ranged && res.status === 200) {
      await res.body?.cancel().catch(() => {});
      live.rangeSupported = false;
      throw new HttpError(200, 'Server ignored Range request — will retry with one connection');
    }
    if (!res.ok) throw new HttpError(res.status, `HTTP ${res.status}`);
    if (!res.body) throw new Error('Empty response body');
    const reader = res.body.getReader();
    let buf: Uint8Array[] = [];
    let bufLen = 0;
    const commit = async () => {
      if (!bufLen) return;
      const ends = live.pieceEnds[i];
      const blob = new Blob(buf as BlobPart[]);
      const end = c.pos + bufLen;
      buf = [];
      bufLen = 0;
      await deps.store.put(live.id, i, ends.length, blob);
      ends.push(end);
      c.pos = end;
      await persist();
    };
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        if (!value?.length) continue;
        let v = value;
        // Never write past the chunk end (some servers over-send).
        if (c.end >= 0) {
          const room = c.end + 1 - (c.pos + bufLen);
          if (room <= 0) break;
          if (v.length > room) v = v.subarray(0, room);
        }
        buf.push(v);
        bufLen += v.length;
        deps.onBytes(v.length);
        if (bufLen >= commitEvery) await commit();
      }
      await commit();
      if (c.end < 0) {
        // Unknown length: the stream end defines the size.
        c.end = c.pos - 1;
        live.totalBytes = c.pos;
        await persist();
      } else if (c.pos <= c.end) {
        throw new Error(`Connection closed early (${c.end + 1 - c.pos} bytes missing)`);
      }
    } catch (e) {
      // Keep whatever arrived (pause = resume later from here).
      await reader.cancel().catch(() => {});
      await commit().catch(() => {});
      throw e;
    }
  };

  const results = await Promise.allSettled(live.chunks.map((_, i) => runChunk(i)));
  await persistChain;
  if (signal.aborted) throw new DOMException('aborted', 'AbortError');
  const failed = results.find((r) => r.status === 'rejected') as PromiseRejectedResult | undefined;
  if (failed) {
    if (live.rangeSupported === false && live.chunks.length > 1) {
      const reset = plan(live, live.totalBytes, false);
      await deps.persist(reset);
    }
    throw failed.reason;
  }
  if (!allDone(live.chunks)) throw new Error('Incomplete download');
  if (live.totalBytes && doneBytes(live.chunks) !== live.totalBytes) throw new Error(`Size mismatch: ${doneBytes(live.chunks)} / ${live.totalBytes}`);
  return { ...live };
}

/** Assemble the finished file (lazy, disk-backed pieces → no copy). */
export async function assemble(store: PieceStore, s: Pick<ProgressiveState, 'id' | 'pieceEnds'>, mime: string): Promise<Blob> {
  const parts: Blob[] = [];
  for (let i = 0; i < s.pieceEnds.length; i++) {
    for (let k = 0; k < s.pieceEnds[i].length; k++) {
      const b = await store.get(s.id, i, k);
      if (!b) throw new Error(`Missing piece ${i}.${k} — storage was cleared`);
      parts.push(b);
    }
  }
  return new Blob(parts, { type: mime });
}

export async function removePieces(store: PieceStore, s: Pick<ProgressiveState, 'id' | 'pieceEnds'>): Promise<void> {
  for (let i = 0; i < s.pieceEnds.length; i++) for (let k = 0; k < s.pieceEnds[i].length; k++) await store.del(s.id, i, k).catch(() => {});
}

export function memoryPieceStore(): PieceStore & { map: Map<string, Blob> } {
  const map = new Map<string, Blob>();
  const k = (id: string, c: number, s: number) => `${id}|${c}|${s}`;
  return {
    kind: 'memory',
    map,
    put: async (id, c, s, d) => void map.set(k(id, c, s), d),
    get: async (id, c, s) => map.get(k(id, c, s)),
    del: async (id, c, s) => void map.delete(k(id, c, s))
  };
}
