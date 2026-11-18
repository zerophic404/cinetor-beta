import { describe, expect, it } from 'vitest';
import { colorSampleUrl, gwImgUrl, imgAttrs, stripOssProcess } from './images';

describe('images: stripOssProcess & distinct CORS colorSampleUrl', () => {
  it('strips x-oss-process query parameter cleanly while preserving other params and hash', () => {
    const base = 'https://pbcdnw.aoneroom.com/image/2026/poster.jpg';
    expect(stripOssProcess(gwImgUrl(base, 342))).toBe(base);
    expect(stripOssProcess(`${base}?x-oss-process=image/resize,w_500&sig=abc`)).toBe(`${base}?sig=abc`);
    expect(stripOssProcess(`${base}?token=1&x-oss-process=image/resize,w_342&sig=2#hero`)).toBe(`${base}?token=1&sig=2#hero`);
    expect(stripOssProcess(`${base}?token=1&x-oss-process=image/resize,w_342`)).toBe(`${base}?token=1`);
    expect(stripOssProcess(base)).toBe(base);
    expect(stripOssProcess('')).toBe('');
  });

  it('keeps colorSampleUrl distinct from Poster imgAttrs src/srcset to prevent browser CORS cache collisions', () => {
    const oss = 'https://pbcdn.aoneroom.com/image/2026/a.jpg';
    const sampleOss = colorSampleUrl(oss, (p) => p);
    for (const tier of ['LOW', 'MID', 'HIGH'] as const) {
      const attrs = imgAttrs(oss, 'poster', tier, (p) => p);
      expect(sampleOss).not.toBe(attrs.src);
      expect(attrs.srcset ?? '').not.toContain(sampleOss);
    }

    const tmdb = 'tmdb:/poster.jpg';
    const sampleTmdb = colorSampleUrl(tmdb, (p) => p);
    for (const tier of ['LOW', 'MID', 'HIGH'] as const) {
      const attrs = imgAttrs(tmdb, 'poster', tier, (p) => p);
      expect(sampleTmdb).not.toBe(attrs.src);
      expect(attrs.srcset ?? '').not.toContain(sampleTmdb);
    }
  });
});
