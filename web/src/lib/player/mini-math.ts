// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
// scrape or hack anything without Mythbrix's explicit permission.
// Floating mini player geometry (pure): free-position viewport clamping + normalized persistence.
export type Corner = 'tl' | 'tr' | 'bl' | 'br';

export interface Box {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface Insets {
  top: number;
  right: number;
  bottom: number;
  left: number;
}

export interface MiniPosFrac {
  xFrac: number;
  yFrac: number;
}

/** Top-left position of the mini window in `corner`. */
export function cornerPos(corner: Corner, w: number, h: number, vw: number, vh: number, m: Insets): { x: number; y: number } {
  return {
    x: corner.endsWith('l') ? m.left : vw - w - m.right,
    y: corner.startsWith('t') ? m.top : vh - h - m.bottom
  };
}

/** Nearest corner to the window's center (velocity nudges the choice, like a fling). */
export function snapCorner(b: Box, vw: number, vh: number, vx = 0, vy = 0): Corner {
  const cx = b.x + b.w / 2 + vx * 120;
  const cy = b.y + b.h / 2 + vy * 120;
  return `${cy < vh / 2 ? 't' : 'b'}${cx < vw / 2 ? 'l' : 'r'}` as Corner;
}

/** Free-drag clamp: keeps exact dropped `(x, y)` inside safe viewport margins without corner-snapping. */
export function clampMiniOffset(
  x: number,
  y: number,
  w: number,
  h: number,
  vw: number,
  vh: number,
  m: Insets
): { x: number; y: number } {
  const minX = m.left;
  const maxX = Math.max(minX, vw - w - m.right);
  const minY = m.top;
  const maxY = Math.max(minY, vh - h - m.bottom);
  const safeX = Number.isFinite(x) ? Math.min(maxX, Math.max(minX, x)) : maxX;
  const safeY = Number.isFinite(y) ? Math.min(maxY, Math.max(minY, y)) : maxY;
  return { x: Math.round(safeX), y: Math.round(safeY) };
}

/** Converts clamped `(x, y)` into normalized `[0..1]` travel fractions so window resize keeps the user's spot. */
export function toMiniPosFrac(
  x: number,
  y: number,
  w: number,
  h: number,
  vw: number,
  vh: number,
  m: Insets
): MiniPosFrac {
  const minX = m.left;
  const maxX = Math.max(minX, vw - w - m.right);
  const minY = m.top;
  const maxY = Math.max(minY, vh - h - m.bottom);
  const spanX = maxX - minX;
  const spanY = maxY - minY;
  const xFrac = spanX <= 0.5 ? 1 : Math.min(1, Math.max(0, (x - minX) / spanX));
  const yFrac = spanY <= 0.5 ? 1 : Math.min(1, Math.max(0, (y - minY) / spanY));
  return { xFrac, yFrac };
}

/** Reconstructs the clamped `(x, y)` offset from normalized `(xFrac, yFrac)` (defaults to bottom-right when unset). */
export function fromMiniPosFrac(
  pos: MiniPosFrac | null | undefined,
  w: number,
  h: number,
  vw: number,
  vh: number,
  m: Insets
): { x: number; y: number } {
  if (!pos || !Number.isFinite(pos.xFrac) || !Number.isFinite(pos.yFrac)) {
    return clampMiniOffset(vw - w - m.right, vh - h - m.bottom, w, h, vw, vh, m);
  }
  const minX = m.left;
  const maxX = Math.max(minX, vw - w - m.right);
  const minY = m.top;
  const maxY = Math.max(minY, vh - h - m.bottom);
  const xf = Math.min(1, Math.max(0, pos.xFrac));
  const yf = Math.min(1, Math.max(0, pos.yFrac));
  return clampMiniOffset(minX + (maxX - minX) * xf, minY + (maxY - minY) * yf, w, h, vw, vh, m);
}

/** Safely parses a stored `MINI_POS_KEY` JSON string. */
export function parseMiniPosFrac(raw: string | null | undefined): MiniPosFrac | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<MiniPosFrac> | null;
    if (
      parsed &&
      typeof parsed.xFrac === 'number' &&
      typeof parsed.yFrac === 'number' &&
      Number.isFinite(parsed.xFrac) &&
      Number.isFinite(parsed.yFrac)
    ) {
      return {
        xFrac: Math.min(1, Math.max(0, parsed.xFrac)),
        yFrac: Math.min(1, Math.max(0, parsed.yFrac))
      };
    }
  } catch {
    /* ignore malformed stored value */
  }
  return null;
}

/** Swiped off-screen: more than 55 % outside horizontally, or a fast fling past an edge. */
export function isSwipeOff(b: Box, vw: number, vx = 0): boolean {
  const outL = Math.max(0, -b.x);
  const outR = Math.max(0, b.x + b.w - vw);
  if (Math.max(outL, outR) > b.w * 0.55) return true;
  return Math.abs(vx) > 1.4 && (outL > b.w * 0.15 || outR > b.w * 0.15);
}

// ---- resize (corner handle) ----

export const MINI_MIN_FRAC = 0.25;
export const MINI_MAX_FRAC = 0.8;
export const MINI_MIN_PX = 160;
/** Persisted mini width as a viewport fraction (survives rotation / window resizing). */
export const MINI_WIDTH_KEY = 'cinetor.miniWidth';
/** Persisted normalized free-drag mini position (`{ xFrac, yFrac }`). */
export const MINI_POS_KEY = 'cinetor.miniPos';
export const MINI_DEFAULT_FRAC = 0.42;

/** Mini width in px, clamped to 25–80 % of the viewport (never below 160 px). */
export function clampMiniWidth(px: number, vw: number): number {
  const min = Math.min(Math.max(MINI_MIN_PX, vw * MINI_MIN_FRAC), vw);
  const max = Math.max(min, vw * MINI_MAX_FRAC);
  return Math.round(Math.min(max, Math.max(min, px)));
}

/** Viewport fraction for a px width (clamped the same way). */
export function miniFrac(px: number, vw: number): number {
  if (vw <= 0) return MINI_DEFAULT_FRAC;
  return Math.min(MINI_MAX_FRAC, Math.max(MINI_MIN_FRAC, clampMiniWidth(px, vw) / vw));
}

/** 16:9 height for a mini width. */
export const miniHeight = (w: number): number => Math.round((w * 9) / 16);

/** New width while dragging the corner handle (right-anchored windows grow leftwards). */
export function resizeWidth(startW: number, dx: number, corner: Corner, vw: number): number {
  return clampMiniWidth(startW + (corner.endsWith('r') ? -dx : dx), vw);
}
