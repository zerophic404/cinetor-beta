// Reactive wrapper around the pure AdultGate for the active profile.
import { localKV } from '$lib/data/kv';
import { profiles } from '$lib/profile/store.svelte';
import { AdultGate, type GateResult } from './gate';

const ACTIVITY_THROTTLE_MS = 15_000;
const RELOCK_POLL_MS = 15_000;

class AdultState {
  private gate = new AdultGate(localKV());
  private version = $state(0);
  private lastKnownUnlocked = false;

  private bump(): void {
    this.lastKnownUnlocked = this.gate.unlocked(profiles.active);
    this.version++;
  }

  get canEnable(): boolean {
    void this.version;
    return this.gate.canEnable(profiles.active);
  }
  /** Visible in the Spaces hub / reachable at all (never on Kids profiles). */
  get enabled(): boolean {
    void this.version;
    return this.gate.enabled(profiles.active);
  }
  get unlocked(): boolean {
    void this.version;
    return this.gate.unlocked(profiles.active);
  }
  get lockedOutMs(): number {
    void this.version;
    return this.gate.lockedOutMs();
  }
  get remainingMs(): number {
    void this.version;
    return this.gate.remainingMs();
  }

  async enable(ageConfirmed: boolean, pin: string): Promise<GateResult> {
    const r = await this.gate.enable(profiles.active, ageConfirmed, pin);
    if (r.ok) await this.gate.unlock(profiles.active, pin);
    this.bump();
    return r;
  }
  disable(): void {
    this.gate.disable(profiles.active);
    this.bump();
  }
  async unlock(pin: string): Promise<boolean> {
    const ok = await this.gate.unlock(profiles.active, pin);
    this.bump();
    return ok;
  }
  lock(): void {
    this.gate.lock();
    this.bump();
  }
  touch(): void {
    this.gate.touch();
  }
  changePin(oldPin: string, newPin: string): Promise<GateResult> {
    return this.gate.changePin(profiles.active, oldPin, newPin);
  }

  /** Layout: re-lock after 5 min of inactivity or 5 min in the background. Returns cleanup. */
  watchVisibility(): () => void {
    let lastTouch = 0;
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') this.gate.onHidden();
      else {
        this.gate.onVisible();
        this.bump();
      }
    };
    const onBlur = () => {
      if (document.visibilityState === 'hidden') this.gate.onHidden();
    };
    const onActivity = () => {
      const now = Date.now();
      if (now - lastTouch < ACTIVITY_THROTTLE_MS) return;
      lastTouch = now;
      this.gate.touch();
    };
    const timer = setInterval(() => {
      if (this.lastKnownUnlocked && !this.gate.unlocked(profiles.active)) this.bump();
    }, RELOCK_POLL_MS);

    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('blur', onBlur);
    window.addEventListener('pointerdown', onActivity, { passive: true });
    window.addEventListener('keydown', onActivity, { passive: true });
    window.addEventListener('touchstart', onActivity, { passive: true });
    window.addEventListener('scroll', onActivity, { passive: true });
    return () => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('blur', onBlur);
      window.removeEventListener('pointerdown', onActivity);
      window.removeEventListener('keydown', onActivity);
      window.removeEventListener('touchstart', onActivity);
      window.removeEventListener('scroll', onActivity);
    };
  }
}

export const adult = new AdultState();
