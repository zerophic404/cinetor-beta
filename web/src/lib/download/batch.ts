// Pure multi-season batch selection, quality matching, title badge & download grouping helpers.
// Relative imports only — vitest runs pure modules without `$lib` path aliases.
//
// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer, scrape
// or hack anything without Mythbrix's explicit permission.
import type { Episode } from '../data/types';
import { percent, qualityList, type QualityLike } from './math';
import type { QueueStatus } from './queue';

export const BATCH_QUALITIES = [1080, 720, 480, 360] as const;
export type BatchQualityHeight = (typeof BATCH_QUALITIES)[number];

export interface BatchEpisodeLike {
  id: string;
  n: number;
  title: string;
  synopsis?: string;
  durationSec?: number;
}

export interface BatchSelectionState<E extends BatchEpisodeLike = Episode> {
  readonly seasons: Map<number, readonly E[]>;
  readonly selectedKeys: Set<string>;
}

export function batchEpisodeKey(seasonNumber: number, episodeId: string): string {
  return `${seasonNumber}:${episodeId}`;
}

export function parseBatchEpisodeKey(key: string): { seasonNumber: number; episodeId: string } | null {
  const idx = key.indexOf(':');
  if (idx <= 0) return null;
  const seasonNumber = Number(key.slice(0, idx));
  const episodeId = key.slice(idx + 1);
  if (!Number.isInteger(seasonNumber) || seasonNumber < 0 || !episodeId) return null;
  return { seasonNumber, episodeId };
}

function isIterableEntries<E>(
  v: Iterable<[number, readonly E[]]> | Record<number, readonly E[]>
): v is Iterable<[number, readonly E[]]> {
  return typeof (v as { [Symbol.iterator]?: unknown })[Symbol.iterator] === 'function';
}

export function createBatchSelection<E extends BatchEpisodeLike = Episode>(
  initial?: Iterable<[number, readonly E[]]> | Record<number, readonly E[]>,
  selectInitial = false
): BatchSelectionState<E> {
  const seasons = new Map<number, readonly E[]>();
  const selectedKeys = new Set<string>();
  if (initial) {
    const entries: [number, readonly E[]][] = isIterableEntries(initial)
      ? [...initial]
      : Object.entries(initial).map(([k, eps]) => [Number(k), eps]);
    for (const [sn, eps] of entries) {
      if (!Number.isFinite(sn)) continue;
      const copy = [...eps];
      seasons.set(sn, copy);
      if (selectInitial) {
        for (const ep of copy) selectedKeys.add(batchEpisodeKey(sn, ep.id));
      }
    }
  }
  return { seasons, selectedKeys };
}

export function setSeasonEpisodes<E extends BatchEpisodeLike>(
  sel: BatchSelectionState<E>,
  seasonNumber: number,
  episodes: readonly E[],
  selectLoaded = false
): BatchSelectionState<E> {
  const seasons = new Map(sel.seasons);
  const copy = [...episodes];
  seasons.set(seasonNumber, copy);

  const validIds = new Set(copy.map((e) => e.id));
  const prefix = `${seasonNumber}:`;
  const selectedKeys = new Set<string>();
  for (const k of sel.selectedKeys) {
    if (k.startsWith(prefix)) {
      if (validIds.has(k.slice(prefix.length))) selectedKeys.add(k);
    } else {
      selectedKeys.add(k);
    }
  }
  if (selectLoaded) {
    for (const ep of copy) selectedKeys.add(batchEpisodeKey(seasonNumber, ep.id));
  }
  return { seasons, selectedKeys };
}

export function isEpisodeSelected<E extends BatchEpisodeLike>(
  sel: BatchSelectionState<E>,
  seasonNumber: number,
  episodeId: string
): boolean {
  return sel.selectedKeys.has(batchEpisodeKey(seasonNumber, episodeId));
}

export function toggleEpisode<E extends BatchEpisodeLike>(
  sel: BatchSelectionState<E>,
  seasonNumber: number,
  episodeId: string
): BatchSelectionState<E> {
  const key = batchEpisodeKey(seasonNumber, episodeId);
  const selectedKeys = new Set(sel.selectedKeys);
  if (selectedKeys.has(key)) selectedKeys.delete(key);
  else selectedKeys.add(key);
  return { seasons: new Map(sel.seasons), selectedKeys };
}

export function selectSeason<E extends BatchEpisodeLike>(
  sel: BatchSelectionState<E>,
  seasonNumber: number
): BatchSelectionState<E> {
  const eps = sel.seasons.get(seasonNumber) ?? [];
  const selectedKeys = new Set(sel.selectedKeys);
  for (const ep of eps) selectedKeys.add(batchEpisodeKey(seasonNumber, ep.id));
  return { seasons: new Map(sel.seasons), selectedKeys };
}

export function unselectSeason<E extends BatchEpisodeLike>(
  sel: BatchSelectionState<E>,
  seasonNumber: number
): BatchSelectionState<E> {
  const prefix = `${seasonNumber}:`;
  const selectedKeys = new Set<string>();
  for (const k of sel.selectedKeys) {
    if (!k.startsWith(prefix)) selectedKeys.add(k);
  }
  return { seasons: new Map(sel.seasons), selectedKeys };
}

export function countSelectedInSeason<E extends BatchEpisodeLike>(
  sel: BatchSelectionState<E>,
  seasonNumber: number
): number {
  const eps = sel.seasons.get(seasonNumber);
  if (!eps?.length) return 0;
  let n = 0;
  for (const ep of eps) {
    if (sel.selectedKeys.has(batchEpisodeKey(seasonNumber, ep.id))) n++;
  }
  return n;
}

export function isSeasonFullySelected<E extends BatchEpisodeLike>(
  sel: BatchSelectionState<E>,
  seasonNumber: number
): boolean {
  const eps = sel.seasons.get(seasonNumber);
  return !!eps?.length && countSelectedInSeason(sel, seasonNumber) === eps.length;
}

export function toggleSeason<E extends BatchEpisodeLike>(
  sel: BatchSelectionState<E>,
  seasonNumber: number
): BatchSelectionState<E> {
  return isSeasonFullySelected(sel, seasonNumber) ? unselectSeason(sel, seasonNumber) : selectSeason(sel, seasonNumber);
}

export function selectAllLoadedSeasons<E extends BatchEpisodeLike>(
  sel: BatchSelectionState<E>
): BatchSelectionState<E> {
  const selectedKeys = new Set<string>();
  for (const [sn, eps] of sel.seasons.entries()) {
    for (const ep of eps) selectedKeys.add(batchEpisodeKey(sn, ep.id));
  }
  return { seasons: new Map(sel.seasons), selectedKeys };
}

export function clearAllSelections<E extends BatchEpisodeLike>(
  sel: BatchSelectionState<E>
): BatchSelectionState<E> {
  return { seasons: new Map(sel.seasons), selectedKeys: new Set() };
}

export function countTotalSelected<E extends BatchEpisodeLike>(sel: BatchSelectionState<E>): number {
  let n = 0;
  for (const [sn, eps] of sel.seasons.entries()) {
    for (const ep of eps) {
      if (sel.selectedKeys.has(batchEpisodeKey(sn, ep.id))) n++;
    }
  }
  return n;
}

export function countTotalLoaded<E extends BatchEpisodeLike>(sel: BatchSelectionState<E>): number {
  let n = 0;
  for (const eps of sel.seasons.values()) n += eps.length;
  return n;
}

export function areAllLoadedSelected<E extends BatchEpisodeLike>(sel: BatchSelectionState<E>): boolean {
  const total = countTotalLoaded(sel);
  return total > 0 && countTotalSelected(sel) === total;
}

export function formatSeasonBadge(seasonNumber: number, selectedCount: number, totalCount: number): string {
  const prefix = seasonNumber === 0 ? 'SP' : `S${seasonNumber}`;
  return totalCount > 0 ? `${prefix} · ${selectedCount}/${totalCount}` : prefix;
}

export function selectedEntries<E extends BatchEpisodeLike>(
  sel: BatchSelectionState<E>
): { seasonNumber: number; episode: E }[] {
  const sortedSeasons = [...sel.seasons.entries()].sort((a, b) => a[0] - b[0]);
  const out: { seasonNumber: number; episode: E }[] = [];
  for (const [seasonNumber, eps] of sortedSeasons) {
    const chosen = eps.filter((ep) => sel.selectedKeys.has(batchEpisodeKey(seasonNumber, ep.id)));
    chosen.sort((a, b) => a.n - b.n);
    for (const episode of chosen) out.push({ seasonNumber, episode });
  }
  return out;
}

export function isYouTubeStreamUrl(url: string | undefined | null): boolean {
  if (!url) return false;
  return /^(?:yt:\/\/|https?:\/\/(?:[\w-]+\.)?(?:youtube(?:-nocookie)?\.com\/(?:watch|embed|shorts|live)|youtu\.be\/))/i.test(
    url.trim()
  );
}

const DOWNLOADABLE_KINDS = new Set(['MP4', 'WEBM', 'MKV', 'MP3', 'HLS', 'DASH']);
const PROGRESSIVE_KINDS = new Set(['MP4', 'WEBM', 'MKV', 'MP3']);

function isDownloadableVariant(v: QualityLike & { url?: string; kind?: string }): boolean {
  if (isYouTubeStreamUrl(v.url)) return false;
  const k = (v.kind ?? v.container ?? '').trim().toUpperCase();
  if (k && !DOWNLOADABLE_KINDS.has(k)) return false;
  return true;
}

function isProgressiveVariant(v: QualityLike & { kind?: string }): boolean {
  const k = (v.kind ?? v.container ?? '').trim().toUpperCase();
  return !k || PROGRESSIVE_KINDS.has(k);
}

/**
 * Pick the best downloadable variant closest to `targetHeight` from the de-duplicated `qualityList`.
 * Prefers progressive MP4/WebM over adaptive manifests when two formats share the same resolution.
 */
export function pickClosestQuality<T extends QualityLike & { url?: string; kind?: string }>(
  variants: readonly T[],
  targetHeight: number
): T | undefined {
  const deduped = qualityList(variants.filter(isDownloadableVariant));
  if (!deduped.length) return undefined;
  const target = targetHeight > 0 ? targetHeight : 1080;
  const withHeight = deduped.filter((v) => typeof v.height === 'number' && Number.isFinite(v.height) && v.height > 0);
  if (!withHeight.length) return deduped[0];

  let best = withHeight[0];
  let bestDist = Math.abs((best.height ?? 0) - target);
  for (let i = 1; i < withHeight.length; i++) {
    const cur = withHeight[i];
    const dist = Math.abs((cur.height ?? 0) - target);
    if (dist < bestDist) {
      best = cur;
      bestDist = dist;
    } else if (dist === bestDist && cur.height === best.height && isProgressiveVariant(cur) && !isProgressiveVariant(best)) {
      best = cur;
    }
  }
  return best;
}

const pad2 = (n: number): string => String(Math.max(0, Math.trunc(n))).padStart(2, '0');

export function formatSeriesSubtitle(
  seasonNumber: number | undefined,
  episodeNumber: number,
  episodeTitle?: string
): string {
  const code =
    seasonNumber === 0
      ? `SP E${pad2(episodeNumber)}`
      : seasonNumber !== undefined && seasonNumber > 0
        ? `S${pad2(seasonNumber)}E${pad2(episodeNumber)}`
        : `E${pad2(episodeNumber)}`;
  const clean = (episodeTitle ?? '').trim();
  const redundant = !clean || new RegExp(`^(?:episode|ep\\.?)[\\s#-]*0*${episodeNumber}$`, 'i').test(clean);
  return redundant ? code : `${code} · ${clean}`;
}

export interface SeriesEpisodeMeta {
  code?: string;
  season?: number;
  episode?: number;
  episodeTitle?: string;
}

export function parseSeriesSubtitle(subtitle?: string, episodeId?: string): SeriesEpisodeMeta {
  let season: number | undefined;
  let episode: number | undefined;
  let episodeTitle: string | undefined;

  const raw = (subtitle ?? '').trim();
  if (raw) {
    const mSe = /^(?:S(\d+)\s*[:·-]?\s*E(\d+)|SP\s*E(\d+))(?:\s*[·•-]\s*(.*))?$/i.exec(raw);
    const mEpOnly = !mSe ? /^E(\d+)(?:\s*[·•-]\s*(.*))?$/i.exec(raw) : null;
    if (mSe) {
      season = mSe[3] !== undefined ? 0 : Number(mSe[1]);
      episode = Number(mSe[2] ?? mSe[3]);
      episodeTitle = mSe[4]?.trim() || undefined;
    } else if (mEpOnly) {
      episode = Number(mEpOnly[1]);
      episodeTitle = mEpOnly[2]?.trim() || undefined;
    } else {
      episodeTitle = raw;
    }
  }

  if ((season === undefined || episode === undefined) && episodeId) {
    const mId = /(?:[-:]s(\d+)e(\d+))$/i.exec(episodeId);
    if (mId) {
      if (season === undefined) season = Number(mId[1]);
      if (episode === undefined) episode = Number(mId[2]);
    }
  }

  if (episode !== undefined && episodeTitle && new RegExp(`^(?:episode|ep\\.?)[\\s#-]*0*${episode}$`, 'i').test(episodeTitle)) {
    episodeTitle = undefined;
  }

  const code =
    episode !== undefined
      ? season === 0
        ? `SP E${pad2(episode)}`
        : season !== undefined
          ? `S${pad2(season)}E${pad2(episode)}`
          : `E${pad2(episode)}`
      : undefined;

  return { code, season, episode, episodeTitle };
}

/**
 * Truncate ultra-long series titles before calling `nativeFileName` so `- S01E02` is never sliced off
 * by the 110-char filename cap on Windows and Linux.
 */
export function safeSeriesFileRecord<R extends { title: string; subtitle?: string }>(r: R, maxTitleChars = 64): R {
  if (!r.subtitle || r.title.length <= maxTitleChars) return r;
  return { ...r, title: r.title.slice(0, maxTitleChars).trim() };
}

const DUB_MAP: [RegExp, string][] = [
  [/\bhindi\b/i, 'Hindi'],
  [/\b(?:bengali|bangla)\b/i, 'Bengali'],
  [/\btamil\b/i, 'Tamil'],
  [/\btelugu\b/i, 'Telugu'],
  [/\bmalayalam\b/i, 'Malayalam'],
  [/\bkannada\b/i, 'Kannada'],
  [/\benglish\b/i, 'English'],
  [/\bdual\s*audio\b/i, 'Dual Audio'],
  [/\bmulti\s*audio\b/i, 'Multi Audio']
];

const DUB_ONLY_RE =
  /^(?:hindi|bengali|bangla|tamil|telugu|malayalam|kannada|english|dual\s*audio|multi\s*audio)(?:\s+dub(?:bed)?)?$/i;
const UHD_RE = /\b(?:2160p|4k|uhd)\b/i;

export interface TitleBadges {
  has4k: boolean;
  ratingLabel: string | null;
  dubs: string[];
  storyGenres: string[];
}

export function extractTitleBadges(
  item: { title: string; altTitle?: string; rating?: number; genres?: readonly string[] },
  variants?: readonly { height?: number; label?: string }[]
): TitleBadges {
  const genres = item.genres ?? [];
  const has4k =
    (variants ?? []).some((v) => (v.height ?? 0) >= 2000 || UHD_RE.test(v.label ?? '')) ||
    UHD_RE.test(item.title) ||
    UHD_RE.test(item.altTitle ?? '') ||
    genres.some((g) => UHD_RE.test(g));

  const ratingLabel = item.rating && item.rating > 0 ? item.rating.toFixed(1) : null;
  const dubs: string[] = [];
  const addDubsFrom = (text: string) => {
    for (const [re, canonical] of DUB_MAP) {
      if (re.test(text) && !dubs.includes(canonical)) dubs.push(canonical);
    }
  };

  for (const m of item.title.matchAll(/[\[(]([^\]()]+)[\])]/g)) addDubsFrom(m[1]);
  const storyGenres: string[] = [];
  for (const g of genres) {
    const trimmed = g.trim();
    if (!trimmed || UHD_RE.test(trimmed)) continue;
    addDubsFrom(trimmed);
    if (DUB_ONLY_RE.test(trimmed)) continue;
    storyGenres.push(trimmed);
  }
  if (item.altTitle) addDubsFrom(item.altTitle);

  return { has4k, ratingLabel, dubs, storyGenres };
}

export interface DownloadGroupRecordLike {
  id: string;
  itemId: string;
  episodeId?: string;
  title: string;
  subtitle?: string;
  poster?: string;
  status: QueueStatus;
  doneBytes: number;
  totalBytes: number;
  estimatedBytes: number;
  fraction?: number;
  kind?: string;
  createdAt: number;
}

export interface GroupedDownloadEntry<R extends DownloadGroupRecordLike> {
  record: R;
  meta: SeriesEpisodeMeta;
}

export interface DownloadSeriesGroup<R extends DownloadGroupRecordLike> {
  key: string;
  itemId: string;
  title: string;
  poster?: string;
  isSeries: boolean;
  seasonSummary: string;
  doneBytes: number;
  totalBytes: number;
  progressPct: number;
  latestCreatedAt: number;
  items: GroupedDownloadEntry<R>[];
}

export function groupDownloadsBySeries<R extends DownloadGroupRecordLike>(
  records: readonly R[]
): DownloadSeriesGroup<R>[] {
  const byItem = new Map<string, R[]>();
  for (const r of records) {
    const k = r.itemId || r.id;
    const bucket = byItem.get(k);
    if (bucket) bucket.push(r);
    else byItem.set(k, [r]);
  }

  const groups: DownloadSeriesGroup<R>[] = [];
  for (const [itemId, list] of byItem.entries()) {
    const entries: GroupedDownloadEntry<R>[] = list.map((record) => ({
      record,
      meta: parseSeriesSubtitle(record.subtitle, record.episodeId)
    }));
    const isSeries = list.length > 1 || entries.some((e) => !!e.record.episodeId || e.meta.episode !== undefined);
    if (isSeries) {
      entries.sort(
        (a, b) =>
          (a.meta.season ?? 999) - (b.meta.season ?? 999) ||
          (a.meta.episode ?? 9999) - (b.meta.episode ?? 9999) ||
          a.record.createdAt - b.record.createdAt
      );
    }

    const seasonCounts = new Map<number, number>();
    for (const e of entries) {
      if (e.meta.season !== undefined) {
        seasonCounts.set(e.meta.season, (seasonCounts.get(e.meta.season) ?? 0) + 1);
      }
    }
    const seasonSummary = [...seasonCounts.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([sn, c]) => `${sn === 0 ? 'SP' : `S${sn}`} (${c})`)
      .join(' · ');

    let doneBytes = 0;
    let totalBytes = 0;
    let fracSum = 0;
    let latestCreatedAt = 0;
    for (const { record: r } of entries) {
      doneBytes += r.doneBytes || 0;
      totalBytes += r.totalBytes || r.estimatedBytes || 0;
      const p = r.kind === 'adaptive' ? (r.fraction ?? 0) * 100 : percent(r.doneBytes, r.totalBytes || r.estimatedBytes);
      fracSum += r.status === 'completed' ? 100 : p;
      if (r.createdAt > latestCreatedAt) latestCreatedAt = r.createdAt;
    }
    const progressPct = entries.length ? fracSum / entries.length : 0;

    groups.push({
      key: isSeries ? `series:${itemId}` : entries[0].record.id,
      itemId,
      title: entries[0].record.title,
      poster: entries.find((e) => e.record.poster)?.record.poster,
      isSeries,
      seasonSummary,
      doneBytes,
      totalBytes,
      progressPct,
      latestCreatedAt,
      items: entries
    });
  }

  return groups.sort((a, b) => b.latestCreatedAt - a.latestCreatedAt);
}
