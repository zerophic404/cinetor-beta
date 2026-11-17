// 18+ gate: hidden until enabled (age confirm + 4-digit PIN, SHA-256 hash stored per profile),
// PIN required once per app session, re-locks after 5 minutes of inactivity or in the background.
// Kids profiles can never enable or unlock it. Pure: storage, clock and hash are injected.
import type { SyncKV } from '../data/kv';
import type { Profile } from '../profile/profiles';

export const RELOCK_MS = 5 * 60_000;
export const MAX_PIN_ATTEMPTS = 5;
export const LOCKOUT_MS = 30_000;
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
  private lastActiveAt: number | null = null;
  private failures = 0;
  private lockoutUntil: number | null = null;

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

  /** Shown in the Spaces hub only when enabled (never for Kids). Hidden by default. */
  enabled(p: P): boolean {
    return !p.kids && this.kv.get(this.k(p, 'enabled')) === '1' && !!this.kv.get(this.k(p, 'pin'));
  }

  hasPin(p: P): boolean {
    return !p.kids && !!this.kv.get(this.k(p, 'pin'));
  }

  /** Remaining cooldown in ms after repeated wrong PIN attempts (0 when clear). */
  lockedOutMs(): number {
    if (this.lockoutUntil === null) return 0;
    const rem = this.lockoutUntil - this.now();
    if (rem <= 0) {
      this.lockoutUntil = null;
      this.failures = 0;
      return 0;
    }
    return rem;
  }

  async enable(p: P, ageConfirmed: boolean, pin: string): Promise<GateResult> {
    if (p.kids) return { ok: false, error: 'Not available on Kids profiles' };
    if (!ageConfirmed) return { ok: false, error: 'Confirm you are 18 or older' };
    if (!isPin(pin)) return { ok: false, error: 'PIN must be 4 digits' };
    this.kv.set(this.k(p, 'pin'), await this.hash(p.id, pin));
    this.kv.set(this.k(p, 'enabled'), '1');
    this.failures = 0;
    this.lockoutUntil = null;
    return { ok: true };
  }

  disable(p: P): void {
    this.kv.del(this.k(p, 'enabled'));
    this.kv.del(this.k(p, 'pin'));
    this.lock();
  }

  async verify(p: P, pin: string): Promise<boolean> {
    if (p.kids) return false;
    const h = this.kv.get(this.k(p, 'pin'));
    return !!h && isPin(pin) && (await this.hash(p.id, pin)) === h;
  }

  async changePin(p: P, oldPin: string, newPin: string): Promise<GateResult> {
    if (p.kids) return { ok: false, error: 'Not available on Kids profiles' };
    if (!(await this.verify(p, oldPin))) return { ok: false, error: 'Current PIN is wrong' };
    if (!isPin(newPin)) return { ok: false, error: 'PIN must be 4 digits' };
    this.kv.set(this.k(p, 'pin'), await this.hash(p.id, newPin));
    return { ok: true };
  }

  /** Unlocked for this session (and not timed out from inactivity or backgrounding). */
  unlocked(p: P): boolean {
    if (p.kids) return false;
    this.checkRelock();
    return this.enabled(p) && this.unlockedFor === p.id;
  }

  /** Remaining session time in ms before automatic 5-minute relock (0 when locked). */
  remainingMs(): number {
    this.checkRelock();
    if (!this.unlockedFor) return 0;
    const ref = this.hiddenAt ?? this.lastActiveAt;
    return ref === null ? 0 : Math.max(0, RELOCK_MS - (this.now() - ref));
  }

  async unlock(p: P, pin: string): Promise<boolean> {
    if (p.kids || !this.enabled(p) || this.lockedOutMs() > 0) return false;
    if (!(await this.verify(p, pin))) {
      this.failures++;
      if (this.failures >= MAX_PIN_ATTEMPTS) {
        this.lockoutUntil = this.now() + LOCKOUT_MS;
        this.failures = 0;
      }
      return false;
    }
    this.failures = 0;
    this.lockoutUntil = null;
    this.unlockedFor = p.id;
    this.hiddenAt = null;
    this.lastActiveAt = this.now();
    return true;
  }

  lock(): void {
    this.unlockedFor = null;
    this.hiddenAt = null;
    this.lastActiveAt = null;
  }

  /** Record foreground user activity to extend the 5-minute session window. */
  touch(): void {
    this.checkRelock();
    if (this.unlockedFor && this.hiddenAt === null) {
      this.lastActiveAt = this.now();
    }
  }

  /** document.visibilityState === 'hidden' */
  onHidden(): void {
    if (this.unlockedFor) this.hiddenAt = this.now();
  }

  /** Back to the foreground: re-lock if hidden for ≥ 5 min, otherwise refresh activity clock. */
  onVisible(): void {
    this.checkRelock();
    if (this.unlockedFor) this.lastActiveAt = this.now();
    this.hiddenAt = null;
  }

  private checkRelock(): void {
    if (this.hiddenAt !== null) {
      if (this.now() - this.hiddenAt >= RELOCK_MS) this.lock();
      return;
    }
    if (this.lastActiveAt !== null && this.now() - this.lastActiveAt >= RELOCK_MS) {
      this.lock();
    }
  }
}
