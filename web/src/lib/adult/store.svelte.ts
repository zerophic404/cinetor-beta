// Reactive wrapper around the pure AdultGate for the active profile.
import { localKV } from '$lib/data/kv';
import { profiles } from '$lib/profile/store.svelte';
import { AdultGate, type GateResult } from './gate';

class AdultState {
  private gate = new AdultGate(localKV());
  private version = $state(0);

  private bump(): void {
    this.version++;
  }

  get canEnable(): boolean {
    void this.version;
    return this.gate.canEnable(profiles.active);
  }
  /** Visible in the Spaces sheet / reachable at all. */
  get enabled(): boolean {
    void this.version;
    return this.gate.enabled(profiles.active);
  }
  get unlocked(): boolean {
    void this.version;
    return this.gate.unlocked(profiles.active);
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
  changePin(oldPin: string, newPin: string): Promise<GateResult> {
    return this.gate.changePin(profiles.active, oldPin, newPin);
  }

  /** Layout: re-lock after 5 min in the background. Returns cleanup. */
  watchVisibility(): () => void {
    const f = () => {
      if (document.visibilityState === 'hidden') this.gate.onHidden();
      else {
        this.gate.onVisible();
        this.bump();
      }
    };
    document.addEventListener('visibilitychange', f);
    return () => document.removeEventListener('visibilitychange', f);
  }
}

export const adult = new AdultState();
