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

  it('theme tintStrength blends item color toward accent (red identity holds)', () => {
    const noir = themes.noir;
    const out = parseHex(blendTint('#0000FF', noir.colors.accent, noir.tintStrength));
    // 18% blue, 82% red.
    expect(out.r).toBe(Math.round(229 * 0.82));
    expect(out.b).toBe(Math.round(20 + (255 - 20) * 0.18));
    expect(out.r).toBeGreaterThan(out.b);
    // Obsidian keeps more of the item color than Noir.
    const obs = parseHex(blendTint('#0000FF', themes.obsidian.colors.accent, themes.obsidian.tintStrength));
    expect(obs.b).toBeGreaterThan(out.b);
  });
});
