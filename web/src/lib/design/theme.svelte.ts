import { defaultTheme, themes, type ThemeName, type ThemeTokens } from './tokens';

export const THEME_KEY = 'cinetor.theme';

export type KnownThemeKey = 'cinema' | 'midnight' | 'emerald' | 'sunset' | 'royal' | 'daylight';

export interface ThemeDescriptor {
  key: KnownThemeKey;
  label: string;
  note: string;
  badge?: string;
}

export const THEME_CATALOG: readonly ThemeDescriptor[] = [
  { key: 'cinema', label: 'Cinema', note: 'Pure black, white and Cinema Red: the signature look', badge: 'Default' },
  { key: 'midnight', label: 'Midnight', note: 'Deep navy glass with electric blue light' },
  { key: 'emerald', label: 'Emerald', note: 'Dark forest canvas with fresh mint highlights' },
  { key: 'sunset', label: 'Sunset', note: 'Warm plum dusk with coral and rose light' },
  { key: 'royal', label: 'Royal', note: 'Deep violet velvet with gold trim' },
  { key: 'daylight', label: 'Daylight', note: 'Bright warm paper, ink text and red accents', badge: 'Light' }
];

/** Saved names from earlier versions map onto the closest of the six current themes. */
const LEGACY_KEYS: Readonly<Record<string, KnownThemeKey>> = {
  night: 'cinema',
  crimson: 'cinema',
  graphite: 'cinema',
  day: 'daylight',
  glass: 'cinema',
  matte: 'cinema',
  material: 'cinema',
  noir: 'cinema',
  obsidian: 'midnight',
  aura: 'sunset',
  ember: 'sunset',
  pearl: 'daylight'
};

export function isThemeName(value: string | null | undefined): value is ThemeName {
  return typeof value === 'string' && Object.prototype.hasOwnProperty.call(themes, value);
}

const PREFERRED_ORDER: readonly string[] = THEME_CATALOG.map((item) => item.key);

export const THEME_NAMES: ThemeName[] = [
  ...PREFERRED_ORDER.filter(isThemeName),
  ...(Object.keys(themes) as ThemeName[]).filter((k) => !PREFERRED_ORDER.includes(k))
];

export function themeDescriptor(name: ThemeName): ThemeDescriptor {
  const found = THEME_CATALOG.find((item) => item.key === (name as string));
  if (found) return found;
  return {
    key: 'cinema',
    label: themes[name]?.name ?? String(name),
    note: 'Cinema theme'
  };
}

export function isLightTheme(name: ThemeName): boolean {
  const tok = themes[name] as (ThemeTokens & { isLight?: boolean }) | undefined;
  if (tok && typeof tok.isLight === 'boolean') return tok.isLight;
  return (name as string) === 'daylight';
}

function fallbackTheme(): ThemeName {
  if (isThemeName('cinema')) return 'cinema';
  return defaultTheme;
}

export function resolveThemeName(raw: string | null | undefined): ThemeName {
  if (!raw) return fallbackTheme();
  const mapped = LEGACY_KEYS[raw] ?? raw;
  return isThemeName(mapped) ? mapped : fallbackTheme();
}

function readPersistedTheme(): string | null {
  try {
    if (typeof localStorage !== 'undefined') {
      return localStorage.getItem(THEME_KEY);
    }
  } catch {
    /* private / restricted storage */
  }
  return null;
}

function initial(): ThemeName {
  if (typeof document === 'undefined') return fallbackTheme();
  const saved = readPersistedTheme();
  if (saved) return resolveThemeName(saved);
  return fallbackTheme();
}

class ThemeStore {
  current = $state<ThemeName>(initial());

  constructor() {
    if (typeof document !== 'undefined') {
      this.applyDom(this.current);
    }
  }

  get tokens(): ThemeTokens {
    return themes[this.current] ?? themes[fallbackTheme()];
  }

  get isLight(): boolean {
    return isLightTheme(this.current);
  }

  private applyDom(name: ThemeName): void {
    if (typeof document === 'undefined') return;
    const tok = themes[name] ?? themes[fallbackTheme()];
    const light = isLightTheme(name);
    const root = document.documentElement;
    root.dataset.theme = name;
    root.style.colorScheme = light ? 'light' : 'dark';

    let metaTheme = document.querySelector('meta[name="theme-color"]');
    if (!metaTheme && document.head) {
      metaTheme = document.createElement('meta');
      metaTheme.setAttribute('name', 'theme-color');
      document.head.appendChild(metaTheme);
    }
    metaTheme?.setAttribute('content', tok.colors.bg);

    const metaScheme = document.querySelector('meta[name="color-scheme"]');
    metaScheme?.setAttribute('content', light ? 'light' : 'dark');
  }

  set(name: ThemeName): void {
    const resolved = resolveThemeName(name);
    this.current = resolved;
    this.applyDom(resolved);
    try {
      localStorage.setItem(THEME_KEY, resolved);
    } catch {
      /* private mode */
    }
  }
}

export const theme = new ThemeStore();
