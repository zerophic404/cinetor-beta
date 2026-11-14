// Local profiles (max 5): name, avatar initials + colour, Kids flag, optional PIN hash, theme.
// Per-profile data lives under keys `cinetor.<kind>.<profileId>[.<rest>]` so deleting a profile
// can sweep everything it owned.
import { readJSON, writeJSON, type SyncKV } from '../data/kv';

export interface Profile {
  id: string;
  name: string;
  color: string;
  kids: boolean;
  /** SHA-256 hex of the optional profile PIN. */
  pinHash?: string;
  theme?: string;
  createdAt: number;
}

export const MAX_PROFILES = 5;
export const PROFILES_KEY = 'cinetor.profiles';
export const ACTIVE_KEY = 'cinetor.profile';
export const DEFAULT_ID = 'default';
export const AVATAR_COLORS = ['#E50914', '#F5A524', '#17C964', '#0EA5E9', '#A855F7', '#EC4899', '#64748B'];

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return '?';
  const s = parts.length === 1 ? parts[0].slice(0, 2) : parts[0][0] + parts[parts.length - 1][0];
  return s.toUpperCase();
}

/** Does `key` belong to profile `id`? (`cinetor.<kind>.<id>` or `cinetor.<kind>.<id>.<rest>`) */
export function ownedBy(key: string, id: string): boolean {
  const p = key.split('.');
  return p.length >= 3 && p[0] === 'cinetor' && p[2] === id && p[1] !== 'profiles';
}

export class ProfileError extends Error {}

export class ProfileStore {
  constructor(
    private kv: SyncKV,
    private now: () => number = Date.now,
    private genId: () => string = () => `p${Date.now().toString(36)}${Math.floor(Math.random() * 1e4).toString(36)}`
  ) {}

  list(): Profile[] {
    const raw = readJSON<Profile[]>(this.kv, PROFILES_KEY, []);
    const list = Array.isArray(raw) ? raw.filter((p) => p && typeof p.id === 'string' && !p.id.includes('.')) : [];
    if (!list.length) {
      const first: Profile = { id: DEFAULT_ID, name: 'Me', color: AVATAR_COLORS[0], kids: false, createdAt: this.now() };
      writeJSON(this.kv, PROFILES_KEY, [first]);
      return [first];
    }
    return list;
  }

  private save(list: Profile[]): void {
    writeJSON(this.kv, PROFILES_KEY, list);
  }

  get(id: string): Profile | undefined {
    return this.list().find((p) => p.id === id);
  }

  activeId(): string {
    const id = this.kv.get(ACTIVE_KEY);
    const list = this.list();
    return id && list.some((p) => p.id === id) ? id : list[0].id;
  }

  active(): Profile {
    return this.get(this.activeId()) ?? this.list()[0];
  }

  setActive(id: string): void {
    if (!this.get(id)) throw new ProfileError('No such profile');
    this.kv.set(ACTIVE_KEY, id);
  }

  canCreate(): boolean {
    return this.list().length < MAX_PROFILES;
  }

  create(input: { name: string; color?: string; kids?: boolean; pinHash?: string }): Profile {
    const list = this.list();
    if (list.length >= MAX_PROFILES) throw new ProfileError(`Up to ${MAX_PROFILES} profiles`);
    const name = input.name.trim().slice(0, 24);
    if (!name) throw new ProfileError('Name required');
    let id = this.genId().replace(/\./g, '');
    while (list.some((p) => p.id === id)) id += 'x';
    const p: Profile = { id, name, color: input.color ?? AVATAR_COLORS[list.length % AVATAR_COLORS.length], kids: !!input.kids, pinHash: input.pinHash, createdAt: this.now() };
    this.save([...list, p]);
    return p;
  }

  update(id: string, patch: Partial<Omit<Profile, 'id' | 'createdAt'>>): Profile {
    const list = this.list();
    const i = list.findIndex((p) => p.id === id);
    if (i < 0) throw new ProfileError('No such profile');
    const next = { ...list[i], ...patch, id, name: (patch.name ?? list[i].name).trim().slice(0, 24) || list[i].name };
    list[i] = next;
    this.save(list);
    return next;
  }

  /** Delete a profile and every key it owned. The last profile cannot be deleted. Returns removed keys. */
  delete(id: string): string[] {
    const list = this.list();
    if (!list.some((p) => p.id === id)) return [];
    if (list.length <= 1) throw new ProfileError('Keep at least one profile');
    const rest = list.filter((p) => p.id !== id);
    this.save(rest);
    const removed = this.kv.keys().filter((k) => ownedBy(k, id));
    for (const k of removed) this.kv.del(k);
    if (this.kv.get(ACTIVE_KEY) === id) this.kv.set(ACTIVE_KEY, rest[0].id);
    return removed;
  }
}
