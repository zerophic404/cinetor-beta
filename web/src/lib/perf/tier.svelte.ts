import { setDurationScale } from '$lib/design/motion';
import { applyTier, detectTier, probeFrameTime, readSignals, type DeviceSignals, type TierFlags, type TierOverride } from './tier';

export const TIER_KEY = 'cinetor.tier';

function storedOverride(): TierOverride {
  try {
    const v = localStorage.getItem(TIER_KEY) as TierOverride | null;
    if (v === 'LOW' || v === 'MID' || v === 'HIGH') return v;
  } catch {
    /* ignore */
  }
  return 'AUTO';
}

class PerfStore {
  signals: DeviceSignals = {};
  override = $state<TierOverride>('AUTO');
  flags = $state<TierFlags>(detectTier({}));

  /** Boot: detect from device signals, then refine with a 1 s frame probe. */
  init(): void {
    this.signals = readSignals();
    this.override = storedOverride();
    this.recompute();
    const run = () =>
      probeFrameTime(1000).then((avgFrameMs) => {
        this.signals = { ...this.signals, avgFrameMs };
        this.recompute();
      });
    // Probe after the splash so its animation doesn't skew the reading.
    setTimeout(run, 1400);
  }

  setOverride(o: TierOverride): void {
    this.override = o;
    try {
      if (o === 'AUTO') localStorage.removeItem(TIER_KEY);
      else localStorage.setItem(TIER_KEY, o);
    } catch {
      /* ignore */
    }
    this.recompute();
  }

  private recompute(): void {
    this.flags = detectTier(this.signals, this.override);
    setDurationScale(this.flags.durationScale);
    applyTier(this.flags);
  }
}

export const perf = new PerfStore();
