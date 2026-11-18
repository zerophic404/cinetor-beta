// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
// scrape or hack anything without Mythbrix's explicit permission.
// App-facing catalog. Fallback chain for Home/Anime/Drama: Gateway → TMDB → mock.
// ADULT: gateway only while the 18+ gate is unlocked (else mock placeholder file).
// TV / Music / Comics: always mock (never call the gateway).
import { spaceForId, type CatalogRepository } from './catalog';
import { isGatewayAdultId, isGatewayId, isGwSpace, streamPlaceholder, subjectIdOf, type GatewayCatalogSource, type PageResult } from './gateway';
import { findPlaceholder } from './gateway-play';
import type { PersistentCache } from './idb';
import { imgAttrs } from './images';
import { isMangadexId, type ComicsSource } from '../comics/mangadex';
import { attachEpisodeSources, attachMockSources } from './mock-sources';
import { normalize } from './search';
import {
  classify,
  isTmdbId,
  mapResult,
  parseTmdbId,
  type Media as TmdbMedia,
  type TmdbCatalogSource,
  type TmdbClient,
  type TmdbResult,
  type VideoSpace
} from './tmdb';
import {
  SPACES,
  VIDEO_SPACES,
  isVideoSpace,
  type CatalogFile,
  type Chapter,
  type Episode,
  type Item,
  type RowMore,
  type Space
} from './types';

export type Origin = 'gateway' | 'tmdb' | 'mock' | 'mangadex';

export interface CatalogDeps {
  mock: CatalogRepository;
  tmdb?: TmdbCatalogSource | null;
  tmdbClient?: TmdbClient | null;
  gateway?: GatewayCatalogSource | null;
  /** COMICS: MangaDex metadata/chapters (mock stays the offline fallback). */
  comics?: ComicsSource | null;
  /** Optional IndexedDB persistence for cross-reload item summaries. */
  persist?: PersistentCache | null;
  /** Asset resolver for mock-relative paths (used by `warmStartupSpaces`). */
  asset?: (p: string) => string;
  /** navigator.onLine in browsers. */
  online?: () => boolean;
  /** 18+ gate state for the active profile (enabled + PIN-unlocked this session). */
  adultUnlocked?: () => boolean;
}

type TmdbSourceInspector = {
  opts?: {
    client?: TmdbClient;
    colorOf?: (posterPath: string) => string | undefined;
  };
};

function inspectTmdb(src: TmdbCatalogSource | null | undefined): TmdbSourceInspector['opts'] | undefined {
  return (src as unknown as TmdbSourceInspector | null | undefined)?.opts;
}

function isKnownSpace(v: string | undefined): v is Space {
  return !!v && (SPACES as readonly string[]).includes(v);
}

function normalizeTitleKey(title: string): string {
  return normalize(title.replace(/\[[^\]]*\]|\([^)]*\)/g, ' '));
}

/** TMDB-origin item with gateway lookup placeholders (title + year → gateway subject). */
export function attachFindSources(item: Item): Item {
  const ref = parseTmdbId(item.id);
  if (!ref) return item;
  const out: Item = { ...item };
  if (ref.media === 'movie') {
    out.sources = [findPlaceholder({ title: item.title, year: item.year, type: 'movie', tmdb: ref.id, se: 0, ep: 0 })];
  } else if (out.seasons?.length) {
    out.seasons = out.seasons.map((s) => ({ ...s, episodes: attachFindEpisodes(item, s.n, s.episodes) }));
  } else {
    out.seasons = [
      {
        n: 1,
        title: 'Season 1',
        episodeCount: 1,
        episodes: [
          {
            id: `${item.id}-s1e1`,
            n: 1,
            title: 'Episode 1',
            durationSec: 0,
            still: item.backdrop || item.poster,
            sources: [findPlaceholder({ title: item.title, year: item.year, type: 'tv', tmdb: ref.id, se: 1, ep: 1 })],
            subtitles: []
          }
        ]
      }
    ];
  }
  return out;
}

function ensureGatewayPlayable(item: Item): Item {
  const sid = subjectIdOf(item.id) ?? item.slug ?? item.id;
  const slug = item.slug || sid;
  const out: Item = { ...item, slug };
  if (out.type === 'SERIES') {
    if (!out.seasons?.length) {
      out.seasons = [
        {
          n: 1,
          title: 'Season 1',
          episodeCount: 1,
          episodes: [
            {
              id: `${out.id}-s1e1`,
              n: 1,
              title: 'Episode 1',
              durationSec: 0,
              still: out.backdrop || out.poster,
              sources: [streamPlaceholder(sid, slug, 1, 1)],
              subtitles: []
            }
          ]
        }
      ];
    }
  } else if (!out.sources?.length) {
    out.sources = [streamPlaceholder(sid, slug, 0, 0)];
  }
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

const SUMMARY_KEY = (id: string) => `cat:item:${id}`;

export class Catalog {
  readonly mock: CatalogRepository;
  readonly tmdb: TmdbCatalogSource | null;
  readonly comics: ComicsSource | null;
  private readonly tmdbClient: TmdbClient | null;
  private readonly gw: GatewayCatalogSource | null;
  private readonly persist: PersistentCache | null;
  private readonly asset: (p: string) => string;
  private online: () => boolean;
  private adultUnlocked: () => boolean;
  private origins = new Map<Space, Origin>();
  private summaries = new Map<string, Item>();

  constructor(deps: CatalogDeps) {
    this.mock = deps.mock;
    this.tmdb = deps.tmdb && deps.tmdb.enabled ? deps.tmdb : null;
    const rawClient = deps.tmdbClient ?? inspectTmdb(this.tmdb)?.client ?? null;
    this.tmdbClient = rawClient && rawClient.enabled ? rawClient : null;
    this.gw = deps.gateway ?? null;
    this.comics = deps.comics ?? null;
    this.persist = deps.persist ?? null;
    this.asset = deps.asset ?? ((p) => this.mock.asset(p));
    this.online = deps.online ?? (() => typeof navigator === 'undefined' || navigator.onLine !== false);
    this.adultUnlocked = deps.adultUnlocked ?? (() => false);
  }

  /** Cache item summaries in memory + IndexedDB so deep links, reloads, and search/cast cards never fail. */
  rememberItems(items: Iterable<Item>): void {
    for (const it of items) {
      if (!it?.id) continue;
      if (it.slug && isGatewayId(it.id)) this.gw?.rememberSlug(it.id, it.slug);
      this.summaries.set(it.id, it);
      if (this.persist && it.space !== 'ADULT' && !isGatewayAdultId(it.id)) {
        void this.persist.set(SUMMARY_KEY(it.id), it);
      }
    }
  }

  private async loadPersistedSummary(id: string): Promise<Item | undefined> {
    const mem = this.summaries.get(id);
    if (mem) return mem;
    if (!this.persist || isGatewayAdultId(id)) return undefined;
    const stored = (await this.persist.get(SUMMARY_KEY(id))) as Item | undefined;
    if (stored && typeof stored === 'object' && stored.id === id && typeof stored.title === 'string') {
      this.summaries.set(id, stored);
      if (stored.slug && isGatewayId(id)) this.gw?.rememberSlug(id, stored.slug);
      return stored;
    }
    return undefined;
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
      this.rememberItems(f.items);
      return f;
    }
    // COMICS: live MangaDex catalogue, mock library as the offline fallback.
    if (space === 'COMICS' && this.comics && this.online()) {
      try {
        const f = await this.comics.file();
        this.origins.set(space, 'mangadex');
        this.rememberItems(f.items);
        return f;
      } catch {
        /* fall through to the bundled library */
      }
    }
    if (this.useTmdb(space)) {
      try {
        const f = await this.tmdb!.file(space as VideoSpace);
        this.origins.set(space, 'tmdb');
        this.rememberItems(f.items);
        return f;
      } catch {
        /* fall through to mock */
      }
    }
    const f = await this.mock.file(space);
    this.origins.set(space, 'mock');
    this.rememberItems(f.items);
    return f;
  }

  /** Alias for `file(space)` used by splash / navigation pre-warmers. */
  space(space: Space): Promise<CatalogFile> {
    return this.file(space);
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
    if (isGatewayAdultId(id) && !this.adultUnlocked()) return undefined;
    if (isMangadexId(id)) return this.comics?.cached(id) ?? this.summaries.get(id);
    if (isGatewayId(id)) return this.gw?.cached(id) ?? this.summaries.get(id);
    if (isTmdbId(id)) return this.tmdb?.cached(id) ?? this.summaries.get(id);
    return this.summaries.get(id);
  }

  /** Comics reader: pages of one chapter ([] = "chapter unavailable"). Mock chapters already carry theirs. */
  async comicPages(item: Pick<Item, 'id'>, chapter: Chapter): Promise<string[]> {
    if (chapter.pages.length) return chapter.pages;
    if (!isMangadexId(item.id) || !this.comics) return [];
    return this.comics.chapterPages(chapter.id).catch(() => []);
  }

  async item(id: string, spaceOrSlug?: Space | string, slugHint?: string): Promise<Item | undefined> {
    let space: Space | undefined;
    let slug = slugHint?.trim() || undefined;
    if (isKnownSpace(spaceOrSlug)) {
      space = spaceOrSlug;
    } else if (typeof spaceOrSlug === 'string' && spaceOrSlug.trim()) {
      slug = spaceOrSlug.trim();
    }
    if (slug) this.hintSlug(id, slug);

    if (isMangadexId(id)) {
      if (!this.comics) return this.loadPersistedSummary(id);
      const full = await this.comics.item(id).catch(() => undefined);
      const res = full ?? this.comics.cached(id) ?? (await this.loadPersistedSummary(id));
      if (res) this.rememberItems([res]);
      return res;
    }

    if (isGatewayId(id)) {
      // 18+ metadata is never fetched while locked.
      if (isGatewayAdultId(id) && !this.adultUnlocked()) return undefined;
      const persisted = this.gw?.cached(id) ?? (await this.loadPersistedSummary(id));
      if (this.gateway) {
        const full = await this.gateway.item(id, slug ?? persisted?.slug).catch(() => undefined);
        if (full) {
          this.rememberItems([full]);
          return full;
        }
      }
      const fallback = this.gw?.cached(id) ?? persisted;
      if (fallback) {
        const playable = ensureGatewayPlayable(fallback);
        this.rememberItems([playable]);
        return playable;
      }
      return undefined;
    }

    if (isTmdbId(id)) {
      const cached = this.tmdb?.cached(id) ?? (await this.loadPersistedSummary(id));
      const full = this.tmdb ? await this.tmdb.item(id).catch(() => undefined) : undefined;
      const it = full ?? cached;
      if (!it) return undefined;
      // Real streams: TMDB title → gateway lookup at play time ('No source available' if none).
      const out = this.gateway || this.gw ? attachFindSources(it) : attachMockSources(it, await this.mockPool(it.space));
      this.rememberItems([out]);
      return out;
    }

    // Bare numeric gateway subject IDs (e.g., deep link `?id=998877`).
    if (/^\d+$/.test(id) && this.gateway) {
      const gwFull = await this.gateway.item(`gw:${id}`, slug).catch(() => undefined);
      if (gwFull) {
        this.rememberItems([gwFull]);
        return gwFull;
      }
    }

    const persisted = await this.loadPersistedSummary(id);
    if (persisted) return persisted;

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
    if (this.gateway || this.gw) return attachFindEpisodes(item, n, eps);
    return attachEpisodeSources(eps, await this.mockPool(item.space));
  }

  /** Infinite rows: next page of a gateway row (empty when the space isn't gateway-backed). */
  async moreRow(space: Space, more: RowMore, categoryId?: string): Promise<PageResult | null> {
    if (!this.usesGateway(space) || !isGwSpace(space) || !more.hasMore) return null;
    const res = await this.gateway!.morePage(space, more, categoryId);
    this.rememberItems(res.items);
    return res;
  }

  /** Genre chip on a gateway space → server-side genre list (null: filter client-side). */
  async genrePage(space: Space, categoryId: string, genre: string): Promise<PageResult | null> {
    if (!this.usesGateway(space) || !isGwSpace(space)) return null;
    const res = await this.gateway!.genrePage(space, categoryId, genre);
    if (res) this.rememberItems(res.items);
    return res;
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

  /** Hybrid remote search (Home/Anime/Drama only): combines Gateway `/search` + TMDB `/search/multi` in parallel. */
  async remoteSearch(query: string, signal?: AbortSignal): Promise<Item[]> {
    if (!this.online()) return [];
    const q = query.trim();
    if (!q) return [];

    const gwPromise = this.gateway
      ? this.gateway.search(q, signal).then(
          (hits) => hits.filter((i) => isVideoSpace(i.space)),
          () => [] as Item[]
        )
      : Promise.resolve([] as Item[]);

    const tmdbPromise = this.tmdb
      ? this.tmdb.search(q, signal).then(
          (hits) =>
            hits
              .filter((i) => isVideoSpace(i.space))
              .map((i) => (this.gateway || this.gw ? attachFindSources(i) : i)),
          () => [] as Item[]
        )
      : Promise.resolve([] as Item[]);

    const [gwHits, tmdbHits] = await Promise.all([gwPromise, tmdbPromise]);
    const seenIds = new Set<string>();
    const seenTitleType = new Set<string>();
    const out: Item[] = [];

    for (const it of [...gwHits, ...tmdbHits]) {
      if (seenIds.has(it.id)) continue;
      const key = `${normalizeTitleKey(it.title)}|${it.type}`;
      if (seenTitleType.has(key)) continue;
      seenIds.add(it.id);
      seenTitleType.add(key);
      out.push(it);
    }

    this.rememberItems(out);
    return out;
  }

  /** Alias for `remoteSearch(query, signal)`. */
  search(query: string, signal?: AbortSignal): Promise<Item[]> {
    return this.remoteSearch(query, signal);
  }

  /** Remote suggestions (gateway `/search/suggest`). */
  async suggest(query: string, signal?: AbortSignal): Promise<string[]> {
    if (!this.gateway || !this.online()) return [];
    return this.gateway.suggest(query, signal).catch(() => []);
  }

  /**
   * Clickable Cast & Crew filmography: combines Gateway `/search?q=<name>` + TMDB
   * `/search/person` → `/person/{id}/combined_credits`, deduplicated and seeded with playable sources.
   */
  async castWorks(personName: string, signal?: AbortSignal): Promise<Item[]> {
    const name = personName.trim();
    if (!name) return [];

    const gwTask =
      this.gateway && this.online()
        ? this.gateway.search(name, signal).then(
            (hits) => hits.filter((i) => isVideoSpace(i.space)),
            () => [] as Item[]
          )
        : Promise.resolve([] as Item[]);

    const tmdbTask = (async (): Promise<Item[]> => {
      if (!this.tmdbClient || !this.online()) return [];
      try {
        const people = await this.tmdbClient.get<{ results?: { id?: number; name?: string }[] }>(
          '/search/person',
          { query: name, page: 1 },
          signal
        );
        const list = people.results ?? [];
        const normWant = normalize(name);
        const person = list.find((p) => p.name && normalize(p.name) === normWant) ?? list[0];
        if (!person?.id) return [];
        const credits = await this.tmdbClient.get<{ cast?: TmdbResult[] }>(
          `/person/${person.id}/combined_credits`,
          {},
          signal
        );
        const rawCast = [...(credits.cast ?? [])].sort(
          (a, b) => (b.popularity ?? 0) - (a.popularity ?? 0) || (b.vote_average ?? 0) - (a.vote_average ?? 0)
        );
        const colorOf = inspectTmdb(this.tmdb)?.colorOf;
        const mapped: Item[] = [];
        for (const r of rawCast) {
          const space = classify(r);
          if (!space) continue;
          const media: TmdbMedia =
            r.media_type === 'movie' || r.media_type === 'tv' ? r.media_type : r.title !== undefined ? 'movie' : 'tv';
          const baseItem = mapResult(r, space, media, { colorOf });
          if (!baseItem) continue;
          const playable = this.gateway || this.gw ? attachFindSources(baseItem) : baseItem;
          mapped.push(playable);
          if (mapped.length >= 36) break;
        }
        return mapped;
      } catch {
        return [];
      }
    })();

    const [gwWorks, tmdbWorks] = await Promise.all([gwTask, tmdbTask]);
    const localCastMatches = [...this.summaries.values()].filter(
      (i) => isVideoSpace(i.space) && i.cast?.some((c) => normalize(c) === normalize(name))
    );

    const seenIds = new Set<string>();
    const seenTitles = new Set<string>();
    const merged: Item[] = [];
    for (const it of [...gwWorks, ...tmdbWorks, ...localCastMatches]) {
      if (seenIds.has(it.id)) continue;
      const tKey = normalizeTitleKey(it.title);
      if (tKey && seenTitles.has(tKey)) continue;
      seenIds.add(it.id);
      if (tKey) seenTitles.add(tKey);
      merged.push(it);
      if (merged.length >= 40) break;
    }

    this.rememberItems(merged);
    return merged;
  }

  /**
   * Pre-warms `HOME`, `ANIME`, and `DRAMA` catalogs concurrently during splash and returns up to 24
   * sized hero + top-row image URLs ready for immediate browser `Image.decode()` pre-caching.
   */
  async warmStartupSpaces(): Promise<string[]> {
    const settled = await Promise.allSettled(VIDEO_SPACES.map((s) => this.file(s)));
    const files = settled
      .filter((r): r is PromiseFulfilledResult<CatalogFile> => r.status === 'fulfilled')
      .map((r) => r.value);
    if (!files.length) return [];

    const urls: string[] = [];
    const seen = new Set<string>();
    const pushImg = (rawPath: string | undefined, kind: 'hero' | 'poster' | 'backdrop') => {
      if (!rawPath || urls.length >= 24) return;
      const src = imgAttrs(rawPath, kind, 'MID', this.asset).src;
      if (!src || seen.has(src)) return;
      seen.add(src);
      urls.push(src);
    };

    // Pass 1: hero images across HOME, ANIME, DRAMA first so instant tab-switching paints zero placeholders.
    for (const f of files) {
      const cat = f.categories[0];
      if (!cat) continue;
      const byId = new Map(f.items.map((i) => [i.id, i]));
      for (const hid of cat.heroIds.slice(0, 3)) {
        const it = byId.get(hid);
        pushImg(it?.backdrop || it?.poster, 'hero');
      }
    }

    // Pass 2: first 2 shelves' visible posters/backdrops across HOME, ANIME, DRAMA.
    for (const f of files) {
      const cat = f.categories[0];
      if (!cat) continue;
      const byId = new Map(f.items.map((i) => [i.id, i]));
      for (const row of cat.rows.slice(0, 2)) {
        const kind = row.style === 'LANDSCAPE' ? 'backdrop' : 'poster';
        for (const iid of row.itemIds.slice(0, 4)) {
          const it = byId.get(iid);
          pushImg(kind === 'backdrop' ? it?.backdrop || it?.poster : it?.poster, kind);
          if (urls.length >= 24) return urls;
        }
      }
    }

    return urls;
  }
}
