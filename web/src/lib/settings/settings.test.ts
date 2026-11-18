import { describe, expect, it } from 'vitest';
import { memoryKV } from '../data/kv';
import {
  DEFAULT_SETTINGS,
  DOUBLE_TAP_OPTIONS,
  SETTINGS_KEY,
  loadSettings,
  resetSettings,
  sanitize,
  saveSettings
} from './settings';

describe('settings', () => {
  it('exposes Cinetor Beta skip options and transparent default subtitle background', () => {
    expect(DOUBLE_TAP_OPTIONS).toEqual([5, 10, 15, 30]);
    expect(DEFAULT_SETTINGS.doubleTapSec).toBe(10);
    expect(DEFAULT_SETTINGS.holdForSpeed).toBe(true);
    expect(DEFAULT_SETTINGS.holdSpeed).toBe(2);
    expect(DEFAULT_SETTINGS.autoPip).toBe(true);
    expect(DEFAULT_SETTINGS.wifiOnly).toBe(false);
    expect(DEFAULT_SETTINGS.bgOpacity).toBe(0);
  });

  it('defaults when nothing stored or garbage stored', () => {
    expect(loadSettings(memoryKV())).toEqual(DEFAULT_SETTINGS);
    expect(loadSettings(memoryKV({ [SETTINGS_KEY]: '{bad json' }))).toEqual(DEFAULT_SETTINGS);
  });

  it('persists valid values, drops invalid ones, and resets cleanly', () => {
    const kv = memoryKV();
    saveSettings(kv, {
      ...DEFAULT_SETTINGS,
      doubleTapSec: 15,
      holdForSpeed: false,
      holdSpeed: 1,
      autoPip: false,
      wifiOnly: true
    });
    expect(loadSettings(kv)).toMatchObject({
      doubleTapSec: 15,
      holdForSpeed: false,
      holdSpeed: 1,
      autoPip: false,
      wifiOnly: true
    });
    expect(sanitize({ doubleTapSec: 7, holdSpeed: 9, subLang: 'english!!', bgOpacity: 5 })).toMatchObject({
      doubleTapSec: 10,
      holdSpeed: 2,
      subLang: 'en',
      bgOpacity: 0
    });

    expect(resetSettings(kv)).toEqual(DEFAULT_SETTINGS);
    expect(loadSettings(kv)).toEqual(DEFAULT_SETTINGS);
  });
});
