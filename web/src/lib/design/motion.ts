import { motion as tokens } from './tokens';

export type DurationName = keyof typeof tokens.durationsMs;

let scale = 1;

export function setDurationScale(s: number): void {
  scale = s;
}

export function durationScale(): number {
  return scale;
}

/** Token duration scaled by the active performance tier. */
export function dur(name: DurationName): number {
  return Math.round(tokens.durationsMs[name] * scale);
}

export const easing = {
  emphasized: `cubic-bezier(${tokens.easing.emphasized.join(',')})`,
  standard: `cubic-bezier(${tokens.easing.standard.join(',')})`
};

/** Svelte transition: fade + small rise, tier-scaled. */
export function rise(_node: Element, { delay = 0, y = 8, duration = dur('long') } = {}) {
  return {
    delay,
    duration,
    css: (t: number) => `opacity:${t};transform:translateY(${(1 - t) * y}px)`
  };
}

export const HERO_AUTO_ADVANCE_MS = tokens.heroAutoAdvanceMs;
export const TINT_ANIM_MS = tokens.tintAnimMs;
