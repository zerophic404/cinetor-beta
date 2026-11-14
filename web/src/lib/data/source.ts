// App-facing catalog: TMDB for Home/Anime/Drama when a token is configured and the network
// works; the mock catalog otherwise (and always for isolated spaces).
import type { CatalogRepository } from './catalog';
import { attachEpisodeSources, attachMockSources } from './mock-sources';
import { isTmdbId, type TmdbCatalogSource, type VideoSpace } from './tmdb';
import { isVideoSpace, type CatalogFile, type Episode, type Item, type Space } from './types';

export type Origin = 'tmdb' | 'mock';

export interface CatalogDeps {
  mock: CatalogRepository;
  tmdb?: TmdbCatalogSource | null;
  /** navigator.onLine in browsers. */
  online?: () => boolean;
}

/** "More like this": same space only, never the item itself; genre overlap then rating. */
export function moreLikeThis(item: Item, pool: Iterable<Item>, limit = 18): Item[] {
  const genres = new Set(item.genres.map((g) => g.toLowerCase()));
  const seen = new Set<string>([item.id]);
  const scored: { i: Item; s: number }[] = [];
  for (const i of pool) {
    if (i.space !== item.space || seen.has(i.id)) continue;
    seen.add(i.id);
    const overlap = i.genres.reduce((n, g) => n + (genres.has(g.toLowerCase()) ? 1 : 0), 0);
    const sameType = i.type === item.type ? 0.5 : 0;
    scored.push({ i, s: overlap * 10 + sameType + i.rating / 10 });
  }
  scored.sort((a, b) => b.s - a.s || a.i.title.localeCompare(b.i.title));
  return scored.slice(0, limit).map((x) => x.i);
}

export class Catalog {
  readonly mock: CatalogRepository;
  readonly tmdb: TmdbCatalogSource | null;
  private online: () => boolean;
  private origins = new Map<Space, Origin>();

  constructor(deps: CatalogDeps) {
    this.mock = deps.mock;
    this.tmdb = deps.tmdb && deps.tmdb.enabled ? deps.tmdb : null;
    this.online = deps.online ?? (() => typeof navigator === 'undefined' || navigator.onLine !== false);
  }

  get remoteEnabled(): boolean {
    return !!this.tmdb;
  }

  /** Where the last file(space) came from. */
  originOf(space: Space): Origin | undefined {
    return this.origins.get(space);
  }

  private useTmdb(space: Space): boolean {
    return !!this.tmdb && isVideoSpace(space) && this.online();
  }

  async file(space: Space): Promise<CatalogFile> {
    if (this.useTmdb(space)) {
      try {
        const f = await this.tmdb!.file(space as VideoSpace);
        this.origins.set(space, 'tmdb');
        return f;
      } catch {
        /* fall through to mock */
      }
    }
    const f = await this.mock.file(space);
    this.origins.set(space, 'mock');
    return f;
  }

  async items(space: Space): Promise<Item[]> {
    return (await this.file(space)).items;
  }

  /** Searchable pool for a set of spaces (each strictly filtered to its own space). */
  async pool(spaces: readonly Space[]): Promise<Item[]> {
    const lists = await Promise.all(spaces.map((s) => this.items(s).then((l) => l.filter((i) => i.space === s), () => [] as Item[])));
    return lists.flat();
  }

  private async mockPool(space: Space): Promise<Item[]> {
    return this.mock.items(space).catch(() => [] as Item[]);
  }

  /** Synchronous best-effort lookup (for instant details header before full load). */
  peek(id: string): Item | undefined {
    return isTmdbId(id) ? this.tmdb?.cached(id) : undefined;
  }

  async item(id: string, space?: Space): Promise<Item | undefined> {
    if (isTmdbId(id)) {
      if (!this.tmdb) return undefined;
      const full = await this.tmdb.item(id).catch(() => undefined);
      const it = full ?? this.tmdb.cached(id);
      if (!it) return undefined;
      return attachMockSources(it, await this.mockPool(it.space));
    }
    return this.mock.item(id, space);
  }

  /** Episodes for a season (lazy for TMDB series), with mock sources attached. */
  async season(item: Item, n: number): Promise<Episode[]> {
    const s = item.seasons?.find((x) => x.n === n);
    if (s?.episodes.length) return s.episodes;
    if (!isTmdbId(item.id) || !this.tmdb) return [];
    const eps = await this.tmdb.season(item, n);
    return attachEpisodeSources(eps, await this.mockPool(item.space));
  }

  async moreLikeThis(item: Item, limit = 18): Promise<Item[]> {
    const related = this.tmdb && isTmdbId(item.id) ? this.tmdb.relatedTo(item.id) : [];
    const file = await this.file(item.space).catch(() => null);
    return moreLikeThis(item, [...related, ...(file?.items ?? [])], limit);
  }

  /** Remote search (TMDB /search/multi) — empty when TMDB is off or offline. */
  async remoteSearch(query: string, signal?: AbortSignal): Promise<Item[]> {
    if (!this.tmdb || !this.online()) return [];
    return this.tmdb.search(query, signal);
  }
}
