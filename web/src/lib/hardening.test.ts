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
