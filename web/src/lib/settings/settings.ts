// App settings (device-wide, localStorage). Pure load/save + validation; the store applies them.
import { readJSON, writeJSON, type SyncKV } from '../data/kv';

export interface Settings {
  autoplayNext: boolean;
  /** Double-tap seek step (seconds): 5 · 10 · 15 · 30. */
  doubleTapSec: number;
  /** Long-press playback speed multiplier (2 when holdForSpeed is enabled, 1 when disabled). */
  holdSpeed: number;
  /** Press and hold video to play at 2× speed. */
  holdForSpeed: boolean;
  /** Floating Mini-Player / Picture-in-Picture when leaving the player. */
  autoPip: boolean;
  /** Restrict background/queued downloads to unmetered Wi-Fi connections. */
  wifiOnly: boolean;
  /** Default subtitle background opacity (0 = transparent). */
  bgOpacity: number;
  gestures: boolean;
  reduceMotion: boolean;
  /** Warm the player on the details page. */
  preloadDetails: boolean;
  /** Turn subtitles on automatically when the preferred language exists. */
  subsAuto: boolean;
  subLang: string;
}

export const SETTINGS_KEY = 'cinetor.settings';
export const DOUBLE_TAP_OPTIONS = [5, 10, 15, 30];
export const HOLD_SPEED_OPTIONS = [1, 1.5, 2, 2.5, 3];
export const SUB_LANGS: { code: string; label: string }[] = [
  { code: 'en', label: 'English' },
  { code: 'bn', label: 'Bangla' },
  { code: 'hi', label: 'Hindi' },
  { code: 'es', label: 'Spanish' },
  { code: 'fr', label: 'French' },
  { code: 'ja', label: 'Japanese' },
  { code: 'ko', label: 'Korean' }
];

export const DEFAULT_SETTINGS: Settings = {
  autoplayNext: true,
  doubleTapSec: 10,
  holdSpeed: 2,
  holdForSpeed: true,
  autoPip: true,
  wifiOnly: false,
  bgOpacity: 0,
  gestures: true,
  reduceMotion: false,
  preloadDetails: true,
  subsAuto: false,
  subLang: 'en'
};

/** Merge stored values over defaults, dropping anything invalid. */
export function sanitize(raw: unknown): Settings {
  const s: Settings = { ...DEFAULT_SETTINGS };
  if (!raw || typeof raw !== 'object') return s;
  const r = raw as Record<string, unknown>;
  for (const k of [
    'autoplayNext',
    'holdForSpeed',
    'autoPip',
    'wifiOnly',
    'gestures',
    'reduceMotion',
    'preloadDetails',
    'subsAuto'
  ] as const) {
    if (typeof r[k] === 'boolean') s[k] = r[k] as boolean;
  }
  if (typeof r.doubleTapSec === 'number' && DOUBLE_TAP_OPTIONS.includes(r.doubleTapSec)) {
    s.doubleTapSec = r.doubleTapSec;
  }
  if (typeof r.holdSpeed === 'number' && HOLD_SPEED_OPTIONS.includes(r.holdSpeed)) {
    s.holdSpeed = r.holdSpeed;
  } else if (typeof r.holdForSpeed === 'boolean') {
    s.holdSpeed = r.holdForSpeed ? 2 : 1;
  }
  if (typeof r.bgOpacity === 'number' && Number.isFinite(r.bgOpacity) && r.bgOpacity >= 0 && r.bgOpacity <= 1) {
    s.bgOpacity = r.bgOpacity;
  }
  if (typeof r.subLang === 'string' && /^[a-z]{2,3}$/.test(r.subLang)) {
    s.subLang = r.subLang;
  }
  return s;
}

export const loadSettings = (kv: SyncKV): Settings => sanitize(readJSON<unknown>(kv, SETTINGS_KEY, null));

export function saveSettings(kv: SyncKV, s: Settings): Settings {
  const clean = sanitize(s);
  writeJSON(kv, SETTINGS_KEY, clean);
  return clean;
}

export function resetSettings(kv: SyncKV): Settings {
  const clean = { ...DEFAULT_SETTINGS };
  writeJSON(kv, SETTINGS_KEY, clean);
  return clean;
}
