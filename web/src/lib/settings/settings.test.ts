import { describe, expect, it } from 'vitest';
import { memoryKV } from '../data/kv';
import { DEFAULT_SETTINGS, SETTINGS_KEY, loadSettings, sanitize, saveSettings } from './settings';

describe('settings', () => {
  it('defaults when nothing stored / garbage stored', () => {
    expect(loadSettings(memoryKV())).toEqual(DEFAULT_SETTINGS);
    expect(loadSettings(memoryKV({ [SETTINGS_KEY]: '{bad json' }))).toEqual(DEFAULT_SETTINGS);
  });
  it('persists valid values and drops invalid ones', () => {
    const kv = memoryKV();
    saveSettings(kv, { ...DEFAULT_SETTINGS, doubleTapSec: 20, holdSpeed: 3, autoplayNext: false });
    expect(loadSettings(kv)).toMatchObject({ doubleTapSec: 20, holdSpeed: 3, autoplayNext: false });
    expect(sanitize({ doubleTapSec: 7, holdSpeed: 9, subLang: 'english!!' })).toMatchObject({ doubleTapSec: 10, holdSpeed: 2, subLang: 'en' });
  });
});
