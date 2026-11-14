// Small FlexSearch-like local index (no deps): word-prefix buckets + scored fallback scan with
// 1-typo tolerance. Pure TS — runs inside the search Web Worker or inline (tests / no Worker).
import { normalize } from './search';
import type { Item, Space } from './types';

/** Slim, structured-clone-friendly copy of an Item for the worker. */
export interface Doc {
  id: string;
  title: string;
  altTitle?: string;
  space: Space;
  rating: number;
}

export const toDoc = (i: Item): Doc => ({ id: i.id, title: i.title, altTitle: i.altTitle, space: i.space, rating: i.rating });

interface Entry {
  doc: Doc;
  t: string;
  a: string;
  tw: string[];
  aw: string[];
}

const PREFIX = 2;

/** Levenshtein distance ≤ 1 (substitution, insertion or deletion). */
export function withinOne(a: string, b: string): boolean {
  if (a === b) return true;
  const la = a.length;
  const lb = b.length;
  if (Math.abs(la - lb) > 1) return false;
  let i = 0;
  let j = 0;
  let edits = 0;
  while (i < la && j < lb) {
    if (a[i] === b[j]) {
      i++;
      j++;
      continue;
    }
    if (++edits > 1) return false;
    if (la > lb) i++;
    else if (lb > la) j++;
    else {
      i++;
      j++;
    }
  }
  return edits + (la - i) + (lb - j) <= 1;
}

/**
 * Score (lower = better) or -1. 0 title prefix · 1 title word prefix · 2 alt-title prefix ·
 * 3 substring / squashed · 4 one typo in a word (queries ≥ 4 chars).
 */
export function scoreEntry(e: Pick<Entry, 't' | 'a' | 'tw' | 'aw'>, nq: string): number {
  if (!nq) return -1;
  if (e.t.startsWith(nq)) return 0;
  if (e.tw.some((w) => w.startsWith(nq)) || e.t.includes(' ' + nq)) return 1;
  if (e.a && (e.a.startsWith(nq) || e.aw.some((w) => w.startsWith(nq)))) return 2;
  if (e.t.includes(nq) || (e.a && e.a.includes(nq))) return 3;
  const squash = nq.replace(/ /g, '');
  if (squash && (e.t.replace(/ /g, '').includes(squash) || e.a.replace(/ /g, '').includes(squash))) return 3;
  const qw = nq.split(' ');
  if (nq.length >= 4) {
    const words = [...e.tw, ...e.aw];
    const ok = qw.every((q) => words.some((w) => w.startsWith(q) || (q.length >= 4 && (withinOne(q, w) || withinOne(q, w.slice(0, q.length))))));
    if (ok) return 4;
  }
  return -1;
}

export class PrefixIndex {
  private entries = new Map<string, Entry>();
  private buckets = new Map<string, Set<string>>();

  get size(): number {
    return this.entries.size;
  }

  add(docs: Doc[]): void {
    for (const doc of docs) {
      if (this.entries.has(doc.id)) continue;
      const t = normalize(doc.title);
      const a = doc.altTitle ? normalize(doc.altTitle) : '';
      const tw = t ? t.split(' ') : [];
      const aw = a ? a.split(' ') : [];
      this.entries.set(doc.id, { doc, t, a, tw, aw });
      for (const w of [...tw, ...aw]) {
        const k = w.slice(0, PREFIX);
        let b = this.buckets.get(k);
        if (!b) this.buckets.set(k, (b = new Set()));
        b.add(doc.id);
      }
    }
  }

  /** Ids ordered by score, rating, title. `nq` may be raw (normalized here). */
  query(q: string, spaces: readonly Space[], limit = 60): string[] {
    const nq = normalize(q);
    if (!nq) return [];
    const allowed = new Set(spaces);
    const hits: { e: Entry; s: number }[] = [];
    const seen = new Set<string>();
    const consider = (e: Entry) => {
      if (seen.has(e.doc.id)) return;
      seen.add(e.doc.id);
      if (!allowed.has(e.doc.space)) return;
      const s = scoreEntry(e, nq);
      if (s >= 0) hits.push({ e, s });
    };
    // 1. Fast path: word-prefix bucket of the first query word.
    const bucket = this.buckets.get(nq.split(' ')[0].slice(0, PREFIX));
    if (bucket) for (const id of bucket) consider(this.entries.get(id)!);
    // 2. Fill with substring / typo matches when the fast path is thin.
    if (hits.length < limit) for (const e of this.entries.values()) consider(e);
    hits.sort((x, y) => x.s - y.s || y.e.doc.rating - x.e.doc.rating || x.e.doc.title.localeCompare(y.e.doc.title));
    return hits.slice(0, limit).map((h) => h.e.doc.id);
  }
}
