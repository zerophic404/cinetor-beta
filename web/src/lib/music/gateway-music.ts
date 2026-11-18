// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
// scrape or hack anything without Mythbrix's explicit permission.
// Live Gateway Music API client + defensive JSON parsers + IndexedDB/LRU cache + offline fallback.
// Pure TS (relative imports only, zero framework aliases) so Vitest drives it directly.
import { fallbackColor } from '../data/color';
import { GW_API, gatewayBase, gatewaySecret, signedGatewayFetch, type SignedResponseLike } from '../data/gateway';
import type { PersistentCache } from '../data/idb';
import { Lru } from '../data/lru';
import type { CatalogFile, Item, Source, SourceKind } from '../data/types';
import type { Media } from '../player/sources';
import type { Track } from './core';
import { activeLyricIndex, parseLrc, plainLyricParagraphs, type LyricLine, type MusicLyrics } from './lyrics';
import { fmtDur } from './queue';

export { activeLyricIndex, parseLrc, plainLyricParagraphs, type LyricLine, type MusicLyrics };

export type MusicQuality = 'flac' | '320kbps';
export type MusicSearchType = 'song' | 'album' | 'playlist';

/** Keeps MUSIC space isolation (`spaceForId` maps prefix `mu-` → `MUSIC`). */
export const MUSIC_GW_PREFIX = 'mu-gw:';
export const LAZY_MUSIC_STREAM_PREFIX = 'mu-stream:';

export const DEFAULT_MUSIC_CATEGORIES = [
  'Trending',
  'Top Charts',
  'Bollywood',
  'Bengali',
  'Arijit Singh',
  'English Hits',
  'Punjabi',
  'Romantic',
  'Lo-Fi & Chill'
] as const;

export const muId = (rawId: string): string => (rawId.startsWith('mu-') ? rawId : `${MUSIC_GW_PREFIX}${rawId}`);
export const rawMusicId = (id: string): string => (id.startsWith(MUSIC_GW_PREFIX) ? id.slice(MUSIC_GW_PREFIX.length) : id);
export const isGatewayMusicId = (id: string): boolean => id.startsWith(MUSIC_GW_PREFIX);
export const lazyMusicUrl = (rawId: string): string => `${LAZY_MUSIC_STREAM_PREFIX}${rawId}`;
export const isLazyMusicUrl = (url: string): boolean => url.startsWith(LAZY_MUSIC_STREAM_PREFIX);

export interface MusicTrack {
  id: string;
  rawId: string;
  title: string;
  artist: string;
  album: string;
  albumId: string | null;
  year: string | null;
  duration: number;
  durationLabel: string;
  artwork: string;
  quality: string;
  format: string;
  hasFlac: boolean;
  mockUrl?: string;
}

export interface MusicChart {
  id: string;
  rawId: string;
  title: string;
  artwork: string;
  count: number;
  subtitle?: string;
}

export interface MusicAlbumSummary {
  id: string;
  rawId: string;
  title: string;
  artist: string;
  year: string | null;
  artwork: string;
  count: number;
}

export interface MusicAlbum {
  id: string;
  rawId: string;
  title: string;
  artist: string;
  year: string | null;
  artwork: string;
  tracks: MusicTrack[];
}

export interface MusicPlaylist {
  id: string;
  rawId: string;
  title: string;
  artist?: string;
  artwork: string;
  count: number;
  tracks: MusicTrack[];
}

export interface MusicTrendingFeed {
  charts: MusicChart[];
  trendingTracks: MusicTrack[];
  categories: string[];
  offlineFallback?: boolean;
}

export interface MusicStreamInfo {
  streamUrl: string;
  downloadUrl: string;
  quality: string;
  format: string;
  headers: Record<string, string>;
}

// ------------------------------------------------------------------------------------ JSON helpers

type Obj = Record<string, unknown>;

const asObj = (v: unknown): Obj | null => (v !== null && typeof v === 'object' && !Array.isArray(v) ? (v as Obj) : null);
const asArr = (v: unknown): unknown[] | null => (Array.isArray(v) ? v : null);

function str(v: unknown): string | null {
  if (typeof v === 'string') {
    const s = v.trim();
    return s && s !== 'null' && s !== 'undefined' ? s : null;
  }
  if (typeof v === 'number' && Number.isFinite(v)) return String(v);
  return null;
}

function int(v: unknown): number | null {
  if (typeof v === 'number' && Number.isFinite(v)) return Math.trunc(v);
  if (typeof v === 'string' && v.trim()) {
    const n = Number(v.trim());
    if (Number.isFinite(n)) return Math.trunc(n);
  }
  return null;
}

function bool(v: unknown): boolean | null {
  if (typeof v === 'boolean') return v;
  if (typeof v === 'string') {
    const s = v.trim().toLowerCase();
    if (s === 'true' || s === '1') return true;
    if (s === 'false' || s === '0') return false;
  }
  return null;
}

function resolveGatewayUrl(raw: string | null, base?: string): string {
  if (!raw) return '';
  if (/^(?:https?:|blob:|data:)/i.test(raw)) return raw;
  if (raw.startsWith('//')) return `https:${raw}`;
  if (raw.startsWith('/') && base) return `${base.replace(/\/+$/, '')}${raw}`;
  return raw;
}

export function parseDurationLabel(label: string | null | undefined): number {
  if (!label) return 0;
  const parts = label.trim().split(':').map((p) => Number(p));
  if (parts.some((n) => !Number.isFinite(n) || n < 0)) return 0;
  if (parts.length === 2) return Math.trunc(parts[0] * 60 + parts[1]);
  if (parts.length === 3) return Math.trunc(parts[0] * 3600 + parts[1] * 60 + parts[2]);
  return 0;
}

// ------------------------------------------------------------------------------------ Wire parsers

export function parseMusicTrack(raw: unknown, base?: string): MusicTrack | null {
  const o = asObj(raw);
  if (!o) return null;
  const rawId = rawMusicId(str(o.id) ?? '');
  const title = str(o.title) ?? str(o.name);
  if (!rawId || !title) return null;

  const explicitDur = Math.max(0, int(o.duration) ?? 0);
  const labelStr = str(o.durationLabel);
  const duration = explicitDur > 0 ? explicitDur : parseDurationLabel(labelStr);
  const durationLabel = labelStr && labelStr !== '0:00' ? labelStr : fmtDur(duration);
  const rawAlbumId = str(o.albumId);

  return {
    id: muId(rawId),
    rawId,
    title,
    artist: str(o.artist) ?? str(o.subtitle) ?? 'Unknown Artist',
    album: str(o.album) ?? '',
    albumId: rawAlbumId ? muId(rawMusicId(rawAlbumId)) : null,
    year: str(o.year),
    duration,
    durationLabel,
    artwork: resolveGatewayUrl(str(o.artwork) ?? str(o.thumbnail) ?? str(o.cover), base),
    quality: str(o.quality) ?? '320kbps Studio Master',
    format: (str(o.format) ?? 'mp4').toLowerCase(),
    hasFlac: bool(o.hasFlac) ?? true
  };
}

export function parseMusicChart(raw: unknown, base?: string): MusicChart | null {
  const o = asObj(raw);
  if (!o) return null;
  const rawId = rawMusicId(str(o.id) ?? '');
  if (!rawId) return null;
  const title = str(o.title) ?? str(o.name) ?? 'Top Chart';
  return {
    id: muId(rawId),
    rawId,
    title,
    artwork: resolveGatewayUrl(str(o.artwork) ?? str(o.thumbnail) ?? str(o.cover), base),
    count: Math.max(0, int(o.count) ?? int(o.trackCount) ?? int(o.songCount) ?? 0),
    subtitle: str(o.artist) ?? str(o.subtitle) ?? undefined
  };
}

export function parseMusicAlbumSummary(raw: unknown, base?: string): MusicAlbumSummary | null {
  const o = asObj(raw);
  if (!o) return null;
  const rawId = rawMusicId(str(o.id) ?? str(o.albumId) ?? '');
  const title = str(o.title) ?? str(o.album) ?? str(o.name);
  if (!rawId || !title) return null;
  return {
    id: muId(rawId),
    rawId,
    title,
    artist: str(o.artist) ?? str(o.subtitle) ?? 'Various Artists',
    year: str(o.year),
    artwork: resolveGatewayUrl(str(o.artwork) ?? str(o.thumbnail) ?? str(o.cover), base),
    count: Math.max(0, int(o.count) ?? int(o.trackCount) ?? 0)
  };
}

export function parseMusicTrending(raw: unknown, base?: string): MusicTrendingFeed {
  const o = asObj(raw);
  if (!o) {
    return { charts: [], trendingTracks: [], categories: [...DEFAULT_MUSIC_CATEGORIES] };
  }
  const charts = (asArr(o.charts) ?? [])
    .map((c) => parseMusicChart(c, base))
    .filter((c): c is MusicChart => c !== null);

  const trackSource = asArr(o.trendingTracks) ?? asArr(o.tracks) ?? [];
  const trendingTracks = trackSource
    .map((t) => parseMusicTrack(t, base))
    .filter((t): t is MusicTrack => t !== null);

  const catsRaw = (asArr(o.categories) ?? [])
    .map((c) => str(c))
    .filter((c): c is string => c !== null);
  const categories = catsRaw.length ? [...new Set(catsRaw)] : [...DEFAULT_MUSIC_CATEGORIES];

  return { charts, trendingTracks, categories };
}

export function parseMusicAlbum(raw: unknown, fallbackId = '', base?: string): MusicAlbum | null {
  const o = asObj(raw);
  if (!o) return null;
  const rawId = rawMusicId(str(o.id) ?? fallbackId);
  const tracks = (asArr(o.tracks) ?? asArr(o.results) ?? [])
    .map((t) => parseMusicTrack(t, base))
    .filter((t): t is MusicTrack => t !== null);
  const title = str(o.title) ?? str(o.name) ?? tracks[0]?.album ?? null;
  if (!rawId || (!title && !tracks.length)) return null;
  return {
    id: muId(rawId),
    rawId,
    title: title ?? 'Album',
    artist: str(o.artist) ?? tracks[0]?.artist ?? 'Unknown Artist',
    year: str(o.year) ?? tracks[0]?.year ?? null,
    artwork: resolveGatewayUrl(str(o.artwork) ?? str(o.cover), base) || tracks[0]?.artwork || '',
    tracks
  };
}

export function parseMusicPlaylist(raw: unknown, fallbackId = '', base?: string): MusicPlaylist | null {
  const o = asObj(raw);
  if (!o) return null;
  const rawId = rawMusicId(str(o.id) ?? fallbackId);
  const tracks = (asArr(o.tracks) ?? asArr(o.results) ?? [])
    .map((t) => parseMusicTrack(t, base))
    .filter((t): t is MusicTrack => t !== null);
  const title = str(o.title) ?? str(o.name) ?? null;
  if (!rawId || (!title && !tracks.length)) return null;
  const count = Math.max(tracks.length, int(o.count) ?? 0);
  return {
    id: muId(rawId),
    rawId,
    title: title ?? 'Playlist',
    artist: str(o.artist) ?? str(o.subtitle) ?? undefined,
    artwork: resolveGatewayUrl(str(o.artwork) ?? str(o.cover), base) || tracks[0]?.artwork || '',
    count,
    tracks
  };
}

export function parseMusicLyrics(raw: unknown): MusicLyrics | null {
  const o = asObj(raw);
  if (!o) return null;
  const plain = str(o.plainLyrics) ?? str(o.lyrics);
  const synced = str(o.syncedLyrics) ?? str(o.lrc);
  if (!plain && !synced) return null;
  return {
    plainLyrics: plain,
    syncedLyrics: synced,
    lines: parseLrc(synced)
  };
}

export function parseMusicStream(raw: unknown, base?: string, requestedQuality?: MusicQuality): MusicStreamInfo | null {
  const o = asObj(raw);
  if (!o) return null;
  const streamUrl = resolveGatewayUrl(str(o.streamUrl) ?? str(o.url) ?? str(o.playUrl), base);
  if (!streamUrl || !/^(?:https?:|blob:)/i.test(streamUrl)) return null;
  const downloadUrl = resolveGatewayUrl(str(o.downloadUrl), base) || streamUrl;
  const defaultQuality = requestedQuality === 'flac' ? 'FLAC Lossless' : '320kbps Studio Master';
  const defaultFormat = requestedQuality === 'flac' ? 'flac' : 'mp4';
  const headers: Record<string, string> = {};
  const rawHeaders = asObj(o.headers);
  if (rawHeaders) {
    for (const [k, v] of Object.entries(rawHeaders)) {
      if (typeof v === 'string' && v.trim()) headers[k] = v.trim();
    }
  }
  return {
    streamUrl,
    downloadUrl,
    quality: str(o.quality) ?? defaultQuality,
    format: (str(o.format) ?? defaultFormat).toLowerCase(),
    headers
  };
}

// ------------------------------------------------------------------------------------ Track & Download Mapping

export function qualityShortLabel(quality: MusicQuality): string {
  return quality === 'flac' ? 'FLAC' : '320kbps';
}

export function qualityBadgeLabel(quality: MusicQuality, hasFlac = true): string {
  return quality === 'flac' && hasFlac ? 'FLAC Lossless' : '320kbps Studio Master';
}

/** Maps gateway audio container into Cinetor's progressive `SourceKind` (never `.bin`). */
export function sourceKindForFormat(format: string): SourceKind {
  const f = format.trim().toLowerCase();
  if (f.includes('webm') || f.includes('opus') || f.includes('ogg')) return 'WEBM';
  if (f.includes('mp4') || f.includes('m4a') || f.includes('aac')) return 'MP4';
  return 'MP3';
}

export function estimatedTrackBytes(durationSec: number, quality: MusicQuality): number {
  const sec = durationSec > 0 ? durationSec : 210;
  const bitsPerSec = quality === 'flac' ? 920_000 : 320_000;
  return Math.round((sec * bitsPerSec) / 8);
}

export function musicTrackToTrack(t: MusicTrack, colorOf?: (art: string) => string | undefined): Track {
  return {
    id: t.id,
    rawId: t.rawId,
    title: t.title,
    artist: t.artist,
    album: t.album,
    albumId: t.albumId ?? undefined,
    year: t.year ?? undefined,
    art: t.artwork,
    color: (t.artwork ? colorOf?.(t.artwork) : undefined) ?? fallbackColor(t.id),
    accent: '#E11D2E',
    url: t.mockUrl ?? lazyMusicUrl(t.rawId),
    durationSec: t.duration,
    durationLabel: t.durationLabel,
    quality: t.quality,
    format: t.format,
    hasFlac: t.hasFlac
  };
}

/** Builds a `Media` descriptor ready for `downloads.start(...)` (Web OPFS & Windows/Linux Desktop `Downloads/Cinetor`). */
export function buildMusicDownloadMedia(
  track: Pick<Track, 'id' | 'title' | 'artist' | 'album' | 'art' | 'color' | 'accent' | 'durationSec'> & { hasFlac?: boolean },
  stream: MusicStreamInfo,
  quality: MusicQuality
): { media: Media; choiceLabel: string; estimatedBytes: number } {
  const targetUrl = stream.downloadUrl || stream.streamUrl;
  const kind = sourceKindForFormat(stream.format);
  const effectiveQuality: MusicQuality = quality === 'flac' && track.hasFlac !== false ? 'flac' : '320kbps';
  const choiceLabel = effectiveQuality === 'flac' ? 'FLAC Lossless' : '320kbps';
  const hasHeaders = Object.keys(stream.headers).length > 0;
  const source: Source = {
    url: targetUrl,
    kind,
    label: choiceLabel,
    headers: hasHeaders ? stream.headers : undefined,
    direct: hasHeaders ? { url: targetUrl, headers: stream.headers } : undefined
  };
  const id = muId(rawMusicId(track.id));
  const item: Item = {
    id,
    space: 'MUSIC',
    categoryId: 'mu-downloads',
    type: 'TRACK',
    title: track.title,
    artist: track.artist,
    album: track.album,
    year: 0,
    rating: 0,
    maturity: 'NR',
    genres: [],
    synopsis: '',
    poster: track.art,
    backdrop: track.art,
    dominantColor: track.color || fallbackColor(id),
    accentColor: track.accent || '#E11D2E',
    durationSec: track.durationSec || 0,
    sources: [source]
  };
  const subtitleParts = [track.artist, track.album].filter(Boolean);
  const media: Media = {
    key: id,
    item,
    source,
    playable: true,
    title: track.title,
    subtitle: subtitleParts.join(' · ') || choiceLabel,
    subtitles: [],
    durationSec: track.durationSec || undefined
  };
  return {
    media,
    choiceLabel,
    estimatedBytes: estimatedTrackBytes(track.durationSec, effectiveQuality)
  };
}

// ------------------------------------------------------------------------------------ Offline Catalog Fallback

function mockItemToMusicTrack(i: Item, asset: (p: string) => string = (p) => p): MusicTrack | null {
  if (i.type !== 'TRACK') return null;
  const src = i.sources?.find((s) => s.kind === 'MP3') ?? i.sources?.[0];
  if (!src) return null;
  const dur = i.durationSec ?? 0;
  return {
    id: i.id,
    rawId: i.id,
    title: i.title,
    artist: i.artist ?? 'Unknown Artist',
    album: i.album ?? '',
    albumId: i.albumId ?? null,
    year: i.year ? String(i.year) : null,
    duration: dur,
    durationLabel: fmtDur(dur),
    artwork: asset(i.poster),
    quality: '320kbps Offline',
    format: src.kind.toLowerCase(),
    hasFlac: false,
    mockUrl: asset(src.url)
  };
}

export function catalogToTrending(file: CatalogFile, asset: (p: string) => string = (p) => p): MusicTrendingFeed {
  const byId = new Map(file.items.map((i) => [i.id, i] as const));
  const albums = file.items.filter((i) => i.type === 'ALBUM');
  const charts: MusicChart[] = albums.map((al) => ({
    id: al.id,
    rawId: al.id,
    title: al.title,
    artwork: asset(al.poster),
    count: al.trackIds?.length ?? 0,
    subtitle: al.artist
  }));
  const discover = file.categories.find((c) => c.id === 'mu-discover');
  const topIds = discover?.rows.find((r) => r.style === 'TOP10')?.itemIds ?? [];
  const orderedTrackItems = topIds.length
    ? topIds.map((id) => byId.get(id)).filter((i): i is Item => !!i)
    : file.items.filter((i) => i.type === 'TRACK');
  const trendingTracks = orderedTrackItems
    .map((i) => mockItemToMusicTrack(i, asset))
    .filter((t): t is MusicTrack => t !== null);
  const categories = ['Trending', ...(file.genres ?? []).filter(Boolean)];
  return { charts, trendingTracks, categories, offlineFallback: true };
}

export function catalogToAlbum(file: CatalogFile, id: string, asset: (p: string) => string = (p) => p): MusicAlbum | null {
  const byId = new Map(file.items.map((i) => [i.id, i] as const));
  const want = rawMusicId(id);
  const al = byId.get(id) ?? byId.get(want);
  if (!al || al.type !== 'ALBUM') return null;
  const tracks = (al.trackIds ?? [])
    .map((tid) => byId.get(tid))
    .map((i) => (i ? mockItemToMusicTrack(i, asset) : null))
    .filter((t): t is MusicTrack => t !== null);
  return {
    id: al.id,
    rawId: al.id,
    title: al.title,
    artist: al.artist ?? 'Unknown Artist',
    year: al.year ? String(al.year) : null,
    artwork: asset(al.poster),
    tracks
  };
}

export function catalogToPlaylist(file: CatalogFile, id: string, asset: (p: string) => string = (p) => p): MusicPlaylist | null {
  const asAlbum = catalogToAlbum(file, id, asset);
  if (asAlbum) {
    return {
      id: asAlbum.id,
      rawId: asAlbum.rawId,
      title: asAlbum.title,
      artist: asAlbum.artist,
      artwork: asAlbum.artwork,
      count: asAlbum.tracks.length,
      tracks: asAlbum.tracks
    };
  }
  const byId = new Map(file.items.map((i) => [i.id, i] as const));
  for (const cat of file.categories) {
    const row = cat.rows.find((r) => r.id === id || r.id === rawMusicId(id));
    if (row) {
      const tracks = row.itemIds
        .map((tid) => byId.get(tid))
        .map((i) => (i ? mockItemToMusicTrack(i, asset) : null))
        .filter((t): t is MusicTrack => t !== null);
      return {
        id: row.id,
        rawId: row.id,
        title: row.title,
        artwork: tracks[0]?.artwork ?? '',
        count: tracks.length,
        tracks
      };
    }
  }
  return null;
}

export function catalogSearchTracks(file: CatalogFile, query: string, asset: (p: string) => string = (p) => p): MusicTrack[] {
  const q = query.trim().toLowerCase();
  if (!q) return [];
  return file.items
    .filter((i) => i.type === 'TRACK' && `${i.title} ${i.artist ?? ''} ${i.album ?? ''} ${i.genres.join(' ')}`.toLowerCase().includes(q))
    .map((i) => mockItemToMusicTrack(i, asset))
    .filter((t): t is MusicTrack => t !== null);
}

export function catalogSearchAlbums(file: CatalogFile, query: string, asset: (p: string) => string = (p) => p): MusicAlbumSummary[] {
  const q = query.trim().toLowerCase();
  if (!q) return [];
  return file.items
    .filter((i) => i.type === 'ALBUM' && `${i.title} ${i.artist ?? ''} ${i.genres.join(' ')}`.toLowerCase().includes(q))
    .map((al) => ({
      id: al.id,
      rawId: al.id,
      title: al.title,
      artist: al.artist ?? 'Various Artists',
      year: al.year ? String(al.year) : null,
      artwork: asset(al.poster),
      count: al.trackIds?.length ?? 0
    }));
}

// ------------------------------------------------------------------------------------ Live Client

interface CachedEntry<T = unknown> {
  ts: number;
  v: T;
}

export type MusicFetcher = (url: string, init?: RequestInit) => Promise<SignedResponseLike>;

export interface GatewayMusicOptions {
  base?: () => string;
  fetcher?: MusicFetcher;
  secret?: () => string;
  persist?: PersistentCache | null;
  offlineCatalog?: () => Promise<CatalogFile>;
  asset?: (path: string) => string;
  ttlMs?: number;
  streamTtlMs?: number;
  timeoutMs?: number;
  now?: () => number;
}

export class GatewayMusicClient {
  private mem = new Lru<string, CachedEntry>(120);
  private streamMem = new Lru<string, CachedEntry<MusicStreamInfo>>(80);
  private inflight = new Map<string, Promise<unknown>>();
  private readonly getBase: () => string;
  private readonly fetcher: MusicFetcher;
  private readonly getSecret: () => string;
  private readonly persist: PersistentCache | null;
  private readonly offlineCatalog?: () => Promise<CatalogFile>;
  private readonly asset: (p: string) => string;
  private readonly ttlMs: number;
  private readonly streamTtlMs: number;
  private readonly timeoutMs: number;
  private readonly now: () => number;

  constructor(opts: GatewayMusicOptions = {}) {
    this.getBase = opts.base ?? (() => gatewayBase());
    this.fetcher = opts.fetcher ?? ((u, init) => fetch(u, init));
    this.getSecret = opts.secret ?? (() => gatewaySecret());
    this.persist = opts.persist ?? null;
    this.offlineCatalog = opts.offlineCatalog;
    this.asset = opts.asset ?? ((p) => p);
    this.ttlMs = opts.ttlMs ?? 10 * 60_000;
    this.streamTtlMs = opts.streamTtlMs ?? 10 * 60_000;
    this.timeoutMs = opts.timeoutMs ?? 10_000;
    this.now = opts.now ?? (() => Date.now());
  }

  get base(): string {
    return this.getBase().replace(/\/+$/, '');
  }

  get enabled(): boolean {
    return this.base.length > 0;
  }

  url(subpath: string, params: Record<string, string | number | undefined> = {}): string {
    const u = new URL(`${this.base}${GW_API}/music${subpath}`);
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== '') u.searchParams.set(k, String(v));
    }
    return u.toString();
  }

  private async fetchJson<T>(url: string, signal?: AbortSignal): Promise<T> {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), this.timeoutMs);
    const onAbort = () => ctl.abort();
    signal?.addEventListener('abort', onAbort);
    try {
      const res = await signedGatewayFetch(
        url,
        {
          signal: ctl.signal,
          headers: { accept: 'application/json' }
        },
        this.base,
        this.fetcher,
        this.getSecret()
      );
      if (!res.ok) throw new Error(`Music HTTP ${res.status}`);
      return (await res.json()) as T;
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
    }
  }

  /** Cached metadata GET (memory LRU → fresh IDB → network → stale IDB). */
  private async cachedGet<T>(subpath: string, params: Record<string, string | number | undefined> = {}, signal?: AbortSignal): Promise<T> {
    if (!this.enabled) throw new Error('Gateway off');
    const url = this.url(subpath, params);
    const now = this.now();
    const memHit = this.mem.get(url);
    if (memHit && now - memHit.ts < this.ttlMs) return memHit.v as T;

    const pending = this.inflight.get(url);
    if (pending) return pending as Promise<T>;

    const idbKey = `gw:music:${subpath}?${new URL(url).searchParams.toString()}`;
    const p = (async () => {
      const stored = this.persist ? ((await this.persist.get(idbKey)) as CachedEntry<T> | undefined) : undefined;
      if (stored && typeof stored.ts === 'number' && now - stored.ts < this.ttlMs) {
        this.mem.set(url, stored);
        return stored.v;
      }
      try {
        const fresh = await this.fetchJson<T>(url, signal);
        const entry: CachedEntry<T> = { ts: this.now(), v: fresh };
        this.mem.set(url, entry);
        if (this.persist) void this.persist.set(idbKey, entry);
        return fresh;
      } catch (e) {
        if (stored && !signal?.aborted) {
          this.mem.set(url, stored);
          return stored.v;
        }
        throw e;
      }
    })();

    this.inflight.set(url, p);
    p.finally(() => this.inflight.delete(url)).catch(() => undefined);
    return p;
  }

  async trending(signal?: AbortSignal): Promise<MusicTrendingFeed> {
    try {
      const raw = await this.cachedGet<unknown>('/trending', {}, signal);
      const parsed = parseMusicTrending(raw, this.base);
      if (parsed.charts.length || parsed.trendingTracks.length) return parsed;
    } catch (e) {
      if (signal?.aborted) throw e;
    }
    if (this.offlineCatalog) {
      const file = await this.offlineCatalog();
      return catalogToTrending(file, this.asset);
    }
    return { charts: [], trendingTracks: [], categories: [...DEFAULT_MUSIC_CATEGORIES] };
  }

  async searchTracks(query: string, page = 1, signal?: AbortSignal): Promise<MusicTrack[]> {
    const q = query.trim();
    if (!q) return [];
    try {
      const raw = await this.cachedGet<Obj>('/search', { q, type: 'song', page }, signal);
      const list = asArr(raw?.results) ?? asArr(raw?.tracks) ?? asArr(raw?.items) ?? [];
      return list.map((t) => parseMusicTrack(t, this.base)).filter((t): t is MusicTrack => t !== null);
    } catch (e) {
      if (signal?.aborted) throw e;
      if (this.offlineCatalog && page === 1) {
        const file = await this.offlineCatalog().catch(() => null);
        if (file) return catalogSearchTracks(file, q, this.asset);
      }
      return [];
    }
  }

  async searchAlbums(query: string, page = 1, signal?: AbortSignal): Promise<MusicAlbumSummary[]> {
    const q = query.trim();
    if (!q) return [];
    try {
      const raw = await this.cachedGet<Obj>('/search', { q, type: 'album', page }, signal);
      const list = asArr(raw?.results) ?? asArr(raw?.albums) ?? asArr(raw?.items) ?? [];
      return list.map((a) => parseMusicAlbumSummary(a, this.base)).filter((a): a is MusicAlbumSummary => a !== null);
    } catch (e) {
      if (signal?.aborted) throw e;
      if (this.offlineCatalog && page === 1) {
        const file = await this.offlineCatalog().catch(() => null);
        if (file) return catalogSearchAlbums(file, q, this.asset);
      }
      return [];
    }
  }

  async searchPlaylists(query: string, page = 1, signal?: AbortSignal): Promise<MusicChart[]> {
    const q = query.trim();
    if (!q) return [];
    try {
      const raw = await this.cachedGet<Obj>('/search', { q, type: 'playlist', page }, signal);
      const list = asArr(raw?.results) ?? asArr(raw?.playlists) ?? asArr(raw?.items) ?? [];
      return list.map((p) => parseMusicChart(p, this.base)).filter((p): p is MusicChart => p !== null);
    } catch (e) {
      if (signal?.aborted) throw e;
      if (this.offlineCatalog && page === 1) {
        const file = await this.offlineCatalog().catch(() => null);
        if (file) {
          return catalogSearchAlbums(file, q, this.asset).map((a) => ({
            id: a.id,
            rawId: a.rawId,
            title: a.title,
            artwork: a.artwork,
            count: a.count,
            subtitle: a.artist
          }));
        }
      }
      return [];
    }
  }

  async album(id: string, signal?: AbortSignal): Promise<MusicAlbum | null> {
    const rawId = rawMusicId(id.trim());
    if (!rawId) return null;
    if (!rawId.startsWith('mu-al-')) {
      try {
        const raw = await this.cachedGet<unknown>('/album', { id: rawId }, signal);
        const parsed = parseMusicAlbum(raw, rawId, this.base);
        if (parsed && parsed.tracks.length) return parsed;
      } catch (e) {
        if (signal?.aborted) throw e;
      }
    }
    if (this.offlineCatalog) {
      const file = await this.offlineCatalog().catch(() => null);
      if (file) return catalogToAlbum(file, id, this.asset);
    }
    return null;
  }

  async playlist(id: string, signal?: AbortSignal): Promise<MusicPlaylist | null> {
    const rawId = rawMusicId(id.trim());
    if (!rawId) return null;
    if (!rawId.startsWith('mu-')) {
      try {
        const raw = await this.cachedGet<unknown>('/playlist', { id: rawId }, signal);
        const parsed = parseMusicPlaylist(raw, rawId, this.base);
        if (parsed && parsed.tracks.length) return parsed;
      } catch (e) {
        if (signal?.aborted) throw e;
      }
    }
    if (this.offlineCatalog) {
      const file = await this.offlineCatalog().catch(() => null);
      if (file) return catalogToPlaylist(file, id, this.asset);
    }
    return null;
  }

  async lyrics(id: string, title = '', artist = '', signal?: AbortSignal): Promise<MusicLyrics | null> {
    const rawId = rawMusicId(id.trim());
    if (!rawId || rawId.startsWith('mu-tr-')) return null;
    try {
      const raw = await this.cachedGet<unknown>('/lyrics', { id: rawId, title: title.trim(), artist: artist.trim() }, signal);
      return parseMusicLyrics(raw);
    } catch {
      return null;
    }
  }

  /**
   * Resolves a playable/downloadable audio stream (`flac` or `320kbps`).
   * Kept strictly in short-lived memory LRU — never written to persistent storage.
   */
  async stream(id: string, quality: MusicQuality = 'flac', signal?: AbortSignal): Promise<MusicStreamInfo | null> {
    const rawId = rawMusicId(id.trim());
    if (!rawId || !this.enabled) return null;
    const cacheKey = `${rawId}:${quality}`;
    const now = this.now();
    const hit = this.streamMem.get(cacheKey);
    if (hit && now - hit.ts < this.streamTtlMs) return hit.v;

    const tryFetch = async (params: Record<string, string | undefined>): Promise<MusicStreamInfo | null> => {
      try {
        const raw = await this.fetchJson<unknown>(this.url('/stream', params), signal);
        return parseMusicStream(raw, this.base, quality);
      } catch (e) {
        if (signal?.aborted) throw e;
        return null;
      }
    };

    const resolved =
      (await tryFetch({ id: rawId, quality })) ??
      (quality === '320kbps' ? await tryFetch({ id: rawId, quality: '320' }) : null) ??
      (await tryFetch({ id: rawId }));

    if (resolved) {
      this.streamMem.set(cacheKey, { ts: this.now(), v: resolved });
    }
    return resolved;
  }

  invalidateStream(id: string): void {
    const rawId = rawMusicId(id.trim());
    this.streamMem.delete(`${rawId}:flac`);
    this.streamMem.delete(`${rawId}:320kbps`);
  }
}
