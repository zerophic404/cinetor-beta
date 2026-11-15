// App-wide catalog. Home/Anime/Drama (+ 18+ while unlocked) come ONLY from the antiblock gateway
// (offline → cached gateway data or error + retry). Live TV & Sports, Music, Comics stay mock.
import { Catalog } from './source';
import { ColorCache } from './color';
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

export const catalog = new Catalog({ mock: repo, gateway: gatewaySource, adultUnlocked: () => adultGate() });

/** Lazy stream resolution (gateway proxy MP4 variants + captions), ≤20 min per signed link. */
export const streams = new StreamResolver({
  base: () => gatewayBase(),
  getJson: (url, signal, timeoutMs) => gatewayClient.fetchJson(url, signal, timeoutMs),
  search: (q, signal) => gatewaySource.searchRaw(q, signal),
  onDead: (id) => deadTitles.add(id)
});
