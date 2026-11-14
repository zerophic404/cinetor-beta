import { base } from '$app/paths';
import { CatalogRepository, DEFAULT_LATENCY, NO_LATENCY } from './catalog';

export const LATENCY_KEY = 'cinetor.latency';

function initialLatency() {
  try {
    if (typeof localStorage !== 'undefined' && localStorage.getItem(LATENCY_KEY) === 'off') return NO_LATENCY;
  } catch {
    /* ignore */
  }
  return DEFAULT_LATENCY;
}

/** App-wide repository instance (mock JSON under `${base}/mock`). */
export const repo = new CatalogRepository({ baseUrl: base, latency: initialLatency() });

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
