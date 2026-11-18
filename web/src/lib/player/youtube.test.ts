import { describe, expect, it } from 'vitest';
import { buildMedia, pickSource, playbackOf } from './sources';
import { extractYouTubeVideoId, isYouTubeSource } from './youtube';
import type { Episode, Item, Source } from '../data/types';

function makeItem(id: string, sources: Source[], extra: Partial<Item> = {}): Item {
  return {
    id,
    space: 'HOME',
    categoryId: 'cat',
    type: 'MOVIE',
    title: `Title ${id}`,
    year: 2025,
    rating: 8.2,
    maturity: 'PG-13',
    genres: ['Action'],
    synopsis: 'Synopsis',
    poster: '',
    backdrop: '',
    dominantColor: '#101014',
    accentColor: '#e11d2e',
    sources,
    subtitles: [],
    ...extra
  };
}

const browserCanPlay = (mime: string): boolean => mime === 'video/mp4' || mime === 'video/webm';

describe('sources noSource & playable MP4/YouTube selection (v2.0.1)', () => {
  it('sets noSource: true and playable: false when item sources are empty', () => {
    const media = buildMedia(makeItem('empty-movie', []), undefined, browserCanPlay, (p) => p);
    expect(media.playable).toBe(false);
    expect(media.noSource).toBe(true);
    expect(media.source).toBeUndefined();
  });

  it('sets noSource: true when series episode and parent item both have empty sources', () => {
    const ep: Episode = {
      id: 's1e1',
      n: 1,
      title: 'Episode 1',
      durationSec: 1200,
      still: '',
      sources: [],
      subtitles: []
    };
    const series = makeItem('empty-series', [], {
      type: 'SERIES',
      seasons: [{ n: 1, episodes: [ep] }]
    });
    const media = buildMedia(series, ep, browserCanPlay, (p) => p);
    expect(media.playable).toBe(false);
    expect(media.noSource).toBe(true);
    expect(media.source).toBeUndefined();
  });

  it('leaves noSource false when sources exist but every source is unsupported MKV', () => {
    const mkvOnly: Source[] = [{ kind: 'MKV', url: 'https://cdn.example.test/film.mkv', label: '1080p FHD' }];
    const media = buildMedia(makeItem('mkv-only', mkvOnly), undefined, () => false, (p) => p);
    expect(media.playable).toBe(false);
    expect(Boolean(media.noSource)).toBe(false);
    expect(media.source?.kind).toBe('MKV');
  });

  it('picks playable H.264 MP4 when mixed with unsupported MKV entries', () => {
    const mixed: Source[] = [
      { kind: 'MKV', url: 'https://cdn.example.test/hevc-1080.mkv', label: '1080p · Hindi', height: 1080 },
      { kind: 'MP4', url: 'https://proxy.example.test/api/public/v1/stream/1/play?res=720p', label: '720p HD · Hindi', height: 720 },
      { kind: 'MP4', url: 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ', label: 'YouTube · HD' }
    ];
    const picked = pickSource(mixed, () => false, { dub: 'Hindi', height: 1080 });
    expect(picked.playable).toBe(true);
    expect(picked.source?.url).toBe('https://proxy.example.test/api/public/v1/stream/1/play?res=720p');

    const media = buildMedia(makeItem('mixed-mp4', mixed), undefined, () => false, (p) => p, { dub: 'Hindi', height: 1080 });
    expect(media.playable).toBe(true);
    expect(Boolean(media.noSource)).toBe(false);
    expect(media.source?.kind).toBe('MP4');
    expect(media.variants?.map((v) => v.url)).toEqual([
      'https://proxy.example.test/api/public/v1/stream/1/play?res=720p',
      'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'
    ]);
  });

  it('picks chromeless YouTube variant when mixed only with unsupported MKV entries', () => {
    const mixedYt: Source[] = [
      { kind: 'MKV', url: 'https://cdn.example.test/movie.mkv', label: '1080p FHD', height: 1080 },
      { kind: 'MP4', url: 'https://www.youtube-nocookie.com/embed/9bZkp7q19f0', label: 'YouTube · HD' }
    ];
    const media = buildMedia(makeItem('mixed-yt', mixedYt), undefined, () => false, (p) => p);
    expect(media.playable).toBe(true);
    expect(Boolean(media.noSource)).toBe(false);
    expect(isYouTubeSource(media.source)).toBe(true);
    expect(extractYouTubeVideoId(media.source?.url)).toBe('9bZkp7q19f0');
    expect(playbackOf(media.source!, () => false)).toBe('youtube');
  });
});
