// Browser persistence: download records (IndexedDB) + piece stores (OPFS, IndexedDB fallback).
import { idbKV, type KV } from '../player/resume';
import type { PieceStore } from './progressive';
import type { DownloadRecord } from './types';

export function recordsKV(): KV<DownloadRecord> | null {
  return typeof indexedDB === 'undefined' ? null : idbKV<DownloadRecord>('cinetor-dl', 'downloads');
}

const pieceName = (id: string, c: number, s: number) => `${encodeURIComponent(id).replace(/%/g, '_')}.${c}.${s}.part`;

type WritableHandle = FileSystemFileHandle & { createWritable?: () => Promise<{ write(d: Blob): Promise<void>; close(): Promise<void> }> };

/** OPFS when the browser supports writable file handles (Chromium, Firefox, Safari 18+). */
export async function opfsPieceStore(): Promise<PieceStore | null> {
  try {
    const sm = navigator.storage as StorageManager & { getDirectory?: () => Promise<FileSystemDirectoryHandle> };
    if (!sm?.getDirectory) return null;
    const root = await sm.getDirectory();
    const dir = await root.getDirectoryHandle('cinetor-downloads', { create: true });
    const probeH = (await dir.getFileHandle('.probe', { create: true })) as WritableHandle;
    if (typeof probeH.createWritable !== 'function') return null;
    return {
      kind: 'opfs',
      async put(id, c, s, data) {
        const h = (await dir.getFileHandle(pieceName(id, c, s), { create: true })) as WritableHandle;
        const w = await h.createWritable!();
        await w.write(data);
        await w.close();
      },
      async get(id, c, s) {
        try {
          const h = await dir.getFileHandle(pieceName(id, c, s));
          return await h.getFile();
        } catch {
          return undefined;
        }
      },
      async del(id, c, s) {
        await dir.removeEntry(pieceName(id, c, s)).catch(() => {});
      }
    };
  } catch {
    return null;
  }
}

export function idbPieceStore(): PieceStore {
  const kv = idbKV<Blob>('cinetor-dl-pieces', 'pieces');
  const k = (id: string, c: number, s: number) => `${id}|${c}|${s}`;
  return {
    kind: 'idb',
    put: (id, c, s, d) => kv.set(k(id, c, s), d),
    get: (id, c, s) => kv.get(k(id, c, s)),
    del: (id, c, s) => kv.del(k(id, c, s))
  };
}

export interface Quota {
  usage: number;
  quota: number;
  persisted: boolean;
}

export async function quota(): Promise<Quota> {
  const sm = typeof navigator !== 'undefined' ? navigator.storage : undefined;
  const est: StorageEstimate = (await sm?.estimate?.().catch(() => undefined)) ?? {};
  const persisted = (await sm?.persisted?.().catch(() => false)) ?? false;
  return { usage: est.usage ?? 0, quota: est.quota ?? 0, persisted };
}

/** Ask the browser not to evict downloads (granted automatically for installed PWAs on Chromium). */
export async function requestPersist(): Promise<boolean> {
  try {
    return (await navigator.storage?.persist?.()) ?? false;
  } catch {
    return false;
  }
}

export const isIOS = (ua: string, maxTouch = 0): boolean => /iPad|iPhone|iPod/.test(ua) || (/Macintosh/.test(ua) && maxTouch > 1);
