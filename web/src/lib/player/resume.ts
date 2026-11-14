// Resume positions per profile (IndexedDB in the browser; any KV in tests). Pure logic.
import { isVideoSpace, type Space } from '../data/types';

export interface ResumeEntry {
  itemId: string;
  episodeId?: string;
  space: Space;
  pos: number;
  dur: number;
  updatedAt: number;
  title?: string;
}

export interface KV<V> {
  get(key: string): Promise<V | undefined>;
  set(key: string, value: V): Promise<void>;
  del(key: string): Promise<void>;
  values(): Promise<V[]>;
}

export const SAVE_EVERY_MS = 5000;
const MIN_POS = 5;
const END_SLACK = 15;

export const mediaKey = (itemId: string, episodeId?: string | null): string => (episodeId ? `${itemId}#${episodeId}` : itemId);

/** Position worth resuming from (skip the first seconds and the credits). */
export function resumable(pos: number, dur: number): boolean {
  if (!(pos >= MIN_POS)) return false;
  if (dur > 0 && (pos > dur - END_SLACK || pos / dur > 0.95)) return false;
  return true;
}

export class ResumeStore {
  constructor(private kv: KV<ResumeEntry & { profile: string }>, private now: () => number = Date.now) {}

  private key(profile: string, itemId: string, episodeId?: string | null) {
    return `${profile}|${mediaKey(itemId, episodeId)}`;
  }
  private lastKey(profile: string, itemId: string) {
    return `${profile}|${itemId}|last`;
  }

  /** Save (or clear when finished). Also remembers the item's last episode. */
  async save(profile: string, e: Omit<ResumeEntry, 'updatedAt'>): Promise<void> {
    const entry = { ...e, profile, updatedAt: this.now() };
    const k = this.key(profile, e.itemId, e.episodeId);
    if (resumable(e.pos, e.dur)) await this.kv.set(k, entry);
    else await this.kv.del(k);
    if (e.episodeId) await this.kv.set(this.lastKey(profile, e.itemId), entry);
  }

  async get(profile: string, itemId: string, episodeId?: string | null): Promise<ResumeEntry | undefined> {
    return this.kv.get(this.key(profile, itemId, episodeId));
  }

  /** Start position for playback (0 when nothing resumable). */
  async startFor(profile: string, itemId: string, episodeId?: string | null): Promise<number> {
    const e = await this.get(profile, itemId, episodeId);
    return e && resumable(e.pos, e.dur) ? e.pos : 0;
  }

  /** Last watched episode id of a series (for the details Play/Resume target). */
  async lastEpisode(profile: string, itemId: string): Promise<string | undefined> {
    return (await this.kv.get(this.lastKey(profile, itemId)))?.episodeId;
  }

  /** Delete every resume entry of a profile (profile deleted / history cleared). */
  async clearProfile(profile: string): Promise<number> {
    const mine = (await this.kv.values()).filter((e) => e.profile === profile);
    for (const e of mine) {
      await this.kv.del(this.key(profile, e.itemId, e.episodeId));
      await this.kv.del(this.lastKey(profile, e.itemId));
    }
    return mine.length;
  }

  /** Continue Watching: video spaces only (isolation), newest first, one entry per item. */
  async continueWatching(profile: string, limit = 20): Promise<ResumeEntry[]> {
    const all = (await this.kv.values()).filter((e) => e.profile === profile && isVideoSpace(e.space) && resumable(e.pos, e.dur));
    all.sort((a, b) => b.updatedAt - a.updatedAt);
    const seen = new Set<string>();
    const out: ResumeEntry[] = [];
    for (const e of all) {
      if (seen.has(e.itemId)) continue;
      seen.add(e.itemId);
      out.push(e);
      if (out.length >= limit) break;
    }
    return out;
  }
}

/** Throttle helper: save every 5 s while playing. */
export const shouldSave = (now: number, lastSaved: number): boolean => now - lastSaved >= SAVE_EVERY_MS;

export function memoryKV<V>(): KV<V> & { map: Map<string, V> } {
  const map = new Map<string, V>();
  return {
    map,
    get: async (k) => map.get(k),
    set: async (k, v) => void map.set(k, v),
    del: async (k) => void map.delete(k),
    values: async () => [...map.values()]
  };
}

/** IndexedDB KV in its own DB (no version clash with the catalog cache). Browser only. */
export function idbKV<V>(dbName: string, store: string): KV<V> {
  let dbp: Promise<IDBDatabase> | null = null;
  const open = () =>
    (dbp ??= new Promise<IDBDatabase>((resolve, reject) => {
      const req = indexedDB.open(dbName, 1);
      req.onupgradeneeded = () => {
        if (!req.result.objectStoreNames.contains(store)) req.result.createObjectStore(store);
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    }));
  function run<T>(mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest): Promise<T> {
    return open().then(
      (db) =>
        new Promise<T>((resolve, reject) => {
          const tx = db.transaction(store, mode);
          const req = fn(tx.objectStore(store));
          tx.oncomplete = () => resolve(req.result as T);
          tx.onerror = () => reject(tx.error);
        })
    );
  }
  return {
    get: (k) => run<V | undefined>('readonly', (s) => s.get(k)).catch(() => undefined),
    set: (k, v) => run<unknown>('readwrite', (s) => s.put(v, k)).then(() => undefined, () => undefined),
    del: (k) => run<unknown>('readwrite', (s) => s.delete(k)).then(() => undefined, () => undefined),
    values: () => run<V[]>('readonly', (s) => s.getAll()).catch(() => [])
  };
}
