/** Dynamic UI tint: item color blended toward the theme accent so red/black identity holds. */

export interface RGB {
  r: number;
  g: number;
  b: number;
}

export function parseHex(hex: string): RGB {
  const h = hex.replace('#', '').slice(0, 6);
  if (!/^[0-9a-f]{6}$/i.test(h)) throw new Error(`Bad color ${hex}`);
  return { r: parseInt(h.slice(0, 2), 16), g: parseInt(h.slice(2, 4), 16), b: parseInt(h.slice(4, 6), 16) };
}

export function toHex({ r, g, b }: RGB): string {
  const c = (n: number) => Math.round(Math.min(255, Math.max(0, n))).toString(16).padStart(2, '0');
  return `#${c(r)}${c(g)}${c(b)}`.toUpperCase();
}

export function mix(a: string, b: string, t: number): string {
  const x = parseHex(a);
  const y = parseHex(b);
  const k = Math.min(1, Math.max(0, t));
  return toHex({ r: x.r + (y.r - x.r) * k, g: x.g + (y.g - x.g) * k, b: x.b + (y.b - x.b) * k });
}

/**
 * Blend the item color toward the accent. `tintStrength` is how much of the ITEM color
 * survives (Noir 0.18 → mostly accent red; Obsidian 0.30 → more item color).
 */
export function blendTint(itemColor: string, accent: string, tintStrength: number): string {
  return mix(accent, itemColor, tintStrength);
}
