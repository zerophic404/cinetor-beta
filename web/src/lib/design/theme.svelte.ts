import { defaultTheme, themes, type ThemeName, type ThemeTokens } from './tokens';

export const THEME_KEY = 'cinetor.theme';

export type KnownThemeKey =
  | 'glass'
  | 'matte'
  | 'material'
  | 'aura'
  | 'pearl'
  | 'noir'
  | 'obsidian'
  | 'ember';

export interface ThemeDescriptor {
  key: KnownThemeKey;
  label: string;
  note: string;
  badge?: string;
}

export const THEME_CATALOG: readonly ThemeDescriptor[] = [
  {
    key: 'glass',
    label: 'Glass',
    note: 'Clear dark cinema glass with a soft specular glow',
    badge: 'Default'
  },
  {
    key: 'matte',
    label: 'Matte',
    note: 'Flat solid charcoal surfaces with zero glare'
  },
  {
    key: 'material',
    label: 'Material',
    note: 'Soft tonal layers, rounder corners and pill actions'
  },
  {
    key: 'aura',
    label: 'Aura',
    note: 'Adaptive canvas washed in artwork color with capsule nav',
    badge: 'Capsule'
  },
  {
    key: 'pearl',
    label: 'Pearl',
    note: 'Warm frosted daylight surfaces with crisp dark type',
    badge: 'Daylight'
  },
  {
    key: 'noir',
    label: 'Noir',
    note: 'Pure OLED black paired with classic cinema red'
  },
  {
    key: 'obsidian',
    label: 'Obsidian',
    note: 'Deep slate glass with rich crimson accents'
  },
  {
    key: 'ember',
    label: 'Ember',
    note: 'Warm curtain dark with glowing ember highlights'
  }
];

const LEGACY_KEYS: Readonly<Record<string, KnownThemeKey>> = {
  night: 'glass',
  crimson: 'glass',
  midnight: 'matte',
  graphite: 'material',
  day: 'pearl'
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
    key: 'glass',
    label: themes[name]?.name ?? String(name),
    note: 'Cinema theme'
  };
}

export function isLightTheme(name: ThemeName): boolean {
  const tok = themes[name] as (ThemeTokens & { isLight?: boolean }) | undefined;
  if (tok && typeof tok.isLight === 'boolean') return tok.isLight;
  return (name as string) === 'pearl';
}

function fallbackTheme(): ThemeName {
  if (isThemeName('glass')) return 'glass';
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
