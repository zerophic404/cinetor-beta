import { describe, expect, it } from 'vitest';
import { memoryKV } from '../data/kv';
import { ResumeStore, memoryKV as resumeKV, type ResumeEntry } from '../player/resume';
import { AdultGate, LOCKOUT_MS, MAX_PIN_ATTEMPTS, RELOCK_MS, isPin, sha256Hex } from './gate';

const adult = { id: 'p1', kids: false };
const adult2 = { id: 'p2', kids: false };
const kid = { id: 'k1', kids: true };

describe('adult gate', () => {
  it('is hidden by default', () => {
    const gate = new AdultGate(memoryKV());
    expect(gate.enabled(adult)).toBe(false);
    expect(gate.unlocked(adult)).toBe(false);
    expect(gate.remainingMs()).toBe(0);
  });

  it('validates 4-digit numeric PIN format strictly', () => {
    expect(isPin('0000')).toBe(true);
    expect(isPin('9876')).toBe(true);
    expect(isPin('123')).toBe(false);
    expect(isPin('12345')).toBe(false);
    expect(isPin('12a4')).toBe(false);
    expect(isPin(' 123')).toBe(false);
  });

  it('needs an age confirmation and a 4-digit PIN; stores only a SHA-256 hash', async () => {
    const kv = memoryKV();
    const gate = new AdultGate(kv);
    expect((await gate.enable(adult, false, '1234')).ok).toBe(false);
    expect((await gate.enable(adult, true, '12a4')).ok).toBe(false);
    expect((await gate.enable(adult, true, '1234')).ok).toBe(true);
    expect(gate.enabled(adult)).toBe(true);
    const stored = kv.get('cinetor.adult.p1.pin')!;
    expect(stored).toMatch(/^[0-9a-f]{64}$/);
    expect(stored).not.toContain('1234');
    expect(stored).toBe(await sha256Hex('cinetor:p1:1234'));
  });

  it('requires the PIN each session and isolates unlock state per profile', async () => {
    const kv = memoryKV();
    const s1 = new AdultGate(kv);
    await s1.enable(adult, true, '4321');
    await s1.enable(adult2, true, '4321');
    expect(s1.unlocked(adult)).toBe(false);
    expect(await s1.unlock(adult, '0000')).toBe(false);
    expect(await s1.unlock(adult, '4321')).toBe(true);
    expect(s1.unlocked(adult)).toBe(true);
    expect(s1.unlocked(adult2)).toBe(false);

    const s2 = new AdultGate(kv); // new app session, same storage
    expect(s2.enabled(adult)).toBe(true);
    expect(s2.unlocked(adult)).toBe(false);
  });

  it('re-locks after 5 minutes hidden in the background', async () => {
    let t = 0;
    const gate = new AdultGate(memoryKV(), () => t);
    await gate.enable(adult, true, '1111');
    await gate.unlock(adult, '1111');
    gate.onHidden();
    t += RELOCK_MS - 1000;
    gate.onVisible();
    expect(gate.unlocked(adult)).toBe(true);
    gate.onHidden();
    t += RELOCK_MS;
    gate.onVisible();
    expect(gate.unlocked(adult)).toBe(false);
  });

  it('re-locks after 5 minutes of foreground inactivity unless touch() extends the session', async () => {
    let t = 1_000;
    const gate = new AdultGate(memoryKV(), () => t);
    await gate.enable(adult, true, '2222');
    await gate.unlock(adult, '2222');
    expect(gate.remainingMs()).toBe(RELOCK_MS);

    // Activity at 4 minutes extends the 5-minute window.
    t += 4 * 60_000;
    expect(gate.unlocked(adult)).toBe(true);
    gate.touch();
    expect(gate.remainingMs()).toBe(RELOCK_MS);

    // 4 minutes after touch (8 minutes total): still unlocked.
    t += 4 * 60_000;
    expect(gate.unlocked(adult)).toBe(true);

    // 5 minutes after last touch: automatically relocked.
    t += 60_000;
    expect(gate.unlocked(adult)).toBe(false);
    expect(gate.remainingMs()).toBe(0);
  });

  it('hard-blocks Kids profiles across enable, verify, unlock and changePin even if storage is tampered', async () => {
    const tamperedHash = await sha256Hex('cinetor:k1:1234');
    const kv = memoryKV({ 'cinetor.adult.k1.enabled': '1', 'cinetor.adult.k1.pin': tamperedHash });
    const gate = new AdultGate(kv);
    expect(gate.canEnable(kid)).toBe(false);
    expect(gate.hasPin(kid)).toBe(false);
    expect((await gate.enable(kid, true, '1234')).ok).toBe(false);
    expect(gate.enabled(kid)).toBe(false);
    expect(await gate.verify(kid, '1234')).toBe(false);
    expect(await gate.unlock(kid, '1234')).toBe(false);
    expect((await gate.changePin(kid, '1234', '5678')).ok).toBe(false);
    expect(gate.unlocked(kid)).toBe(false);
  });

  it('throttles repeated wrong PIN attempts and recovers after cooldown', async () => {
    let t = 0;
    const gate = new AdultGate(memoryKV(), () => t);
    await gate.enable(adult, true, '1234');
    for (let i = 0; i < MAX_PIN_ATTEMPTS; i++) {
      expect(await gate.unlock(adult, '9999')).toBe(false);
    }
    expect(gate.lockedOutMs()).toBe(LOCKOUT_MS);
    // Even the right PIN is rejected while in cooldown.
    expect(await gate.unlock(adult, '1234')).toBe(false);
    // Once cooldown expires, the right PIN unlocks cleanly.
    t += LOCKOUT_MS;
    expect(gate.lockedOutMs()).toBe(0);
    expect(await gate.unlock(adult, '1234')).toBe(true);
    expect(gate.unlocked(adult)).toBe(true);
  });

  it('changes the PIN only with the current one and disables cleanly', async () => {
    const gate = new AdultGate(memoryKV());
    await gate.enable(adult, true, '1234');
    await gate.unlock(adult, '1234');
    expect((await gate.changePin(adult, '9999', '5555')).ok).toBe(false);
    expect((await gate.changePin(adult, '1234', '5555')).ok).toBe(true);
    expect(await gate.verify(adult, '5555')).toBe(true);
    gate.disable(adult);
    expect(gate.enabled(adult)).toBe(false);
    expect(gate.unlocked(adult)).toBe(false);
  });

  it('adult is not in continue watching', async () => {
    const store = new ResumeStore(resumeKV<ResumeEntry & { profile: string }>(), () => 1);
    await store.save('p1', { itemId: 'ad-001', space: 'ADULT', pos: 300, dur: 3000 });
    await store.save('p1', { itemId: 'hm-001', space: 'HOME', pos: 300, dur: 3000 });
    const cw = await store.continueWatching('p1');
    expect(cw.map((e) => e.itemId)).toEqual(['hm-001']);
  });
});
