/**
 * Tiny IndexedDB key/value cache for parsed mock catalog files (no deps).
 * Browser-only and lazy: nothing touches `indexedDB` until the first get/set, so importing this
 * module during SSR/prerender is safe. Every failure degrades to "cache miss" / no-op.
 */
export interface PersistentCache {
  get(key: string): Promise<unknown | undefined>;
  set(key: string, value: unknown): Promise<void>;
}

const DB_NAME = 'cinetor';
const DB_VERSION = 1;
const STORE = 'catalog';

export function idbCache(): PersistentCache | null {
  if (typeof indexedDB === 'undefined') return null;
  let dbp: Promise<IDBDatabase> | null = null;

  const open = (): Promise<IDBDatabase> => {
    if (!dbp) {
      dbp = new Promise<IDBDatabase>((resolve, reject) => {
        const req = indexedDB.open(DB_NAME, DB_VERSION);
        req.onupgradeneeded = () => {
          if (!req.result.objectStoreNames.contains(STORE)) req.result.createObjectStore(STORE);
        };
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
        req.onblocked = () => reject(new Error('IndexedDB blocked'));
      });
    }
    return dbp;
  };

  function run<T>(mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
    return open().then(
      (db) =>
        new Promise<T>((resolve, reject) => {
          const tx = db.transaction(STORE, mode);
          const req = fn(tx.objectStore(STORE));
          tx.oncomplete = () => resolve(req.result);
          tx.onerror = () => reject(tx.error);
          tx.onabort = () => reject(tx.error);
        })
    );
  }

  return {
    get: (key) => run<unknown>('readonly', (s) => s.get(key)).catch(() => undefined),
    set: (key, value) =>
      run<IDBValidKey>('readwrite', (s) => s.put(value, key)).then(
        () => undefined,
        () => undefined
      )
  };
}
