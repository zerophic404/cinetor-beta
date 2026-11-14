// Reactive settings singleton: persisted in localStorage, applied instantly (motion, player).
import { localKV } from '$lib/data/kv';
import { loadSettings, saveSettings, type Settings } from './settings';

class SettingsStore {
  s = $state<Settings>(loadSettings(localKV()));

  set<K extends keyof Settings>(key: K, value: Settings[K]): void {
    this.s = saveSettings(localKV(), { ...this.s, [key]: value });
    this.apply();
  }

  /** Apply document-level effects (reduce motion). */
  apply(): void {
    if (typeof document === 'undefined') return;
    if (this.s.reduceMotion) document.documentElement.dataset.motion = 'reduce';
    else delete document.documentElement.dataset.motion;
  }
}

export const settings = new SettingsStore();
