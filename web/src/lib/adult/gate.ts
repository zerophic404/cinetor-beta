// 18+ gate: hidden until enabled (age confirm + 4-digit PIN, SHA-256 hash stored per profile),
// PIN required once per app session, re-locks after 5 minutes in the background. Kids profiles
// can never enable it. Pure: storage, clock and hash are injected.
import type { SyncKV } from '../data/kv';
import type { Profile } from '../profile/profiles';

export const RELOCK_MS = 5 * 60_000;
export const isPin = (pin: string): boolean => /^\d{4}$/.test(pin);

export async function sha256Hex(text: string): Promise<string> {
  const data = new TextEncoder().encode(text);
  const buf = await globalThis.crypto.subtle.digest('SHA-256', data);
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export const pinHash = (profileId: string, pin: string): Promise<string> => sha256Hex(`cinetor:${profileId}:${pin}`);

type P = Pick<Profile, 'id' | 'kids'>;

export type GateResult = { ok: true } | { ok: false; error: string };

export class AdultGate {
  private unlockedFor: string | null = null;
  private hiddenAt: number | null = null;

  constructor(
    private kv: SyncKV,
    private now: () => number = Date.now,
    private hash: (profileId: string, pin: string) => Promise<string> = pinHash
  ) {}

  private k(p: P, what: 'enabled' | 'pin') {
    return `cinetor.adult.${p.id}.${what}`;
  }

  canEnable(p: P): boolean {
    return !p.kids;
  }

  /** Shown in the Spaces sheet only when enabled (never for Kids). Hidden by default. */
  enabled(p: P): boolean {
    return !p.kids && this.kv.get(this.k(p, 'enabled')) === '1' && !!this.kv.get(this.k(p, 'pin'));
  }

  hasPin(p: P): boolean {
    return !!this.kv.get(this.k(p, 'pin'));
  }

  async enable(p: P, ageConfirmed: boolean, pin: string): Promise<GateResult> {
    if (p.kids) return { ok: false, error: 'Not available on Kids profiles' };
    if (!ageConfirmed) return { ok: false, error: 'Confirm you are 18 or older' };
    if (!isPin(pin)) return { ok: false, error: 'PIN must be 4 digits' };
    this.kv.set(this.k(p, 'pin'), await this.hash(p.id, pin));
    this.kv.set(this.k(p, 'enabled'), '1');
    return { ok: true };
  }

  disable(p: P): void {
    this.kv.del(this.k(p, 'enabled'));
    this.kv.del(this.k(p, 'pin'));
    this.lock();
  }

  async verify(p: P, pin: string): Promise<boolean> {
    const h = this.kv.get(this.k(p, 'pin'));
    return !!h && isPin(pin) && (await this.hash(p.id, pin)) === h;
  }

  async changePin(p: P, oldPin: string, newPin: string): Promise<GateResult> {
    if (!(await this.verify(p, oldPin))) return { ok: false, error: 'Current PIN is wrong' };
    if (!isPin(newPin)) return { ok: false, error: 'PIN must be 4 digits' };
    this.kv.set(this.k(p, 'pin'), await this.hash(p.id, newPin));
    return { ok: true };
  }

  /** Unlocked for this session (and not timed out in the background). */
  unlocked(p: P): boolean {
    this.checkRelock();
    return this.enabled(p) && this.unlockedFor === p.id;
  }

  async unlock(p: P, pin: string): Promise<boolean> {
    if (!this.enabled(p) || !(await this.verify(p, pin))) return false;
    this.unlockedFor = p.id;
    this.hiddenAt = null;
    return true;
  }

  lock(): void {
    this.unlockedFor = null;
    this.hiddenAt = null;
  }

  /** document.visibilityState === 'hidden' */
  onHidden(): void {
    if (this.unlockedFor) this.hiddenAt = this.now();
  }

  /** Back to the foreground: re-lock if hidden for ≥ 5 min. */
  onVisible(): void {
    this.checkRelock();
    this.hiddenAt = null;
  }

  private checkRelock(): void {
    if (this.hiddenAt !== null && this.now() - this.hiddenAt >= RELOCK_MS) this.lock();
  }
}
