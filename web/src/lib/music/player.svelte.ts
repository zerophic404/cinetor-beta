// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
// scrape or hack anything without Mythbrix's explicit permission.
// Reactive music player singleton + live gateway music client + FLAC/320kbps download bridge.
// The single <audio> element lives in the /app layout and is attached once so playback survives navigation.
import { goto } from '$app/navigation';
import { base } from '$app/paths';
import { colors, persist } from '$lib/data/app-catalog';
import { gatewayBase } from '$lib/data/gateway';
import { colorSampleUrl } from '$lib/data/images';
import { localKV } from '$lib/data/kv';
import { img, repo } from '$lib/data/repo';
import type { Item, Source } from '$lib/data/types';
import { downloads } from '$lib/download/manager.svelte';
import type { QueueStatus } from '$lib/download/queue';
import { GENERIC, report } from '$lib/errors';
import { arbiter } from '$lib/media/arbiter';
import type { Media } from '$lib/player/sources';
import { toasts } from '$lib/ui/toast.svelte';
import { MusicCore, TRACK_UNAVAILABLE_MSG, type AudioQuality, type Track } from './core';
import {
  GatewayMusicClient,
  buildMusicDownloadMedia,
  isLazyMusicUrl,
  muId,
  musicTrackToTrack,
  qualityShortLabel,
  rawMusicId,
  type MusicLyrics,
  type MusicQuality,
  type MusicStreamInfo,
  type MusicTrack
} from './gateway-music';
import { nextIndex, type Repeat } from './queue';

const QUALITY_STORAGE_KEY = 'cinetor.music.quality';

export const musicApi = new GatewayMusicClient({
  base: () => gatewayBase(),
  persist,
  offlineCatalog: () => repo.file('MUSIC'),
  asset: (p) => img(p)
});

export function toPlayerTrack(t: MusicTrack): Track {
  return musicTrackToTrack(t, (art) => colors.peek(colorSampleUrl(art, (p) => img(p))));
}

function loadSavedQuality(): MusicQuality {
  const saved = localKV().get(QUALITY_STORAGE_KEY);
  return saved === '320kbps' ? '320kbps' : 'flac';
}

function buildOfflineProbeMedia(track: Track): Media {
  const id = muId(rawMusicId(track.id));
  const placeholder: Source = { url: track.url, kind: 'MP3' };
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
    dominantColor: track.color,
    accentColor: track.accent,
    durationSec: track.durationSec,
    sources: [placeholder]
  };
  return {
    key: id,
    item,
    source: placeholder,
    playable: true,
    title: track.title,
    subtitle: track.artist,
    subtitles: [],
    durationSec: track.durationSec
  };
}

class MusicPlayer {
  tracks = $state.raw<Track[]>([]);
  index = $state(-1);
  playing = $state(false);
  loading = $state(false);
  error = $state<string | null>(null);
  shuffle = $state(false);
  repeat = $state<Repeat>('off');
  quality = $state<MusicQuality>(loadSavedQuality());
  qualityLabel = $state('FLAC Lossless');
  t = $state(0);
  dur = $state(0);
  expanded = $state(false);
  lyrics = $state.raw<MusicLyrics | null>(null);
  lyricsLoading = $state(false);
  downloadingIds = $state<Record<string, boolean>>({});

  readonly core = new MusicCore(null, arbiter, () => this.sync());
  private el: HTMLAudioElement | null = null;
  private lyricsAbort: AbortController | null = null;
  private lastLyricsTrackId = '';

  constructor() {
    this.core.quality = this.quality;
    this.core.setResolver(async (track, wantQuality, signal) => {
      const normId = muId(rawMusicId(track.id));
      if (downloads.isDownloaded(normId)) {
        const off = await downloads.offlineMedia(buildOfflineProbeMedia(track)).catch(() => null);
        if (off?.source?.url && !isLazyMusicUrl(off.source.url)) {
          return {
            url: off.source.url,
            qualityLabel: downloads.records[normId]?.tracks.quality || 'Offline Copy'
          };
        }
      }
      const st = await musicApi.stream(track.rawId ?? track.id, wantQuality, signal);
      if (!st) return null;
      this.warmUpcoming(wantQuality);
      return {
        url: st.streamUrl,
        qualityLabel: st.quality,
        format: st.format
      };
    });
  }

  get current(): Track | null {
    return this.tracks[this.index] ?? null;
  }

  private warmUpcoming(q: AudioQuality): void {
    const nextIdx = nextIndex(this.core.queue, true);
    if (nextIdx === null) return;
    const nxt = this.core.tracks[nextIdx];
    if (nxt && isLazyMusicUrl(nxt.url)) {
      void musicApi.stream(nxt.rawId ?? nxt.id, q).catch(() => null);
    }
  }

  private refreshLyrics(cur: Track | null): void {
    const nextId = cur?.id ?? '';
    if (nextId === this.lastLyricsTrackId) return;
    this.lastLyricsTrackId = nextId;
    this.lyricsAbort?.abort();
    this.lyrics = null;
    if (!cur) {
      this.lyricsLoading = false;
      return;
    }
    const ctl = new AbortController();
    this.lyricsAbort = ctl;
    this.lyricsLoading = true;
    void musicApi
      .lyrics(cur.rawId ?? cur.id, cur.title, cur.artist, ctl.signal)
      .then((res) => {
        if (ctl.signal.aborted) return;
        this.lyrics = res;
        this.lyricsLoading = false;
      })
      .catch(() => {
        if (!ctl.signal.aborted) {
          this.lyrics = null;
          this.lyricsLoading = false;
        }
      });
  }

  private sync(): void {
    const c = this.core;
    this.tracks = c.tracks;
    this.index = c.index;
    this.shuffle = c.shuffle;
    this.repeat = c.repeat;
    this.quality = c.quality;
    this.qualityLabel = c.streamQualityLabel;
    this.loading = c.loading;
    this.error = c.error;
    this.playing = c.playing;
    if (this.el) {
      this.t = this.el.currentTime || 0;
      this.dur = Number.isFinite(this.el.duration) && this.el.duration > 0 ? this.el.duration : (c.current?.durationSec ?? 0);
    } else {
      this.dur = c.current?.durationSec ?? 0;
    }
    this.refreshLyrics(c.current);
  }

  /** Svelte action for the layout's <audio>. */
  attach = (el: HTMLAudioElement) => {
    this.el = el;
    this.core.setAudio(el);
    const sync = () => this.sync();
    const evs = ['play', 'pause', 'timeupdate', 'durationchange', 'loadedmetadata', 'seeked', 'emptied', 'canplay', 'playing'] as const;
    for (const e of evs) el.addEventListener(e, sync);
    const onWaiting = () => {
      if (this.core.current) this.loading = true;
    };
    const onPlaying = () => {
      this.loading = false;
      this.error = null;
    };
    const onError = () => {
      if (!this.core.current || !el.getAttribute('src')) return;
      const cur = this.core.current;
      musicApi.invalidateStream(cur.rawId ?? cur.id);
      this.loading = false;
      this.playing = false;
      this.error = TRACK_UNAVAILABLE_MSG;
    };
    const onPlay = () => arbiter.claim('music');
    const onEnded = () => void this.core.ended();
    el.addEventListener('waiting', onWaiting);
    el.addEventListener('playing', onPlaying);
    el.addEventListener('error', onError);
    el.addEventListener('play', onPlay);
    el.addEventListener('ended', onEnded);
    this.mediaSession();
    return {
      destroy: () => {
        for (const e of evs) el.removeEventListener(e, sync);
        el.removeEventListener('waiting', onWaiting);
        el.removeEventListener('playing', onPlaying);
        el.removeEventListener('error', onError);
        el.removeEventListener('play', onPlay);
        el.removeEventListener('ended', onEnded);
        this.core.setAudio(null);
        this.el = null;
      }
    };
  };

  playQueue(tracks: Track[], start = 0): void {
    void this.core.playQueue(tracks, start).then(() => this.updateMeta());
  }

  playMusicTracks(list: readonly MusicTrack[], start = 0): void {
    this.playQueue(list.map(toPlayerTrack), start);
  }

  playIndex(i: number): void {
    void this.core.playIndex(i).then(() => this.updateMeta());
  }

  toggle(): void {
    void this.core.toggle();
  }

  next(): void {
    void this.core.next().then(() => this.updateMeta());
  }

  prev(): void {
    void this.core.prev().then(() => this.updateMeta());
  }

  seek(sec: number): void {
    this.core.seek(sec);
  }

  setShuffle(on: boolean): void {
    this.core.setShuffle(on);
  }

  cycleRepeat(): void {
    this.core.cycleRepeat();
  }

  setQuality(q: MusicQuality): void {
    localKV().set(QUALITY_STORAGE_KEY, q);
    void this.core.setQuality(q);
  }

  toggleQuality(): void {
    this.setQuality(this.quality === 'flac' ? '320kbps' : 'flac');
  }

  move(from: number, to: number): void {
    this.core.move(from, to);
  }

  remove(i: number): void {
    this.core.remove(i);
  }

  stop(): void {
    this.expanded = false;
    this.core.stop();
  }

  downloadStatus(trackId: string): QueueStatus | 'resolving' | null {
    const key = muId(rawMusicId(trackId));
    if (this.downloadingIds[key]) return 'resolving';
    return downloads.records[key]?.status ?? null;
  }

  isDownloaded(trackId: string): boolean {
    const key = muId(rawMusicId(trackId));
    return downloads.isDownloaded(key);
  }

  async downloadTrack(target: Track | MusicTrack, explicitQuality?: MusicQuality, silent = false): Promise<boolean> {
    const tr = 'url' in target ? target : toPlayerTrack(target);
    const key = muId(rawMusicId(tr.id));
    const existing = downloads.records[key]?.status;
    if (existing === 'completed' || existing === 'running' || existing === 'queued') {
      if (!silent) {
        toasts.show(
          existing === 'completed' ? `"${tr.title}" is already downloaded` : `"${tr.title}" is already in your download queue`,
          { label: 'Show downloads', run: () => void goto(`${base}/app/downloads`) }
        );
      }
      return true;
    }

    const q: MusicQuality = (explicitQuality ?? this.quality) === 'flac' && tr.hasFlac !== false ? 'flac' : '320kbps';
    this.downloadingIds = { ...this.downloadingIds, [key]: true };
    try {
      let streamInfo: MusicStreamInfo | null = null;
      if (!isLazyMusicUrl(tr.url)) {
        streamInfo = {
          streamUrl: tr.url,
          downloadUrl: tr.url,
          quality: tr.quality ?? qualityShortLabel(q),
          format: tr.format ?? 'mp3',
          headers: {}
        };
      } else {
        streamInfo = await musicApi.stream(tr.rawId ?? tr.id, q);
      }
      if (!streamInfo) throw new Error('No source available');

      const { media, choiceLabel, estimatedBytes } = buildMusicDownloadMedia(tr, streamInfo, q);
      await downloads.start({
        media,
        choice: { quality: choiceLabel, texts: [] },
        estimatedBytes,
        durationSec: tr.durationSec || undefined
      });
      if (!silent) {
        toasts.show(`Downloading "${tr.title}" (${qualityShortLabel(q)})`, {
          label: 'Show downloads',
          run: () => void goto(`${base}/app/downloads`)
        });
      }
      return true;
    } catch (e) {
      if (!silent) {
        toasts.show(report('music.download', e, GENERIC.download));
      }
      return false;
    } finally {
      const copy = { ...this.downloadingIds };
      delete copy[key];
      this.downloadingIds = copy;
    }
  }

  async downloadBatch(targets: readonly (Track | MusicTrack)[], explicitQuality?: MusicQuality): Promise<number> {
    if (!targets.length) return 0;
    const q = explicitQuality ?? this.quality;
    let queued = 0;
    for (const item of targets) {
      const ok = await this.downloadTrack(item, q, true);
      if (ok) queued++;
    }
    if (queued > 0) {
      toasts.show(`Queued ${queued} ${queued === 1 ? 'track' : 'tracks'} (${qualityShortLabel(q)})`, {
        label: 'Show downloads',
        run: () => void goto(`${base}/app/downloads`)
      });
    } else {
      toasts.show(GENERIC.download);
    }
    return queued;
  }

  private updateMeta(): void {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
    const t = this.core.current;
    if (!t) return;
    try {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: t.title,
        artist: t.artist,
        album: t.album,
        artwork: t.art ? [{ src: t.art, sizes: '512x512' }] : []
      });
    } catch {
      /* ignore */
    }
  }

  private mediaSession(): void {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
    this.el?.addEventListener('play', () => {
      const ms = navigator.mediaSession;
      const set = (a: MediaSessionAction, f: MediaSessionActionHandler | null) => {
        try {
          ms.setActionHandler(a, f);
        } catch {
          /* unsupported */
        }
      };
      set('play', () => this.toggle());
      set('pause', () => this.toggle());
      set('nexttrack', () => this.next());
      set('previoustrack', () => this.prev());
      set('seekto', (d) => d.seekTime !== undefined && this.seek(d.seekTime));
      this.updateMeta();
    });
  }
}

export const music = new MusicPlayer();
