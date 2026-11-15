<script lang="ts">
  import type { Item } from '$lib/data/types';
  import { img } from '$lib/data/repo';
  import { detailsHref, watchHref } from '$lib/ui/handoff';
  import { clockLabel, matchMinute, sportsTabs, startsIn, type SportsTab } from './epg';
  import { live } from './live.svelte';

  let { items }: { items: Item[] } = $props();
  let tab = $state<SportsTab>('live');
  const groups = $derived(sportsTabs(items));
  const list = $derived(groups[tab]);
  const TABS: { id: SportsTab; label: string }[] = [
    { id: 'live', label: 'Live now' },
    { id: 'upcoming', label: 'Upcoming' },
    { id: 'results', label: 'Results' }
  ];
</script>

<div class="h-scroll tabs" role="tablist" aria-label="Sports">
  {#each TABS as t (t.id)}
    <button class="chip" role="tab" aria-selected={tab === t.id} class:active={tab === t.id} onclick={() => (tab = t.id)}>
      {#if t.id === 'live'}<span class="dot" aria-hidden="true"></span>{/if}
      {t.label} <small>{groups[t.id].length}</small>
    </button>
  {/each}
</div>

{#if !list.length}
  <p class="empty">No events here right now.</p>
{:else}
  <div class="events">
    {#each list as ev (ev.id)}
      <a class="event glass" href={ev.status === 'LIVE' ? watchHref(ev.id) : detailsHref(ev)} style:--home={ev.homeTeam?.color ?? '#444'} style:--away={ev.awayTeam?.color ?? '#444'}>
        <div class="bg" style:background-image="url({img(ev.backdrop)})" aria-hidden="true"></div>
        <header>
          <span class="league">{ev.league ?? ev.genres[0]}</span>
          {#if ev.status === 'LIVE'}
            <span class="live-badge">LIVE · {matchMinute(ev, live.base, live.now)}</span>
          {:else if ev.status === 'UPCOMING'}
            <span class="when">{clockLabel(live.base + (ev.startOffsetSec ?? 0))} · {startsIn(ev, live.base, live.now)}</span>
          {:else}
            <span class="when">FT</span>
          {/if}
        </header>
        <div class="board" aria-label="Scoreboard">
          <div class="team">
            <span class="crest home">{ev.homeTeam?.short ?? '—'}</span>
            <span class="name">{ev.homeTeam?.name ?? ''}</span>
          </div>
          <div class="score">
            {#if ev.status === 'UPCOMING'}
              <b>vs</b>
            {:else}
              <b>{ev.homeTeam?.score ?? 0}</b><i>:</i><b>{ev.awayTeam?.score ?? 0}</b>
            {/if}
          </div>
          <div class="team">
            <span class="crest away">{ev.awayTeam?.short ?? '—'}</span>
            <span class="name">{ev.awayTeam?.name ?? ''}</span>
          </div>
        </div>
      </a>
    {/each}
  </div>
{/if}

<style>
  .tabs {
    margin-top: 6px;
  }
  .chip small {
    margin-left: 6px;
    opacity: 0.7;
  }
  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #ff2d2d;
    margin-right: 8px;
    animation: pulse 1.4s ease-in-out infinite;
  }
  @keyframes pulse {
    50% { opacity: 0.35; }
  }
  .empty {
    text-align: center;
    color: var(--text-secondary);
    padding: 40px 16px;
  }
  .events {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
    gap: 14px;
    padding: 4px 16px;
  }
  .event {
    position: relative;
    display: grid;
    gap: 12px;
    padding: 14px 16px 18px;
    border-radius: 22px;
    text-decoration: none;
    overflow: hidden;
    isolation: isolate;
  }
  .bg {
    position: absolute;
    inset: 0;
    z-index: -1;
    background-size: cover;
    background-position: center;
    opacity: 0.22;
    filter: saturate(1.2);
  }
  .event::after {
    content: '';
    position: absolute;
    inset: auto 0 0 0;
    height: 4px;
    background: linear-gradient(90deg, var(--home), var(--away));
  }
  header {
    display: flex;
    justify-content: space-between;
    gap: 8px;
    font-size: 0.75rem;
  }
  .league {
    color: var(--text-secondary);
    font-weight: 650;
  }
  .live-badge {
    background: #e50914;
    color: #fff;
    font-weight: 800;
    padding: 2px 8px;
    border-radius: 6px;
    letter-spacing: 0.04em;
  }
  .when {
    color: var(--text-secondary);
  }
  .board {
    display: grid;
    grid-template-columns: 1fr auto 1fr;
    align-items: center;
    gap: 10px;
  }
  .team {
    display: grid;
    justify-items: center;
    gap: 6px;
    text-align: center;
  }
  .crest {
    display: grid;
    place-items: center;
    width: 48px;
    height: 48px;
    border-radius: 50%;
    font-weight: 800;
    font-size: 0.78rem;
    color: #fff;
    text-shadow: 0 1px 2px rgb(0 0 0 / 0.6);
  }
  .crest.home {
    background: var(--home);
  }
  .crest.away {
    background: var(--away);
  }
  .name {
    font-size: 0.8rem;
    font-weight: 650;
  }
  .score {
    display: flex;
    gap: 6px;
    font-size: 1.8rem;
    font-variant-numeric: tabular-nums;
  }
  .score i {
    font-style: normal;
    opacity: 0.5;
  }
</style>
