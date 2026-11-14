// Browser wiring for search: Web Worker backend (inline fallback), per-scope engines kept for
// the session so reopening search is instant, and the IndexedDB-backed history.
import { catalog, persist } from './app-catalog';
import { InlineBackend, SearchEngine, type LocalBackend } from './search-engine';
import { memoryStore, SearchHistory } from './search-history';
import type { Doc } from './search-index';
import { isVideoSpace, VIDEO_SPACES, type Space } from './types';

class WorkerBackend implements LocalBackend {
  private n = 0;
  private waiting = new Map<number, (ids: string[]) => void>();
  private docs: Doc[] = [];
  private fallback: InlineBackend | null = null;

  constructor(private worker: Worker) {
    worker.onmessage = (e: MessageEvent<{ id: number; ids: string[] }>) => {
      const cb = this.waiting.get(e.data.id);
      this.waiting.delete(e.data.id);
      cb?.(e.data.ids);
    };
    worker.onerror = () => this.fail();
  }

  private fail() {
    if (this.fallback) return;
    this.fallback = new InlineBackend();
    this.fallback.add(this.docs);
    this.docs = [];
    this.worker.terminate();
    for (const cb of this.waiting.values()) cb([]);
    this.waiting.clear();
  }

  add(docs: Doc[]): void {
    if (this.fallback) return this.fallback.add(docs);
    this.docs.push(...docs);
    this.worker.postMessage({ type: 'add', docs });
  }

  query(q: string, spaces: readonly Space[], limit: number): Promise<string[]> {
    if (this.fallback) return this.fallback.query(q, spaces, limit);
    return new Promise((resolve) => {
      const id = ++this.n;
      this.waiting.set(id, resolve);
      this.worker.postMessage({ type: 'query', id, q, spaces: [...spaces], limit });
    });
  }
}

function backend(): LocalBackend {
  if (typeof Worker === 'undefined') return new InlineBackend();
  try {
    return new WorkerBackend(new Worker(new URL('./search.worker.ts', import.meta.url), { type: 'module' }));
  } catch {
    return new InlineBackend();
  }
}

const engines = new Map<string, { engine: SearchEngine; ready: Promise<void> }>();

/** Global engine (no space) or in-space engine. Isolated spaces never get remote (TMDB) search. */
export function engineFor(space?: Space | null): { engine: SearchEngine; ready: Promise<void> } {
  const key = space ?? 'global';
  let e = engines.get(key);
  if (!e) {
    const scope: readonly Space[] = space ? [space] : VIDEO_SPACES;
    const remoteOk = !space || isVideoSpace(space);
    const engine = new SearchEngine({
      scope,
      local: backend(),
      remote: remoteOk && catalog.remoteEnabled ? (q, signal) => catalog.remoteSearch(q, signal) : null
    });
    const ready = catalog.pool(scope).then(
      (items) => engine.index(items),
      () => undefined
    );
    e = { engine, ready };
    engines.set(key, e);
  }
  return e;
}

export const history = new SearchHistory(persist ?? memoryStore());
