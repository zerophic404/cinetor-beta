// Keyboard shortcuts → player actions (pure).
export type KeyAction =
  | { type: 'toggle' }
  | { type: 'seek'; by: number }
  | { type: 'volume'; by: number }
  | { type: 'fullscreen' }
  | { type: 'mute' }
  | { type: 'captions' }
  | { type: 'speed'; dir: -1 | 1 }
  | { type: 'next' }
  | { type: 'seekPct'; pct: number };

export interface KeyLike {
  key: string;
  ctrlKey?: boolean;
  metaKey?: boolean;
  altKey?: boolean;
}

export function keyAction(e: KeyLike): KeyAction | null {
  if (e.ctrlKey || e.metaKey || e.altKey) return null;
  switch (e.key) {
    case ' ':
    case 'k':
    case 'K':
      return { type: 'toggle' };
    case 'j':
    case 'J':
      return { type: 'seek', by: -10 };
    case 'l':
    case 'L':
      return { type: 'seek', by: 10 };
    case 'ArrowLeft':
      return { type: 'seek', by: -5 };
    case 'ArrowRight':
      return { type: 'seek', by: 5 };
    case 'ArrowUp':
      return { type: 'volume', by: 0.05 };
    case 'ArrowDown':
      return { type: 'volume', by: -0.05 };
    case 'f':
    case 'F':
      return { type: 'fullscreen' };
    case 'm':
    case 'M':
      return { type: 'mute' };
    case 'c':
    case 'C':
      return { type: 'captions' };
    case '<':
      return { type: 'speed', dir: -1 };
    case '>':
      return { type: 'speed', dir: 1 };
    case 'n':
    case 'N':
      return { type: 'next' };
  }
  if (/^[0-9]$/.test(e.key)) return { type: 'seekPct', pct: Number(e.key) * 10 };
  return null;
}

export const SPEEDS = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.25, 2.5, 2.75, 3];

export function stepSpeed(cur: number, dir: -1 | 1): number {
  const i = SPEEDS.findIndex((s) => s >= cur - 1e-6);
  const idx = i < 0 ? SPEEDS.length - 1 : SPEEDS[i] === cur ? i + dir : dir > 0 ? i : i - 1;
  return SPEEDS[Math.min(SPEEDS.length - 1, Math.max(0, idx))];
}
