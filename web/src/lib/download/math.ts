// Pure download math: range splitting/resume, size estimates, speed meter, ETA, formatting.

/** One HTTP Range slice of a progressive file. `end` inclusive; `pos` = next byte to fetch. end = -1 → unknown length (single open range). */
export interface Chunk {
  start: number;
  end: number;
  pos: number;
}

export const MIN_CHUNK = 1024 * 1024; // 1 MiB
export const MAX_CONNECTIONS = 8;

export const clampConnections = (n: number): number => Math.min(MAX_CONNECTIONS, Math.max(1, Math.round(n || 1)));

/** Split [0,total) into ≤ n contiguous chunks (each ≥ minChunk except when total is small). Unknown total → one open chunk. */
export function splitRanges(total: number, n: number, minChunk = MIN_CHUNK): Chunk[] {
  if (!(total > 0)) return [{ start: 0, end: -1, pos: 0 }];
  const count = Math.max(1, Math.min(clampConnections(n), Math.ceil(total / Math.max(1, minChunk))));
  const base = Math.floor(total / count);
  const rem = total % count;
  const out: Chunk[] = [];
  let start = 0;
  for (let i = 0; i < count; i++) {
    const len = base + (i < rem ? 1 : 0);
    out.push({ start, end: start + len - 1, pos: start });
    start += len;
  }
  return out;
}

export const chunkDone = (c: Chunk): boolean => c.end >= 0 && c.pos > c.end;
export const chunkSize = (c: Chunk): number => (c.end < 0 ? 0 : c.end - c.start + 1);
export const chunkBytes = (c: Chunk): number => Math.max(0, c.pos - c.start);
export const doneBytes = (chunks: Chunk[]): number => chunks.reduce((a, c) => a + chunkBytes(c), 0);
export const remainingChunks = (chunks: Chunk[]): Chunk[] => chunks.filter((c) => !chunkDone(c));
export const allDone = (chunks: Chunk[]): boolean => chunks.length > 0 && chunks.every(chunkDone);

/** Range header resuming a chunk from its persisted offset. */
export const rangeHeader = (c: Chunk): string => (c.end < 0 ? `bytes=${c.pos}-` : `bytes=${c.pos}-${c.end}`);

/**
 * Reconcile persisted offsets with what storage actually committed (crash between write + persist).
 * pos = min(persisted, start + committed), never below start.
 */
export function resumeFrom(persisted: Chunk[], committed: (index: number) => number): Chunk[] {
  return persisted.map((c, i) => {
    const onDisk = c.start + Math.max(0, committed(i));
    const pos = Math.max(c.start, Math.min(c.pos, onDisk));
    return { ...c, pos };
  });
}

/** Estimated bytes for a stream: bits/s × seconds / 8 (+ ~2% container overhead). */
export function estimateSize(bandwidthBps: number, durationSec: number, overhead = 0.02): number {
  if (!(bandwidthBps > 0) || !(durationSec > 0)) return 0;
  return Math.round(((bandwidthBps * durationSec) / 8) * (1 + overhead));
}

/** Rolling speed meter: per-second buckets for current speed + 60 s sparkline; average over active time. */
export class SpeedMeter {
  private buckets = new Map<number, number>();
  total = 0;
  private firstAt: number | null = null;
  private activeMs = 0;
  private lastAt: number | null = null;

  constructor(private windowSec = 3, private historySec = 60) {}

  add(bytes: number, nowMs: number): void {
    if (this.firstAt === null) this.firstAt = nowMs;
    if (this.lastAt !== null) {
      const gap = nowMs - this.lastAt;
      // Gaps > 5 s (paused / stalled) don't count as active time.
      if (gap > 0 && gap < 5000) this.activeMs += gap;
    }
    this.lastAt = nowMs;
    this.total += bytes;
    const s = Math.floor(nowMs / 1000);
    this.buckets.set(s, (this.buckets.get(s) ?? 0) + bytes);
    const cutoff = s - this.historySec - 1;
    for (const k of this.buckets.keys()) if (k < cutoff) this.buckets.delete(k);
  }

  /** Bytes/s over the last `windowSec` full-ish seconds. */
  current(nowMs: number): number {
    const s = Math.floor(nowMs / 1000);
    let sum = 0;
    for (let i = 0; i < this.windowSec; i++) sum += this.buckets.get(s - i) ?? 0;
    // Current second is partial: weight the window by elapsed fraction.
    const frac = (nowMs % 1000) / 1000;
    const span = this.windowSec - 1 + Math.max(frac, 0.25);
    return sum / span;
  }

  /** Average bytes/s across active (non-paused) time. */
  average(): number {
    const sec = this.activeMs / 1000;
    return sec >= 0.5 ? this.total / sec : 0;
  }

  /** Bytes/s for each of the last `historySec` seconds (oldest first). */
  sparkline(nowMs: number): number[] {
    const s = Math.floor(nowMs / 1000);
    const out: number[] = [];
    for (let i = this.historySec - 1; i >= 0; i--) out.push(this.buckets.get(s - i) ?? 0);
    return out;
  }
}

/** Seconds remaining, or null when unknown (no speed / no total). */
export function eta(remainingBytes: number, bytesPerSec: number): number | null {
  if (!(remainingBytes >= 0) || !(bytesPerSec > 0)) return null;
  return Math.ceil(remainingBytes / bytesPerSec);
}

export const percent = (done: number, total: number): number => (total > 0 ? Math.min(100, Math.max(0, (done / total) * 100)) : 0);

export function formatBytes(b: number): string {
  if (!(b > 0)) return '0 B';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(u.length - 1, Math.floor(Math.log(b) / Math.log(1024)));
  const v = b / 1024 ** i;
  return `${v >= 100 || i === 0 ? Math.round(v) : v.toFixed(1)} ${u[i]}`;
}

export const formatSpeed = (bps: number): string => `${formatBytes(bps)}/s`;

export function formatDuration(sec: number | null | undefined): string {
  if (sec === null || sec === undefined || !Number.isFinite(sec) || sec < 0) return '—';
  const s = Math.round(sec);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  if (h) return `${h}h ${m}m`;
  if (m) return `${m}m ${String(r).padStart(2, '0')}s`;
  return `${r}s`;
}

/** Exponential backoff for retries: 2 s, 4 s, 8 s … capped. */
export const backoffMs = (attempt: number, baseMs = 2000, capMs = 60_000): number => Math.min(capMs, baseMs * 2 ** Math.max(0, attempt));

// ------------------------------------------------------------------ option de-duplication ----
// docs/requirements-hardening.md §4: group by quality; an identical resolution + format + size must
// appear ONCE, and the kept entry is the primary / fastest source for that option.

/** Shape shared by progressive variants and adaptive quality levels. */
export interface QualityLike {
  height?: number;
  label?: string;
  kind?: string;
  container?: string;
  bytes?: number;
  estimatedBytes?: number;
  bandwidth?: number;
  /** Set by the catalog on the title's own / default source. */
  primary?: boolean;
}

/** Size bucket in MB: two sources differing by <1 MB are the same option to a user. */
const sizeOf = (o: QualityLike): number => o.bytes ?? o.estimatedBytes ?? 0;

/**
 * Identity of a download option as the *user* sees it: resolution (or label), container/format and
 * size. Deliberately excludes bandwidth and URL — the same file offered by two sources is one option.
 */
export function optionKey(o: QualityLike): string {
  const size = sizeOf(o);
  const res = o.height ? `${o.height}p` : (o.label ?? '').trim().toLowerCase();
  const fmt = (o.kind ?? o.container ?? '').toUpperCase();
  return `${res}|${fmt}|${size ? Math.round(size / 1_000_000) : ''}`;
}

/** Higher = preferred when two entries are the same option (primary first, then fastest). */
const rank = (o: QualityLike): number => (o.primary ? 1 : 0) * 1e15 + (o.bandwidth ?? 0);

/**
 * De-duplicate quality/download options. Identical resolution + format + size collapse into one
 * entry — the primary (else fastest) source — keeping the position of the first occurrence so the
 * list stays ordered best-first.
 */
export function dedupeOptions<T extends QualityLike>(list: readonly T[]): T[] {
  const at = new Map<string, number>();
  const out: T[] = [];
  for (const o of list) {
    const key = optionKey(o);
    const i = at.get(key);
    if (i === undefined) {
      at.set(key, out.length);
      out.push(o);
    } else if (rank(o) > rank(out[i])) {
      out[i] = o; // same option, better source → keep the better one in place
    }
  }
  return out;
}

export interface QualityGroup<T> {
  /** Resolution height when known (0 = unknown / audio-only / labelled option). */
  height: number;
  /** Human label for the group, e.g. "1080p" or the option's own label. */
  label: string;
  options: T[];
}

/**
 * Group de-duplicated options by quality, best first. Within a group the formats stay in their
 * original (primary-first) order, so the UI can show "1080p · MP4 / MKV" under one heading.
 */
export function groupByQuality<T extends QualityLike>(list: readonly T[]): QualityGroup<T>[] {
  const groups = new Map<string, QualityGroup<T>>();
  for (const o of dedupeOptions(list)) {
    const height = o.height && Number.isFinite(o.height) ? o.height : 0;
    const label = height ? `${height}p` : (o.label ?? 'Original').trim() || 'Original';
    const g = groups.get(label);
    if (g) g.options.push(o);
    else groups.set(label, { height, label, options: [o] });
  }
  return [...groups.values()].sort((a, b) => b.height - a.height || a.label.localeCompare(b.label));
}

/**
 * The exact list the download UI shows: de-duplicated and ordered best quality first. Selection
 * indexes into *this* list, so it must be the only place the order is decided.
 */
export function qualityList<T extends QualityLike>(list: readonly T[]): T[] {
  return groupByQuality(list).flatMap((g) => g.options);
}
