// Source selection + media description (pure; asset resolver injected).
import type { Episode, Item, Season, Source, Sub } from '../data/types';

export type Playback = 'shaka' | 'native' | 'unsupported';

export const MIME: Partial<Record<Source['kind'], string>> = {
  HLS: 'application/x-mpegurl',
  DASH: 'application/dash+xml',
  MP4: 'video/mp4',
  WEBM: 'video/webm',
  MKV: 'video/x-matroska',
  MP3: 'audio/mpeg',
  SS: 'application/vnd.ms-sstr+xml'
};

/** How the browser can play a source. `canPlay(mime)` = HTMLMediaElement.canPlayType != ''. */
export function playbackOf(src: Source, canPlay: (mime: string) => boolean): Playback {
  switch (src.kind) {
    case 'HLS':
    case 'DASH':
    case 'SS':
      return 'shaka';
    case 'MP4':
    case 'WEBM':
    case 'MP3':
      return 'native';
    case 'MKV':
      return canPlay('video/x-matroska') || canPlay('video/webm') ? 'native' : 'unsupported';
    default:
      return 'unsupported';
  }
}

const RANK: Record<Source['kind'], number> = { HLS: 0, DASH: 1, MP4: 2, WEBM: 3, MP3: 4, SS: 5, MKV: 6, RTSP: 9 };

/** Best playable source (adaptive first), or the first one when nothing is playable (→ fallback UI). */
export function pickSource(sources: Source[], canPlay: (mime: string) => boolean): { source?: Source; playable: boolean } {
  const ok = sources.filter((s) => playbackOf(s, canPlay) !== 'unsupported').sort((a, b) => RANK[a.kind] - RANK[b.kind]);
  if (ok.length) return { source: ok[0], playable: true };
  return { source: sources[0], playable: false };
}

export interface Media {
  key: string;
  item: Item;
  episode?: Episode;
  source?: Source;
  playable: boolean;
  title: string;
  subtitle?: string;
  subtitles: Sub[];
  introStartSec?: number;
  introEndSec?: number;
  durationSec?: number;
  /** Gateway quality variants (MP4 via proxy, highest first) — quality menu reloads with another one. */
  variants?: Source[];
  /** Resolution ran and found nothing playable → "No source available". */
  noSource?: boolean;
}

export function buildMedia(item: Item, episode: Episode | undefined, canPlay: (mime: string) => boolean, asset: (p: string) => string): Media {
  const sources = episode?.sources?.length ? episode.sources : (item.sources ?? []);
  const { source, playable } = pickSource(sources, canPlay);
  const subs = (episode?.subtitles?.length ? episode.subtitles : (item.subtitles ?? [])).map((s) => ({ ...s, url: /^(https?:|blob:|data:)/.test(s.url) ? s.url : asset(s.url) }));
  const season = episode && item.seasons?.find((s) => s.episodes.some((e) => e.id === episode.id));
  return {
    key: episode ? `${item.id}#${episode.id}` : item.id,
    item,
    episode,
    source,
    playable,
    title: item.title,
    subtitle: episode ? `${season ? `S${season.n} ` : ''}E${episode.n} · ${episode.title}` : undefined,
    subtitles: subs,
    introStartSec: episode?.introStartSec ?? item.introStartSec,
    introEndSec: episode?.introEndSec ?? item.introEndSec,
    durationSec: episode?.durationSec ?? item.durationSec
  };
}

/** Next episode across seasons (seasons must have their episodes loaded). */
export function nextEpisode(seasons: Season[] | undefined, episodeId: string | undefined): Episode | undefined {
  if (!seasons || !episodeId) return undefined;
  const flat = [...seasons].sort((a, b) => a.n - b.n).flatMap((s) => s.episodes);
  const i = flat.findIndex((e) => e.id === episodeId);
  return i >= 0 ? flat[i + 1] : undefined;
}

/** Android intent / generic link to hand an unsupported *local* file to an external player. */
/**
 * Only *local* media may be handed to an external app or the clipboard: a remote URL in a link,
 * share intent or "copy link" would expose the gateway / stream host to the user and anything
 * reading the DOM (docs/requirements-hardening.md §2). Blob, file and Tauri asset URLs carry no host.
 */
export function isLocalMediaUrl(url: string | undefined | null): boolean {
  if (!url) return false;
  return /^(?:blob:|file:|asset:|https?:\/\/(?:asset\.localhost|localhost|127\.0\.0\.1)(?::\d+)?\/)/i.test(url);
}

export function externalPlayerHref(url: string, ua: string): string {
  if (/Android/i.test(ua)) {
    const m = url.match(/^(https?):\/\/(.*)$/);
    if (m) return `intent://${m[2]}#Intent;scheme=${m[1]};type=video/*;end`;
  }
  return url;
}
