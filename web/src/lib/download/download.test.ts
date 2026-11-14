import { describe, expect, it } from 'vitest';
import { allDone, backoffMs, chunkDone, doneBytes, estimateSize, eta, formatBytes, rangeHeader, resumeFrom, splitRanges, SpeedMeter, percent } from './math';
import { DownloadQueue, type QueueStatus } from './queue';
import { memoryPieceStore, runProgressive, assemble, reconcile, type ProgressiveState } from './progressive';
import { selectTracks, summarize, type ShakaTrackLite } from './adaptive';
import { tabOf, visibleDownloads } from './types';

describe('range split', () => {
  it('chunked_splitsRangesEvenly', () => {
    const c = splitRanges(10 * 1024 * 1024, 4);
    expect(c).toHaveLength(4);
    expect(c[0].start).toBe(0);
    expect(c[3].end).toBe(10 * 1024 * 1024 - 1);
    for (let i = 1; i < c.length; i++) expect(c[i].start).toBe(c[i - 1].end + 1);
    const sizes = c.map((x) => x.end - x.start + 1);
    expect(Math.max(...sizes) - Math.min(...sizes)).toBeLessThanOrEqual(1);
  });
  it('caps connections at 1–8 and by min chunk size', () => {
    expect(splitRanges(100 * 1024 * 1024, 20)).toHaveLength(8);
    expect(splitRanges(100 * 1024 * 1024, 0)).toHaveLength(1);
    expect(splitRanges(1500 * 1024, 8)).toHaveLength(2); // 1.5 MiB / 1 MiB min chunk
    expect(splitRanges(10, 8)).toEqual([{ start: 0, end: 9, pos: 0 }]);
  });
  it('unknown length → single open range', () => {
    const c = splitRanges(0, 4);
    expect(c).toEqual([{ start: 0, end: -1, pos: 0 }]);
    expect(rangeHeader({ start: 0, end: -1, pos: 500 })).toBe('bytes=500-');
  });
  it('chunked_resumesFromPersistedOffsets', () => {
    const c = splitRanges(4000, 2, 1000);
    c[0].pos = 1500;
    c[1].pos = 3999;
    expect(doneBytes(c)).toBe(1500 + 1999);
    expect(rangeHeader(c[0])).toBe('bytes=1500-1999');
    // committed less than persisted (crash) → min wins; committed more → persisted wins
    const r = resumeFrom(c, (i) => (i === 0 ? 1200 : 5000));
    expect(r[0].pos).toBe(1200);
    expect(r[1].pos).toBe(3999);
    c[1].pos = 4000;
    expect(chunkDone(c[1])).toBe(true);
    expect(allDone(c)).toBe(false);
  });
});

describe('size + speed', () => {
  it('sizeEstimate_fromBitrateAndDuration', () => {
    // 4 Mbps × 600 s = 300 MB (+2%)
    expect(estimateSize(4_000_000, 600)).toBe(Math.round(300_000_000 * 1.02));
    expect(estimateSize(0, 600)).toBe(0);
    expect(estimateSize(1000, 0)).toBe(0);
  });
  it('speed meter: current window, average over active time, 60 s sparkline', () => {
    const m = new SpeedMeter();
    const t0 = 1_000_000;
    for (let s = 0; s < 10; s++) m.add(1_000_000, t0 + s * 1000);
    // last 3 buckets (one partial) ≈ 1 MB/s
    expect(m.current(t0 + 9 * 1000 + 999)).toBeCloseTo(1_000_000, -4);
    expect(m.average()).toBeCloseTo(10_000_000 / 9, -2);
    const sp = m.sparkline(t0 + 9000);
    expect(sp).toHaveLength(60);
    expect(sp.slice(-10).every((v) => v === 1_000_000)).toBe(true);
    // pause gap > 5 s does not count as active time
    m.add(1_000_000, t0 + 60_000);
    expect(m.average()).toBeCloseTo(11_000_000 / 9, -2);
    expect(m.current(t0 + 200_000)).toBe(0);
  });
  it('eta + percent + format', () => {
    expect(eta(10_000_000, 1_000_000)).toBe(10);
    expect(eta(100, 0)).toBeNull();
    expect(percent(50, 200)).toBe(25);
    expect(percent(5, 0)).toBe(0);
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(0)).toBe('0 B');
    expect(backoffMs(0)).toBe(2000);
    expect(backoffMs(2)).toBe(8000);
    expect(backoffMs(20)).toBe(60_000);
  });
});

const tick = () => new Promise((r) => setTimeout(r, 0));

describe('queue', () => {
  function setup(max = 2) {
    const gates = new Map<string, { resolve: () => void; reject: (e: unknown) => void }>();
    const started: string[] = [];
    const log: [string, QueueStatus][] = [];
    const q = new DownloadQueue(
      (id, signal) =>
        new Promise<void>((resolve, reject) => {
          started.push(id);
          gates.set(id, { resolve, reject });
          signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
        }),
      { onStatus: (id, s) => log.push([id, s]) },
      max
    );
    return { q, gates, started, log };
  }

  it('queue_respectsMaxConcurrent', async () => {
    const { q, gates, started } = setup(2);
    ['a', 'b', 'c', 'd'].forEach((id) => q.enqueue(id));
    expect(started).toEqual(['a', 'b']);
    expect(q.runningIds).toHaveLength(2);
    expect(q.status.get('c')).toBe('queued');
    gates.get('a')!.resolve();
    await tick();
    expect(q.status.get('a')).toBe('completed');
    expect(started).toEqual(['a', 'b', 'c']);
    expect(q.runningIds.length).toBeLessThanOrEqual(2);
  });

  it('pause / resume / cancel / failure free slots', async () => {
    const { q, gates, started } = setup(2);
    ['a', 'b', 'c'].forEach((id) => q.enqueue(id));
    q.pause('a');
    await tick();
    expect(q.status.get('a')).toBe('paused');
    expect(started).toContain('c');
    q.cancel('c');
    await tick();
    expect(q.status.get('c')).toBe('canceled');
    gates.get('b')!.reject(new Error('boom'));
    await tick();
    expect(q.status.get('b')).toBe('failed');
    q.resume('a');
    await tick();
    expect(q.status.get('a')).toBe('running');
    // cancel a waiting item never starts it
    q.setMax(1);
    q.enqueue('z');
    q.cancel('z');
    expect(q.status.get('z')).toBe('canceled');
    expect(started).not.toContain('z');
  });

  it('prioritize moves waiting item to the front; raising max starts more', async () => {
    const { q, started } = setup(1);
    ['a', 'b', 'c'].forEach((id) => q.enqueue(id));
    q.prioritize('c');
    expect(q.waitingIds).toEqual(['c', 'b']);
    q.setMax(3);
    expect(started).toEqual(['a', 'c', 'b']);
  });
});

/** Fake HTTP server over a byte buffer honouring Range; can cut the stream after N bytes. */
function fakeServer(data: Uint8Array, opts: { ranges?: boolean; cutAfter?: number; slice?: number } = {}) {
  const requests: string[] = [];
  let served = 0;
  const f = (async (_url: string, init?: RequestInit) => {
    const h = (init?.headers ?? {}) as Record<string, string>;
    const signal = init?.signal ?? undefined;
    if (init?.method === 'HEAD') return new Response(null, { status: 200, headers: { 'content-length': String(data.length), 'content-type': 'video/mp4' } });
    requests.push(h.Range ?? 'full');
    let start = 0;
    let end = data.length - 1;
    let status = 200;
    if (h.Range && opts.ranges !== false) {
      const m = h.Range.match(/bytes=(\d+)-(\d*)/)!;
      start = Number(m[1]);
      end = m[2] ? Number(m[2]) : data.length - 1;
      status = 206;
    }
    const body = data.subarray(start, end + 1);
    const step = opts.slice ?? 1000;
    let i = 0;
    const stream = new ReadableStream<Uint8Array>({
      pull(ctl) {
        if (signal?.aborted) return ctl.error(new DOMException('aborted', 'AbortError'));
        if (opts.cutAfter !== undefined && served >= opts.cutAfter) return ctl.error(new TypeError('network down'));
        if (i >= body.length) return ctl.close();
        const part = body.subarray(i, i + step);
        i += part.length;
        served += part.length;
        ctl.enqueue(part);
      }
    });
    return new Response(stream, { status, headers: { 'content-length': String(body.length), ...(status === 206 ? { 'content-range': `bytes ${start}-${end}/${data.length}` } : {}) } });
  }) as unknown as typeof fetch;
  return { f, requests, get served() { return served; } };
}

const bytes = (n: number) => Uint8Array.from({ length: n }, (_, i) => (i * 31 + 7) & 255);
const base = (): ProgressiveState => ({ id: 'x', url: 'https://cdn.test/a.mp4', connections: 4, totalBytes: 0, chunks: [], pieceEnds: [] });

describe('progressive downloader', () => {
  it('downloads with N connections and assembles identical bytes', async () => {
    const data = bytes(50_000);
    const srv = fakeServer(data);
    const store = memoryPieceStore();
    let persisted = 0;
    let received = 0;
    const s = await runProgressive({ ...base(), connections: 4 }, { fetch: srv.f, store, persist: async () => void persisted++, onBytes: (n) => (received += n), commitEvery: 4096 }, new AbortController().signal);
    expect(s.totalBytes).toBe(50_000);
    expect(received).toBe(50_000);
    expect(persisted).toBeGreaterThan(4);
    // 50 KB < 1 MiB min chunk → single connection
    expect(s.chunks).toHaveLength(1);
    const out = new Uint8Array(await (await assemble(store, s, 'video/mp4')).arrayBuffer());
    expect(out).toEqual(data);
  });

  it('chunked_resumesFromPersistedOffsets after a dropped connection', async () => {
    const data = bytes(3 * 1024 * 1024);
    const store = memoryPieceStore();
    const box: { s: ProgressiveState | null } = { s: null };
    const persist = async (s: ProgressiveState) => void (box.s = s);
    const first = fakeServer(data, { cutAfter: 1_200_000, slice: 64 * 1024 });
    await expect(runProgressive({ ...base(), connections: 3 }, { fetch: first.f, store, persist, onBytes: () => {}, commitEvery: 128 * 1024 }, new AbortController().signal)).rejects.toThrow();
    expect(box.s).not.toBeNull();
    const mid = reconcile(box.s!);
    expect(mid.chunks).toHaveLength(3);
    const doneBefore = mid.chunks.reduce((a, c) => a + (c.pos - c.start), 0);
    expect(doneBefore).toBeGreaterThan(0);
    expect(doneBefore).toBeLessThan(data.length);
    const second = fakeServer(data, { slice: 64 * 1024 });
    const fin = await runProgressive(box.s!, { fetch: second.f, store, persist, onBytes: () => {}, commitEvery: 128 * 1024 }, new AbortController().signal);
    // resumed requests start at the persisted offsets, not at chunk starts
    const partial = mid.chunks.filter((c) => c.pos > c.start && c.pos <= c.end);
    expect(partial.length).toBeGreaterThan(0);
    for (const c of partial) expect(second.requests).toContain(`bytes=${c.pos}-${c.end}`);
    expect(second.served).toBe(data.length - doneBefore);
    const out = new Uint8Array(await (await assemble(store, fin, 'video/mp4')).arrayBuffer());
    expect(out).toEqual(data);
  });

  it('chunked_noAcceptRanges_singleConnection', async () => {
    const data = bytes(2 * 1024 * 1024 + 10);
    const srv = fakeServer(data, { ranges: false, slice: 256 * 1024 });
    const store = memoryPieceStore();
    const s = await runProgressive({ ...base(), connections: 8 }, { fetch: srv.f, store, persist: async () => {}, onBytes: () => {} }, new AbortController().signal);
    expect(s.rangeSupported).toBe(false);
    expect(s.chunks).toHaveLength(1);
    expect(srv.requests.filter((r) => r === 'full')).toHaveLength(1);
    expect(new Uint8Array(await (await assemble(store, s, 'video/mp4')).arrayBuffer())).toEqual(data);
  });

  it('pause (abort) keeps committed bytes', async () => {
    const data = bytes(2 * 1024 * 1024);
    const srv = fakeServer(data, { slice: 32 * 1024 });
    const store = memoryPieceStore();
    const ctl = new AbortController();
    const box: { s: ProgressiveState | null } = { s: null };
    let got = 0;
    const p = runProgressive({ ...base(), connections: 2 }, {
      fetch: srv.f,
      store,
      persist: async (s) => void (box.s = s),
      onBytes: (n) => {
        got += n;
        if (got > 600_000) ctl.abort();
      },
      commitEvery: 64 * 1024
    }, ctl.signal);
    await expect(p).rejects.toThrow();
    const kept = box.s!.chunks.reduce((a, c) => a + (c.pos - c.start), 0);
    expect(kept).toBeGreaterThan(500_000);
  });
});

describe('adaptive helpers', () => {
  const v = (id: number, height: number, bandwidth: number, language = 'en'): ShakaTrackLite => ({ id, type: 'variant', height, bandwidth, language });
  it('one quality per height with size estimate; text languages', () => {
    const s = summarize([v(1, 720, 2_000_000), v(2, 720, 2_500_000), v(3, 360, 600_000), v(4, 720, 2_400_000, 'es')], [{ id: 9, type: 'text', bandwidth: 0, language: 'bn' }], 100);
    expect(s.qualities.map((q) => q.label)).toEqual(['720p', '360p']);
    expect(s.qualities[0].bandwidth).toBe(2_500_000);
    expect(s.qualities[0].estimatedBytes).toBe(estimateSize(2_500_000, 100));
    expect(s.audios.map((a) => a.language)).toEqual(['en', 'es']);
    expect(s.texts.map((t) => t.language)).toEqual(['bn']);
  });
  it('selectTracks matches height, audio and texts across instances', () => {
    const offered = [v(11, 720, 2_000_000), v(12, 720, 2_400_000, 'es'), v(13, 360, 600_000), { id: 20, type: 'text', bandwidth: 0, language: 'bn' }, { id: 21, type: 'text', bandwidth: 0, language: 'en' }];
    const sel = selectTracks(offered, { quality: '720p', height: 720, bandwidth: 2_400_000, audio: 'es', texts: ['bn'] });
    expect(sel.map((t) => t.id)).toEqual([12, 20]);
  });
});

describe('visibility + tabs', () => {
  it('adultDownloads_hiddenWhenLocked', () => {
    const list = [{ space: 'HOME' as const }, { space: 'ADULT' as const }];
    expect(visibleDownloads(list, false)).toHaveLength(1);
    expect(visibleDownloads(list, true)).toHaveLength(2);
  });
  it('tabs', () => {
    expect(tabOf('running')).toBe('active');
    expect(tabOf('paused')).toBe('active');
    expect(tabOf('completed')).toBe('completed');
    expect(tabOf('canceled')).toBe('failed');
  });
});
