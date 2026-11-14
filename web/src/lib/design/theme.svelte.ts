import { defaultTheme, themes, type ThemeName } from './tokens';

export const THEME_KEY = 'cinetor.theme';
export const THEME_NAMES = Object.keys(themes) as ThemeName[];

function initial(): ThemeName {
  if (typeof document === 'undefined') return defaultTheme;
  const t = document.documentElement.dataset.theme as ThemeName | undefined;
  return t && t in themes ? t : defaultTheme;
}

class ThemeStore {
  current = $state<ThemeName>(initial());

  get tokens() {
    return themes[this.current];
  }

  set(name: ThemeName): void {
    if (!(name in themes)) return;
    this.current = name;
    if (typeof document !== 'undefined') {
      document.documentElement.dataset.theme = name;
      const meta = document.querySelector('meta[name="theme-color"]');
      meta?.setAttribute('content', themes[name].colors.bg);
    }
    try {
      localStorage.setItem(THEME_KEY, name);
    } catch {
      /* private mode */
    }
  }
}

export const theme = new ThemeStore();
