import { SPACES, SPACE_FILE, type CatalogFile, type Category, type Item, type Row, type Space } from './types';

export interface LatencyConfig {
  enabled: boolean;
  minMs: number;
  maxMs: number;
}

export const DEFAULT_LATENCY: LatencyConfig = { enabled: true, minMs: 300, maxMs: 1200 };
export const NO_LATENCY: LatencyConfig = { enabled: false, minMs: 0, maxMs: 0 };

export interface CatalogOptions {
  /** Base URL prefix (SvelteKit `base`), e.g. '' or '/cinetor'. */
  baseUrl: string;
  fetcher?: (url: string) => Promise<{ ok: boolean; status: number; json(): Promise<unknown> }>;
  latency?: LatencyConfig;
  /** Injected for tests. */
  random?: () => number;
  sleep?: (ms: number) => Promise<void>;
}

interface Loaded {
  file: CatalogFile;
  byId: Map<string, Item>;
  rowById: Map<string, Row>;
}

/** Guess the owning space from an id prefix so item()/row() only fetch one file. */
const PREFIX: [string, Space][] = [
  ['hm-', 'HOME'],
  ['home-', 'HOME'],
  ['an-', 'ANIME'],
  ['dr-', 'DRAMA'],
  ['tv-', 'TV'],
  ['mu-', 'MUSIC'],
  ['co-', 'COMICS'],
  ['ad-', 'ADULT']
];

export function spaceForId(id: string): Space | undefined {
  return PREFIX.find(([p]) => id.startsWith(p))?.[1];
}

/**
 * Web equivalent of the Android CatalogRepository: fetches `${base}/mock/<space>.json`,
 * caches each file in memory (in-flight requests are shared), optional simulated latency
 * on cache misses so skeletons are visible.
 */
export class CatalogRepository {
  private cache = new Map<Space, Promise<Loaded>>();
  private opts: Required<Omit<CatalogOptions, 'latency'>> & { latency: LatencyConfig };

  constructor(opts: CatalogOptions) {
    this.opts = {
      baseUrl: opts.baseUrl.replace(/\/$/, ''),
      fetcher: opts.fetcher ?? ((u) => fetch(u)),
      latency: opts.latency ?? DEFAULT_LATENCY,
      random: opts.random ?? Math.random,
      sleep: opts.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)))
    };
  }

  setLatency(latency: LatencyConfig): void {
    this.opts.latency = latency;
  }

  get latency(): LatencyConfig {
    return this.opts.latency;
  }

  /** Absolute URL for a mock-relative asset path ('images/...', 'subs/...'). */
  asset(path: string): string {
    return `${this.opts.baseUrl}/mock/${path}`;
  }

  isLoaded(space: Space): boolean {
    return this.cache.has(space);
  }

  private delay(): Promise<void> {
    const { enabled, minMs, maxMs } = this.opts.latency;
    if (!enabled || maxMs <= 0) return Promise.resolve();
    const ms = Math.round(minMs + this.opts.random() * Math.max(0, maxMs - minMs));
    return this.opts.sleep(ms);
  }

  load(space: Space): Promise<Loaded> {
    let p = this.cache.get(space);
    if (!p) {
      p = (async () => {
        const url = `${this.opts.baseUrl}/mock/${SPACE_FILE[space]}.json`;
        const [res] = await Promise.all([this.opts.fetcher(url), this.delay()]);
        if (!res.ok) throw new Error(`Catalog ${space}: HTTP ${res.status}`);
        const file = (await res.json()) as CatalogFile;
        const byId = new Map(file.items.map((i) => [i.id, i] as const));
        const rowById = new Map<string, Row>();
        for (const c of file.categories) for (const r of c.rows) rowById.set(r.id, r);
        return { file, byId, rowById };
      })();
      // Failed loads must not poison the cache.
      p.catch(() => this.cache.delete(space));
      this.cache.set(space, p);
    }
    return p;
  }

  async file(space: Space): Promise<CatalogFile> {
    return (await this.load(space)).file;
  }

  async categories(space: Space): Promise<Category[]> {
    return (await this.load(space)).file.categories;
  }

  async category(space: Space, categoryId: string): Promise<Category | undefined> {
    return (await this.categories(space)).find((c) => c.id === categoryId);
  }

  async genres(space: Space): Promise<string[]> {
    const { file } = await this.load(space);
    if (file.genres?.length) return file.genres;
    return [...new Set(file.items.flatMap((i) => i.genres))];
  }

  async byGenre(space: Space, genre: string): Promise<Item[]> {
    const g = genre.toLowerCase();
    return (await this.load(space)).file.items.filter((i) => i.genres.some((x) => x.toLowerCase() === g));
  }

  async items(space: Space): Promise<Item[]> {
    return (await this.load(space)).file.items;
  }

  async itemsByIds(space: Space, ids: string[]): Promise<Item[]> {
    const { byId } = await this.load(space);
    return ids.map((id) => byId.get(id)).filter((i): i is Item => !!i);
  }

  async item(id: string, space?: Space): Promise<Item | undefined> {
    return this.find(id, space, (l) => l.byId.get(id));
  }

  async row(rowId: string, space?: Space): Promise<{ row: Row; items: Item[] } | undefined> {
    const s = space ?? spaceForId(rowId);
    const hit = await this.find(rowId, s, (l) => l.rowById.get(rowId));
    if (!hit) return undefined;
    const owner = s ?? (await this.ownerOfRow(rowId));
    return { row: hit, items: owner ? await this.itemsByIds(owner, hit.itemIds) : [] };
  }

  private async ownerOfRow(rowId: string): Promise<Space | undefined> {
    for (const s of SPACES) if (this.cache.has(s) && (await this.load(s)).rowById.has(rowId)) return s;
    return undefined;
  }

  private async find<T>(id: string, space: Space | undefined, pick: (l: Loaded) => T | undefined) {
    // 1. already-loaded files, 2. space hint / id prefix, 3. everything.
    for (const s of SPACES) {
      if (!this.cache.has(s)) continue;
      const v = pick(await this.load(s));
      if (v) return v;
    }
    const hint = space ?? spaceForId(id);
    if (hint) {
      const v = pick(await this.load(hint));
      if (v) return v;
    }
    for (const s of SPACES) {
      const v = pick(await this.load(s));
      if (v) return v;
    }
    return undefined;
  }
}
