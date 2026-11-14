// Tiny synchronous key/value abstraction over localStorage (memory in tests / SSR / private mode).
export interface SyncKV {
  get(key: string): string | null;
  set(key: string, value: string): void;
  del(key: string): void;
  keys(): string[];
}

export function memoryKV(seed: Record<string, string> = {}): SyncKV & { map: Map<string, string> } {
  const map = new Map(Object.entries(seed));
  return {
    map,
    get: (k) => (map.has(k) ? (map.get(k) as string) : null),
    set: (k, v) => void map.set(k, v),
    del: (k) => void map.delete(k),
    keys: () => [...map.keys()]
  };
}

let fallback: SyncKV | null = null;

/** localStorage-backed store; falls back to an in-memory map when unavailable. */
export function localKV(): SyncKV {
  try {
    if (typeof localStorage !== 'undefined') {
      const ls = localStorage;
      return {
        get: (k) => {
          try {
            return ls.getItem(k);
          } catch {
            return null;
          }
        },
        set: (k, v) => {
          try {
            ls.setItem(k, v);
          } catch {
            /* quota / private mode */
          }
        },
        del: (k) => {
          try {
            ls.removeItem(k);
          } catch {
            /* ignore */
          }
        },
        keys: () => {
          const out: string[] = [];
          try {
            for (let i = 0; i < ls.length; i++) {
              const k = ls.key(i);
              if (k !== null) out.push(k);
            }
          } catch {
            /* ignore */
          }
          return out;
        }
      };
    }
  } catch {
    /* SecurityError */
  }
  return (fallback ??= memoryKV());
}

export function readJSON<T>(kv: SyncKV, key: string, dflt: T): T {
  const raw = kv.get(key);
  if (raw === null) return dflt;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return dflt;
  }
}

export const writeJSON = (kv: SyncKV, key: string, value: unknown): void => kv.set(key, JSON.stringify(value));
