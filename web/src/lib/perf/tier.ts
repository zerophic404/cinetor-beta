export type Tier = 'LOW' | 'MID' | 'HIGH';
export type TierOverride = 'AUTO' | Tier;

export interface DeviceSignals {
  /** navigator.deviceMemory (GB, Chromium only, capped at 8). */
  deviceMemory?: number;
  /** navigator.hardwareConcurrency. */
  cores?: number;
  reducedMotion?: boolean;
  /** Average frame time from the boot rAF probe (ms). */
  avgFrameMs?: number;
}

export interface TierFlags {
  tier: Tier;
  /** Live backdrop-filter blur anywhere at all. */
  liveBlur: boolean;
  /** Where live blur is allowed: none (LOW) · bars/sheets only (MID) · everywhere (HIGH). */
  blurScope: 'none' | 'bars' | 'all';
  parallax: boolean;
  kenBurns: boolean;
  /** Multiplier for all animation durations. */
  durationScale: number;
  springOvershoot: boolean;
  crossfadeMs: number;
  /** Background color morph stops (2 / 3 / animated mesh). */
  colorMorph: '2-stop' | '3-stop' | 'mesh';
}

const ORDER: Tier[] = ['LOW', 'MID', 'HIGH'];

/** Rules from the plan's Design system → Performance tiers (web variant). */
export function baseTier(s: DeviceSignals): Tier {
  const mem = s.deviceMemory;
  const cores = s.cores;
  if ((mem !== undefined && mem < 3) || (cores !== undefined && cores <= 4)) return 'LOW';
  if (mem !== undefined && mem >= 6 && (cores === undefined || cores >= 6)) return 'HIGH';
  // Unknown memory (Safari/Firefox): many cores still earns HIGH.
  if (mem === undefined && cores !== undefined && cores >= 8) return 'HIGH';
  return 'MID';
}

export function flagsFor(tier: Tier, reducedMotion = false): TierFlags {
  const f: TierFlags =
    tier === 'LOW'
      ? { tier, liveBlur: false, blurScope: 'none', parallax: false, kenBurns: false, durationScale: 0.6, springOvershoot: false, crossfadeMs: 0, colorMorph: '2-stop' }
      : tier === 'MID'
        ? { tier, liveBlur: true, blurScope: 'bars', parallax: true, kenBurns: false, durationScale: 1, springOvershoot: true, crossfadeMs: 150, colorMorph: '3-stop' }
        : { tier, liveBlur: true, blurScope: 'all', parallax: true, kenBurns: true, durationScale: 1, springOvershoot: true, crossfadeMs: 250, colorMorph: 'mesh' };
  if (reducedMotion) {
    f.parallax = false;
    f.kenBurns = false;
    f.springOvershoot = false;
    f.durationScale = Math.min(f.durationScale, 0.6);
  }
  return f;
}

/**
 * Pure tier detection. A user override (Low/Medium/High) always wins; otherwise device
 * signals decide and a slow frame probe (> 20 ms avg) downgrades one tier.
 */
export function detectTier(signals: DeviceSignals, override: TierOverride = 'AUTO'): TierFlags {
  if (override !== 'AUTO') return flagsFor(override, !!signals.reducedMotion);
  let tier = baseTier(signals);
  if (signals.avgFrameMs !== undefined && signals.avgFrameMs > 20) {
    tier = ORDER[Math.max(0, ORDER.indexOf(tier) - 1)];
  }
  return flagsFor(tier, !!signals.reducedMotion);
}

/** Browser-only: read device signals. */
export function readSignals(): DeviceSignals {
  if (typeof navigator === 'undefined') return {};
  const nav = navigator as Navigator & { deviceMemory?: number };
  return {
    deviceMemory: typeof nav.deviceMemory === 'number' ? nav.deviceMemory : undefined,
    cores: nav.hardwareConcurrency || undefined,
    reducedMotion: typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
  };
}

/** Browser-only: ~1 s requestAnimationFrame probe → average frame time in ms. */
export function probeFrameTime(durationMs = 1000): Promise<number> {
  return new Promise((resolve) => {
    if (typeof requestAnimationFrame !== 'function') return resolve(16.7);
    let frames = 0;
    let start = 0;
    const tick = (t: number) => {
      if (!start) start = t;
      else frames++;
      if (t - start >= durationMs || document.hidden) resolve(frames ? (t - start) / frames : 16.7);
      else requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });
}

/** Browser-only: reflect flags on <html> for CSS ([data-tier], --dur-scale). */
export function applyTier(flags: TierFlags, root: HTMLElement = document.documentElement): void {
  root.dataset.tier = flags.tier.toLowerCase();
  root.dataset.blur = flags.blurScope;
  root.style.setProperty('--dur-scale', String(flags.durationScale));
  root.classList.toggle('no-kenburns', !flags.kenBurns);
  root.classList.toggle('no-parallax', !flags.parallax);
}
