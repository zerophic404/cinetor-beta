// User-facing error text: generic messages only — never a URL, host, path, provider or query string
// (docs/requirements-hardening.md §2). Technical detail goes to an in-memory debug log that is OFF
// by default and only shown under Settings → Developer; it is never auto-sent anywhere.
//
// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer, scrape
// or hack anything without Mythbrix's explicit permission.
import { localKV, type SyncKV } from './data/kv';

export const DEV_LOG_KEY = 'cinetor.devLog';

/** Generic copy used across player / downloads / catalog. */
export const GENERIC = {
  playback: 'Playback failed. Try another quality.',
  download: 'Download failed. Please try again.',
  catalog: "Couldn't load content. Check your connection and retry.",
  offline: 'You are offline.',
  unknown: 'Something went wrong. Please try again.'
} as const;

/** Messages that are safe to show verbatim (no host / path / provider in them). */
const SAFE = [
  'No source available',
  'Live streams cannot be downloaded',
  'This source cannot be downloaded',
  'Not enough browser storage for this download',
  '18+ is locked',
  'Chapter unavailable, try another',
  'You are offline'
];

const URLISH =
  /\b(?:[a-z][a-z0-9+.-]*:\/\/\S+|\/\/\S+|(?:[\w-]+\.)+(?:[a-z]{2,24})(?::\d+)?(?:\/\S*)?|\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?)/gi;

/** Remove anything that looks like a URL, host, IP or query string from a string. */
export function scrub(text: string): string {
  return text
    .replace(URLISH, '…')
    .replace(/[?&][\w%.-]+=[^\s&]*/g, '')
    .replace(/\s{2,}/g, ' ')
    .trim();
}

const messageOf = (e: unknown): string => (e instanceof Error ? e.message : typeof e === 'string' ? e : '');

/**
 * Message to show the user. Known-safe texts pass through; every other error becomes the generic
 * fallback (unknown text may embed a URL, host or provider name, so it is never echoed).
 */
export function userMessage(e: unknown, fallback: string = GENERIC.unknown): string {
  const raw = messageOf(e).trim();
  const hit = SAFE.find((s) => raw.toLowerCase().startsWith(s.toLowerCase()));
  return hit ?? fallback;
}

export interface DebugEntry {
  t: number;
  tag: string;
  text: string;
}

/** Ring buffer of technical detail. Disabled by default; entries are dropped while off. */
export class DebugLog {
  private buf: DebugEntry[] = [];
  constructor(
    private kv: SyncKV = localKV(),
    private max = 200,
    private now: () => number = Date.now
  ) {}

  get enabled(): boolean {
    return this.kv.get(DEV_LOG_KEY) === '1';
  }

  setEnabled(on: boolean): void {
    if (on) this.kv.set(DEV_LOG_KEY, '1');
    else {
      this.kv.del(DEV_LOG_KEY);
      this.buf = [];
    }
  }

  add(tag: string, detail: unknown): void {
    if (!this.enabled) return;
    const text = messageOf(detail) || String(detail ?? '');
    this.buf.push({ t: this.now(), tag, text: text.slice(0, 400) });
    if (this.buf.length > this.max) this.buf.splice(0, this.buf.length - this.max);
  }

  get entries(): readonly DebugEntry[] {
    return this.buf;
  }

  clear(): void {
    this.buf = [];
  }
}

export const debugLog = new DebugLog();

/** Report an error: generic text for the UI, detail only in the (opt-in) debug log. */
export function report(tag: string, e: unknown, fallback: string = GENERIC.unknown): string {
  debugLog.add(tag, e);
  return userMessage(e, fallback);
}
