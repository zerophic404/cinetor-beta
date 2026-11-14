import { describe, expect, it } from 'vitest';
import { memoryKV } from '../data/kv';
import { ResumeStore, memoryKV as resumeKV, type ResumeEntry } from '../player/resume';
import { MAX_PROFILES, ProfileError, ProfileStore, initials, ownedBy } from './profiles';

describe('profiles', () => {
  it('starts with one default profile', () => {
    const s = new ProfileStore(memoryKV());
    expect(s.list()).toHaveLength(1);
    expect(s.activeId()).toBe('default');
  });

  it('allows at most five profiles', () => {
    let n = 0;
    const s = new ProfileStore(memoryKV(), () => 1, () => `id${n++}`);
    for (let i = 0; i < MAX_PROFILES - 1; i++) s.create({ name: `P${i}` });
    expect(s.list()).toHaveLength(MAX_PROFILES);
    expect(s.canCreate()).toBe(false);
    expect(() => s.create({ name: 'Sixth' })).toThrow(ProfileError);
  });

  it('deleteProfile removes its data (only its data)', () => {
    const kv = memoryKV();
    const s = new ProfileStore(kv, () => 1, () => 'kid');
    const kid = s.create({ name: 'Kid', kids: true });
    kv.set('cinetor.mylist.kid.video', '["hm-1"]');
    kv.set('cinetor.read.kid.co-ma-001', '{}');
    kv.set('cinetor.tvfav.kid', '[]');
    kv.set('cinetor.adult.kid.pin', 'x');
    kv.set('cinetor.mylist.default.video', '["hm-2"]');
    s.setActive(kid.id);
    s.delete(kid.id);
    expect(s.get('kid')).toBeUndefined();
    expect(kv.keys().filter((k) => k.includes('.kid'))).toEqual([]);
    expect(kv.get('cinetor.mylist.default.video')).toBe('["hm-2"]');
    expect(s.activeId()).toBe('default');
    expect(() => s.delete('default')).toThrow(ProfileError);
  });

  it('ownedBy matches only the exact profile segment', () => {
    expect(ownedBy('cinetor.mylist.p1.video', 'p1')).toBe(true);
    expect(ownedBy('cinetor.mylist.p10.video', 'p1')).toBe(false);
    expect(ownedBy('cinetor.profile', 'p1')).toBe(false);
  });

  it('initials', () => {
    expect(initials('Ahmed Ishtiaq Zarir')).toBe('AZ');
    expect(initials('mythbrix')).toBe('MY');
  });
});

describe('continue watching', () => {
  it('only lists video spaces (Home/Anime/Drama)', async () => {
    const store = new ResumeStore(resumeKV<ResumeEntry & { profile: string }>(), () => 1);
    for (const [id, space] of [
      ['hm-1', 'HOME'],
      ['an-1', 'ANIME'],
      ['dr-1', 'DRAMA'],
      ['tv-ch-101', 'TV'],
      ['mu-tr-001', 'MUSIC'],
      ['ad-001', 'ADULT']
    ] as const) {
      await store.save('p1', { itemId: id, space, pos: 100, dur: 1000 });
    }
    const ids = (await store.continueWatching('p1')).map((e) => e.itemId).sort();
    expect(ids).toEqual(['an-1', 'dr-1', 'hm-1']);
    expect(await store.continueWatching('p2')).toEqual([]);
  });
});
