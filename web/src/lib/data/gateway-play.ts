// Gateway playback: lazy placeholder sources ('gw-stream:' for gateway items, 'gw-find:' for
// TMDB-origin items) are resolved right before play / download into proxy MP4 variants.
// Browsers can't send Cookie/Referer → video goes through the gateway proxy
// (`/stream/{id}/play?url=…&res=…`, Range 206). The proxy does NOT rewrite DASH/HLS manifests
// (relative segment URLs would hit the proxy path) → only MP4 sources are offered on web/desktop.
// Pure TS (vitest): fetch + clock injected.
import { normalize } from './search';
import { GW_API, type GwListItem } from './gateway';
import type { Source, Sub } from './types';
import type { Media } from '../player/sources';

export const STREAM_SCHEME = 'gw-stream:';
export const FIND_SCHEME = 'gw-find:';

export const isLazySource = (s?: Pick<Source, 'url'> | null): boolean => !!s && (s.url.startsWith(STREAM_SCHEME) || s.url.startsWith(FIND_SCHEME));

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

// ------------------------------------------------------------------------------------ URLs

export const absGateway = (base: string, path: string): string => (/^https?:\/\//i.test(path) ? path : `${base}${path.startsWith('/') ? '' : '/'}${path}`);

/** Proxy for one CDN file (gateway adds its default MovieBox Referer/Origin — enough for MP4). */
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

// ------------------------------------------------------------------------------------ mapping

export interface GwStreamSource {
  source?: string;
  resolution?: string | number;
  format?: string;
  url?: string;
  size?: number | string;
  headers?: Record<string, string> | null;
  proxy_url?: string | null;
}

export interface GwStream {
  subject_id?: string;
  has_resource?: boolean;
  direct_play_url?: string | null;
  sources?: GwStreamSource[];
}

export interface GwCaptions {
  captions?: { lan?: string; lanName?: string; url?: string }[];
}

export interface ResolveApi {
  ok?: boolean;
  sources?: { provider?: string; quality?: string; url?: string; type?: string; headers?: Record<string, string> }[];
}

export const heightOf = (res: unknown): number => {
  const n = parseInt(String(res ?? ''), 10);
  return Number.isFinite(n) && n > 0 ? n : 0;
};

/** MP4 sources → proxy variants (one per height, highest first). DASH/HLS dropped (proxy can't rewrite manifests). */
export function streamToSources(base: string, subjectId: string, st: GwStream): Source[] {
  const byH = new Map<number, Source>();
  for (const s of st.sources ?? []) {
    if (!s.url || String(s.format ?? '').toUpperCase() !== 'MP4') continue;
    const h = heightOf(s.resolution);
    if (byH.has(h)) continue;
    const res = h ? `${h}p` : String(s.resolution ?? 'auto');
    byH.set(h, {
      url: s.proxy_url ? absGateway(base, s.proxy_url) : proxyUrl(base, subjectId, s.url, res),
      kind: 'MP4',
      label: h ? `${h}p` : 'Auto',
      height: h || undefined,
      direct: { url: s.url, headers: { ...(s.headers ?? {}) } }
    });
  }
  return [...byH.entries()].sort((a, b) => b[0] - a[0]).map(([, s]) => s);
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

/** `/api/v1/resolve` (TMDB resolver): MP4 / progressive links through the generic proxy. */
export function resolverToSources(base: string, r: ResolveApi | null | undefined): Source[] {
  const out: Source[] = [];
  for (const s of r?.sources ?? []) {
    if (!s.url) continue;
    const t = String(s.type ?? '').toLowerCase();
    const isMp4 = t === 'mp4' || t === 'file' || /\.mp4(\?|$)/i.test(s.url);
    if (!isMp4) continue;
    const h = heightOf(s.quality);
    if (out.some((x) => (x.height ?? 0) === h)) continue;
    out.push({ url: proxyUrl(base, null, s.url, h ? `${h}p` : 'auto'), kind: 'MP4', label: h ? `${h}p` : 'Auto', height: h || undefined, direct: { url: s.url, headers: { ...(s.headers ?? {}) } } });
  }
  return out.sort((a, b) => (b.height ?? 0) - (a.height ?? 0));
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
    // Dubbed / regional re-uploads ("[Hindi]") rank below the original.
    if (/\[[^\]]*\]/.test(h.name)) s -= 10;
    if ((h.type === 'tv') === (ref.type === 'tv')) s += 20;
    else s -= 30;
    if (s > 0 && (!best || s > best.s)) best = { h, s };
  }
  return best?.h;
}

/** Highest variant ≤ preferred height (default 1080), else the lowest one. */
export function pickVariant(sources: Source[], prefHeight = 1080): Source | undefined {
  if (!sources.length) return undefined;
  const ok = sources.filter((s) => !s.height || s.height <= prefHeight);
  return ok[0] ?? sources[sources.length - 1];
}

export interface Resolved {
  sources: Source[];
  subtitles: Sub[];
}

/** Media with the lazy placeholder swapped for a playable proxy variant (or noSource). */
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
}

export class StreamResolver {
  private cache = new Map<string, { ts: number; p: Promise<Resolved> }>();
  constructor(private d: ResolverDeps) {}

  private now(): number {
    return this.d.now?.() ?? Date.now();
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
    const q = new URLSearchParams({ detail_path: r.slug, se: String(r.se), ep: String(r.ep) });
    const id = encodeURIComponent(r.subjectId);
    // full=1 lists every MP4 (the default list can be DASH-only).
    const [st, caps] = await Promise.all([
      this.d.getJson<GwStream>(`${base}${GW_API}/stream/${id}?${q}&full=1`, signal, 15_000),
      this.d.getJson<GwCaptions>(`${base}${GW_API}/stream/${id}/captions?${q}`, signal, 15_000).catch(() => null)
    ]);
    const sources = streamToSources(base, r.subjectId, st);
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
      const sources = resolverToSources(base, r);
      if (sources.length) return { sources, subtitles: [] };
    }
    const hits = await this.d.search(f.title, signal).catch(() => [] as GwListItem[]);
    const m = matchSearch(hits, f);
    if (!m?.subject_id || !m.slug) return { sources: [], subtitles: [] };
    return this.stream({ subjectId: m.subject_id, slug: m.slug, se: f.type === 'tv' ? f.se || 1 : 0, ep: f.type === 'tv' ? f.ep || 1 : 0 }, signal);
  }
}
