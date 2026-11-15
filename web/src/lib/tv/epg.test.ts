import { describe, expect, it } from 'vitest';
import { CatalogRepository, NO_LATENCY } from '../data/catalog';
import { search } from '../data/search';
import { fileFetcher } from '../data/test-utils';
import type { Item, Program } from '../data/types';
import {
  FAVORITES,
  channelsOf,
  currentProgram,
  filterChannels,
  guideBlocks,
  matchMinute,
  nowLinePosition,
  programProgress,
  sportsTabs,
  toggleFavorite,
  windowStartFor,
  zapChannel
} from './epg';

const prog = (id: string, startOffsetSec: number, durationSec: number, genre = 'News'): Program => ({ id, title: id, genre, startOffsetSec, durationSec });

function ch(id: string, n: number, group: string, programs: Program[] = []): Item {
  return {
    id, space: 'TV', categoryId: 'tv-live', type: 'CHANNEL', title: id, year: 2026, rating: 7, maturity: 'TV-G',
    genres: [group], synopsis: '', poster: '', backdrop: '', dominantColor: '#000', accentColor: '#f00',
    live: true, channelNumber: n, channelGroup: group, programs
  };
}

describe('epg nowLinePosition', () => {
  it('is proportional to elapsed time from the window start', () => {
    expect(nowLinePosition(1000 + 1800, 1000, 0.1)).toBe(180);
    expect(nowLinePosition(1000, 1000, 0.1)).toBe(0);
  });
  it('is null outside the window', () => {
    expect(nowLinePosition(900, 1000, 0.1)).toBeNull();
    expect(nowLinePosition(1000 + 5 * 3600, 1000, 0.1, 4 * 3600)).toBeNull();
  });
  it('window starts on the previous half hour', () => {
    expect(windowStartFor(3600 + 1799)).toBe(3600);
    expect(windowStartFor(3600 + 1800)).toBe(5400);
  });
});

describe('epg programmes', () => {
  const base = 10_000;
  const programs = [prog('a', -900, 1800), prog('b', 900, 1800)];
  it('finds the programme on air and its progress', () => {
    const cur = currentProgram(programs, base, base);
    expect(cur?.program.id).toBe('a');
    expect(programProgress(cur!, base)).toBeCloseTo(0.5);
    expect(currentProgram(programs, base, base + 1000)?.program.id).toBe('b');
  });
  it('clips guide blocks to the window and flags the live one', () => {
    const blocks = guideBlocks(programs, base, base, 0.1, base + 10, 3600);
    expect(blocks[0]).toMatchObject({ left: 0, width: 90, live: true });
    expect(blocks[1]).toMatchObject({ left: 90, width: 180, live: false });
  });
});

describe('zap', () => {
  const list = [ch('c1', 1, 'News'), ch('c2', 2, 'News'), ch('c3', 3, 'Kids')];
  it('moves to the next / previous channel', () => {
    expect(zapChannel(list, 'c1', 1)?.id).toBe('c2');
    expect(zapChannel(list, 'c2', -1)?.id).toBe('c1');
  });
  it('wraps at both ends', () => {
    expect(zapChannel(list, 'c3', 1)?.id).toBe('c1');
    expect(zapChannel(list, 'c1', -1)?.id).toBe('c3');
  });
  it('handles unknown / empty', () => {
    expect(zapChannel(list, 'zz', 1)?.id).toBe('c1');
    expect(zapChannel([], 'c1', 1)).toBeUndefined();
  });
});

describe('channel filters + favorites', () => {
  const list = channelsOf([ch('c3', 3, 'Kids', [prog('k', -10, 100, 'Kids')]), ch('c1', 1, 'News', [prog('n', -10, 100, 'Sports')])]);
  it('sorts by channel number', () => expect(list.map((c) => c.id)).toEqual(['c1', 'c3']));
  it('filters by group, favorites and on-air genre', () => {
    const f = { group: null, genre: null, favorites: [] as string[], baseSec: 0, nowSec: 0 };
    expect(filterChannels(list, { ...f, group: 'Kids' }).map((c) => c.id)).toEqual(['c3']);
    expect(filterChannels(list, { ...f, group: FAVORITES, favorites: toggleFavorite([], 'c1') }).map((c) => c.id)).toEqual(['c1']);
    expect(filterChannels(list, { ...f, genre: 'sports' }).map((c) => c.id)).toEqual(['c1']);
  });
  it('toggleFavorite adds then removes', () => {
    expect(toggleFavorite(toggleFavorite([], 'x'), 'x')).toEqual([]);
  });
});

describe('sports', () => {
  it('splits events into live / upcoming / results', async () => {
    const repo = new CatalogRepository({ baseUrl: '', latency: NO_LATENCY, fetcher: fileFetcher(), persist: null });
    const tabs = sportsTabs(await repo.items('TV'));
    expect(tabs.live.length).toBeGreaterThan(0);
    expect(tabs.upcoming.every((e) => e.status === 'UPCOMING')).toBe(true);
    expect(tabs.results.every((e) => e.status === 'FINISHED')).toBe(true);
  });
  it('match minute caps at full time', () => {
    expect(matchMinute({ startOffsetSec: -600, durationSec: 5400 }, 0, 0)).toBe("10'");
    expect(matchMinute({ startOffsetSec: -9000, durationSec: 5400 }, 0, 0)).toBe("90+'");
  });
});

describe('isolation', () => {
  it('live items are not in global search', async () => {
    const calls: string[] = [];
    const repo = new CatalogRepository({ baseUrl: '', latency: NO_LATENCY, fetcher: fileFetcher(calls), persist: null });
    const tv = await repo.items('TV');
    const name = tv.find((i) => i.type === 'CHANNEL')!.title; // "Cinetor News 24"
    calls.length = 0;
    const res = await search(repo, name.split(' ')[0]);
    const all = res.groups.flatMap((g) => g.items);
    expect(all.some((i) => i.space === 'TV' || i.live)).toBe(false);
    expect(res.groups.every((g) => g.space !== 'TV')).toBe(true);
  });
});
