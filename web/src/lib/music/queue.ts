// Music queue pure logic: reorder, shuffle order, repeat modes, next/previous index.
export type Repeat = 'off' | 'all' | 'one';

export interface QueueState {
  /** Track count in the queue. */
  length: number;
  /** Index (into the queue) of the current track, -1 when empty. */
  index: number;
  shuffle: boolean;
  repeat: Repeat;
  /** Shuffled play order (queue indices), current track first. Ignored when shuffle is off. */
  order: number[];
}

/** Move an element; returns a new array. Out-of-range indices are clamped. */
export function reorder<T>(list: readonly T[], from: number, to: number): T[] {
  const out = list.slice();
  if (from < 0 || from >= out.length) return out;
  const t = Math.max(0, Math.min(out.length - 1, to));
  const [x] = out.splice(from, 1);
  out.splice(t, 0, x);
  return out;
}

/** Where index `i` ends up after moving `from` → `to`. */
export function movedIndex(i: number, from: number, to: number): number {
  if (i === from) return to;
  if (from < i && to >= i) return i - 1;
  if (from > i && to <= i) return i + 1;
  return i;
}

/** Fisher–Yates over all indices with `current` pinned first. */
export function shuffleOrder(length: number, current: number, rand: () => number = Math.random): number[] {
  const rest = [...Array(length).keys()].filter((i) => i !== current);
  for (let i = rest.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [rest[i], rest[j]] = [rest[j], rest[i]];
  }
  return current >= 0 && current < length ? [current, ...rest] : rest;
}

/** Reorder the queue keeping the current track current (and the shuffle order consistent). */
export function reorderQueue(q: QueueState, from: number, to: number): QueueState {
  const t = Math.max(0, Math.min(q.length - 1, to));
  return { ...q, index: q.index < 0 ? q.index : movedIndex(q.index, from, t), order: q.order.map((i) => movedIndex(i, from, t)) };
}

/**
 * Next track index, or null to stop.
 * `auto` = track ended by itself (repeat-one replays); a user "next" always advances.
 */
export function nextIndex(q: QueueState, auto = false): number | null {
  if (q.length <= 0 || q.index < 0) return null;
  if (auto && q.repeat === 'one') return q.index;
  if (q.shuffle && q.order.length === q.length) {
    const pos = q.order.indexOf(q.index);
    if (pos >= 0 && pos < q.order.length - 1) return q.order[pos + 1];
    return q.repeat === 'off' ? null : q.order[0];
  }
  if (q.index < q.length - 1) return q.index + 1;
  return q.repeat === 'off' ? null : 0;
}

/** Previous track index (wraps only with repeat-all), or null. */
export function prevIndex(q: QueueState): number | null {
  if (q.length <= 0 || q.index < 0) return null;
  if (q.shuffle && q.order.length === q.length) {
    const pos = q.order.indexOf(q.index);
    if (pos > 0) return q.order[pos - 1];
    return q.repeat === 'all' ? q.order[q.order.length - 1] : null;
  }
  if (q.index > 0) return q.index - 1;
  return q.repeat === 'all' ? q.length - 1 : null;
}

export const nextRepeat = (r: Repeat): Repeat => (r === 'off' ? 'all' : r === 'all' ? 'one' : 'off');

export function fmtDur(sec: number): string {
  if (!Number.isFinite(sec) || sec < 0) return '0:00';
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${m}:${String(s).padStart(2, '0')}`;
}
