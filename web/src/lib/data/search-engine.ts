// Search engine (pure, injectable): instant local results on every keystroke + TMDB remote after
// a 200 ms debounce (AbortController cancels stale calls), LRU query cache, strict space scoping.
import { Lru } from './lru';
import { normalize } from './search';
import { PrefixIndex, toDoc, type Doc } from './search-index';
import type { Item, Space } from './types';

export interface LocalBackend {
  add(docs: Doc[]): void;
  query(q: string, spaces: readonly Space[], limit: number): Promise<string[]>;
}

/** Same-thread backend (tests, browsers without Worker, worker failure fallback). */
export class InlineBackend implements LocalBackend {
  readonly index = new PrefixIndex();
  add(docs: Doc[]): void {
    this.index.add(docs);
  }
  query(q: string, spaces: readonly Space[], limit: number): Promise<string[]> {
    return Promise.resolve(this.index.query(q, spaces, limit));
  }
}

export type RemoteSearch = (q: string, signal: AbortSignal) => Promise<Item[]>;

export interface SearchUpdate {
  query: string;
  local: Item[];
  remote: Item[];
  /** Local first, then remote items not already shown (stable keys, no reflow of local hits). */
  results: Item[];
  localReady: boolean;
  remotePending: boolean;
}

export interface EngineOptions {
  /** Spaces this engine may ever return (global = HOME/ANIME/DRAMA; in-space = [space]). */
  scope: readonly Space[];
  local?: LocalBackend;
  remote?: RemoteSearch | null;
  debounceMs?: number;
  cacheSize?: number;
  limit?: number;
}

export function mergeResults(local: Item[], remote: Item[]): Item[] {
  const seen = new Set(local.map((i) => i.id));
  const out = [...local];
  for (const i of remote) {
    if (seen.has(i.id)) continue;
    seen.add(i.id);
    out.push(i);
  }
  return out;
}

export class SearchEngine {
  readonly scope: readonly Space[];
  private local: LocalBackend;
  private remote: RemoteSearch | null;
  private debounceMs: number;
  private limit: number;
  private items = new Map<string, Item>();
  private cache: Lru<string, Item[]>;
  private seq = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private ctrl: AbortController | null = null;
  private settle: (() => void) | null = null;
  /** Number of network calls made (diagnostics / tests). */
  remoteCalls = 0;

  constructor(opts: EngineOptions) {
    this.scope = opts.scope;
    this.local = opts.local ?? new InlineBackend();
    this.remote = opts.remote ?? null;
    this.debounceMs = opts.debounceMs ?? 200;
    this.limit = opts.limit ?? 60;
    this.cache = new Lru(opts.cacheSize ?? 100);
  }

  get size(): number {
    return this.items.size;
  }

  allowed(i: Item): boolean {
    return this.scope.includes(i.space) && (i.space !== 'ADULT' || this.scope.length === 1);
  }

  all(): Item[] {
    return [...this.items.values()];
  }

  get(id: string): Item | undefined {
    return this.items.get(id);
  }

  /** Add items to the local index (anything outside the scope is ignored). */
  index(items: Iterable<Item>): void {
    const fresh: Item[] = [];
    for (const i of items) {
      if (!this.allowed(i) || this.items.has(i.id)) continue;
      this.items.set(i.id, i);
      fresh.push(i);
    }
    if (fresh.length) this.local.add(fresh.map(toDoc));
  }

  cancel(): void {
    this.seq++;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.ctrl?.abort();
    this.ctrl = null;
    // A superseded query's promise resolves right away (its results are never emitted).
    this.settle?.();
    this.settle = null;
  }

  /**
   * Run a query. `onUpdate` fires for local results, then again when remote arrives — never for
   * a query that has been superseded. Resolves when this query is fully settled (or superseded).
   */
  search(query: string, onUpdate: (u: SearchUpdate) => void): Promise<void> {
    this.cancel();
    const my = this.seq;
    const nq = normalize(query);
    const live = () => my === this.seq;
    if (!nq) {
      onUpdate({ query, local: [], remote: [], results: [], localReady: true, remotePending: false });
      return Promise.resolve();
    }
    const cached = this.remote ? this.cache.get(nq) : undefined;
    const state = {
      local: [] as Item[],
      remote: cached ?? ([] as Item[]),
      localReady: false,
      remotePending: !!this.remote && !cached
    };
    const emit = () => {
      if (!live()) return;
      onUpdate({ query, local: state.local, remote: state.remote, results: mergeResults(state.local, state.remote), localReady: state.localReady, remotePending: state.remotePending });
    };

    const localP = this.local.query(nq, this.scope, this.limit).then(
      (ids) => {
        if (!live()) return;
        state.local = ids.map((id) => this.items.get(id)).filter((i): i is Item => !!i && this.allowed(i));
        state.localReady = true;
        emit();
      },
      () => {
        state.localReady = true;
        emit();
      }
    );

    if (!state.remotePending) return localP;

    const remote = this.remote!;
    const remoteP = new Promise<void>((resolve) => {
      this.settle = resolve;
      this.timer = setTimeout(() => {
        this.timer = null;
        if (!live()) return resolve();
        const ctrl = new AbortController();
        this.ctrl = ctrl;
        this.remoteCalls++;
        remote(query, ctrl.signal).then(
          (res) => {
            const ok = res.filter((i) => this.allowed(i));
            this.cache.set(nq, ok);
            this.index(ok);
            if (live()) {
              state.remote = ok;
              state.remotePending = false;
              emit();
            }
            resolve();
          },
          () => {
            if (live()) {
              state.remotePending = false;
              emit();
            }
            resolve();
          }
        );
      }, this.debounceMs);
    });
    return Promise.all([localP, remoteP]).then(() => undefined);
  }
}
