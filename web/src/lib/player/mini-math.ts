// Floating mini player geometry (pure).
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

/** Swiped off-screen: more than 55 % outside horizontally, or a fast fling past an edge. */
export function isSwipeOff(b: Box, vw: number, vx = 0): boolean {
  const outL = Math.max(0, -b.x);
  const outR = Math.max(0, b.x + b.w - vw);
  if (Math.max(outL, outR) > b.w * 0.55) return true;
  return Math.abs(vx) > 1.4 && (outL > b.w * 0.15 || outR > b.w * 0.15);
}
