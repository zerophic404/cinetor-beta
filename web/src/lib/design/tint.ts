/** Dynamic UI tint & WCAG contrast math: item color blended toward theme accent so brand identity holds. */

export interface RGB {
  r: number;
  g: number;
  b: number;
}

export function isValidHex(hex: string | null | undefined): hex is string {
  if (typeof hex !== 'string') return false;
  const h = hex.trim().replace(/^#/, '');
  return /^[0-9a-f]{6}([0-9a-f]{2})?$/i.test(h);
}

export function parseHex(hex: string): RGB {
  const h = hex.trim().replace('#', '').slice(0, 6);
  if (!/^[0-9a-f]{6}$/i.test(h)) throw new Error(`Bad color ${hex}`);
  return {
    r: parseInt(h.slice(0, 2), 16),
    g: parseInt(h.slice(2, 4), 16),
    b: parseInt(h.slice(4, 6), 16)
  };
}

export function toHex({ r, g, b }: RGB): string {
  const c = (n: number) => Math.round(Math.min(255, Math.max(0, n))).toString(16).padStart(2, '0');
  return `#${c(r)}${c(g)}${c(b)}`.toUpperCase();
}

export function mix(a: string, b: string, t: number): string {
  const x = parseHex(a);
  const y = parseHex(b);
  const k = Math.min(1, Math.max(0, t));
  return toHex({
    r: x.r + (y.r - x.r) * k,
    g: x.g + (y.g - x.g) * k,
    b: x.b + (y.b - x.b) * k
  });
}

/** WCAG 2.1 relative luminance in [0, 1]. */
export function relativeLuminance(hex: string): number {
  const { r, g, b } = parseHex(hex);
  const chan = (v: number) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * chan(r) + 0.7152 * chan(g) + 0.0722 * chan(b);
}

/** WCAG 2.1 contrast ratio in [1, 21]. */
export function contrastRatio(hexA: string, hexB: string): number {
  const l1 = relativeLuminance(hexA);
  const l2 = relativeLuminance(hexB);
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
}

/**
 * Blend the item color toward the accent. `tintStrength` is how much of the ITEM color
 * survives (Glass 0.28, Aura 0.42, Pearl 0.16, Material 0.0 → pure accent).
 */
export function blendTint(itemColor: string, accent: string, tintStrength: number): string {
  if (!isValidHex(itemColor)) return isValidHex(accent) ? toHex(parseHex(accent)) : '#E11D2E';
  if (!isValidHex(accent)) return toHex(parseHex(itemColor));
  return mix(accent, itemColor, tintStrength);
}
