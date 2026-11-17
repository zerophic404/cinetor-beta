// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
// scrape or hack anything without Mythbrix's explicit permission.
// Gateway playback: lazy placeholder sources ('gw-stream:' for gateway items, 'gw-find:' for
// TMDB-origin items) are resolved right before play / download into ranked BDIX, proxy MP4, and
// chromeless YouTube variants. Pure TS (vitest): fetch, network info + clock injected.
import { normalize } from './search';
import { GW_API, type GwListItem } from './gateway';
import type { Source, SourceKind, Sub } from './types';
import type { Media } from '../player/sources';

export const STREAM_SCHEME = 'gw-stream:';
export const FIND_SCHEME = 'gw-find:';
export const YOUTUBE_EMBED_BASE = 'https://www.youtube-nocookie.com/embed/';

export const isLazySource = (s?: Pick<Source, 'url'> | null): boolean =>
  !!s && (s.url.startsWith(STREAM_SCHEME) || s.url.startsWith(FIND_SCHEME));

export interface StreamRef {
  subjectId: string;
  slug: string;
  se: number;
  ep: number;
}

export interface FindRef {
  title: string;
  year: number;
  type: 'movie' | 'tv';
  tmdb?: number;
  se: number;
  ep: number;
}

export function parseStreamRef(url: string): StreamRef | null {
  if (!url.startsWith(STREAM_SCHEME)) return null;
  const [id, q = ''] = url.slice(STREAM_SCHEME.length).split('?');
  const p = new URLSearchParams(q);
  if (!id) return null;
  return { subjectId: id, slug: p.get('detail_path') ?? '', se: Number(p.get('se')) || 0, ep: Number(p.get('ep')) || 0 };
}

/** TMDB item/episode → placeholder: resolve by tmdb id, else gateway search by title + year. */
export function findPlaceholder(ref: FindRef): Source {
  const q = new URLSearchParams({ title: ref.title, year: String(ref.year || ''), type: ref.type, se: String(ref.se), ep: String(ref.ep) });
  if (ref.tmdb) q.set('tmdb', String(ref.tmdb));
  return { url: `${FIND_SCHEME}?${q}`, kind: 'MP4', label: 'Auto' };
}

export function parseFindRef(url: string): FindRef | null {
  if (!url.startsWith(FIND_SCHEME)) return null;
  const p = new URLSearchParams(url.slice(FIND_SCHEME.length).replace(/^\?/, ''));
  const title = p.get('title');
  if (!title) return null;
  return {
    title,
    year: Number(p.get('year')) || 0,
    type: p.get('type') === 'tv' ? 'tv' : 'movie',
    tmdb: Number(p.get('tmdb')) || undefined,
    se: Number(p.get('se')) || 0,
    ep: Number(p.get('ep')) || 0
  };
}

// ------------------------------------------------------------------------------------ URLs & YouTube

export const absGateway = (base: string, path: string): string =>
  /^https?:\/\//i.test(path) ? path : `${base}${path.startsWith('/') ? '' : '/'}${path}`;

/** Proxy for one CDN file (gateway adds required upstream headers for MP4). */
export function proxyUrl(base: string, subjectId: string | null, cdnUrl: string, res: string): string {
  const path = subjectId ? `${GW_API}/stream/${encodeURIComponent(subjectId)}/play` : `${GW_API}/play`;
  return `${base}${path}?url=${encodeURIComponent(cdnUrl)}&res=${encodeURIComponent(res)}`;
}

/** Proxy that resolves the subject itself (best source; may be a manifest for DASH-only titles). */
export function directPlayUrl(base: string, r: StreamRef, res?: string): string {
  const q = new URLSearchParams({ detail_path: r.slug, se: String(r.se), ep: String(r.ep) });
  if (res) q.set('res', res);
  return `${base}${GW_API}/stream/${encodeURIComponent(r.subjectId)}/play?${q}`;
}

export const subtitleProxy = (base: string, url: string): string => `${base}${GW_API}/subtitle?url=${encodeURIComponent(url)}`;

const YT_SCHEME_RE = /^yt:(?:\/\/)?([A-Za-z0-9_-]{11})(?:[/?#&]|$)/i;
const YT_PATH_RE = /(?:youtu\.be\/|youtube(?:-nocookie)?\.com\/(?:embed\/|v\/|shorts\/|live\/))([A-Za-z0-9_-]{11})(?:[/?#&]|$)/i;
const YT_WATCH_RE = /youtube(?:-nocookie)?\.com\/watch\?(?:[^#]*&)?v=([A-Za-z0-9_-]{11})(?:[&#]|$)/i;
const YT_BARE_ID_RE = /^[A-Za-z0-9_-]{11}$/;

/** Extract 11-character YouTube video ID from watch, short, shorts, live, embed, or `yt://` URLs. */
export function extractYouTubeVideoId(url: string | null | undefined): string | null {
  if (!url) return null;
  const s = url.trim();
  const m = YT_SCHEME_RE.exec(s) ?? YT_PATH_RE.exec(s) ?? YT_WATCH_RE.exec(s);
  return m ? m[1] : null;
}

export function isYouTubeUrl(url: string | null | undefined): boolean {
  return extractYouTubeVideoId(url) !== null;
}

/** Normalize any YouTube link (or bare 11-char ID when `allowBareId` is true) into chromeless embed URL. */
export function toYouTubeEmbedUrl(urlOrId: string | null | undefined, allowBareId = false): string | null {
  if (!urlOrId) return null;
  const s = urlOrId.trim();
  const id = extractYouTubeVideoId(s) ?? (allowBareId && YT_BARE_ID_RE.test(s) ? s : null);
  return id ? `${YOUTUBE_EMBED_BASE}${id}` : null;
}

// ------------------------------------------------------------------------------------ Network, Health & Labels

export type NetworkKind = 'wifi' | 'mobile';

export interface NetworkInfoLike {
  type?: string;
  effectiveType?: string;
  saveData?: boolean;
}

/** Detect unmetered (`'wifi'`) vs metered/cellular (`'mobile'`) from `navigator.connection`. */
export function detectNetworkKind(conn?: NetworkInfoLike | null): NetworkKind {
  const c =
    conn !== undefined
      ? conn
      : typeof navigator !== 'undefined'
        ? ((navigator as Navigator & { connection?: NetworkInfoLike }).connection ?? null)
        : null;
  if (!c) return 'wifi';
  if (c.saveData) return 'mobile';
  const t = (c.type ?? '').toLowerCase();
  if (t === 'cellular' || t === 'mobile' || t === 'wimax' || t === 'bluetooth') return 'mobile';
  if (t === 'wifi' || t === 'ethernet') return 'wifi';
  const eff = (c.effectiveType ?? '').toLowerCase();
  if (eff === 'slow-2g' || eff === '2g' || eff === '3g') return 'mobile';
  return 'wifi';
}

const IMAGE_PATH_RE = /\.(?:jpe?g|png|webp|gif|bmp|svg)$|\/null$/i;
const ADULT_STREAM_RE =
  /(?:porn|xvideos|xnxx|xhamster|redtube|youporn|spankbang|eporner|onlyfans|brazzers|\bsex\b|xxx)/i;
const DUAL_AUDIO_RE =
  /(?:dual[ ._-]?audio|multi[ ._-]?audio|\bdual\b|hin(?:di)?[ ._-]+eng(?:lish)?|eng(?:lish)?[ ._-]+hin(?:di)?|ben(?:gali)?[ ._-]+eng(?:lish)?)/i;
const HEVC_RE = /(?:x265|hevc|h\.?265|10[ -]?bit|hdr10?\b)/i;

function unwrapProxiedUrl(url: string): string {
  try {
    const qIdx = url.indexOf('?');
    if (qIdx < 0) return url;
    const inner = new URLSearchParams(url.slice(qIdx + 1)).get('url');
    return inner && /^https?:\/\//i.test(inner) ? inner : url;
  } catch {
    return url;
  }
}

function safeDecodePath(url: string): string {
  const raw = unwrapProxiedUrl(url).split('?')[0];
  try {
    return decodeURIComponent(raw);
  } catch {
    return raw;
  }
}

export function isImageLink(url: string): boolean {
  const path = unwrapProxiedUrl(url).split('?')[0];
  return IMAGE_PATH_RE.test(path) || path.includes('image.tmdb.org');
}

export function isAdultStreamLink(url: string): boolean {
  const path = unwrapProxiedUrl(url).split('?')[0];
  return ADULT_STREAM_RE.test(path);
}

export function isDualAudio(input: {
  label?: string | null;
  url?: string | null;
  directUrl?: string | null;
  language?: string | null;
}): boolean {
  const hay = [
    input.label ?? '',
    input.language ?? '',
    input.url ? safeDecodePath(input.url) : '',
    input.directUrl ? safeDecodePath(input.directUrl) : ''
  ].join(' ');
  return DUAL_AUDIO_RE.test(hay);
}

export function qualityLabel(height: number): string {
  if (height >= 2160) return '4K UHD';
  if (height >= 1440) return '2K QHD';
  if (height >= 1080) return '1080p FHD';
  if (height >= 720) return '720p HD';
  if (height >= 480) return '480p';
  if (height > 0) return `${height}p`;
  return 'Auto';
}

export function formatStreamSize(bytes: number | string | null | undefined): string {
  const n = typeof bytes === 'number' ? bytes : parseInt(String(bytes ?? ''), 10);
  if (!Number.isFinite(n) || n < 1024 * 1024) return '';
  const gb = 1024 ** 3;
  if (n >= gb) return `${(n / gb).toFixed(1).replace(/\.0$/, '')} GB`;
  return `${Math.round(n / (1024 * 1024))} MB`;
}

/** Clean user-facing label: strictly quality, audio language/dual-audio, and size — zero server/provider names. */
export function cleanStreamLabel(opts: {
  height: number;
  dual?: boolean;
  language?: string | null;
  size?: number | string | null;
  bdix?: boolean;
  youtube?: boolean;
}): string {
  if (opts.youtube) {
    if (opts.height >= 2160) return 'YouTube · 4K UHD';
    if (opts.height >= 1440) return 'YouTube · 2K QHD';
    if (opts.height >= 1080) return 'YouTube · 1080p FHD';
    if (opts.height > 0 && opts.height < 720) return `YouTube · ${opts.height}p`;
    return 'YouTube · HD';
  }
  const lang = (opts.language ?? '').trim();
  const audioPart = opts.dual ? 'Dual Audio' : lang || '';
  const parts = [
    qualityLabel(opts.height),
    opts.bdix ? 'High-Speed' : '',
    audioPart,
    formatStreamSize(opts.size)
  ].filter(Boolean);
  return parts.join(' · ');
}

function hostKeyOf(url: string): string {
  const target = unwrapProxiedUrl(url);
  const afterScheme = target.includes('://') ? target.split('://')[1] : target;
  return afterScheme.split('/')[0].split(':')[0].toLowerCase();
}

function linkKeyOf(url: string): string {
  return unwrapProxiedUrl(url).split('?')[0];
}

/**
 * Tracks failed stream hosts (403, 429, 451) and missing/dead files (404, 410, media errors) for 6 hours.
 * Unwraps proxied `?url=` targets so a dead upstream CDN never blacklists the gateway proxy host itself.
 */
export class LinkHealth {
  private hosts = new Map<string, number>();
  private links = new Map<string, number>();
  hevcFailed = false;
  preferDualAudio = false;

  constructor(
    private now: () => number = () => Date.now(),
    private restMs = 6 * 60 * 60_000
  ) {}

  failed(url: string, code = 404): void {
    if (!url) return;
    const until = this.now() + this.restMs;
    if (code === 403 || code === 429 || code === 451) {
      const host = hostKeyOf(url);
      if (host) this.hosts.set(host, until);
      return;
    }
    this.links.set(linkKeyOf(url), until);
    this.links.set(url.split('?')[0], until);
  }

  refused(url: string): boolean {
    if (!url) return false;
    const t = this.now();
    const host = hostKeyOf(url);
    if (host && (this.hosts.get(host) ?? 0) > t) return true;
    return (this.links.get(linkKeyOf(url)) ?? 0) > t || (this.links.get(url.split('?')[0]) ?? 0) > t;
  }

  undecodable(src: Pick<Source, 'url' | 'label' | 'direct'>): boolean {
    if (!this.hevcFailed) return false;
    const hay = [src.label ?? '', safeDecodePath(src.url), src.direct?.url ? safeDecodePath(src.direct.url) : ''].join(' ');
    return HEVC_RE.test(hay);
  }

  clear(): void {
    this.hosts.clear();
    this.links.clear();
    this.hevcFailed = false;
    this.preferDualAudio = false;
  }
}

export const linkHealth = new LinkHealth();

export type RankableSource = Source & {
  isBdix?: boolean;
  sizeBytes?: number;
};

export interface RankOptions {
  network?: NetworkKind;
  connection?: NetworkInfoLike | null;
  health?: LinkHealth;
  allowAdult?: boolean;
}

function isProxyStreamUrl(url: string): boolean {
  return url.includes(`${GW_API}/stream/`) || url.includes(`${GW_API}/play?`);
}

function sourceTier(s: RankableSource, network: NetworkKind): number {
  const yt = isYouTubeUrl(s.url);
  const proxy = !yt && isProxyStreamUrl(s.url);
  const bdix = s.isBdix === true || (!yt && !proxy);
  if (network === 'wifi') {
    if (bdix) return 0;
    if (proxy) return 1;
    return 2;
  }
  // Mobile/metered: gateway proxy MP4 first, YouTube second, unreachable LAN BDIX last.
  if (proxy) return 0;
  if (yt) return 1;
  return 2;
}

/** Port of Cinetor Beta `StreamResolver.mobileFit`: 720p first, then 480p, 1080p, 360p. */
function mobileFit(height = 0): number {
  if (height >= 700 && height <= 800) return 0;
  if (height >= 450 && height <= 600) return 1;
  if (height >= 1000 && height <= 1100) return 2;
  if (height >= 300 && height <= 449) return 3;
  if (height === 0) return 4;
  return 5;
}

/** Rank playable stream candidates by health, codec capability, dual-audio preference, and network tier. */
export function rankSources(options: readonly RankableSource[], opts: RankOptions = {}): Source[] {
  const health = opts.health ?? linkHealth;
  const network = opts.network ?? detectNetworkKind(opts.connection);

  const seenUrls = new Set<string>();
  const unique: RankableSource[] = [];
  for (const s of options) {
    if (!s.url || seenUrls.has(s.url)) continue;
    if (isImageLink(s.url) || (s.direct?.url && isImageLink(s.direct.url))) continue;
    if (!opts.allowAdult && (isAdultStreamLink(s.url) || (s.direct?.url && isAdultStreamLink(s.direct.url)))) continue;
    seenUrls.add(s.url);
    unique.push(s);
  }

  const healthy = unique.filter((s) => !health.refused(s.url) && (!s.direct?.url || !health.refused(s.direct.url)));
  const usable = healthy.length ? healthy : unique;

  return [...usable].sort((a, b) => {
    const undecDiff = (health.undecodable(a) ? 1 : 0) - (health.undecodable(b) ? 1 : 0);
    if (undecDiff !== 0) return undecDiff;

    if (health.preferDualAudio) {
      const aDual = isDualAudio({ label: a.label, url: a.url, directUrl: a.direct?.url });
      const bDual = isDualAudio({ label: b.label, url: b.url, directUrl: b.direct?.url });
      if (aDual !== bDual) return aDual ? -1 : 1;
    }

    const tierDiff = sourceTier(a, network) - sourceTier(b, network);
    if (tierDiff !== 0) return tierDiff;

    const ah = a.height ?? 0;
    const bh = b.height ?? 0;
    if (network === 'mobile') {
      const fitDiff = mobileFit(ah) - mobileFit(bh);
      if (fitDiff !== 0) return fitDiff;
      return (a.sizeBytes ?? 0) - (b.sizeBytes ?? 0);
    }
    if (bh !== ah) return bh - ah;
    return (b.sizeBytes ?? 0) - (a.sizeBytes ?? 0);
  });
}

// ------------------------------------------------------------------------------------ mapping

export interface GwStreamSource {
  source?: string;
  isBdix?: boolean;
  resolution?: string | number;
  format?: string;
  url?: string;
  size?: number | string;
  language?: string | null;
  headers?: Record<string, string> | null;
  proxy_url?: string | null;
}

export interface GwStream {
  subject_id?: string;
  has_resource?: boolean;
  direct_play_url?: string | null;
  trailer?: string | null;
  trailer_url?: string | null;
  sources?: GwStreamSource[];
}

export interface GwCaptions {
  captions?: { lan?: string; lanName?: string; url?: string }[];
}

export interface ResolveApi {
  ok?: boolean;
  sources?: {
    provider?: string;
    quality?: string;
    url?: string;
    type?: string;
    isBdix?: boolean;
    language?: string;
    headers?: Record<string, string>;
  }[];
}

export const heightOf = (res: unknown): number => {
  const n = parseInt(String(res ?? '').replace(/\D+/g, ''), 10);
  return Number.isFinite(n) && n > 0 ? n : 0;
};

/** Optional YouTube trailer source extracted from a `/stream/{id}` payload. */
export function streamTrailerSource(st: GwStream | null | undefined): Source | null {
  const embed = toYouTubeEmbedUrl(st?.trailer_url ?? st?.trailer ?? null);
  if (!embed) return null;
  return {
    url: embed,
    kind: 'MP4',
    label: cleanStreamLabel({ height: 720, youtube: true }),
    height: 720
  };
}

/**
 * Gateway `/stream/{id}` sources → ranked BDIX, proxy MP4, and chromeless YouTube variants.
 * Upstream DASH/HLS are dropped on web/desktop because the proxy cannot rewrite relative manifests.
 */
export function streamToSources(base: string, subjectId: string, st: GwStream, opts: RankOptions = {}): Source[] {
  const candidates: RankableSource[] = [];
  const seenBuckets = new Set<string>();

  for (const s of st.sources ?? []) {
    if (!s.url) continue;
    const h = heightOf(s.resolution);
    const fmt = String(s.format ?? '').toUpperCase();
    const srcName = String(s.source ?? '');

    const allowBareYt = fmt === 'YOUTUBE' || /^youtube$/i.test(srcName);
    const ytEmbed = toYouTubeEmbedUrl(s.url, allowBareYt);
    if (ytEmbed) {
      const ytKey = `yt|${ytEmbed}`;
      if (seenBuckets.has(ytKey)) continue;
      seenBuckets.add(ytKey);
      candidates.push({
        url: ytEmbed,
        kind: 'MP4',
        label: cleanStreamLabel({ height: h || 720, youtube: true }),
        height: h || 720
      });
      continue;
    }

    const bdix = s.isBdix === true || /^bdix$/i.test(srcName);
    const isMp4 = fmt === 'MP4';
    const isDirectFile = bdix && (isMp4 || fmt === 'MKV' || fmt === 'WEBM' || fmt === '' || /\.(?:mp4|mkv|webm)(\?|$)/i.test(s.url));
    if (!isMp4 && !isDirectFile) continue;

    const dual = isDualAudio({ label: s.language ?? '', url: s.url, language: s.language });
    const lang = (s.language ?? '').trim();
    const sizeNum = typeof s.size === 'number' ? s.size : parseInt(String(s.size ?? ''), 10) || 0;
    const bucketKey = `${bdix ? 'bdix' : 'proxy'}|${h}|${dual ? '1' : '0'}|${lang.toLowerCase()}`;
    if (seenBuckets.has(bucketKey)) continue;
    seenBuckets.add(bucketKey);

    const res = h ? `${h}p` : String(s.resolution ?? 'auto');
    if (bdix) {
      const kind: SourceKind = fmt === 'WEBM' ? 'WEBM' : fmt === 'MKV' ? 'MKV' : 'MP4';
      candidates.push({
        url: s.url,
        kind,
        label: cleanStreamLabel({ height: h, dual, language: lang, size: sizeNum, bdix: true }),
        height: h || undefined,
        headers: s.headers ? { ...s.headers } : undefined,
        direct: { url: s.url, headers: { ...(s.headers ?? {}) } },
        isBdix: true,
        sizeBytes: sizeNum
      });
      continue;
    }

    candidates.push({
      url: s.proxy_url ? absGateway(base, s.proxy_url) : proxyUrl(base, subjectId, s.url, res),
      kind: 'MP4',
      label: cleanStreamLabel({ height: h, dual, language: lang, size: sizeNum }),
      height: h || undefined,
      direct: { url: s.url, headers: { ...(s.headers ?? {}) } },
      isBdix: false,
      sizeBytes: sizeNum
    });
  }

  return rankSources(candidates, opts);
}

export function captionsToSubs(base: string, c: GwCaptions | null | undefined): Sub[] {
  const out: Sub[] = [];
  for (const x of c?.captions ?? []) {
    if (!x.url) continue;
    const lang = (x.lan || 'und').toLowerCase();
    if (out.some((s) => s.lang === lang && s.label === (x.lanName || lang))) continue;
    out.push({ lang, label: x.lanName || lang, url: subtitleProxy(base, x.url), format: 'VTT' });
  }
  return out;
}

/** `/api/v1/resolve` (TMDB resolver): BDIX, progressive MP4, and YouTube links ranked by network. */
export function resolverToSources(base: string, r: ResolveApi | null | undefined, opts: RankOptions = {}): Source[] {
  const candidates: RankableSource[] = [];
  const seenBuckets = new Set<string>();

  for (const s of r?.sources ?? []) {
    if (!s.url) continue;
    const h = heightOf(s.quality);
    const t = String(s.type ?? '').toLowerCase();
    const ytEmbed = toYouTubeEmbedUrl(s.url, t === 'youtube');
    if (ytEmbed) {
      const ytKey = `yt|${ytEmbed}`;
      if (seenBuckets.has(ytKey)) continue;
      seenBuckets.add(ytKey);
      candidates.push({
        url: ytEmbed,
        kind: 'MP4',
        label: cleanStreamLabel({ height: h || 720, youtube: true }),
        height: h || 720
      });
      continue;
    }

    const bdix = s.isBdix === true || /^bdix$/i.test(s.provider ?? '');
    const isMp4 = t === 'mp4' || t === 'file' || /\.mp4(\?|$)/i.test(s.url);
    if (!isMp4 && !bdix) continue;

    const dual = isDualAudio({ url: s.url, language: s.language });
    const lang = (s.language ?? '').trim();
    const bucketKey = `${bdix ? 'bdix' : 'proxy'}|${h}|${dual ? '1' : '0'}|${lang.toLowerCase()}`;
    if (seenBuckets.has(bucketKey)) continue;
    seenBuckets.add(bucketKey);

    if (bdix) {
      candidates.push({
        url: s.url,
        kind: 'MP4',
        label: cleanStreamLabel({ height: h, dual, language: lang, bdix: true }),
        height: h || undefined,
        direct: { url: s.url, headers: { ...(s.headers ?? {}) } },
        isBdix: true
      });
      continue;
    }

    candidates.push({
      url: proxyUrl(base, null, s.url, h ? `${h}p` : 'auto'),
      kind: 'MP4',
      label: cleanStreamLabel({ height: h, dual, language: lang }),
      height: h || undefined,
      direct: { url: s.url, headers: { ...(s.headers ?? {}) } },
      isBdix: false
    });
  }

  return rankSources(candidates, opts);
}

/** Best gateway hit for a TMDB title: same normalized title, then year (±1), then type. */
export function matchSearch(hits: GwListItem[], ref: Pick<FindRef, 'title' | 'year' | 'type'>): GwListItem | undefined {
  const want = normalize(ref.title);
  if (!want) return undefined;
  let best: { h: GwListItem; s: number } | undefined;
  for (const h of hits) {
    if (!h.subject_id || !h.slug || !h.name) continue;
    const name = normalize(h.name.replace(/\[[^\]]*\]/g, ''));
    let s = name === want ? 100 : name.startsWith(want) || want.startsWith(name) ? 40 : -1;
    if (s < 0) continue;
    const y = Number(h.year) || Number((h.release_date ?? '').slice(0, 4)) || 0;
    if (ref.year && y) s += y === ref.year ? 30 : Math.abs(y - ref.year) === 1 ? 15 : -50;
    if (/\[[^\]]*\]/.test(h.name)) s -= 10;
    if ((String(h.type ?? '').toLowerCase() === 'tv') === (ref.type === 'tv')) s += 20;
    else s -= 30;
    if (s > 0 && (!best || s > best.s)) best = { h, s };
  }
  return best?.h;
}

/** First ranked healthy variant ≤ preferred height (default 1080), else the fallback variant. */
export function pickVariant(sources: Source[], prefHeight = 1080, health: LinkHealth = linkHealth): Source | undefined {
  if (!sources.length) return undefined;
  const healthy = sources.filter((s) => !health.refused(s.url) && (!s.direct?.url || !health.refused(s.direct.url)));
  const pool = healthy.length ? healthy : sources;
  const ok = pool.filter((s) => !s.height || s.height <= prefHeight);
  return ok[0] ?? pool[pool.length - 1];
}

export interface Resolved {
  sources: Source[];
  subtitles: Sub[];
}

/** Media with the lazy placeholder swapped for a playable ranked variant (or noSource). */
export function applyResolved(m: Media, r: Resolved, prefHeight?: number): Media {
  const source = pickVariant(r.sources, prefHeight);
  const seen = new Set(m.subtitles.map((s) => s.url));
  return {
    ...m,
    source,
    variants: r.sources,
    playable: !!source,
    noSource: !source,
    subtitles: [...m.subtitles, ...r.subtitles.filter((s) => !seen.has(s.url))]
  };
}

// ------------------------------------------------------------------------------------ resolver

export type JsonGet = <T>(url: string, signal?: AbortSignal, timeoutMs?: number) => Promise<T>;

export interface ResolverDeps {
  base: () => string;
  getJson: JsonGet;
  /** Gateway `/search` raw hits (TMDB title → subject). */
  search: (q: string, signal?: AbortSignal) => Promise<GwListItem[]>;
  now?: () => number;
  /** Signed CDN links expire: never reuse a resolution longer than this. */
  ttlMs?: number;
  /** A movie / first episode resolved to nothing → hide the title from lists. */
  onDead?: (subjectId: string) => void;
  /** Optional injected LinkHealth tracker (defaults to `linkHealth` singleton). */
  health?: LinkHealth;
  /** Optional network connection reader (defaults to `navigator.connection`). */
  connection?: () => NetworkInfoLike | null;
}

export class StreamResolver {
  private cache = new Map<string, { ts: number; p: Promise<Resolved> }>();
  readonly health: LinkHealth;

  constructor(private d: ResolverDeps) {
    this.health = d.health ?? linkHealth;
  }

  private now(): number {
    return this.d.now?.() ?? Date.now();
  }

  private rankOpts(): RankOptions {
    return {
      health: this.health,
      connection: this.d.connection ? this.d.connection() : undefined
    };
  }

  /** Drop a cached resolution (403 / expired link → re-resolve). */
  invalidate(url: string): void {
    this.cache.delete(url);
  }

  resolve(src: Pick<Source, 'url'>, signal?: AbortSignal): Promise<Resolved> {
    const key = src.url;
    const hit = this.cache.get(key);
    if (hit && this.now() - hit.ts < (this.d.ttlMs ?? 20 * 60_000)) return hit.p;
    const p = this.run(src.url, signal);
    this.cache.set(key, { ts: this.now(), p });
    p.then(
      (r) => {
        if (!r.sources.length) this.cache.delete(key);
      },
      () => this.cache.delete(key)
    );
    return p;
  }

  private async run(url: string, signal?: AbortSignal): Promise<Resolved> {
    const s = parseStreamRef(url);
    if (s) return this.stream(s, signal);
    const f = parseFindRef(url);
    if (f) return this.find(f, signal);
    return { sources: [], subtitles: [] };
  }

  async stream(r: StreamRef, signal?: AbortSignal): Promise<Resolved> {
    const base = this.d.base();
    const id = encodeURIComponent(r.subjectId);
    const queryFor = (se: number, ep: number) => new URLSearchParams({ detail_path: r.slug, se: String(se), ep: String(ep) });
    const q = queryFor(r.se, r.ep);

    // full=1 lists every MP4 + secondary BDIX/YouTube variant (the default list can be DASH-only).
    const [st, caps] = await Promise.all([
      this.d.getJson<GwStream>(`${base}${GW_API}/stream/${id}?${q}&full=1`, signal, 15_000),
      this.d.getJson<GwCaptions>(`${base}${GW_API}/stream/${id}/captions?${q}`, signal, 15_000).catch(() => null)
    ]);

    let sources = streamToSources(base, r.subjectId, st, this.rankOpts());
    // Cinetor Beta fallback: some titles index movies under (1,1) or single-ep specials under (0,0).
    if (!sources.length && ((r.se === 0 && r.ep === 0) || (r.se === 1 && r.ep === 1))) {
      const altSe = r.se === 0 ? 1 : 0;
      const altEp = r.ep === 0 ? 1 : 0;
      const altQ = queryFor(altSe, altEp);
      const altSt = await this.d.getJson<GwStream>(`${base}${GW_API}/stream/${id}?${altQ}&full=1`, signal, 15_000).catch(() => null);
      if (altSt) sources = streamToSources(base, r.subjectId, altSt, this.rankOpts());
    }

    if (!sources.length && r.se <= 1 && r.ep <= 1) this.d.onDead?.(r.subjectId);
    return { sources, subtitles: captionsToSubs(base, caps) };
  }

  async find(f: FindRef, signal?: AbortSignal): Promise<Resolved> {
    const base = this.d.base();
    if (f.tmdb) {
      const q = new URLSearchParams({ tmdb: String(f.tmdb), type: f.type, title: f.title, preferBdix: '1' });
      if (f.year) q.set('year', String(f.year));
      if (f.type === 'tv') {
        q.set('s', String(f.se || 1));
        q.set('e', String(f.ep || 1));
      }
      const r = await this.d.getJson<ResolveApi>(`${base}/api/v1/resolve?${q}`, signal, 8_000).catch(() => null);
      const sources = resolverToSources(base, r, this.rankOpts());
      if (sources.length) return { sources, subtitles: [] };
    }
    const hits = await this.d.search(f.title, signal).catch(() => [] as GwListItem[]);
    const m = matchSearch(hits, f);
    if (!m?.subject_id || !m.slug) return { sources: [], subtitles: [] };
    return this.stream(
      { subjectId: m.subject_id, slug: m.slug, se: f.type === 'tv' ? f.se || 1 : 0, ep: f.type === 'tv' ? f.ep || 1 : 0 },
      signal
    );
  }
}
