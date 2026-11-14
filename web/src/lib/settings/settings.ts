// App settings (device-wide, localStorage). Pure load/save + validation; the store applies them.
import { readJSON, writeJSON, type SyncKV } from '../data/kv';

export interface Settings {
  autoplayNext: boolean;
  /** Double-tap seek step (seconds). */
  doubleTapSec: number;
  /** Long-press playback speed. */
  holdSpeed: number;
  gestures: boolean;
  reduceMotion: boolean;
  /** Warm the player on the details page. */
  preloadDetails: boolean;
  /** Turn subtitles on automatically when the preferred language exists. */
  subsAuto: boolean;
  subLang: string;
}

export const SETTINGS_KEY = 'cinetor.settings';
export const DOUBLE_TAP_OPTIONS = [5, 10, 15, 20, 30];
export const HOLD_SPEED_OPTIONS = [1.5, 2, 2.5, 3];
export const SUB_LANGS: { code: string; label: string }[] = [
  { code: 'en', label: 'English' },
  { code: 'bn', label: 'বাংলা (Bangla)' },
  { code: 'es', label: 'Español' },
  { code: 'fr', label: 'Français' },
  { code: 'ja', label: '日本語' },
  { code: 'ko', label: '한국어' }
];

export const DEFAULT_SETTINGS: Settings = {
  autoplayNext: true,
  doubleTapSec: 10,
  holdSpeed: 2,
  gestures: true,
  reduceMotion: false,
  preloadDetails: true,
  subsAuto: false,
  subLang: 'en'
};

/** Merge stored values over defaults, dropping anything invalid. */
export function sanitize(raw: unknown): Settings {
  const s = { ...DEFAULT_SETTINGS };
  if (!raw || typeof raw !== 'object') return s;
  const r = raw as Record<string, unknown>;
  for (const k of ['autoplayNext', 'gestures', 'reduceMotion', 'preloadDetails', 'subsAuto'] as const) if (typeof r[k] === 'boolean') s[k] = r[k] as boolean;
  if (typeof r.doubleTapSec === 'number' && DOUBLE_TAP_OPTIONS.includes(r.doubleTapSec)) s.doubleTapSec = r.doubleTapSec;
  if (typeof r.holdSpeed === 'number' && HOLD_SPEED_OPTIONS.includes(r.holdSpeed)) s.holdSpeed = r.holdSpeed;
  if (typeof r.subLang === 'string' && /^[a-z]{2,3}$/.test(r.subLang)) s.subLang = r.subLang;
  return s;
}

export const loadSettings = (kv: SyncKV): Settings => sanitize(readJSON<unknown>(kv, SETTINGS_KEY, null));

export function saveSettings(kv: SyncKV, s: Settings): Settings {
  const clean = sanitize(s);
  writeJSON(kv, SETTINGS_KEY, clean);
  return clean;
}
