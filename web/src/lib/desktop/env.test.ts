import { describe, expect, it } from 'vitest';
import { applyNativeProgress, chooseBackend, isTauriEnv, localPlaylist, locationInfo, MEDIA_TOKEN, nativeFileName, saveCopySupported } from './env';

describe('desktop env', () => {
  it('detects Tauri by its injected internals', () => {
    expect(isTauriEnv({ __TAURI_INTERNALS__: {} })).toBe(true);
    expect(isTauriEnv({})).toBe(false);
    expect(isTauriEnv(undefined)).toBe(false);
    expect(isTauriEnv()).toBe(false); // node: no window
  });

  it('chooses native engine only inside Tauri for progressive + HLS', () => {
    expect(chooseBackend({ tauri: false, kind: 'progressive', container: 'MP4' })).toBe('browser');
    expect(chooseBackend({ tauri: false, kind: 'adaptive', container: 'HLS' })).toBe('browser');
    expect(chooseBackend({ tauri: true, kind: 'progressive', container: 'MP4' })).toBe('native');
    expect(chooseBackend({ tauri: true, kind: 'adaptive', container: 'HLS' })).toBe('native');
    expect(chooseBackend({ tauri: true, kind: 'adaptive', container: 'DASH' })).toBe('browser');
  });

  it('builds safe file names with the container extension', () => {
    expect(nativeFileName({ title: 'Big Buck: Bunny?', container: 'MP4' })).toBe('Big Buck Bunny.mp4');
    expect(nativeFileName({ title: 'Show', subtitle: 'S1 E2', container: 'HLS', tracks: { quality: '720p' } })).toBe('Show - S1 E2 [720p].ts');
    expect(nativeFileName({ title: '', container: 'XYZ' })).toBe('download.bin');
    expect(nativeFileName({ title: 'x'.repeat(300), container: 'WEBM' }).length).toBe(115);
  });

  it('points the byte-range playlist at the local media url', () => {
    const t = `#EXTM3U\n#EXT-X-MAP:URI="${MEDIA_TOKEN}",BYTERANGE="10@0"\n#EXTINF:6.000,\n#EXT-X-BYTERANGE:50@10\n${MEDIA_TOKEN}\n#EXT-X-ENDLIST\n`;
    const out = localPlaylist(t, 'http://asset.localhost/C%3A%5Cx.mp4');
    expect(out).not.toContain(MEDIA_TOKEN);
    expect(out.match(/asset\.localhost/g)?.length).toBe(2);
  });

  it('maps native progress onto the record and returns the byte delta', () => {
    const r = { doneBytes: 100, totalBytes: 0, chunks: [] as { start: number; end: number; pos: number }[] } as Parameters<typeof applyNativeProgress>[0];
    const d = applyNativeProgress(r, { id: 'a', doneBytes: 600, totalBytes: 1000, speed: 1, chunks: [{ start: 0, end: 999, pos: 600 }], rangeSupported: true, segmentsDone: 0, segmentsTotal: 0 });
    expect(d).toBe(500);
    expect(r.totalBytes).toBe(1000);
    expect(r.fraction).toBeCloseTo(0.6);
    expect(r.chunks).toEqual([{ start: 0, end: 999, pos: 600 }]);
    const d2 = applyNativeProgress(r, { id: 'a', doneBytes: 700, totalBytes: 2000, speed: 1, chunks: [], rangeSupported: true, segmentsDone: 3, segmentsTotal: 12 });
    expect(d2).toBe(100);
    expect(r.segmentsTotal).toBe(12);
    expect(r.fraction).toBeCloseTo(0.25);
  });
});

describe('download location copy (§4) and leak-safety (§2)', () => {
  it('detects the File System Access save picker', () => {
    expect(saveCopySupported({ showSaveFilePicker: () => {} })).toBe(true);
    expect(saveCopySupported({})).toBe(false);
    expect(saveCopySupported({ showSaveFilePicker: 'nope' })).toBe(false);
    expect(saveCopySupported(undefined)).toBe(false);
  });

  it('desktop shows the chosen folder and says it is changeable', () => {
    const i = locationInfo({ tauri: true, dir: '/home/u/Movies' });
    expect(i.label).toBe('/home/u/Movies');
    expect(i.changeable).toBe(true);
    expect(i.note.toLowerCase()).toContain('change');
  });

  it('desktop falls back to the default Downloads/Cinetor folder', () => {
    expect(locationInfo({ tauri: true }).label).toBe('Downloads/Cinetor');
    expect(locationInfo({ tauri: true, dir: '   ' }).label).toBe('Downloads/Cinetor');
    expect(locationInfo({ tauri: true, dir: null }).label).toBe('Downloads/Cinetor');
  });

  it('web says it keeps files privately and offers Save a copy where supported', () => {
    const ok = locationInfo({ tauri: false, saveCopy: true });
    expect(ok.changeable).toBe(false);
    expect(ok.label).toBe('Private app storage');
    expect(ok.note).toContain('Save a copy');
  });

  it('web states plainly when a save location cannot be chosen', () => {
    const no = locationInfo({ tauri: false, saveCopy: false });
    expect(no.note).toContain('cannot save a copy');
    expect(no.note).not.toContain('Save a copy…');
  });

  it('never leaks a URL, host or path of a stream source in location copy', () => {
    const urlish = /https?:\/\/|\/\/|\.(?:com|net|org|io|dev|xyz|vercel\.app)\b/i;
    for (const i of [
      locationInfo({ tauri: true, dir: '/home/u/Movies' }),
      locationInfo({ tauri: true }),
      locationInfo({ tauri: false, saveCopy: true }),
      locationInfo({ tauri: false, saveCopy: false })
    ]) {
      expect(i.note).not.toMatch(urlish);
      expect(i.note.toLowerCase()).not.toContain('gateway');
      expect(i.note.toLowerCase()).not.toContain('proxy');
      expect(i.note.toLowerCase()).not.toContain('cdn');
    }
  });
});
