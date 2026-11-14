// Search Web Worker: keeps the local prefix index off the main thread.
import { PrefixIndex, type Doc } from './search-index';
import type { Space } from './types';

type In = { type: 'add'; docs: Doc[] } | { type: 'query'; id: number; q: string; spaces: Space[]; limit: number };

const index = new PrefixIndex();
const ctx = self as unknown as { onmessage: ((e: MessageEvent<In>) => void) | null; postMessage(m: unknown): void };

ctx.onmessage = (e) => {
  const m = e.data;
  if (m.type === 'add') index.add(m.docs);
  else if (m.type === 'query') ctx.postMessage({ id: m.id, ids: index.query(m.q, m.spaces, m.limit) });
};
