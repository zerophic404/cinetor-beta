import { base } from '$app/paths';
import { CatalogRepository, DEFAULT_LATENCY, NO_LATENCY } from './catalog';
import { normalize } from './search';
import type { Item } from './types';

export const LATENCY_KEY = 'cinetor.latency';

function initialLatency() {
  try {
    if (typeof localStorage !== 'undefined' && localStorage.getItem(LATENCY_KEY) === 'off') return NO_LATENCY;
  } catch {
    /* ignore */
  }
  return DEFAULT_LATENCY;
}

export interface BoundCatalogHandlers {
  castWorks: (personName: string, signal?: AbortSignal) => Promise<Item[]>;
  warmStartupSpaces: () => Promise<string[]>;
}

export class AppRepository extends CatalogRepository {
  private bound: BoundCatalogHandlers | null = null;

  bindCatalog(handlers: BoundCatalogHandlers): void {
    this.bound = handlers;
  }

  async castWorks(personName: string, signal?: AbortSignal): Promise<Item[]> {
    if (this.bound) return this.bound.castWorks(personName, signal);
    const want = normalize(personName);
    if (!want) return [];
    const all = await this.allItems().catch(() => [] as Item[]);
    return all.filter((i) => i.cast?.some((c) => normalize(c) === want));
  }

  async warmStartupSpaces(): Promise<string[]> {
    if (this.bound) return this.bound.warmStartupSpaces();
    return [];
  }
}

/** App-wide repository instance (mock JSON under `${base}/mock`) — isolated TV / Music / Comics + bound catalog helpers. */
export const repo = new AppRepository({ baseUrl: base, latency: initialLatency(), spaces: ['TV', 'MUSIC', 'COMICS'] });

export const castWorks = (personName: string, signal?: AbortSignal): Promise<Item[]> => repo.castWorks(personName, signal);
export const warmStartupSpaces = (): Promise<string[]> => repo.warmStartupSpaces();

export function setSimulatedLatency(on: boolean): void {
  repo.setLatency(on ? DEFAULT_LATENCY : NO_LATENCY);
  try {
    if (on) localStorage.removeItem(LATENCY_KEY);
    else localStorage.setItem(LATENCY_KEY, 'off');
  } catch {
    /* ignore */
  }
}

export const img = (path: string) => repo.asset(path);
