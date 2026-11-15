// App-facing catalog. Fallback chain for Home/Anime/Drama: Gateway → TMDB → mock.
// ADULT: gateway only while the 18+ gate is unlocked (else mock placeholder file).
// TV / Music / Comics: always mock (never call the gateway).
import { spaceForId, type CatalogRepository } from './catalog';
import { isGatewayAdultId, isGatewayId, isGwSpace, type GatewayCatalogSource, type PageResult } from './gateway';
import { findPlaceholder } from './gateway-play';
import { isMangadexId, type ComicsSource } from '../comics/mangadex';
import { attachEpisodeSources, attachMockSources } from './mock-sources';
import { isTmdbId, parseTmdbId, type TmdbCatalogSource, type VideoSpace } from './tmdb';
import { isVideoSpace, type CatalogFile, type Chapter, type Episode, type Item, type RowMore, type Space } from './types';

export type Origin = 'gateway' | 'tmdb' | 'mock' | 'mangadex';

export interface CatalogDeps {
  mock: CatalogRepository;
  tmdb?: TmdbCatalogSource | null;
  gateway?: GatewayCatalogSource | null;
  /** COMICS: MangaDex metadata/chapters (mock stays the offline fallback). */
  comics?: ComicsSource | null;
  /** navigator.onLine in browsers. */
  online?: () => boolean;
  /** 18+ gate state for the active profile (enabled + PIN-unlocked this session). */
  adultUnlocked?: () => boolean;
}

/** TMDB-origin item with gateway lookup placeholders (title + year → gateway subject). */
export function attachFindSources(item: Item): Item {
  const ref = parseTmdbId(item.id);
  if (!ref) return item;
  const out: Item = { ...item };
  if (ref.media === 'movie') out.sources = [findPlaceholder({ title: item.title, year: item.year, type: 'movie', tmdb: ref.id, se: 0, ep: 0 })];
  if (out.seasons) out.seasons = out.seasons.map((s) => ({ ...s, episodes: attachFindEpisodes(item, s.n, s.episodes) }));
  return out;
}

export function attachFindEpisodes(item: Item, season: number, eps: Episode[]): Episode[] {
  const ref = parseTmdbId(item.id);
  return eps.map((e) =>
    e.sources.length ? e : { ...e, sources: [findPlaceholder({ title: item.title, year: item.year, type: 'tv', tmdb: ref?.id, se: season, ep: e.n })] }
  );
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
  readonly comics: ComicsSource | null;
  private readonly gw: GatewayCatalogSource | null;
  private online: () => boolean;
  private adultUnlocked: () => boolean;
  private origins = new Map<Space, Origin>();

  constructor(deps: CatalogDeps) {
    this.mock = deps.mock;
    this.tmdb = deps.tmdb && deps.tmdb.enabled ? deps.tmdb : null;
    this.gw = deps.gateway ?? null;
    this.comics = deps.comics ?? null;
    this.online = deps.online ?? (() => typeof navigator === 'undefined' || navigator.onLine !== false);
    this.adultUnlocked = deps.adultUnlocked ?? (() => false);
  }

  /** Gateway configured (base URL non-empty; Settings → Developer can turn it off). */
  get gateway(): GatewayCatalogSource | null {
    return this.gw && this.gw.enabled ? this.gw : null;
  }

  get remoteEnabled(): boolean {
    return !!this.gateway || !!this.tmdb;
  }

  /**
   * Gateway-owned spaces (Home/Anime/Drama/Adult) when a gateway is wired: gateway ONLY — no TMDB/mock
   * fallback (offline = cached gateway data or an error + retry). TV/Music/Comics never use it.
   */
  ownsSpace(space: Space): boolean {
    return !!this.gw && isGwSpace(space);
  }

  /** Spaces served by the gateway right now (Adult only while unlocked). */
  usesGateway(space: Space): boolean {
    if (!this.gateway || !isGwSpace(space)) return false;
    return space === 'ADULT' ? this.adultUnlocked() : isVideoSpace(space);
  }

  /** Where the last file(space) came from. */
  originOf(space: Space): Origin | undefined {
    return this.origins.get(space);
  }

  private useTmdb(space: Space): boolean {
    return !!this.tmdb && isVideoSpace(space) && this.online();
  }

  async file(space: Space): Promise<CatalogFile> {
    if (this.ownsSpace(space)) {
      if (!this.usesGateway(space) || !isGwSpace(space)) throw new Error(space === 'ADULT' ? '18+ is locked' : 'Streaming gateway is turned off (Settings → Developer)');
      const f = await this.gateway!.file(space);
      this.origins.set(space, 'gateway');
      return f;
    }
    // COMICS: live MangaDex catalogue, mock library as the offline fallback.
    if (space === 'COMICS' && this.comics && this.online()) {
      try {
        const f = await this.comics.file();
        this.origins.set(space, 'mangadex');
        return f;
      } catch {
        /* fall through to the bundled library */
      }
    }
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
    if (isMangadexId(id)) return this.comics?.cached(id);
    if (isGatewayId(id)) return isGatewayAdultId(id) && !this.adultUnlocked() ? undefined : this.gw?.cached(id);
    return isTmdbId(id) ? this.tmdb?.cached(id) : undefined;
  }

  /** Comics reader: pages of one chapter ([] = "chapter unavailable"). Mock chapters already carry theirs. */
  async comicPages(item: Pick<Item, 'id'>, chapter: Chapter): Promise<string[]> {
    if (chapter.pages.length) return chapter.pages;
    if (!isMangadexId(item.id) || !this.comics) return [];
    return this.comics.chapterPages(chapter.id).catch(() => []);
  }

  async item(id: string, space?: Space): Promise<Item | undefined> {
    if (isMangadexId(id)) {
      if (!this.comics) return undefined;
      const full = await this.comics.item(id).catch(() => undefined);
      return full ?? this.comics.cached(id);
    }
    if (isGatewayId(id)) {
      // 18+ metadata is never fetched while locked.
      if (!this.gateway || (isGatewayAdultId(id) && !this.adultUnlocked())) return undefined;
      const full = await this.gateway.item(id).catch(() => undefined);
      return full ?? this.gateway.cached(id);
    }
    if (isTmdbId(id)) {
      if (!this.tmdb || this.gw) return undefined;
      const full = await this.tmdb.item(id).catch(() => undefined);
      const it = full ?? this.tmdb.cached(id);
      if (!it) return undefined;
      // Real streams: TMDB title → gateway lookup at play time ('No source available' if none).
      if (this.gateway) return attachFindSources(it);
      return attachMockSources(it, await this.mockPool(it.space));
    }
    // Old mock ids of gateway-owned spaces (history from earlier versions) resolve to nothing.
    if (this.gw && (space ? isGwSpace(space) : isGwSpace(spaceForId(id) ?? 'TV'))) return undefined;
    return this.mock.item(id, space).catch(() => undefined);
  }

  /** Episodes for a season (inline for gateway/mock, lazy for TMDB series). */
  async season(item: Item, n: number): Promise<Episode[]> {
    const s = item.seasons?.find((x) => x.n === n);
    if (s?.episodes.length) return s.episodes;
    if (!isTmdbId(item.id) || !this.tmdb) return [];
    const eps = await this.tmdb.season(item, n);
    if (this.gateway) return attachFindEpisodes(item, n, eps);
    return attachEpisodeSources(eps, await this.mockPool(item.space));
  }

  /** Infinite rows: next page of a gateway row (empty when the space isn't gateway-backed). */
  async moreRow(space: Space, more: RowMore, categoryId?: string): Promise<PageResult | null> {
    if (!this.usesGateway(space) || !isGwSpace(space) || !more.hasMore) return null;
    return this.gateway!.morePage(space, more, categoryId);
  }

  /** Genre chip on a gateway space → server-side genre list (null: filter client-side). */
  async genrePage(space: Space, categoryId: string, genre: string): Promise<PageResult | null> {
    if (!this.usesGateway(space) || !isGwSpace(space)) return null;
    return this.gateway!.genrePage(space, categoryId, genre);
  }

  /** Remember a deep-link slug for a gateway id (`/app/title?id=gw:…&slug=…`). */
  hintSlug(id: string, slug: string | null | undefined): void {
    if (slug && isGatewayId(id)) this.gw?.rememberSlug(id, slug);
  }

  async moreLikeThis(item: Item, limit = 18): Promise<Item[]> {
    if (isGatewayAdultId(item.id) && !this.adultUnlocked()) return [];
    const related = this.tmdb && isTmdbId(item.id) ? this.tmdb.relatedTo(item.id) : [];
    const file = await this.file(item.space).catch(() => null);
    return moreLikeThis(item, [...related, ...(file?.items ?? [])], limit);
  }

  /** Global remote search (Home/Anime/Drama only): gateway `/search`, TMDB when the gateway fails/empty. */
  async remoteSearch(query: string, signal?: AbortSignal): Promise<Item[]> {
    if (!this.online()) return [];
    if (this.gw) {
      if (!this.gateway) return [];
      const hits = await this.gateway.search(query, signal);
      return hits.filter((i) => isVideoSpace(i.space));
    }
    if (!this.tmdb) return [];
    return this.tmdb.search(query, signal);
  }

  /** Remote suggestions (gateway `/search/suggest`). */
  async suggest(query: string, signal?: AbortSignal): Promise<string[]> {
    if (!this.gateway || !this.online()) return [];
    return this.gateway.suggest(query, signal).catch(() => []);
  }
}
