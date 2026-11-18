import { describe, expect, it } from 'vitest';
import {
  activeCues,
  audioDubOfSource,
  clampOffset,
  cueHtml,
  DEFAULT_SUB_STYLE,
  formatTrackLanguage,
  fullLanguageName,
  parseSubtitles,
  parseTimestamp,
  parseVtt,
  POPULAR_SUBTITLE_LANGUAGES,
  shiftCues,
  srtToVtt
} from './subs';

const SRT = `\uFEFF1\r\n00:00:01,000 --> 00:00:03,500\r\nHello <i>world</i>\r\n\r\n2\r\n00:01:02,05 --> 00:01:04,000\r\nLine one\r\nLine two\r\n`;

describe('subtitles', () => {
  it('srtParse_basic: SRT → VTT', () => {
    const vtt = srtToVtt(SRT);
    expect(vtt.startsWith('WEBVTT')).toBe(true);
    expect(vtt).toContain('00:00:01.000 --> 00:00:03.500');
    expect(vtt).toContain('00:01:02.050 --> 00:01:04.000');
    expect(vtt).not.toMatch(/^\d+$/m);
    const cues = parseVtt(vtt);
    expect(cues).toHaveLength(2);
    expect(cues[0]).toEqual({ start: 1, end: 3.5, text: 'Hello <i>world</i>' });
    expect(cues[1].start).toBeCloseTo(62.05, 6);
    expect(cues[1]).toMatchObject({ end: 64, text: 'Line one\nLine two' });
  });

  it('parses VTT with header, NOTE and short timestamps', () => {
    const cues = parseSubtitles('WEBVTT\n\nNOTE hi\n\nid1\n00:05.000 --> 00:06.000 align:start\nA\n');
    expect(cues).toEqual([{ start: 5, end: 6, text: 'A' }]);
    expect(parseTimestamp('1:00:00.5')).toBe(3600.5);
    expect(parseTimestamp('x')).toBeNaN();
  });

  it('offset_shiftsCueTimes both directions', () => {
    const cues = [{ start: 10, end: 12, text: 'a' }];
    expect(activeCues(cues, 10.5, 0)).toHaveLength(1);
    expect(activeCues(cues, 10.5, 1)).toHaveLength(0); // delayed by 1 s
    expect(activeCues(cues, 11.5, 1)).toHaveLength(1);
    expect(activeCues(cues, 9.6, -0.5)).toHaveLength(1); // earlier by 0.5 s
    expect(shiftCues(cues, -0.5)[0]).toMatchObject({ start: 9.5, end: 11.5 });
    expect(shiftCues([{ start: 1, end: 2, text: 'x' }], -5)).toHaveLength(0);
  });

  it('clamps offset to ±10 s in 50 ms steps', () => {
    expect(clampOffset(12)).toBe(10);
    expect(clampOffset(-11)).toBe(-10);
    expect(clampOffset(0.123)).toBeCloseTo(0.1, 5);
    expect(clampOffset(0.07)).toBeCloseTo(0.05, 5);
    expect(Object.is(clampOffset(-0.01), -0)).toBe(false);
  });

  it('cue html is safe', () => {
    expect(cueHtml('<i>hi</i> <script>x</script>\nb')).toBe('<i>hi</i> x<br>b');
  });

  it('defaults subtitle background opacity to 0 (transparent)', () => {
    expect(DEFAULT_SUB_STYLE).toEqual({ size: 100, color: '#ffffff', bg: 0 });
  });

  it('maps short language codes and dub labels to full human language words', () => {
    expect(fullLanguageName('bn')).toBe('Bangla');
    expect(fullLanguageName('ben')).toBe('Bangla');
    expect(fullLanguageName('bengali')).toBe('Bangla');
    expect(fullLanguageName('en')).toBe('English');
    expect(fullLanguageName('eng')).toBe('English');
    expect(fullLanguageName('hi')).toBe('Hindi');
    expect(fullLanguageName('hin')).toBe('Hindi');
    expect(fullLanguageName('ur')).toBe('Urdu');
    expect(fullLanguageName('zh')).toBe('Chinese');
    expect(fullLanguageName('chi')).toBe('Chinese');
    expect(fullLanguageName('zh-TW')).toBe('Chinese');
    expect(fullLanguageName('ar')).toBe('Arabic');
    expect(fullLanguageName('ko')).toBe('Korean');
    expect(fullLanguageName('ja')).toBe('Japanese');
    expect(fullLanguageName('es')).toBe('Spanish');
    expect(fullLanguageName('fr')).toBe('French');
    expect(fullLanguageName('id')).toBe('Indonesian');
    expect(fullLanguageName('in')).toBe('Indonesian');
    expect(fullLanguageName('tr')).toBe('Turkish');
    expect(fullLanguageName('ta')).toBe('Tamil');
    expect(fullLanguageName('te')).toBe('Telugu');
    expect(fullLanguageName('und')).toBe('Original');
    expect(fullLanguageName('')).toBe('Original');
    expect(formatTrackLanguage('bn', 'bn')).toBe('Bangla');
    expect(formatTrackLanguage('Subtitle 1', 'hi')).toBe('Hindi');
    expect(POPULAR_SUBTITLE_LANGUAGES.map((l) => l.name)).toEqual([
      'Bangla',
      'English',
      'Hindi',
      'Urdu',
      'Chinese',
      'Arabic',
      'Korean',
      'Japanese',
      'Spanish',
      'French',
      'Indonesian'
    ]);
    expect(audioDubOfSource({ label: '1080p FHD · High-Speed · Hindi · 1.4 GB' })).toBe('Hindi');
    expect(audioDubOfSource({ label: '720p HD · bn · 650 MB' })).toBe('Bangla');
    expect(audioDubOfSource({ label: '1080p FHD · Original' })).toBe('Original');
  });
});
