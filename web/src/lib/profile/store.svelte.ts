// Reactive profiles singleton (localStorage). Switching profile reloads the app so every
// per-profile store (my list, resume, favourites, reader progress, theme) re-reads its data.
import { localKV } from '$lib/data/kv';
import { resume } from '$lib/player/controller';
import { history } from '$lib/data/search-client';
import { THEME_KEY } from '$lib/design/theme.svelte';
import { ProfileStore, type Profile } from './profiles';

const SCOPES = ['global', 'tv', 'music', 'comics', 'adult'];

class Profiles {
  private store = new ProfileStore(localKV());
  list = $state.raw<Profile[]>(this.store.list());
  activeId = $state(this.store.activeId());

  get active(): Profile {
    return this.list.find((p) => p.id === this.activeId) ?? this.list[0];
  }

  get canCreate(): boolean {
    return this.list.length < 5;
  }

  private refresh(): void {
    this.list = this.store.list();
    this.activeId = this.store.activeId();
  }

  create(input: { name: string; color?: string; kids?: boolean; pinHash?: string }): Profile {
    const p = this.store.create(input);
    this.refresh();
    return p;
  }

  update(id: string, patch: Partial<Omit<Profile, 'id' | 'createdAt'>>): void {
    this.store.update(id, patch);
    this.refresh();
  }

  /** Remove a profile + all its local data (localStorage keys, resume positions, search history). */
  async remove(id: string): Promise<void> {
    const wasActive = id === this.activeId;
    this.store.delete(id);
    await clearProfileHistory(id);
    this.refresh();
    if (wasActive) this.reload();
  }

  switchTo(id: string): void {
    if (id === this.activeId) return;
    this.store.setActive(id);
    this.refresh();
    this.reload();
  }

  /** Persist the theme on the active profile (applied before first paint via cinetor.theme). */
  rememberTheme(theme: string): void {
    try {
      this.store.update(this.activeId, { theme });
      this.list = this.store.list();
    } catch {
      /* ignore */
    }
  }

  private reload(): void {
    if (typeof location === 'undefined') return;
    const theme = this.active.theme;
    try {
      if (theme) localStorage.setItem(THEME_KEY, theme);
    } catch {
      /* ignore */
    }
    location.reload();
  }
}

/** Watch history (resume / continue watching) + search recents of one profile. */
export async function clearProfileHistory(id: string): Promise<void> {
  await resume.clearProfile(id).catch(() => 0);
  await Promise.all(SCOPES.map((s) => history.clear(id, s).catch(() => undefined)));
}

export const profiles = new Profiles();
