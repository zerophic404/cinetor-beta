<script lang="ts">
  import type { Item } from '$lib/data/types';
  import { img } from '$lib/data/repo';
  import { music } from './player.svelte';
  import { trackOf, type Track } from './core';
  import { fmtDur } from './queue';

  interface Props {
    items: Item[];
    numbered?: boolean;
    /** Show artwork thumbnails (off on album pages). */
    art?: boolean;
  }
  let { items, numbered = true, art = true }: Props = $props();

  const tracks = $derived(items.map((i) => trackOf(i, img)).filter((t): t is Track => !!t));

  function play(i: number) {
    const t = tracks[i];
    if (music.current?.id === t.id) {
      music.toggle();
      return;
    }
    music.playQueue(tracks, i);
  }
</script>

<ol class="tracks">
  {#each tracks as t, i (t.id)}
    {@const now = music.current?.id === t.id}
    <li>
      <button class="row" class:now onclick={() => play(i)} aria-label="{now && music.playing ? 'Pause' : 'Play'} {t.title} by {t.artist}">
        {#if numbered}
          <span class="n">
            {#if now && music.playing}<span class="eq" aria-hidden="true"><i></i><i></i><i></i></span>{:else}{i + 1}{/if}
          </span>
        {/if}
        {#if art}<img src={t.art} alt="" width="44" height="44" loading="lazy" />{/if}
        <span class="txt"><b>{t.title}</b><small>{t.artist}{t.album && art ? ` · ${t.album}` : ''}</small></span>
        <span class="d">{fmtDur(t.durationSec)}</span>
      </button>
    </li>
  {/each}
</ol>

<style>
  .tracks {
    list-style: none;
    margin: 0;
    padding: 0 16px;
    display: grid;
    gap: 2px;
  }
  .row {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px;
    border: 0;
    border-radius: 14px;
    background: none;
    text-align: left;
    cursor: pointer;
  }
  .row:hover,
  .row:focus-visible {
    background: var(--glass-fill);
  }
  .row.now b {
    color: var(--accent);
  }
  .n {
    width: 22px;
    text-align: center;
    color: var(--text-secondary);
    font-variant-numeric: tabular-nums;
    font-size: 0.85rem;
  }
  img {
    width: 44px;
    height: 44px;
    border-radius: 8px;
    object-fit: cover;
  }
  .txt {
    flex: 1;
    display: grid;
    min-width: 0;
  }
  b,
  small {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  small,
  .d {
    color: var(--text-secondary);
    font-size: 0.78rem;
  }
  .eq {
    display: inline-flex;
    gap: 2px;
    align-items: flex-end;
    height: 14px;
  }
  .eq i {
    width: 3px;
    background: var(--accent);
    animation: eq 0.9s ease-in-out infinite;
  }
  .eq i:nth-child(2) {
    animation-delay: 0.2s;
  }
  .eq i:nth-child(3) {
    animation-delay: 0.4s;
  }
  @keyframes eq {
    0%, 100% { height: 4px; }
    50% { height: 14px; }
  }
</style>
