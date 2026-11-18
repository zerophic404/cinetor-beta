import { describe, expect, it } from 'vitest';
import { blendTint, mix, parseHex, toHex } from './tint';
import { themes } from './tokens';

describe('tint blending', () => {
  it('parses and formats hex', () => {
    expect(parseHex('#E50914')).toEqual({ r: 229, g: 9, b: 20 });
    expect(toHex({ r: 229, g: 9, b: 20 })).toBe('#E50914');
    expect(parseHex('#FF2D3B73')).toEqual({ r: 255, g: 45, b: 59 });
  });

  it('mix endpoints and midpoint', () => {
    expect(mix('#000000', '#FFFFFF', 0)).toBe('#000000');
    expect(mix('#000000', '#FFFFFF', 1)).toBe('#FFFFFF');
    expect(mix('#000000', '#FFFFFF', 0.5)).toBe('#808080');
  });

  it('strength 0 keeps accent, 1 keeps item color', () => {
    expect(blendTint('#0000FF', '#E50914', 0)).toBe('#E50914');
    expect(blendTint('#0000FF', '#E50914', 1)).toBe('#0000FF');
  });

  it('theme tintStrength blends the artwork colour toward the theme accent', () => {
    // Cinema keeps a share of the artwork colour but its red identity dominates.
    const cinema = themes.cinema;
    const k = cinema.tintStrength;
    expect(k).toBeGreaterThan(0);
    expect(k).toBeLessThan(0.5);
    const accent = parseHex(cinema.colors.accent);
    const out = parseHex(blendTint('#0000FF', cinema.colors.accent, k));
    expect(out.r).toBe(Math.round(accent.r * (1 - k)));
    expect(out.b).toBe(Math.round(accent.b + (255 - accent.b) * k));
    expect(out.r).toBeGreaterThan(out.b);
  });
});
