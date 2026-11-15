// App-wide catalog. Home/Anime/Drama (+ 18+ while unlocked) come ONLY from the antiblock gateway
// (offline → cached gateway data or error + retry). Live TV & Sports and Music stay mock; Comics
// come from MangaDex (mock library = offline fallback).
import { Catalog } from './source';
import { ColorCache } from './color';
import { ComicsSource, type JsonFetch } from '../comics/mangadex';
import { isTauriEnv } from '../desktop/env';
import { invoke } from '../desktop/tauri';
import { DeadTitles, GatewayCatalogSource, GatewayClient, gatewayBase } from './gateway';
import { StreamResolver } from './gateway-play';
import { idbCache } from './idb';
import { colorSampleUrl } from './images';
import { repo } from './repo';

export const persist = idbCache();

/** Dominant colors (64px canvas sample of a small thumbnail), memory LRU + IndexedDB. */
export const colors = new ColorCache({ persist });
const colorOf = (poster: string) => colors.peek(colorSampleUrl(poster, repo.asset.bind(repo)));

/** Titles that failed to resolve a playable stream (hidden everywhere). */
export const deadTitles = new DeadTitles();

/** Gateway base is read on every request (Settings → Developer override applies without reload). */
export const gatewayClient = new GatewayClient({ base: () => gatewayBase(), persist });
export const gatewaySource = new GatewayCatalogSource({ client: gatewayClient, colorOf, persist, isDead: (id) => deadTitles.has(id) });

let adultGate: () => boolean = () => false;

/** Wired by the /app layout to the 18+ store (enabled + PIN-unlocked this session). */
export function setAdultGate(fn: () => boolean): void {
  adultGate = fn;
}

/**
 * COMICS: MangaDex metadata/chapters + gateway chapter-page proxy.
 * The desktop app routes JSON through Rust (`md_get`) because api.mangadex.org only echoes CORS
 * headers for allow-listed origins; browsers therefore fall back to the bundled offline library.
 */
const webJson: JsonFetch = async (url, init) => {
  const res = await fetch(url, { signal: init?.signal, headers: { accept: 'application/json' } });
  if (!res.ok) throw new Error(`Comics HTTP ${res.status}`);
  return res.json();
};
const nativeJson: JsonFetch | null = isTauriEnv()
  ? async (url, init) => JSON.parse(await invoke<string>('md_get', { url, ua: init?.headers?.['User-Agent'] ?? 'CinetorApp/1.0' }))
  : null;

export const comicsSource = new ComicsSource({ web: webJson, native: nativeJson, gatewayBase: () => gatewayBase() });

export const catalog = new Catalog({ mock: repo, gateway: gatewaySource, comics: comicsSource, adultUnlocked: () => adultGate() });

/** Lazy stream resolution (gateway proxy MP4 variants + captions), ≤20 min per signed link. */
export const streams = new StreamResolver({
  base: () => gatewayBase(),
  getJson: (url, signal, timeoutMs) => gatewayClient.fetchJson(url, signal, timeoutMs),
  search: (q, signal) => gatewaySource.searchRaw(q, signal),
  onDead: (id) => deadTitles.add(id)
});
