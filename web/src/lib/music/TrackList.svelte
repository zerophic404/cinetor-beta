<script lang="ts">
  // This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
  // scrape or hack anything without Mythbrix's explicit permission.
  import type { Item } from '$lib/data/types';
  import { img } from '$lib/data/repo';
  import { music } from './player.svelte';
  import { trackOf, type Track } from './core';
  import { qualityShortLabel } from './gateway-music';
  import { fmtDur } from './queue';

  interface Props {
    tracks?: Track[];
    items?: Item[];
    numbered?: boolean;
    art?: boolean;
    selectable?: boolean;
    selectedIds?: readonly string[];
    onToggleSelect?: (id: string) => void;
  }

  let {
    tracks: trackProp,
    items = [],
    numbered = true,
    art = true,
    selectable = false,
    selectedIds = [],
    onToggleSelect
  }: Props = $props();

  const resolvedTracks = $derived(
    trackProp ?? items.map((i) => trackOf(i, img)).filter((t): t is Track => !!t)
  );
  const selectedSet = $derived(new Set(selectedIds));

  function handleRowClick(i: number) {
    const t = resolvedTracks[i];
    if (!t) return;
    if (selectable) {
      onToggleSelect?.(t.id);
      return;
    }
    if (music.current?.id === t.id) {
      music.toggle();
      return;
    }
    music.playQueue(resolvedTracks, i);
  }
</script>

<ol class="tracks">
  {#each resolvedTracks as t, i (t.id)}
    {@const now = music.current?.id === t.id}
    {@const dlStatus = music.downloadStatus(t.id)}
    {@const isChecked = selectedSet.has(t.id)}
    <li class="track-li" class:now class:selected={selectable && isChecked}>
      <button
        type="button"
        class="row"
        class:now
        onclick={() => handleRowClick(i)}
        aria-pressed={selectable ? isChecked : undefined}
        aria-label={selectable
          ? `${isChecked ? 'Deselect' : 'Select'} ${t.title} by ${t.artist}`
          : `${now && music.playing ? 'Pause' : 'Play'} ${t.title} by ${t.artist}`}
      >
        {#if selectable}
          <span class="chk" class:checked={isChecked} aria-hidden="true">
            {#if isChecked}✓{/if}
          </span>
        {:else if numbered}
          <span class="n">
            {#if now && music.loading}
              <span class="spin" aria-hidden="true"></span>
            {:else if now && music.playing}
              <span class="eq" aria-hidden="true"><i></i><i></i><i></i></span>
            {:else}
              {i + 1}
            {/if}
          </span>
        {/if}

        {#if art}
          {#if t.art}
            <img src={t.art} alt="" width="46" height="46" loading="lazy" decoding="async" />
          {:else}
            <span class="art-ph" aria-hidden="true">♪</span>
          {/if}
        {/if}

        <span class="txt">
          <span class="title-line">
            <b>{t.title}</b>
            {#if t.hasFlac !== false}
              <span class="badge">FLAC</span>
            {/if}
          </span>
          <small>{t.artist}{t.album && art ? ` · ${t.album}` : ''}</small>
        </span>

        <span class="d">{t.durationLabel || fmtDur(t.durationSec)}</span>
      </button>

      {#if !selectable}
        <button
          type="button"
          class="dl-btn"
          class:done={dlStatus === 'completed'}
          class:busy={dlStatus === 'resolving' || dlStatus === 'queued' || dlStatus === 'running'}
          onclick={() => void music.downloadTrack(t)}
          aria-label={dlStatus === 'completed'
            ? `${t.title} downloaded`
            : `Download ${t.title} (${qualityShortLabel(music.quality)})`}
          title={dlStatus === 'completed'
            ? 'Downloaded'
            : `Download (${qualityShortLabel(music.quality)})`}
        >
          {#if dlStatus === 'resolving' || dlStatus === 'queued' || dlStatus === 'running'}
            <span class="spin" aria-hidden="true"></span>
          {:else if dlStatus === 'completed'}
            <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
              <path fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
            </svg>
          {:else}
            <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
              <path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M12 4v11m0 0l-4-4m4 4l4-4M5 19h14" />
            </svg>
          {/if}
        </button>
      {/if}
    </li>
  {/each}
</ol>

<style>
  .tracks {
    list-style: none;
    margin: 0;
    padding: 0 16px;
    display: grid;
    gap: 4px;
  }
  .track-li {
    display: flex;
    align-items: center;
    gap: 4px;
    border-radius: 16px;
    padding-right: 4px;
    transition: background calc(var(--dur-short, 140ms) * var(--dur-scale, 1)) var(--ease-standard, ease);
  }
  .track-li:hover {
    background: var(--glass-fill);
  }
  .track-li.now {
    background: color-mix(in srgb, var(--accent) 11%, var(--glass-fill));
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent) 26%, transparent);
  }
  .track-li.selected {
    background: color-mix(in srgb, var(--accent) 14%, var(--glass-fill));
  }
  .row {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px 10px;
    border: 0;
    border-radius: 14px;
    background: none;
    text-align: left;
    cursor: pointer;
  }
  .row.now b {
    color: var(--accent);
  }
  .chk {
    width: 22px;
    height: 22px;
    border-radius: 7px;
    border: 1.5px solid var(--text-tertiary);
    display: grid;
    place-items: center;
    font-size: 0.78rem;
    font-weight: 800;
    flex: none;
  }
  .chk.checked {
    background: var(--accent);
    border-color: var(--accent);
    color: var(--on-accent);
  }
  .n {
    width: 24px;
    text-align: center;
    color: var(--text-secondary);
    font-variant-numeric: tabular-nums;
    font-size: 0.84rem;
    flex: none;
  }
  img,
  .art-ph {
    width: 46px;
    height: 46px;
    border-radius: 10px;
    object-fit: cover;
    flex: none;
    background: var(--surface-elevated);
  }
  .art-ph {
    display: grid;
    place-items: center;
    color: var(--text-tertiary);
    font-size: 1.1rem;
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .txt {
    flex: 1;
    display: grid;
    gap: 2px;
    min-width: 0;
  }
  .title-line {
    display: flex;
    align-items: center;
    gap: 7px;
    min-width: 0;
  }
  b {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    font-weight: 650;
  }
  .badge {
    flex: none;
    padding: 1px 6px;
    border-radius: 5px;
    font-size: 0.62rem;
    font-weight: 800;
    letter-spacing: 0.05em;
    color: var(--accent);
    background: color-mix(in srgb, var(--accent) 14%, transparent);
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent) 34%, transparent);
  }
  small {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    color: var(--text-secondary);
    font-size: 0.78rem;
  }
  .d {
    flex: none;
    color: var(--text-secondary);
    font-size: 0.78rem;
    font-variant-numeric: tabular-nums;
  }
  .dl-btn {
    flex: none;
    width: 38px;
    height: 38px;
    display: grid;
    place-items: center;
    border: 0;
    border-radius: 50%;
    background: none;
    color: var(--text-secondary);
    cursor: pointer;
    transition: background calc(var(--dur-short, 140ms) * var(--dur-scale, 1)), color calc(var(--dur-short, 140ms) * var(--dur-scale, 1));
  }
  .dl-btn:hover,
  .dl-btn:focus-visible {
    background: var(--glass-fill);
    color: var(--text-primary);
  }
  .dl-btn.done {
    color: var(--success, #22c55e);
  }
  .dl-btn.busy {
    color: var(--accent);
  }
  .spin {
    width: 15px;
    height: 15px;
    border-radius: 50%;
    border: 2px solid color-mix(in srgb, var(--accent) 28%, transparent);
    border-top-color: var(--accent);
    animation: spin 0.75s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  .eq {
    display: inline-flex;
    gap: 2px;
    align-items: flex-end;
    height: 14px;
  }
  .eq i {
    width: 3px;
    border-radius: 2px;
    background: var(--accent);
    animation: eq 0.9s ease-in-out infinite;
  }
  .eq i:nth-child(2) {
    animation-delay: 0.2s;
  }
  .eq i:nth-child(3) {
    animation-delay: 0.4s;
  }
  :global([data-tier='low']) .eq i {
    animation: none;
    height: 10px;
  }
  @keyframes eq {
    0%,
    100% {
      height: 4px;
    }
    50% {
      height: 14px;
    }
  }
</style>
