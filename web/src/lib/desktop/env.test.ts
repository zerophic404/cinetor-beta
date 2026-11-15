import { describe, expect, it } from 'vitest';
import { applyNativeProgress, chooseBackend, isTauriEnv, localPlaylist, MEDIA_TOKEN, nativeFileName } from './env';

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
