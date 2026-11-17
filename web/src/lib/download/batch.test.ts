// Multi-season batch episode selection, quality matching & series download grouping tests.
// Pure logic only — relative imports (vitest config has no `$lib` alias).
//
// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer, scrape
// or hack anything without Mythbrix's explicit permission.
import { describe, expect, it } from 'vitest';
import {
  areAllLoadedSelected,
  BATCH_QUALITIES,
  batchEpisodeKey,
  clearAllSelections,
  countSelectedInSeason,
  countTotalLoaded,
  countTotalSelected,
  createBatchSelection,
  extractTitleBadges,
  formatSeasonBadge,
  formatSeriesSubtitle,
  groupDownloadsBySeries,
  isEpisodeSelected,
  isSeasonFullySelected,
  isYouTubeStreamUrl,
  parseBatchEpisodeKey,
  parseSeriesSubtitle,
  pickClosestQuality,
  safeSeriesFileRecord,
  selectAllLoadedSeasons,
  selectedEntries,
  selectSeason,
  setSeasonEpisodes,
  toggleEpisode,
  toggleSeason,
  unselectSeason,
  type BatchEpisodeLike
} from './batch';
import type { QualityLike } from './math';

interface Ep extends BatchEpisodeLike {
  id: string;
  n: number;
  title: string;
  durationSec: number;
}

const ep = (season: number, n: number, title = `Episode ${n}`): Ep => ({
  id: `gw:99-s${season}e${n}`,
  n,
  title,
  durationSec: 1440
});

const s1 = [ep(1, 1, 'Pilot'), ep(1, 2, 'Signal'), ep(1, 3, 'Fracture')];
const s2 = [ep(2, 1, 'Return'), ep(2, 2, 'Horizon'), ep(2, 3, 'Eclipse'), ep(2, 4, 'Dawn')];
const s3 = [ep(3, 1, 'Aftermath'), ep(3, 2, 'Finale')];

describe('batchEpisodeKey', () => {
  it('encodes and parses season + episode identity without cross-season collisions', () => {
    const k1 = batchEpisodeKey(1, 'ep-1');
    const k2 = batchEpisodeKey(2, 'ep-1');
    expect(k1).not.toBe(k2);
    expect(parseBatchEpisodeKey(k1)).toEqual({ seasonNumber: 1, episodeId: 'ep-1' });
    expect(parseBatchEpisodeKey('0:special:part1')).toEqual({ seasonNumber: 0, episodeId: 'special:part1' });
    expect(parseBatchEpisodeKey('invalid')).toBeNull();
  });
});

describe('multi-season selection state', () => {
  it('starts empty by default and tracks loaded episodes across multiple seasons', () => {
    const sel = createBatchSelection<Ep>([
      [1, s1],
      [2, s2]
    ]);
    expect(countTotalLoaded(sel)).toBe(7);
    expect(countTotalSelected(sel)).toBe(0);
    expect(countSelectedInSeason(sel, 1)).toBe(0);
    expect(countSelectedInSeason(sel, 2)).toBe(0);
    expect(areAllLoadedSelected(sel)).toBe(false);
  });

  it('toggles single episodes across Season 1, Season 2, and Season 3 without mutating prior state', () => {
    const base = createBatchSelection<Ep>({ 1: s1, 2: s2, 3: s3 });
    const a = toggleEpisode(base, 1, s1[0].id);
    const b = toggleEpisode(a, 2, s2[2].id);
    const c = toggleEpisode(b, 3, s3[1].id);

    expect(countTotalSelected(base)).toBe(0);
    expect(isEpisodeSelected(c, 1, s1[0].id)).toBe(true);
    expect(isEpisodeSelected(c, 1, s1[1].id)).toBe(false);
    expect(isEpisodeSelected(c, 2, s2[2].id)).toBe(true);
    expect(isEpisodeSelected(c, 3, s3[1].id)).toBe(true);
    expect(countSelectedInSeason(c, 1)).toBe(1);
    expect(countSelectedInSeason(c, 2)).toBe(1);
    expect(countSelectedInSeason(c, 3)).toBe(1);
    expect(countTotalSelected(c)).toBe(3);

    const d = toggleEpisode(c, 2, s2[2].id);
    expect(isEpisodeSelected(d, 2, s2[2].id)).toBe(false);
    expect(countSelectedInSeason(d, 2)).toBe(0);
    expect(countTotalSelected(d)).toBe(2);
  });

  it('selects and unselects one season while preserving selections in other seasons', () => {
    let sel = createBatchSelection<Ep>({ 1: s1, 2: s2 });
    sel = toggleEpisode(sel, 2, s2[1].id);
    sel = selectSeason(sel, 1);

    expect(isSeasonFullySelected(sel, 1)).toBe(true);
    expect(isSeasonFullySelected(sel, 2)).toBe(false);
    expect(countSelectedInSeason(sel, 1)).toBe(3);
    expect(countSelectedInSeason(sel, 2)).toBe(1);
    expect(countTotalSelected(sel)).toBe(4);

    sel = unselectSeason(sel, 1);
    expect(isSeasonFullySelected(sel, 1)).toBe(false);
    expect(countSelectedInSeason(sel, 1)).toBe(0);
    expect(countSelectedInSeason(sel, 2)).toBe(1);
    expect(countTotalSelected(sel)).toBe(1);
  });

  it('toggleSeason selects incomplete season and unselects fully selected season', () => {
    let sel = createBatchSelection<Ep>({ 1: s1, 2: s2 });
    sel = toggleEpisode(sel, 1, s1[0].id);
    sel = toggleSeason(sel, 1);
    expect(countSelectedInSeason(sel, 1)).toBe(3);
    sel = toggleSeason(sel, 1);
    expect(countSelectedInSeason(sel, 1)).toBe(0);
  });

  it('selectAllLoadedSeasons and clearAllSelections span every loaded season', () => {
    let sel = createBatchSelection<Ep>({ 1: s1, 2: s2, 3: s3 });
    sel = selectAllLoadedSeasons(sel);
    expect(countTotalSelected(sel)).toBe(9);
    expect(areAllLoadedSelected(sel)).toBe(true);
    expect(isSeasonFullySelected(sel, 1)).toBe(true);
    expect(isSeasonFullySelected(sel, 2)).toBe(true);
    expect(isSeasonFullySelected(sel, 3)).toBe(true);

    sel = clearAllSelections(sel);
    expect(countTotalSelected(sel)).toBe(0);
    expect(areAllLoadedSelected(sel)).toBe(false);
  });

  it('setSeasonEpisodes lazily adds a new season without dropping selections in earlier seasons', () => {
    let sel = createBatchSelection<Ep>({ 1: s1 });
    sel = selectSeason(sel, 1);
    sel = setSeasonEpisodes(sel, 2, s2);
    expect(countSelectedInSeason(sel, 1)).toBe(3);
    expect(countSelectedInSeason(sel, 2)).toBe(0);
    expect(countTotalLoaded(sel)).toBe(7);

    // Pruning stale keys if a season reloads with fewer episodes
    sel = toggleEpisode(sel, 2, s2[3].id);
    expect(countSelectedInSeason(sel, 2)).toBe(1);
    sel = setSeasonEpisodes(sel, 2, s2.slice(0, 2));
    expect(countSelectedInSeason(sel, 2)).toBe(0);
    expect(countSelectedInSeason(sel, 1)).toBe(3);
  });

  it('selectedEntries returns episodes ordered by season ascending then episode number ascending', () => {
    let sel = createBatchSelection<Ep>({ 2: [s2[2], s2[0]], 1: [s1[2], s1[0]] });
    // Select out of order: S2E3, S1E3, S2E1, S1E1
    sel = toggleEpisode(sel, 2, s2[2].id);
    sel = toggleEpisode(sel, 1, s1[2].id);
    sel = toggleEpisode(sel, 2, s2[0].id);
    sel = toggleEpisode(sel, 1, s1[0].id);

    const ordered = selectedEntries(sel).map((x) => `S${x.seasonNumber}E${x.episode.n}`);
    expect(ordered).toEqual(['S1E1', 'S1E3', 'S2E1', 'S2E3']);
  });

  it('formats compact season tab badges', () => {
    expect(formatSeasonBadge(1, 3, 10)).toBe('S1 · 3/10');
    expect(formatSeasonBadge(2, 0, 8)).toBe('S2 · 0/8');
    expect(formatSeasonBadge(0, 1, 4)).toBe('SP · 1/4');
    expect(formatSeasonBadge(3, 0, 0)).toBe('S3');
  });
});

describe('pickClosestQuality', () => {
  const v = (o: QualityLike & { url?: string; kind?: string }) => o;

  it('exposes the 4 standard batch quality targets', () => {
    expect(BATCH_QUALITIES).toEqual([1080, 720, 480, 360]);
  });

  it('picks exact height match and dedupes via qualityList (primary / fastest wins)', () => {
    const variants = [
      v({ height: 1080, kind: 'MP4', bytes: 900_000_000, bandwidth: 4_000_000, url: 'https://gw.example/1080' }),
      v({ height: 720, kind: 'MP4', bytes: 450_000_000, bandwidth: 1_500_000, url: 'https://gw.example/720-slow' }),
      v({ height: 720, kind: 'MP4', bytes: 450_000_000, bandwidth: 3_200_000, primary: true, url: 'https://gw.example/720-primary' }),
      v({ height: 480, kind: 'MP4', bytes: 250_000_000, bandwidth: 900_000, url: 'https://gw.example/480' })
    ];
    const picked = pickClosestQuality(variants, 720);
    expect(picked?.url).toBe('https://gw.example/720-primary');
  });

  it('picks strictly closest available resolution when exact target is unavailable', () => {
    const variants = [
      v({ height: 1080, kind: 'MP4', url: '1080' }),
      v({ height: 480, kind: 'MP4', url: '480' })
    ];
    // |480 - 720| = 240 < |1080 - 720| = 360
    expect(pickClosestQuality(variants, 720)?.url).toBe('480');
    // For 1080 target when max is 720: picks 720
    expect(pickClosestQuality([v({ height: 720, kind: 'MP4', url: '720' }), v({ height: 360, kind: 'MP4', url: '360' })], 1080)?.url).toBe('720');
    // For 360 target when min is 480: picks 480
    expect(pickClosestQuality(variants, 360)?.url).toBe('480');
  });

  it('prefers progressive MP4 over adaptive HLS/DASH at the same resolution', () => {
    const variants = [
      v({ height: 720, kind: 'HLS', url: 'hls-720' }),
      v({ height: 720, kind: 'MP4', url: 'mp4-720' })
    ];
    expect(pickClosestQuality(variants, 720)?.url).toBe('mp4-720');
  });

  it('filters out YouTube embed URLs and unsupported RTSP streams', () => {
    expect(isYouTubeStreamUrl('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ')).toBe(true);
    expect(isYouTubeStreamUrl('https://youtu.be/dQw4w9WgXcQ')).toBe(true);
    expect(isYouTubeStreamUrl('yt://dQw4w9WgXcQ')).toBe(true);
    expect(isYouTubeStreamUrl('https://cdn.example/video.mp4')).toBe(false);

    const mixed = [
      v({ height: 1080, kind: 'MP4', url: 'https://www.youtube-nocookie.com/embed/abc12345678' }),
      v({ height: 1080, kind: 'RTSP', url: 'rtsp://live/1' }),
      v({ height: 720, kind: 'MP4', url: 'https://cdn.example/ep1-720.mp4' })
    ];
    expect(pickClosestQuality(mixed, 1080)?.url).toBe('https://cdn.example/ep1-720.mp4');
    expect(pickClosestQuality([v({ height: 1080, kind: 'MP4', url: 'https://www.youtube-nocookie.com/embed/abc12345678' })], 1080)).toBeUndefined();
  });

  it('falls back to unlabelled source when no height metadata exists, and returns undefined for empty input', () => {
    expect(pickClosestQuality([], 1080)).toBeUndefined();
    const unlabelled = [v({ label: 'Auto', kind: 'MP4', url: 'auto-mp4' })];
    expect(pickClosestQuality(unlabelled, 720)?.url).toBe('auto-mp4');
  });
});

describe('series subtitles, badges & downloads grouping', () => {
  it('formats clean SxxExx episode subtitles without redundant Episode N labels', () => {
    expect(formatSeriesSubtitle(1, 2, 'Signal')).toBe('S01E02 · Signal');
    expect(formatSeriesSubtitle(2, 12, 'Episode 12')).toBe('S02E12');
    expect(formatSeriesSubtitle(1, 5, 'Ep 5')).toBe('S01E05');
    expect(formatSeriesSubtitle(0, 1, 'OVA')).toBe('SP E01 · OVA');
  });

  it('parses legacy and new episode subtitles + gateway episode ids', () => {
    expect(parseSeriesSubtitle('S02E07 · Eclipse')).toEqual({ code: 'S02E07', season: 2, episode: 7, episodeTitle: 'Eclipse' });
    expect(parseSeriesSubtitle('S1 E3 · Fracture')).toEqual({ code: 'S01E03', season: 1, episode: 3, episodeTitle: 'Fracture' });
    expect(parseSeriesSubtitle('E4 · Dawn', 'gw:55-s2e4')).toEqual({ code: 'S02E04', season: 2, episode: 4, episodeTitle: 'Dawn' });
    expect(parseSeriesSubtitle(undefined, 'gw:55-s3e11')).toEqual({ code: 'S03E11', season: 3, episode: 11, episodeTitle: undefined });
  });

  it('keeps SxxExx intact in native filenames even when series title exceeds 80 chars', () => {
    const longTitle = 'A Very Long Light Novel Adaptation Title That Goes On And On Across Many Words In Season Two';
    const safe = safeSeriesFileRecord({ title: longTitle, subtitle: 'S02E11 · Climax', container: 'MP4', tracks: { quality: '1080p' } });
    expect(safe.title.length).toBeLessThanOrEqual(64);
    expect(`${safe.title} - ${safe.subtitle}`).toContain('S02E11');
  });

  it('extracts 4K UHD, rating, and dub badges cleanly while keeping story genres separate', () => {
    const b = extractTitleBadges(
      {
        title: 'Shadow Protocol [Hindi]',
        altTitle: 'Dual Audio',
        rating: 8.36,
        genres: ['Action', 'Thriller', 'Bengali', '4K']
      },
      [{ height: 2160 }]
    );
    expect(b.has4k).toBe(true);
    expect(b.ratingLabel).toBe('8.4');
    expect(b.dubs).toEqual(['Hindi', 'Bengali', 'Dual Audio']);
    expect(b.storyGenres).toEqual(['Action', 'Thriller']);
  });

  it('groups multi-episode series downloads chronologically while keeping movies standalone', () => {
    const records = [
      { id: 'gw:1#s2e2', itemId: 'gw:1', episodeId: 'gw:1-s2e2', title: 'Dark Matter', subtitle: 'S02E02 · Horizon', poster: 'p1', status: 'queued' as const, doneBytes: 0, totalBytes: 100, estimatedBytes: 100, createdAt: 30 },
      { id: 'gw:9', itemId: 'gw:9', title: 'Solo Movie', poster: 'pm', status: 'running' as const, doneBytes: 50, totalBytes: 100, estimatedBytes: 100, createdAt: 25 },
      { id: 'gw:1#s1e1', itemId: 'gw:1', episodeId: 'gw:1-s1e1', title: 'Dark Matter', subtitle: 'S01E01 · Pilot', poster: 'p1', status: 'running' as const, doneBytes: 40, totalBytes: 100, estimatedBytes: 100, createdAt: 20 }
    ];
    const groups = groupDownloadsBySeries(records);
    expect(groups).toHaveLength(2);
    expect(groups[0].isSeries).toBe(true);
    expect(groups[0].title).toBe('Dark Matter');
    expect(groups[0].items.map((x) => x.meta.code)).toEqual(['S01E01', 'S02E02']);
    expect(groups[0].seasonSummary).toBe('S1 (1) · S2 (1)');
    expect(groups[1].isSeries).toBe(false);
    expect(groups[1].title).toBe('Solo Movie');
  });
});
