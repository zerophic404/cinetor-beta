import { describe, expect, it } from 'vitest';
import { memoryKV } from '../data/kv';
import { ResumeStore, memoryKV as resumeKV, type ResumeEntry } from '../player/resume';
import { AdultGate, RELOCK_MS, sha256Hex } from './gate';

const adult = { id: 'p1', kids: false };
const kid = { id: 'k1', kids: true };

describe('adult gate', () => {
  it('is hidden by default', () => {
    const gate = new AdultGate(memoryKV());
    expect(gate.enabled(adult)).toBe(false);
    expect(gate.unlocked(adult)).toBe(false);
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

  it('requires the PIN each session', async () => {
    const kv = memoryKV();
    const s1 = new AdultGate(kv);
    await s1.enable(adult, true, '4321');
    expect(s1.unlocked(adult)).toBe(false);
    expect(await s1.unlock(adult, '0000')).toBe(false);
    expect(await s1.unlock(adult, '4321')).toBe(true);
    expect(s1.unlocked(adult)).toBe(true);
    const s2 = new AdultGate(kv); // new app session, same storage
    expect(s2.enabled(adult)).toBe(true);
    expect(s2.unlocked(adult)).toBe(false);
  });

  it('re-locks after 5 minutes hidden', async () => {
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

  it('kids cannot enable', async () => {
    const kv = memoryKV({ 'cinetor.adult.k1.enabled': '1', 'cinetor.adult.k1.pin': 'x' });
    const gate = new AdultGate(kv);
    expect(gate.canEnable(kid)).toBe(false);
    expect((await gate.enable(kid, true, '1234')).ok).toBe(false);
    expect(gate.enabled(kid)).toBe(false);
  });

  it('changes the PIN only with the current one', async () => {
    const gate = new AdultGate(memoryKV());
    await gate.enable(adult, true, '1234');
    expect((await gate.changePin(adult, '9999', '5555')).ok).toBe(false);
    expect((await gate.changePin(adult, '1234', '5555')).ok).toBe(true);
    expect(await gate.verify(adult, '5555')).toBe(true);
  });

  it('adult is not in continue watching', async () => {
    const store = new ResumeStore(resumeKV<ResumeEntry & { profile: string }>(), () => 1);
    await store.save('p1', { itemId: 'ad-001', space: 'ADULT', pos: 300, dur: 3000 });
    await store.save('p1', { itemId: 'hm-001', space: 'HOME', pos: 300, dur: 3000 });
    const cw = await store.continueWatching('p1');
    expect(cw.map((e) => e.itemId)).toEqual(['hm-001']);
  });
});
