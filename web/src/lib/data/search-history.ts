// Search history: LOCAL ONLY (IndexedDB), per profile; isolated spaces keep their own lists and
// are never written to the global (Home/Anime/Drama) history.
import type { PersistentCache } from './idb';
import { isVideoSpace, type Space } from './types';

export const HISTORY_MAX = 50;

/** 'global' for global search and video spaces; the space slug for isolated in-space search. */
export const historyScope = (space?: Space | null): string => (!space || isVideoSpace(space) ? 'global' : space.toLowerCase());

export function memoryStore(): PersistentCache & { dump(): Map<string, unknown> } {
  const m = new Map<string, unknown>();
  return {
    get: async (k) => m.get(k),
    set: async (k, v) => void m.set(k, v),
    dump: () => m
  };
}

export class SearchHistory {
  constructor(
    private store: PersistentCache,
    private max = HISTORY_MAX
  ) {}

  private key(profile: string, scope: string) {
    return `search-history:${profile}:${scope}`;
  }

  async list(profile: string, scope: string): Promise<string[]> {
    const v = await this.store.get(this.key(profile, scope));
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];
  }

  async isPaused(profile: string): Promise<boolean> {
    return (await this.store.get(`search-history-paused:${profile}`)) === true;
  }

  async setPaused(profile: string, paused: boolean): Promise<void> {
    await this.store.set(`search-history-paused:${profile}`, paused);
  }

  async add(profile: string, scope: string, query: string): Promise<string[]> {
    const q = query.trim().replace(/\s+/g, ' ');
    const cur = await this.list(profile, scope);
    if (!q || (await this.isPaused(profile))) return cur;
    const lower = q.toLowerCase();
    const next = [q, ...cur.filter((x) => x.toLowerCase() !== lower)].slice(0, this.max);
    await this.store.set(this.key(profile, scope), next);
    return next;
  }

  async remove(profile: string, scope: string, query: string): Promise<string[]> {
    const next = (await this.list(profile, scope)).filter((x) => x !== query);
    await this.store.set(this.key(profile, scope), next);
    return next;
  }

  async clear(profile: string, scope: string): Promise<void> {
    await this.store.set(this.key(profile, scope), []);
  }
}
