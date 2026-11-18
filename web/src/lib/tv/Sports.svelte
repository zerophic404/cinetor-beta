<script lang="ts">
  import { base } from '$app/paths';
  import { fallbackColor } from '$lib/data/color';
  import type { Item } from '$lib/data/types';
  import { img } from '$lib/data/repo';
  import { detailsHref } from '$lib/ui/handoff';
  import { clockLabel, matchMinute, startsIn } from './epg';
  import { live } from './live.svelte';

  let { items, onWatchLive }: { items: Item[]; onWatchLive?: (ev: Item) => void } = $props();

  type StatusFilter = 'all' | 'live' | 'upcoming';
  const SPORT_FILTERS = ['All', 'Cricket', 'Football', 'Basketball', 'Combat', 'Tennis', 'Motorsport', 'Rugby'] as const;
  type SportFilter = (typeof SPORT_FILTERS)[number];

  let sportFilter = $state<SportFilter>('All');
  let statusFilter = $state<StatusFilter>('all');

  const SPORT_KEYWORDS: Record<Exclude<SportFilter, 'All'>, RegExp> = {
    Cricket: /cricket|t20|odi|ipl|bbl|ashes|wickets/i,
    Football: /football|soccer|premier|champions|laliga|bundesliga|serie a|fc\b|united|rovers/i,
    Basketball: /basketball|hoops|nba|euroleague/i,
    Combat: /combat|mma|ufc|boxing|wrestling|fight|knockout/i,
    Tennis: /tennis|atp|wta|wimbledon|open|slam/i,
    Motorsport: /motorsport|f1|formula|motogp|gp\b|racing|rally|nascar/i,
    Rugby: /rugby|six nations|super rugby/i
  };

  function matchesSport(ev: Item, sf: SportFilter): boolean {
    if (sf === 'All') return true;
    const hay = [ev.title, ev.league ?? '', ...ev.genres].join(' ');
    return SPORT_KEYWORDS[sf].test(hay);
  }

  function monogram(name: string): string {
    const words = name.trim().split(/\s+/).filter(Boolean);
    if (!words.length) return 'TM';
    if (words.length === 1) return words[0].slice(0, 3).toUpperCase();
    return words
      .slice(0, 3)
      .map((w) => w[0])
      .join('')
      .toUpperCase();
  }

  interface MatchSide {
    name: string;
    short: string;
    color: string;
    score: number | null;
  }

  function parseMatchup(ev: Item): { home: MatchSide; away: MatchSide } | null {
    if (ev.homeTeam && ev.awayTeam) {
      return {
        home: {
          name: ev.homeTeam.name,
          short: ev.homeTeam.short || monogram(ev.homeTeam.name),
          color: ev.homeTeam.color || fallbackColor(`${ev.id}-h`),
          score: ev.homeTeam.score ?? null
        },
        away: {
          name: ev.awayTeam.name,
          short: ev.awayTeam.short || monogram(ev.awayTeam.name),
          color: ev.awayTeam.color || fallbackColor(`${ev.id}-a`),
          score: ev.awayTeam.score ?? null
        }
      };
    }
    const m = /^(.+?)\s+(?:vs\.?|v\.?)\s+(.+)$/i.exec(ev.title.trim());
    if (!m) return null;
    const hName = m[1].trim();
    const aName = m[2].trim();
    if (!hName || !aName) return null;
    return {
      home: { name: hName, short: monogram(hName), color: fallbackColor(hName), score: null },
      away: { name: aName, short: monogram(aName), color: fallbackColor(aName), score: null }
    };
  }

  const allEvents = $derived.by(() => {
    const rank: Record<string, number> = { LIVE: 0, UPCOMING: 1, FINISHED: 2 };
    return items
      .filter((i) => i.type === 'EVENT' && matchesSport(i, sportFilter))
      .sort((a, b) => {
        const ra = rank[a.status ?? 'UPCOMING'] ?? 1;
        const rb = rank[b.status ?? 'UPCOMING'] ?? 1;
        if (ra !== rb) return ra - rb;
        return (a.startOffsetSec ?? 0) - (b.startOffsetSec ?? 0);
      });
  });

  const liveCount = $derived(allEvents.filter((e) => e.status === 'LIVE').length);
  const upcomingCount = $derived(allEvents.filter((e) => e.status === 'UPCOMING').length);

  const visibleEvents = $derived.by(() => {
    if (statusFilter === 'live') return allEvents.filter((e) => e.status === 'LIVE');
    if (statusFilter === 'upcoming') return allEvents.filter((e) => e.status === 'UPCOMING');
    return allEvents;
  });

  const liveWatchUrl = (ev: Item) => `${base}/app/watch?id=${encodeURIComponent(ev.id)}&live=1`;
</script>

<div class="sports-wrap">
  <div class="controls-bar">
    <div class="status-seg" role="tablist" aria-label="Match status">
      <button
        type="button"
        role="tab"
        aria-selected={statusFilter === 'all'}
        class:active={statusFilter === 'all'}
        onclick={() => (statusFilter = 'all')}
      >
        All <small>{allEvents.length}</small>
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={statusFilter === 'live'}
        class:active={statusFilter === 'live'}
        onclick={() => (statusFilter = 'live')}
      >
        <span class="pulse-dot" aria-hidden="true"></span>
        Live Now <small>{liveCount}</small>
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={statusFilter === 'upcoming'}
        class:active={statusFilter === 'upcoming'}
        onclick={() => (statusFilter = 'upcoming')}
      >
        Upcoming <small>{upcomingCount}</small>
      </button>
    </div>

    <div class="h-scroll sport-chips" role="group" aria-label="Sport category">
      {#each SPORT_FILTERS as sf (sf)}
        <button
          type="button"
          class="chip"
          class:active={sportFilter === sf}
          aria-pressed={sportFilter === sf}
          onclick={() => (sportFilter = sf)}
        >
          {sf}
        </button>
      {/each}
    </div>
  </div>

  {#if !visibleEvents.length}
    <div class="empty-card glass">
      <p><b>No {sportFilter === 'All' ? 'matches' : `${sportFilter} matches`} in this view right now.</b></p>
      {#if sportFilter !== 'All' || statusFilter !== 'all'}
        <button
          type="button"
          class="btn btn-ghost reset-btn"
          onclick={() => {
            sportFilter = 'All';
            statusFilter = 'all';
          }}
        >
          Show all sports
        </button>
      {/if}
    </div>
  {:else}
    <div class="match-grid">
      {#each visibleEvents as ev (ev.id)}
        {@const matchup = parseMatchup(ev)}
        {@const isLive = ev.status === 'LIVE'}
        {@const isUpcoming = ev.status === 'UPCOMING'}
        <article
          class="match-card"
          class:is-live={isLive}
          style:--home={matchup?.home.color ?? ev.accentColor}
          style:--away={matchup?.away.color ?? ev.dominantColor}
        >
          {#if ev.backdrop}
            <div class="stadium-bg" style:background-image="url({img(ev.backdrop)})" aria-hidden="true"></div>
          {/if}

          <header class="card-top">
            <span class="league-pill">{ev.league ?? ev.genres[0] ?? 'Sports'}</span>
            {#if isLive}
              <span class="live-pill">
                <span class="pulse-dot on-red" aria-hidden="true"></span>
                LIVE · {matchMinute(ev, live.base, live.now)}
              </span>
            {:else if isUpcoming}
              <span class="time-pill">
                {clockLabel(live.base + (ev.startOffsetSec ?? 0))} · {startsIn(ev, live.base, live.now)}
              </span>
            {:else}
              <span class="time-pill">FINAL</span>
            {/if}
          </header>

          {#if matchup}
            <div class="faceoff" aria-label="{matchup.home.name} versus {matchup.away.name}">
              <div class="club">
                <span class="crest home">{matchup.home.short}</span>
                <span class="club-name">{matchup.home.name}</span>
              </div>

              <div class="vs-center">
                {#if !isUpcoming && (matchup.home.score !== null || matchup.away.score !== null)}
                  <div class="scoreline">
                    <b>{matchup.home.score ?? 0}</b>
                    <span class="sep">:</span>
                    <b>{matchup.away.score ?? 0}</b>
                  </div>
                  <span class="vs-sub">VS</span>
                {:else}
                  <span class="vs-medallion">VS</span>
                {/if}
              </div>

              <div class="club">
                <span class="crest away">{matchup.away.short}</span>
                <span class="club-name">{matchup.away.name}</span>
              </div>
            </div>
          {:else}
            <div class="event-banner">
              <h3>{ev.title}</h3>
              {#if ev.synopsis}<p>{ev.synopsis}</p>{/if}
            </div>
          {/if}

          <footer class="card-bottom">
            <span class="broadcaster">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <rect x="3" y="7" width="18" height="12" rx="2" />
                <path d="m8 3 4 4 4-4" />
              </svg>
              <span>{ev.genres[0] ?? 'Sports HD'} · Broadcast</span>
            </span>

            {#if isLive && onWatchLive}
              <button type="button" class="watch-cta live" onclick={() => onWatchLive(ev)}>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                  <path d="M8 5.5v13l11-6.5z" />
                </svg>
                Watch Live
              </button>
            {:else if isLive}
              <a class="watch-cta live" href={liveWatchUrl(ev)}>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                  <path d="M8 5.5v13l11-6.5z" />
                </svg>
                Watch Live
              </a>
            {:else if onWatchLive && ev.sources?.length}
              <button type="button" class="watch-cta" onclick={() => onWatchLive(ev)}>
                Watch Stream
              </button>
            {:else}
              <a class="watch-cta" href={detailsHref(ev)}>Match Info</a>
            {/if}
          </footer>
        </article>
      {/each}
    </div>
  {/if}
</div>

<style>
  .sports-wrap {
    display: grid;
    gap: 12px;
  }
  .controls-bar {
    display: grid;
    gap: 8px;
  }
  .status-seg {
    display: inline-flex;
    flex-wrap: wrap;
    gap: 4px;
    margin: 0 16px;
    padding: 4px;
    width: fit-content;
    border-radius: 999px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .status-seg button {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    min-height: 34px;
    padding: 0 14px;
    border: 0;
    border-radius: 999px;
    background: none;
    color: var(--text-secondary);
    font-size: 0.84rem;
    font-weight: 700;
    cursor: pointer;
    transition:
      background-color 0.16s ease,
      color 0.16s ease;
  }
  .status-seg button small {
    opacity: 0.72;
    font-variant-numeric: tabular-nums;
  }
  .status-seg button.active {
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
  }
  .sport-chips {
    padding-top: 2px;
    padding-bottom: 8px;
  }
  .pulse-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #ef4444;
    animation: live-pulse 1.4s ease-in-out infinite;
  }
  .pulse-dot.on-red {
    background: #ffffff;
  }
  @keyframes live-pulse {
    0%,
    100% {
      opacity: 1;
      transform: scale(1);
    }
    50% {
      opacity: 0.35;
      transform: scale(0.84);
    }
  }
  .empty-card {
    max-width: 440px;
    margin: 24px auto;
    padding: 28px 20px;
    border-radius: 22px;
    text-align: center;
    color: var(--text-secondary);
  }
  .empty-card p {
    margin: 0;
    color: var(--text-primary);
  }
  .reset-btn {
    margin-top: 12px;
    min-height: 36px;
    padding: 0 16px;
    font-size: 0.84rem;
  }
  .match-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(310px, 1fr));
    gap: 14px;
    padding: 2px 16px 16px;
  }
  .match-card {
    position: relative;
    display: grid;
    gap: 16px;
    padding: 15px 16px;
    border-radius: 18px;
    overflow: hidden;
    isolation: isolate;
    background:
      linear-gradient(160deg, color-mix(in srgb, var(--surface-elevated, var(--surface)) 94%, transparent), var(--surface));
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.08),
      inset 0 0 0 1px var(--glass-stroke),
      0 12px 28px -16px rgb(0 0 0 / 0.65);
    transition: transform 0.16s var(--ease-emphasized, ease);
  }
  .match-card:hover {
    transform: translateY(-2px);
  }
  .match-card.is-live {
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.12),
      inset 0 0 0 1px color-mix(in srgb, var(--accent) 48%, var(--glass-stroke)),
      0 14px 32px -16px var(--accent-glow);
  }
  .match-card::after {
    content: '';
    position: absolute;
    inset: auto 0 0 0;
    height: 3px;
    background: linear-gradient(90deg, var(--home), var(--away));
    opacity: 0.88;
  }
  .stadium-bg {
    position: absolute;
    inset: 0;
    z-index: -1;
    background-size: cover;
    background-position: center;
    opacity: 0.14;
  }
  .card-top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }
  .league-pill {
    padding: 4px 10px;
    border-radius: 999px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    color: var(--text-secondary);
    font-size: 0.73rem;
    font-weight: 720;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .live-pill {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    flex: none;
    padding: 4px 10px;
    border-radius: 999px;
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
    color: var(--on-accent);
    font-size: 0.7rem;
    font-weight: 820;
    letter-spacing: 0.04em;
    font-variant-numeric: tabular-nums;
  }
  .time-pill {
    flex: none;
    padding: 4px 10px;
    border-radius: 999px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    color: var(--text-secondary);
    font-size: 0.72rem;
    font-weight: 680;
    font-variant-numeric: tabular-nums;
  }
  .faceoff {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
    align-items: center;
    gap: 10px;
    padding: 2px 0;
  }
  .club {
    display: grid;
    justify-items: center;
    gap: 8px;
    text-align: center;
    min-width: 0;
  }
  .crest {
    width: 50px;
    height: 50px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    font-size: 0.8rem;
    font-weight: 850;
    letter-spacing: 0.03em;
    color: #ffffff;
    text-shadow: 0 1px 3px rgb(0 0 0 / 0.55);
    box-shadow:
      inset 0 2px 4px rgb(255 255 255 / 0.25),
      0 6px 14px -6px rgb(0 0 0 / 0.5);
  }
  .crest.home {
    background: linear-gradient(145deg, color-mix(in srgb, var(--home) 88%, #ffffff), var(--home));
  }
  .crest.away {
    background: linear-gradient(145deg, color-mix(in srgb, var(--away) 88%, #ffffff), var(--away));
  }
  .club-name {
    font-size: 0.86rem;
    font-weight: 730;
    line-height: 1.25;
    color: var(--text-primary);
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .vs-center {
    display: grid;
    justify-items: center;
    gap: 3px;
  }
  .vs-medallion {
    width: 38px;
    height: 38px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    color: var(--text-secondary);
    font-size: 0.76rem;
    font-weight: 850;
    letter-spacing: 0.06em;
  }
  .scoreline {
    display: flex;
    align-items: baseline;
    gap: 6px;
    font-size: 1.55rem;
    font-weight: 850;
    font-variant-numeric: tabular-nums;
    color: var(--text-primary);
  }
  .scoreline .sep {
    opacity: 0.45;
  }
  .vs-sub {
    font-size: 0.65rem;
    font-weight: 800;
    letter-spacing: 0.08em;
    color: var(--text-secondary);
  }
  .event-banner {
    display: grid;
    gap: 4px;
    padding: 4px 0;
  }
  .event-banner h3 {
    margin: 0;
    font-size: 1.02rem;
    font-weight: 780;
  }
  .event-banner p {
    margin: 0;
    color: var(--text-secondary);
    font-size: 0.82rem;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .card-bottom {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    padding-top: 10px;
    border-top: 1px solid var(--divider);
  }
  .broadcaster {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
    color: var(--text-secondary);
    font-size: 0.76rem;
    font-weight: 640;
  }
  .broadcaster span {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .watch-cta {
    flex: none;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    min-height: 34px;
    padding: 0 14px;
    border: 0;
    border-radius: 999px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    color: var(--text-primary);
    font-size: 0.8rem;
    font-weight: 760;
    text-decoration: none;
    cursor: pointer;
    transition: transform 0.12s ease;
  }
  .watch-cta:active {
    transform: scale(0.96);
  }
  .watch-cta.live {
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
    box-shadow: 0 6px 18px -6px var(--accent-glow);
  }
</style>
