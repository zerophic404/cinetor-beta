import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { ABOUT, APP_VERSION, aboutLinks } from './about';

describe('about copy + links', () => {
  it('has the exact credits and rights reserved copy', () => {
    expect(ABOUT.name).toBe('Cinetor');
    expect(ABOUT.developedBy).toBe('Developed By Mythbrix');
    expect(ABOUT.rightsReserved).toBe('All rights reserved Mythbrix');
    expect(ABOUT.thanksPrefix + ABOUT.thanks.label).toBe('Thanks to Ahmed Ishtiaq Zarir');
    // only the name is linked
    expect(ABOUT.thanks.label).toBe('Ahmed Ishtiaq Zarir');
    expect(ABOUT.thanks.href).toBe('https://ahmedishtiaquezarir.pro.bd/');
  });

  it('has the exact Telegram links', () => {
    expect(ABOUT.community).toEqual([
      { label: 'Telegram Channel', href: 'https://t.me/mythbrix_vault', target: '_blank', rel: 'noopener' },
      { label: 'Telegram Group', href: 'https://t.me/cinetor', target: '_blank', rel: 'noopener' }
    ]);
  });

  it('opens every link in a new tab with rel=noopener', () => {
    for (const l of aboutLinks()) {
      expect(l.target).toBe('_blank');
      expect(l.rel).toContain('noopener');
      expect(l.href).toMatch(/^https:\/\//);
    }
  });

  it('shows TMDB attribution, package version, and zero open-source license lists', () => {
    expect(ABOUT.tmdbNotice).toContain('TMDB');
    expect(ABOUT.tmdb.href).toBe('https://www.themoviedb.org/');
    const pkg = JSON.parse(readFileSync(new URL('../../package.json', import.meta.url), 'utf8')) as { version: string };
    expect(APP_VERSION).toBe(pkg.version);

    const page = readFileSync(new URL('../routes/app/about/+page.svelte', import.meta.url), 'utf8');
    expect(page).toContain('ABOUT.rightsReserved');
    expect(page.toLowerCase()).not.toContain('open-source license');
    expect(page.toLowerCase()).not.toContain('open source license');
  });
});
