// Test-only helpers (node). Not imported by app code.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const MOCK = fileURLToPath(new URL('../../../../shared/mock/', import.meta.url));

/** Fetcher over the real shared mock JSON. Records requested URLs. */
export function fileFetcher(calls: string[] = []) {
  return async (url: string) => {
    calls.push(url);
    const name = url.split('/mock/')[1];
    const body = JSON.parse(readFileSync(MOCK + name, 'utf8'));
    return { ok: true, status: 200, json: async () => body };
  };
}
