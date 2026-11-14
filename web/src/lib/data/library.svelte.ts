// Local profile + My List (per profile; isolated spaces keep their own list, never the global one).
import { isVideoSpace, type Item, type Space } from './types';

export const PROFILE_KEY = 'cinetor.profile';

export function activeProfileId(): string {
  try {
    return (typeof localStorage !== 'undefined' && localStorage.getItem(PROFILE_KEY)) || 'default';
  } catch {
    return 'default';
  }
}

const scopeOf = (s: Space) => (isVideoSpace(s) ? 'video' : s.toLowerCase());
const key = (profile: string, scope: string) => `cinetor.mylist.${profile}.${scope}`;

function read(k: string): string[] {
  try {
    const v = JSON.parse(localStorage.getItem(k) ?? '[]');
    return Array.isArray(v) ? v.filter((x) => typeof x === 'string') : [];
  } catch {
    return [];
  }
}

class MyList {
  private lists = $state<Record<string, string[]>>({});
  private profile = activeProfileId();

  private ensure(scope: string): string[] {
    return this.lists[scope] ?? read(key(this.profile, scope));
  }

  has(item: Pick<Item, 'id' | 'space'>): boolean {
    const scope = scopeOf(item.space);
    return (this.lists[scope] ?? read(key(this.profile, scope))).includes(item.id);
  }

  toggle(item: Pick<Item, 'id' | 'space'>): boolean {
    const scope = scopeOf(item.space);
    const cur = this.ensure(scope);
    const on = !cur.includes(item.id);
    const next = on ? [item.id, ...cur].slice(0, 500) : cur.filter((x) => x !== item.id);
    this.lists[scope] = next;
    try {
      localStorage.setItem(key(this.profile, scope), JSON.stringify(next));
    } catch {
      /* ignore */
    }
    return on;
  }
}

export const myList = new MyList();
