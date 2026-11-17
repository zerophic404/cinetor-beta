import { describe, expect, it } from 'vitest';
import { spaceForId } from '../data/catalog';
import type { PersistentCache } from '../data/idb';
import type { CatalogFile } from '../data/types';
import { MediaArbiter } from '../media/arbiter';
import { MusicCore, type AudioLike, type Track } from './core';
import {
  DEFAULT_MUSIC_CATEGORIES,
  GatewayMusicClient,
  buildMusicDownloadMedia,
  musicTrackToTrack,
  parseMusicAlbum,
  parseMusicLyrics,
  parseMusicPlaylist,
  parseMusicStream,
  parseMusicTrack,
  parseMusicTrending,
  rawMusicId,
  sourceKindForFormat,
  type MusicFetcher
} from './gateway-music';
import { activeLyricIndex, parseLrc, plainLyricParagraphs } from './lyrics';
import { nextIndex, prevIndex, reorder, reorderQueue, shuffleOrder, type QueueState } from './queue';

const q = (o: Partial<QueueState>): QueueState => ({ length: 4, index: 0, shuffle: false, repeat: 'off', order: [], ...o });

describe('queue reorder', () => {
  it('moves an item and keeps the current track current', () => {
    expect(reorder(['a', 'b', 'c', 'd'], 0, 3)).toEqual(['b', 'c', 'd', 'a']);
    expect(reorder(['a', 'b', 'c', 'd'], 3, 1)).toEqual(['a', 'd', 'b', 'c']);
    // current = c (index 2)
    expect(reorderQueue(q({ index: 2 }), 0, 3).index).toBe(1);
    expect(reorderQueue(q({ index: 2 }), 3, 0).index).toBe(3);
    expect(reorderQueue(q({ index: 2 }), 2, 0).index).toBe(0);
    expect(reorderQueue(q({ index: 2 }), 0, 1).index).toBe(2);
  });
  it('keeps the shuffle order pointing at the same tracks', () => {
    const before = q({ index: 1, shuffle: true, order: [1, 3, 0, 2] });
    const after = reorderQueue(before, 3, 0); // d moves to front: a b c d -> d a b c
    expect(after.index).toBe(2);
    expect(after.order).toEqual([2, 0, 1, 3]);
  });
});

describe('shuffle / repeat next index', () => {
  it('sequential with repeat off stops at the end, repeat all wraps', () => {
    expect(nextIndex(q({ index: 1 }))).toBe(2);
    expect(nextIndex(q({ index: 3 }))).toBeNull();
    expect(nextIndex(q({ index: 3, repeat: 'all' }))).toBe(0);
    expect(prevIndex(q({ index: 0 }))).toBeNull();
    expect(prevIndex(q({ index: 0, repeat: 'all' }))).toBe(3);
  });
  it('repeat one replays on auto-advance but a user next still advances', () => {
    expect(nextIndex(q({ index: 2, repeat: 'one' }), true)).toBe(2);
    expect(nextIndex(q({ index: 2, repeat: 'one' }))).toBe(3);
  });
  it('shuffle follows the shuffled order, current first', () => {
    const order = shuffleOrder(4, 2, () => 0.5);
    expect(order[0]).toBe(2);
    expect([...order].sort()).toEqual([0, 1, 2, 3]);
    const s = q({ index: 2, shuffle: true, order });
    expect(nextIndex(s)).toBe(order[1]);
    expect(nextIndex(q({ index: order[3], shuffle: true, order }))).toBeNull();
    expect(nextIndex(q({ index: order[3], shuffle: true, order, repeat: 'all' }))).toBe(order[0]);
    expect(prevIndex(q({ index: order[1], shuffle: true, order }))).toBe(order[0]);
  });
});

function fakeAudio(): AudioLike & { plays: number } {
  return {
    src: '',
    currentTime: 0,
    duration: 100,
    paused: true,
    plays: 0,
    play() {
      (this as { paused: boolean }).paused = false;
      this.plays++;
      return Promise.resolve();
    },
    pause() {
      (this as { paused: boolean }).paused = true;
    }
  };
}

const track = (id: string): Track => ({ id, title: id, artist: 'A', album: 'B', art: '', color: '#000', accent: '#f00', url: `https://x/${id}.mp3`, durationSec: 100 });

describe('music + video exclusive', () => {
  it('starting music stops the video player', async () => {
    const arb = new MediaArbiter();
    let videoStops = 0;
    arb.register('video', () => videoStops++);
    const audio = fakeAudio();
    const core = new MusicCore(audio, arb);
    await core.playQueue([track('t1'), track('t2')], 0);
    expect(videoStops).toBe(1);
    expect(audio.paused).toBe(false);
    expect(audio.src).toContain('t1');
    expect(arb.active).toBe('music');
  });
  it('starting video pauses music (single audio)', async () => {
    const arb = new MediaArbiter();
    const audio = fakeAudio();
    const core = new MusicCore(audio, arb);
    await core.playQueue([track('t1')], 0);
    arb.claim('video');
    expect(audio.paused).toBe(true);
    expect(core.playing).toBe(false);
    expect(arb.active).toBe('video');
  });
  it('auto-advances and stops at the end with repeat off', async () => {
    const audio = fakeAudio();
    const core = new MusicCore(audio, new MediaArbiter());
    await core.playQueue([track('t1'), track('t2')], 0);
    await core.ended();
    expect(core.current?.id).toBe('t2');
    await core.ended();
    expect(audio.paused).toBe(true);
    core.move(1, 0);
    expect(core.current?.id).toBe('t2');
    expect(core.tracks.map((t) => t.id)).toEqual(['t2', 't1']);
  });
});

describe('LRC synced lyrics parser & active-line lookup', () => {
  it('parses centiseconds, milliseconds, integer seconds, metadata tags, and sorts chronologically', () => {
    const raw = [
      '[ar:Arijit Singh]',
      '[ti:Midnight Rain]',
      '[00:15.25]Second verse line',
      '[00:04.500]First line with 3-digit ms',
      '[00:09.05]   ',
      '[01:02]Minute mark without decimals'
    ].join('\n');

    const lines = parseLrc(raw);
    expect(lines).toEqual([
      { timeMs: 4500, text: 'First line with 3-digit ms' },
      { timeMs: 15250, text: 'Second verse line' },
      { timeMs: 62000, text: 'Minute mark without decimals' }
    ]);
  });

  it('expands multi-timestamp chorus lines, applies [offset], and strips inline word tags', () => {
    const raw = [
      '[offset:+200]',
      '[00:10.00][00:40.00]Chorus <00:10.50>echoes loud',
      '[00:00.10]Early whisper'
    ].join('\n');

    const lines = parseLrc(raw);
    expect(lines).toEqual([
      { timeMs: 300, text: 'Early whisper' },
      { timeMs: 10200, text: 'Chorus echoes loud' },
      { timeMs: 40200, text: 'Chorus echoes loud' }
    ]);
  });

  it('clamps negative offset at 0ms and returns empty array for blank input', () => {
    expect(parseLrc(null)).toEqual([]);
    expect(parseLrc('   \n  ')).toEqual([]);
    const shifted = parseLrc('[offset:-500]\n[00:00.20]Starts immediately\n[00:02.00]Next line');
    expect(shifted).toEqual([
      { timeMs: 0, text: 'Starts immediately' },
      { timeMs: 1500, text: 'Next line' }
    ]);
  });

  it('finds the active lyric index via binary search across playback timestamps', () => {
    const lines = [
      { timeMs: 2000, text: 'Line one' },
      { timeMs: 5500, text: 'Line two' },
      { timeMs: 9000, text: 'Line three' }
    ];
    expect(activeLyricIndex([], 3000)).toBe(-1);
    expect(activeLyricIndex(lines, 0)).toBe(-1);
    expect(activeLyricIndex(lines, 1999)).toBe(-1);
    expect(activeLyricIndex(lines, 2000)).toBe(0);
    expect(activeLyricIndex(lines, 5499)).toBe(0);
    expect(activeLyricIndex(lines, 5500)).toBe(1);
    expect(activeLyricIndex(lines, 8999)).toBe(1);
    expect(activeLyricIndex(lines, 9000)).toBe(2);
    expect(activeLyricIndex(lines, 45000)).toBe(2);
  });

  it('splits plain lyrics into clean non-empty lines', () => {
    expect(plainLyricParagraphs(null)).toEqual([]);
    expect(plainLyricParagraphs('First line\r\n\r\n  Second line  \n')).toEqual(['First line', 'Second line']);
  });
});

function memPersist(): PersistentCache & { map: Map<string, unknown> } {
  const map = new Map<string, unknown>();
  return {
    map,
    get: async (k) => map.get(k),
    set: async (k, v) => {
      map.set(k, v);
    }
  };
}

const sampleOfflineCatalog: CatalogFile = {
  space: 'MUSIC',
  genres: ['Synthwave', 'Acoustic'],
  categories: [
    {
      id: 'mu-discover',
      space: 'MUSIC',
      title: 'Discover',
      heroIds: ['mu-al-001'],
      rows: [{ id: 'mu-row-top', title: 'Top Mix', style: 'TOP10', itemIds: ['mu-tr-001', 'mu-tr-002'] }]
    }
  ],
  items: [
    {
      id: 'mu-al-001',
      space: 'MUSIC',
      categoryId: 'mu-discover',
      type: 'ALBUM',
      title: 'Neon Horizon',
      artist: 'Nova Pulse',
      year: 2025,
      rating: 4.8,
      maturity: 'NR',
      genres: ['Synthwave'],
      synopsis: 'Midnight analog synth sessions.',
      poster: 'images/mu-al-001.webp',
      backdrop: 'images/mu-al-001.webp',
      dominantColor: '#1A080C',
      accentColor: '#E11D2E',
      trackIds: ['mu-tr-001', 'mu-tr-002']
    },
    {
      id: 'mu-tr-001',
      space: 'MUSIC',
      categoryId: 'mu-discover',
      type: 'TRACK',
      title: 'Starlight Drive',
      artist: 'Nova Pulse',
      album: 'Neon Horizon',
      albumId: 'mu-al-001',
      year: 2025,
      rating: 4.9,
      maturity: 'NR',
      genres: ['Synthwave'],
      synopsis: '',
      poster: 'images/mu-al-001.webp',
      backdrop: 'images/mu-al-001.webp',
      dominantColor: '#1A080C',
      accentColor: '#E11D2E',
      durationSec: 215,
      sources: [{ url: '/mock/audio/starlight.mp3', kind: 'MP3' }]
    },
    {
      id: 'mu-tr-002',
      space: 'MUSIC',
      categoryId: 'mu-discover',
      type: 'TRACK',
      title: 'Velvet Echo',
      artist: 'Nova Pulse',
      album: 'Neon Horizon',
      albumId: 'mu-al-001',
      year: 2025,
      rating: 4.7,
      maturity: 'NR',
      genres: ['Acoustic'],
      synopsis: '',
      poster: 'images/mu-al-001.webp',
      backdrop: 'images/mu-al-001.webp',
      dominantColor: '#1A080C',
      accentColor: '#E11D2E',
      durationSec: 184,
      sources: [{ url: '/mock/audio/velvet.mp3', kind: 'MP3' }]
    }
  ]
};

describe('gateway music JSON parsing & MUSIC space isolation', () => {
  it('parses trending charts, tracks, and categories while keeping mu-gw IDs in MUSIC space', () => {
    const feed = parseMusicTrending(
      {
        charts: [
          { id: 'pl-top50', title: 'Global Top 50', artwork: '/art/top50.jpg', count: 50 },
          { title: 'Missing ID ignored' }
        ],
        trendingTracks: [
          {
            id: 'trk-99',
            title: 'Crimson Sky',
            artist: '',
            album: 'Nocturne',
            albumId: 'alb-7',
            year: '2026',
            duration: 0,
            durationLabel: '3:42',
            artwork: 'https://img.example/99.jpg',
            quality: 'FLAC Lossless',
            format: 'flac',
            hasFlac: true
          }
        ],
        categories: ['Trending', 'Bengali', '  Lo-Fi & Chill  ', '']
      },
      'https://gw.example'
    );

    expect(feed.charts).toHaveLength(1);
    expect(feed.charts[0]).toMatchObject({
      id: 'mu-gw:pl-top50',
      rawId: 'pl-top50',
      title: 'Global Top 50',
      artwork: 'https://gw.example/art/top50.jpg',
      count: 50
    });
    expect(spaceForId(feed.charts[0].id)).toBe('MUSIC');

    expect(feed.trendingTracks).toHaveLength(1);
    const t = feed.trendingTracks[0];
    expect(t.id).toBe('mu-gw:trk-99');
    expect(rawMusicId(t.id)).toBe('trk-99');
    expect(spaceForId(t.id)).toBe('MUSIC');
    expect(t.artist).toBe('Unknown Artist');
    expect(t.albumId).toBe('mu-gw:alb-7');
    expect(t.duration).toBe(222);
    expect(t.durationLabel).toBe('3:42');
    expect(t.hasFlac).toBe(true);
    expect(feed.categories).toEqual(['Trending', 'Bengali', 'Lo-Fi & Chill']);
  });

  it('falls back to DEFAULT_MUSIC_CATEGORIES and guards malformed track/album/playlist/lyrics/stream payloads', () => {
    expect(parseMusicTrending({}).categories).toEqual([...DEFAULT_MUSIC_CATEGORIES]);
    expect(parseMusicTrack(null)).toBeNull();
    expect(parseMusicTrack({ id: '1' })).toBeNull();
    expect(parseMusicLyrics({ plainLyrics: '   ', syncedLyrics: null })).toBeNull();

    const lyr = parseMusicLyrics({
      plainLyrics: 'Hello world',
      syncedLyrics: '[00:05.50]Hello world'
    });
    expect(lyr?.lines).toEqual([{ timeMs: 5500, text: 'Hello world' }]);

    const alb = parseMusicAlbum(
      {
        id: 'alb-1',
        title: 'Afterglow',
        artist: 'Luna',
        year: 2024,
        artwork: 'https://img.example/alb.jpg',
        tracks: [{ id: 's1', title: 'Intro', duration: 95 }]
      },
      'alb-1'
    );
    expect(alb?.id).toBe('mu-gw:alb-1');
    expect(alb?.tracks[0].durationLabel).toBe('1:35');

    const pl = parseMusicPlaylist(
      {
        title: 'Late Night',
        tracks: [{ id: 's2', title: 'Drift', artist: 'Kairo', duration: 200 }]
      },
      'mu-gw:pl-77'
    );
    expect(pl?.id).toBe('mu-gw:pl-77');
    expect(pl?.count).toBe(1);

    expect(parseMusicStream({ quality: 'FLAC' })).toBeNull();
    const st = parseMusicStream(
      {
        streamUrl: '/api/public/v1/music/proxy?id=s2',
        quality: 'FLAC Lossless',
        format: 'flac',
        headers: { Referer: 'https://audio.example/', Bad: 123 }
      },
      'https://gw.example',
      'flac'
    );
    expect(st).toEqual({
      streamUrl: 'https://gw.example/api/public/v1/music/proxy?id=s2',
      downloadUrl: 'https://gw.example/api/public/v1/music/proxy?id=s2',
      quality: 'FLAC Lossless',
      format: 'flac',
      headers: { Referer: 'https://audio.example/' }
    });
  });

  it('builds MUSIC download descriptors with progressive container and desktop headers', () => {
    expect(sourceKindForFormat('mp4')).toBe('MP4');
    expect(sourceKindForFormat('webm')).toBe('WEBM');
    expect(sourceKindForFormat('flac')).toBe('MP3');
    expect(sourceKindForFormat('mp3')).toBe('MP3');

    const mt = parseMusicTrack({
      id: 'dl-1',
      title: 'Solaris',
      artist: 'Vega',
      album: 'Orbit',
      duration: 200,
      artwork: 'https://img.example/s.jpg',
      hasFlac: true
    })!;
    const st = {
      streamUrl: 'https://audio.example/solaris.flac',
      downloadUrl: 'https://audio.example/solaris-dl.flac',
      quality: 'FLAC Lossless',
      format: 'flac',
      headers: { Referer: 'https://audio.example/' }
    };
    const built = buildMusicDownloadMedia(musicTrackToTrack(mt), st, 'flac');
    expect(built.media.key).toBe('mu-gw:dl-1');
    expect(built.media.item.space).toBe('MUSIC');
    expect(built.media.source?.kind).toBe('MP3');
    expect(built.media.source?.url).toBe('https://audio.example/solaris-dl.flac');
    expect(built.media.source?.direct).toEqual({
      url: 'https://audio.example/solaris-dl.flac',
      headers: { Referer: 'https://audio.example/' }
    });
    expect(built.choiceLabel).toBe('FLAC Lossless');
    expect(built.estimatedBytes).toBeGreaterThan(1_000_000);
  });
});

describe('GatewayMusicClient live endpoints, IndexedDB cache, stream quality & offline fallback', () => {
  it('fetches trending, search (song/album/playlist), album, playlist, lyrics, and stream with flac/320kbps', async () => {
    const seenUrls: string[] = [];
    const persist = memPersist();
    const okJson = (body: unknown) => ({ ok: true, status: 200, json: async () => body });
    const fetcher: MusicFetcher = async (u) => {
      seenUrls.push(u);
      const parsed = new URL(u);
      const p = parsed.pathname;
      if (p.endsWith('/music/trending')) {
        return okJson({
          charts: [{ id: 'c1', title: 'Top Hits', count: 20 }],
          trendingTracks: [{ id: 't1', title: 'Track One', artist: 'Artist A', duration: 180 }],
          categories: ['Trending', 'Bollywood']
        });
      }
      if (p.endsWith('/music/search')) {
        const type = parsed.searchParams.get('type');
        if (type === 'album') {
          return okJson({ results: [{ id: 'a1', title: 'Album One', artist: 'Artist A', year: '2025' }] });
        }
        if (type === 'playlist') {
          return okJson({ results: [{ id: 'p1', title: 'Chill Mix', count: 15 }] });
        }
        return okJson({ results: [{ id: 't2', title: 'Searched Song', artist: 'Artist B', duration: 210 }] });
      }
      if (p.endsWith('/music/album')) {
        return okJson({
          id: 'a1',
          title: 'Album One',
          artist: 'Artist A',
          year: '2025',
          tracks: [{ id: 't1', title: 'Track One', artist: 'Artist A', duration: 180 }]
        });
      }
      if (p.endsWith('/music/playlist')) {
        return okJson({
          id: 'p1',
          title: 'Chill Mix',
          count: 1,
          tracks: [{ id: 't2', title: 'Searched Song', artist: 'Artist B', duration: 210 }]
        });
      }
      if (p.endsWith('/music/lyrics')) {
        return okJson({
          plainLyrics: 'Sing along',
          syncedLyrics: '[00:03.20]Sing along'
        });
      }
      if (p.endsWith('/music/stream')) {
        const id = parsed.searchParams.get('id');
        const q = parsed.searchParams.get('quality');
        if (id === 't-fallback' && q) {
          return { ok: false, status: 404, json: async () => ({}) };
        }
        if (q === 'flac') {
          return okJson({ streamUrl: 'https://audio.example/t1.flac', quality: 'FLAC Lossless', format: 'flac' });
        }
        return okJson({ streamUrl: 'https://audio.example/t1.m4a', quality: '320kbps Studio Master', format: 'mp4' });
      }
      return { ok: false, status: 404, json: async () => ({}) };
    };

    const client = new GatewayMusicClient({
      base: () => 'https://gw.example',
      fetcher,
      persist
    });

    const feed = await client.trending();
    expect(feed.trendingTracks[0].id).toBe('mu-gw:t1');
    // Second call hits fresh cache without network
    await client.trending();
    expect(seenUrls.filter((u) => u.includes('/music/trending'))).toHaveLength(1);

    const songs = await client.searchTracks('arijit', 2);
    expect(songs[0].title).toBe('Searched Song');
    expect(seenUrls.some((u) => u.includes('/api/public/v1/music/search?') && u.includes('type=song') && u.includes('page=2'))).toBe(true);

    const albums = await client.searchAlbums('arijit');
    expect(albums[0].id).toBe('mu-gw:a1');

    const playlists = await client.searchPlaylists('chill');
    expect(playlists[0].id).toBe('mu-gw:p1');

    const albDetail = await client.album('mu-gw:a1');
    expect(albDetail?.title).toBe('Album One');
    expect(albDetail?.tracks).toHaveLength(1);

    const plDetail = await client.playlist('mu-gw:p1');
    expect(plDetail?.title).toBe('Chill Mix');
    expect(plDetail?.tracks).toHaveLength(1);

    const lyr = await client.lyrics('mu-gw:t1', 'Track One', 'Artist A');
    expect(lyr?.lines).toEqual([{ timeMs: 3200, text: 'Sing along' }]);

    const flac = await client.stream('mu-gw:t1', 'flac');
    expect(flac?.quality).toBe('FLAC Lossless');
    const hq320 = await client.stream('mu-gw:t1', '320kbps');
    expect(hq320?.quality).toBe('320kbps Studio Master');

    // Fallback when ?quality=flac fails -> retries /music/stream?id=... without quality param
    const fbStream = await client.stream('mu-gw:t-fallback', 'flac');
    expect(fbStream?.streamUrl).toBe('https://audio.example/t1.m4a');

    // Security check: stream URLs are never written into PersistentCache
    for (const k of persist.map.keys()) {
      expect(k).not.toContain('/music/stream');
    }
  });

  it('serves stale IndexedDB cache when offline, and falls back to bundled catalog when cache is cold', async () => {
    const persist = memPersist();
    let online = true;
    let nowMs = 1_000;

    const fetcher: MusicFetcher = async () => {
      if (!online) throw new Error('Network unreachable');
      return {
        ok: true,
        status: 200,
        json: async () => ({
          charts: [{ id: 'c-live', title: 'Live Chart', count: 10 }],
          trendingTracks: [{ id: 't-live', title: 'Live Song', artist: 'Live Artist', duration: 190 }],
          categories: ['Trending']
        })
      };
    };

    const warmClient = new GatewayMusicClient({
      base: () => 'https://gw.example',
      fetcher,
      persist,
      offlineCatalog: async () => sampleOfflineCatalog,
      now: () => nowMs,
      ttlMs: 5_000
    });

    const live = await warmClient.trending();
    expect(live.trendingTracks[0].title).toBe('Live Song');

    // Expire TTL and go offline on a fresh instance sharing IndexedDB persist
    nowMs = 50_000;
    online = false;
    const warmReloaded = new GatewayMusicClient({
      base: () => 'https://gw.example',
      fetcher,
      persist,
      offlineCatalog: async () => sampleOfflineCatalog,
      now: () => nowMs,
      ttlMs: 5_000
    });
    const fromStaleIdb = await warmReloaded.trending();
    expect(fromStaleIdb.trendingTracks[0].title).toBe('Live Song');

    // Completely cold cache + offline -> falls back to bundled offlineCatalog
    const coldOffline = new GatewayMusicClient({
      base: () => 'https://gw.example',
      fetcher,
      persist: memPersist(),
      offlineCatalog: async () => sampleOfflineCatalog,
      asset: (p) => `/cinetor-app/mock/${p}`
    });
    const fallbackFeed = await coldOffline.trending();
    expect(fallbackFeed.offlineFallback).toBe(true);
    expect(fallbackFeed.trendingTracks.map((t) => t.id)).toEqual(['mu-tr-001', 'mu-tr-002']);

    const fallbackAlbum = await coldOffline.album('mu-al-001');
    expect(fallbackAlbum?.title).toBe('Neon Horizon');
    expect(fallbackAlbum?.tracks).toHaveLength(2);

    const fallbackSearch = await coldOffline.searchTracks('Starlight');
    expect(fallbackSearch).toHaveLength(1);
    expect(fallbackSearch[0].id).toBe('mu-tr-001');
  });
});

describe('MusicCore lazy gateway stream resolution & FLAC/320kbps quality switching', () => {
  it('resolves mu-stream tracks on demand, pauses video immediately, and switches FLAC/320kbps keeping position', async () => {
    const arb = new MediaArbiter();
    let videoPaused = 0;
    arb.register('video', () => videoPaused++);

    const audio = fakeAudio();
    const calls: string[] = [];
    const core = new MusicCore(audio, arb);
    core.setResolver(async (t, quality) => {
      calls.push(`${t.rawId ?? t.id}:${quality}`);
      return {
        url: `https://audio.example/${t.rawId ?? t.id}-${quality}.mp4`,
        qualityLabel: quality === 'flac' ? 'FLAC Lossless' : '320kbps Studio Master'
      };
    });

    const gwTrack: Track = {
      id: 'mu-gw:song7',
      rawId: 'song7',
      title: 'Velvet Sky',
      artist: 'Aria',
      album: 'Noir',
      art: '',
      color: '#111',
      accent: '#E11D2E',
      url: 'mu-stream:song7',
      durationSec: 210,
      hasFlac: true
    };

    await core.playQueue([gwTrack], 0);
    expect(videoPaused).toBe(1);
    expect(calls).toEqual(['song7:flac']);
    expect(audio.src).toBe('https://audio.example/song7-flac.mp4');
    expect(core.streamQualityLabel).toBe('FLAC Lossless');
    expect(audio.paused).toBe(false);

    // Advance playback to 42s and toggle quality to 320kbps -> keeps currentTime!
    audio.currentTime = 42;
    await core.setQuality('320kbps');
    expect(calls).toEqual(['song7:flac', 'song7:320kbps']);
    expect(audio.src).toBe('https://audio.example/song7-320kbps.mp4');
    expect(audio.currentTime).toBe(42);
    expect(core.streamQualityLabel).toBe('320kbps Studio Master');
  });

  it('sets a clean host-free error when stream resolution fails and clears it on next valid track', async () => {
    const audio = fakeAudio();
    const core = new MusicCore(audio, new MediaArbiter());
    core.setResolver(async (t) => {
      if (t.id === 'mu-gw:broken') return null;
      return { url: 'https://audio.example/ok.mp3', qualityLabel: 'FLAC Lossless' };
    });

    await core.playQueue(
      [
        { id: 'mu-gw:broken', rawId: 'broken', title: 'Broken', artist: 'X', album: 'Y', art: '', color: '#000', accent: '#E11D2E', url: 'mu-stream:broken', durationSec: 120 },
        { id: 'mu-gw:good', rawId: 'good', title: 'Good', artist: 'X', album: 'Y', art: '', color: '#000', accent: '#E11D2E', url: 'mu-stream:good', durationSec: 120 }
      ],
      0
    );
    expect(core.error).toBe('Track unavailable right now. Try another song.');
    expect(core.loading).toBe(false);

    await core.next();
    expect(core.error).toBeNull();
    expect(audio.src).toBe('https://audio.example/ok.mp3');
  });
});
