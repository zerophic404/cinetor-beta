// Download option de-duplication / grouping — docs/requirements-hardening.md §4.
// Pure logic only, so these use relative imports (the vitest config has no `$lib` alias).
//
// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer, scrape
// or hack anything without Mythbrix's explicit permission.
import { describe, expect, it } from 'vitest';
import { dedupeOptions, groupByQuality, optionKey, type QualityLike } from './math';

const v = (o: QualityLike & { url?: string }) => o;

describe('optionKey', () => {
  it('is identical for the same resolution + format + size from different sources', () => {
    const a = v({ height: 1080, kind: 'MP4', bytes: 1_400_000_000, bandwidth: 5_000_000, url: 'a' });
    const b = v({ height: 1080, kind: 'MP4', bytes: 1_400_400_000, bandwidth: 9_000_000, url: 'b' });
    expect(optionKey(a)).toBe(optionKey(b));
  });

  it('separates different resolution, format or size', () => {
    const base = v({ height: 1080, kind: 'MP4', bytes: 1_000_000_000 });
    expect(optionKey({ ...base, height: 720 })).not.toBe(optionKey(base));
    expect(optionKey({ ...base, kind: 'MKV' })).not.toBe(optionKey(base));
    expect(optionKey({ ...base, bytes: 2_000_000_000 })).not.toBe(optionKey(base));
  });

  it('ignores bandwidth and is case-insensitive about the format', () => {
    expect(optionKey({ height: 720, kind: 'mp4', bytes: 5_000_000, bandwidth: 1 })).toBe(optionKey({ height: 720, container: 'MP4', bytes: 5_000_000, bandwidth: 999 }));
  });

  it('falls back to the label when there is no height', () => {
    expect(optionKey({ label: 'Original (file)' })).toBe(optionKey({ label: ' original (FILE) ' }));
    expect(optionKey({ label: 'Original' })).not.toBe(optionKey({ label: 'Audio' }));
  });

  it('uses estimatedBytes when bytes is missing', () => {
    expect(optionKey({ height: 480, kind: 'MP4', estimatedBytes: 300_000_000 })).toBe(optionKey({ height: 480, kind: 'MP4', bytes: 300_000_000 }));
  });
});

describe('dedupeOptions', () => {
  it('shows an identical option exactly once', () => {
    const out = dedupeOptions([
      v({ height: 1080, kind: 'MP4', bytes: 1_400_000_000, url: 'a' }),
      v({ height: 1080, kind: 'MP4', bytes: 1_400_000_000, url: 'b' }),
      v({ height: 1080, kind: 'MP4', bytes: 1_400_000_000, url: 'c' }),
      v({ height: 720, kind: 'MP4', bytes: 700_000_000, url: 'd' })
    ]);
    expect(out).toHaveLength(2);
    expect(out.map((o) => o.height)).toEqual([1080, 720]);
  });

  it('prefers the primary source over a faster one', () => {
    const out = dedupeOptions([
      v({ height: 1080, kind: 'MP4', bytes: 1_000_000_000, bandwidth: 9_000_000, url: 'fast' }),
      v({ height: 1080, kind: 'MP4', bytes: 1_000_000_000, bandwidth: 1_000_000, primary: true, url: 'primary' })
    ]);
    expect(out).toHaveLength(1);
    expect(out[0].url).toBe('primary');
  });

  it('prefers the fastest source when none is primary', () => {
    const out = dedupeOptions([
      v({ height: 720, kind: 'MP4', bytes: 500_000_000, bandwidth: 2_000_000, url: 'slow' }),
      v({ height: 720, kind: 'MP4', bytes: 500_000_000, bandwidth: 8_000_000, url: 'fast' })
    ]);
    expect(out).toHaveLength(1);
    expect(out[0].url).toBe('fast');
  });

  it('keeps the position of the first occurrence (list stays best-first)', () => {
    const out = dedupeOptions([
      v({ height: 1080, kind: 'MP4', bytes: 1_000_000_000, bandwidth: 1, url: 'a1' }),
      v({ height: 720, kind: 'MP4', bytes: 500_000_000, url: 'b' }),
      v({ height: 1080, kind: 'MP4', bytes: 1_000_000_000, bandwidth: 9, url: 'a2' })
    ]);
    expect(out.map((o) => o.url)).toEqual(['a2', 'b']);
  });

  it('keeps distinct formats of the same resolution', () => {
    const out = dedupeOptions([
      v({ height: 1080, kind: 'MP4', bytes: 1_000_000_000 }),
      v({ height: 1080, kind: 'MKV', bytes: 1_300_000_000 })
    ]);
    expect(out).toHaveLength(2);
  });

  it('handles empty and single-item lists', () => {
    expect(dedupeOptions([])).toEqual([]);
    expect(dedupeOptions([v({ height: 360 })])).toHaveLength(1);
  });

  it('does not mutate the input', () => {
    const input = [v({ height: 720, kind: 'MP4', bytes: 1, bandwidth: 1 }), v({ height: 720, kind: 'MP4', bytes: 1, bandwidth: 2 })];
    const copy = [...input];
    dedupeOptions(input);
    expect(input).toEqual(copy);
  });
});

describe('groupByQuality', () => {
  it('groups de-duplicated options by quality, best first', () => {
    const g = groupByQuality([
      v({ height: 720, kind: 'MP4', bytes: 700_000_000, url: 'a' }),
      v({ height: 1080, kind: 'MP4', bytes: 1_400_000_000, url: 'b' }),
      v({ height: 1080, kind: 'MKV', bytes: 1_900_000_000, url: 'c' }),
      v({ height: 1080, kind: 'MP4', bytes: 1_400_000_000, url: 'dup' })
    ]);
    expect(g.map((x) => x.label)).toEqual(['1080p', '720p']);
    expect(g[0].options).toHaveLength(2);
    expect(g[1].options).toHaveLength(1);
  });

  it('puts unknown-height options last under their own label', () => {
    const g = groupByQuality([v({ label: 'Original (file)' }), v({ height: 480, kind: 'MP4', bytes: 1 })]);
    expect(g.map((x) => x.label)).toEqual(['480p', 'Original (file)']);
    expect(g[1].height).toBe(0);
  });

  it('never repeats an identical option across groups', () => {
    const list = Array.from({ length: 6 }, (_, i) => v({ height: 1080, kind: 'MP4', bytes: 1_000_000_000, url: `s${i}` }));
    const g = groupByQuality(list);
    expect(g).toHaveLength(1);
    expect(g[0].options).toHaveLength(1);
  });

  it('is empty for an empty list', () => {
    expect(groupByQuality([])).toEqual([]);
  });
});
