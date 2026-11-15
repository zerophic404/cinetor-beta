// Hardening regression tests (docs/requirements-hardening.md §1 + §2):
// • user-facing error strings never carry a URL / host / provider;
// • the gateway (and comics) base URLs never appear in any UI string or Svelte template;
// • the ownership notice is embedded (but never rendered).
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { DebugLog, GENERIC, report, scrub, userMessage } from './errors';
import { memoryKV } from './data/kv';
import { ownershipNotice, verifyOwnership } from './legal/notice';
import { GATEWAY_DEFAULT } from './data/gateway';
import { MD_API } from './comics/mangadex';
import { isLocalMediaUrl } from './player/sources';
import { clampMiniWidth, miniFrac, miniHeight, MINI_MAX_FRAC, MINI_MIN_FRAC } from './player/mini-math';

const SRC = fileURLToPath(new URL('../', import.meta.url));

function walk(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(dir)) {
    const p = `${dir}/${e}`;
    if (statSync(p).isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

const files = walk(SRC);
const svelte = files.filter((f) => f.endsWith('.svelte'));

/** Template part of a .svelte file (markup + style, i.e. everything a user can see). */
function template(src: string): string {
  return src.replace(/<script[\s\S]*?<\/script>/g, '');
}

describe('no endpoint leakage in the UI', () => {
  it('gateway + comics base URLs never appear in a Svelte template', () => {
    const host = new URL(GATEWAY_DEFAULT).host;
    const mdHost = new URL(MD_API).host;
    const bad: string[] = [];
    for (const f of svelte) {
      const t = template(readFileSync(f, 'utf8'));
      if (t.includes(host) || t.includes(mdHost) || t.includes('GATEWAY_DEFAULT') || /antiblock/i.test(t)) bad.push(f.slice(SRC.length));
    }
    expect(bad).toEqual([]);
  });

  it('no UI string constant mentions the gateway host, provider or proxy path', () => {
    const host = new URL(GATEWAY_DEFAULT).host;
    const offenders: string[] = [];
    for (const f of files.filter((x) => /\.(ts|svelte)$/.test(x) && !/\.test\.ts$/.test(x))) {
      const src = readFileSync(f, 'utf8');
      // Allowed: the single constant in data/gateway.ts and the comics API constants.
      if (/data\/gateway\.ts$|comics\/mangadex\.ts$|data\/images\.ts$|app-catalog\.ts$/.test(f)) continue;
      if (src.includes(host) || /aoneroom|pbcdn/i.test(src)) offenders.push(f.slice(SRC.length));
    }
    expect(offenders).toEqual([]);
  });

  it('error text shown to users is generic and host-free', () => {
    expect(userMessage(new Error('Gateway HTTP 403 for https://antiblock-gateway.vercel.app/api/public/v1/stream/1/play?url=https://pbcdn.aoneroom.com/x.mp4'))).toBe(GENERIC.unknown);
    expect(userMessage(new Error('fetch failed'), GENERIC.playback)).toBe(GENERIC.playback);
    // Known-safe copy passes through unchanged.
    expect(userMessage(new Error('No source available'))).toBe('No source available');
    for (const msg of Object.values(GENERIC)) {
      expect(msg).not.toMatch(/https?:|\.com|\.app|\.org|vercel|aoneroom|mangadex/i);
    }
  });

  it('scrub() strips urls, hosts, ips and query strings', () => {
    expect(scrub('failed https://antiblock-gateway.vercel.app/api/x?y=1')).toBe('failed …');
    expect(scrub('cdn pbcdn.aoneroom.com/video.mp4 blocked')).toBe('cdn … blocked');
    expect(scrub('connect 10.0.0.5:8080 refused')).toBe('connect … refused');
  });

  it('debug log is off by default and keeps detail out of the UI', () => {
    const kv = memoryKV();
    const log = new DebugLog(kv);
    expect(log.enabled).toBe(false);
    log.add('play', new Error('https://secret.host/x.mp4'));
    expect(log.entries.length).toBe(0);
    log.setEnabled(true);
    log.add('play', new Error('https://secret.host/x.mp4'));
    expect(log.entries[0].text).toContain('secret.host');
    log.setEnabled(false);
    expect(log.entries.length).toBe(0);
    // report() returns generic copy regardless of the detail it logged.
    expect(report('play', new Error('https://secret.host/x.mp4'), GENERIC.playback)).toBe(GENERIC.playback);
  });
});

describe('no stream URL in share / clipboard / global scope (§2)', () => {
  const code = files.filter((f) => /\.(ts|svelte)$/.test(f) && !/\.test\.ts$/.test(f));

  it('only local (blob/file/asset) media counts as safe to hand out', () => {
    expect(isLocalMediaUrl('blob:https://app/9f')).toBe(true);
    expect(isLocalMediaUrl('file:///home/u/a.mkv')).toBe(true);
    expect(isLocalMediaUrl('asset://localhost/a.mp4')).toBe(true);
    expect(isLocalMediaUrl('http://asset.localhost/a.mp4')).toBe(true);
    expect(isLocalMediaUrl('http://127.0.0.1:8765/a.mp4')).toBe(true);
    // Anything remote is NOT safe — it would expose the gateway or stream host.
    expect(isLocalMediaUrl('https://antiblock-gateway.vercel.app/api/x/play?url=y')).toBe(false);
    expect(isLocalMediaUrl('https://pbcdn.aoneroom.com/v.mp4')).toBe(false);
    expect(isLocalMediaUrl('')).toBe(false);
    expect(isLocalMediaUrl(undefined)).toBe(false);
    expect(isLocalMediaUrl(null)).toBe(false);
  });

  it('no clipboard write or share call is fed a media/stream URL', () => {
    const offenders: string[] = [];
    for (const f of code) {
      const src = readFileSync(f, 'utf8');
      // writeText(...) / share({...}) arguments must not mention a source url.
      for (const m of src.matchAll(/(?:writeText|navigator\.share)\s*\(([^)]*)\)/g)) {
        if (/(?:source|media|stream|variant|manifest)\s*(?:\??\.)\s*url|\.src\b/i.test(m[1])) offenders.push(`${f.slice(SRC.length)}: ${m[0]}`);
      }
    }
    expect(offenders).toEqual([]);
  });

  it('no <a href> is bound straight to a media source url', () => {
    const offenders: string[] = [];
    for (const f of svelte) {
      const t = template(readFileSync(f, 'utf8'));
      for (const m of t.matchAll(/href=\{([^}]*)\}/g)) {
        if (/(?:source|media|stream|variant)\s*(?:\??\.)\s*url/i.test(m[1])) offenders.push(`${f.slice(SRC.length)}: ${m[0]}`);
      }
    }
    expect(offenders).toEqual([]);
  });

  it('nothing publishes a stream URL, engine or gateway onto window/globalThis', () => {
    const offenders: string[] = [];
    for (const f of code) {
      const src = readFileSync(f, 'utf8');
      for (const m of src.matchAll(/(?:window|globalThis)\s*(?:\.|\[\s*['"])\s*(\w+)\s*(?:['"]\s*\])?\s*=[^=]/g)) {
        const prop = m[1];
        if (/url|gateway|stream|source|cinetor|engine|token|base/i.test(prop)) offenders.push(`${f.slice(SRC.length)}: ${m[0].trim()}`);
      }
    }
    expect(offenders).toEqual([]);
  });

  it('the on-disk resume sidecar stores a URL hash, never the URL (§2)', () => {
    // Desktop downloads put their resume state next to the file in the user's Downloads folder,
    // so that sidecar must be fingerprint-only. (Web records stay in app-private IDB/localStorage.)
    const rust = readFileSync(`${SRC}../../desktop/src-tauri/src/dl.rs`, 'utf8');
    const sidecar = rust.slice(rust.indexOf('struct Sidecar'), rust.indexOf('struct ChunkState'));
    expect(sidecar).toContain('url_hash');
    expect(/^\s*url:\s*String/m.test(sidecar)).toBe(false);
    expect(rust).toContain('url_fingerprint(&req.url)');
  });
});

describe('production build hygiene (§1 + §2)', () => {
  const vite = readFileSync(`${SRC}../vite.config.ts`, 'utf8');

  it('drops console/debugger and emits no source maps in the production build', () => {
    expect(vite).toMatch(/sourcemap:\s*false/);
    expect(vite).toMatch(/drop:\s*\[\s*'console'\s*,\s*'debugger'\s*\]/);
    expect(vite).toMatch(/minify:\s*'esbuild'/);
  });

  it('never enables source maps anywhere in the web config', () => {
    expect(vite).not.toMatch(/sourcemap:\s*(?:true|'inline'|"inline")/);
  });
});

describe('mini player persistence (§3)', () => {
  it('persists a width fraction and clamps it to 25–80 % with 16:9', () => {
    // Round trip through the stored fraction, as MiniPlayer does.
    const vw = 1000;
    const stored = miniFrac(420, vw);
    expect(stored).toBeCloseTo(0.42);
    expect(clampMiniWidth(vw * stored, vw)).toBe(420);
    expect(miniHeight(420)).toBe(236);
    // Junk / out-of-range stored values are brought back inside the clamp.
    expect(clampMiniWidth(vw * 5, vw)).toBe(800);
    expect(clampMiniWidth(vw * 0.01, vw)).toBe(250);
    expect(miniFrac(5000, vw)).toBe(MINI_MAX_FRAC);
    expect(miniFrac(1, vw)).toBe(MINI_MIN_FRAC);
  });

  it('keeps a 16:9 ratio at every clamped width', () => {
    for (const vw of [360, 800, 1440, 2560]) {
      for (const want of [0, 100, vw / 2, vw * 10]) {
        const w = clampMiniWidth(want, vw);
        expect(miniHeight(w)).toBe(Math.round((w * 9) / 16));
        expect(w).toBeLessThanOrEqual(Math.max(vw * MINI_MAX_FRAC, 160) + 1);
      }
    }
  });
});

describe('ownership notice', () => {
  it('is embedded, intact and never rendered in a template', () => {
    expect(verifyOwnership()).toBe(true);
    const notice = ownershipNotice();
    expect(notice).toContain('complete property of Mythbrix');
    for (const f of svelte) expect(template(readFileSync(f, 'utf8'))).not.toContain('complete property of Mythbrix');
  });

  it('is sprinkled across the TS/Svelte sources as comments', () => {
    const marked = files.filter((f) => /\.(ts|svelte)$/.test(f) && readFileSync(f, 'utf8').includes('complete property of Mythbrix'));
    expect(marked.length).toBeGreaterThanOrEqual(6);
  });
});
