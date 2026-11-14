<script lang="ts">
  import type { Item } from '$lib/data/types';
  import { img } from '$lib/data/repo';
  import { watchHref } from '$lib/ui/handoff';
  import { GUIDE_WINDOW_SEC, clockLabel, guideBlocks, nowLinePosition, timeSlots, windowStartFor } from './epg';

  interface Props {
    channels: Item[];
    base: number;
    now: number;
  }
  let { channels, base, now }: Props = $props();

  const PX_PER_SEC = 240 / 1800; // 240px per half hour
  const winStart = $derived(windowStartFor(now));
  const slots = $derived(timeSlots(winStart));
  const nowX = $derived(nowLinePosition(now, winStart, PX_PER_SEC));
  const width = GUIDE_WINDOW_SEC * PX_PER_SEC;
  let scroller = $state<HTMLDivElement>();

  // Keep "now" in view on first paint.
  $effect(() => {
    if (scroller && nowX !== null && scroller.scrollLeft === 0) scroller.scrollLeft = Math.max(0, nowX - 80);
  });
</script>

<section class="guide glass" aria-label="TV guide">
  <div class="scroll" bind:this={scroller}>
    <div class="inner" style:width="{width + 96}px">
      <div class="ruler">
        <span class="corner">Now {clockLabel(now)}</span>
        <div class="ticks" style:width="{width}px">
          {#each slots as t (t)}
            <span class="tick" style:left="{(t - winStart) * PX_PER_SEC}px">{clockLabel(t)}</span>
          {/each}
        </div>
      </div>
      {#each channels as ch (ch.id)}
        <div class="line">
          <a class="chan" href={watchHref(ch.id)} title={ch.title}>
            {#if ch.logo}<img src={img(ch.logo)} alt="" loading="lazy" width="40" height="40" />{/if}
            <span>{ch.channelNumber}</span>
          </a>
          <div class="progs" style:width="{width}px">
            {#each guideBlocks(ch.programs, base, winStart, PX_PER_SEC, now) as b (b.program.id)}
              <a
                class="prog"
                class:live={b.live}
               
                href={watchHref(ch.id)}
                style:left="{b.left}px"
                style:width="{b.width - 4}px"
                title="{b.program.title} · {clockLabel(b.start)}–{clockLabel(b.end)}"
              >
                <b>{b.program.title}</b>
                <small>{clockLabel(b.start)}–{clockLabel(b.end)}</small>
              </a>
            {/each}
          </div>
        </div>
      {/each}
      {#if nowX !== null}
        <div class="now-line" style:left="{96 + nowX}px" aria-hidden="true"><i></i></div>
      {/if}
    </div>
  </div>
</section>

<style>
  .guide {
    margin: 0 16px;
    padding: 8px 0;
    border-radius: var(--radius-lg, 20px);
    overflow: hidden;
  }
  .scroll {
    overflow-x: auto;
    overscroll-behavior-x: contain;
    scrollbar-width: thin;
  }
  .inner {
    position: relative;
  }
  .ruler,
  .line {
    display: flex;
    align-items: stretch;
  }
  .corner,
  .chan {
    position: sticky;
    left: 0;
    z-index: 2;
    flex: none;
    width: 96px;
    background: color-mix(in srgb, var(--bg, #000) 88%, transparent);
  }
  .corner {
    font-size: 0.72rem;
    font-weight: 700;
    color: var(--accent);
    padding: 6px 10px;
  }
  .ticks,
  .progs {
    position: relative;
    flex: none;
  }
  .ticks {
    height: 28px;
  }
  .tick {
    position: absolute;
    top: 6px;
    font-size: 0.72rem;
    color: var(--text-secondary);
    padding-left: 6px;
    border-left: 1px solid var(--glass-stroke);
  }
  .line {
    height: 58px;
    margin-top: 6px;
  }
  .chan {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 0 8px;
    text-decoration: none;
    font-weight: 700;
    font-size: 0.8rem;
  }
  .chan img {
    width: 40px;
    height: 40px;
    border-radius: 10px;
    object-fit: cover;
  }
  .prog {
    position: absolute;
    top: 0;
    bottom: 0;
    display: grid;
    align-content: center;
    padding: 0 10px;
    border-radius: 12px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    text-decoration: none;
    overflow: hidden;
    white-space: nowrap;
  }
  .prog b {
    font-size: 0.82rem;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .prog small {
    font-size: 0.7rem;
    color: var(--text-secondary);
  }
  .prog.live {
    background: color-mix(in srgb, var(--accent) 22%, var(--glass-fill));
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent) 60%, transparent);
  }
  .prog:hover,
  .prog:focus-visible {
    background: color-mix(in srgb, var(--accent) 34%, var(--glass-fill));
  }
  .now-line {
    position: absolute;
    top: 0;
    bottom: 0;
    width: 2px;
    background: var(--accent);
    box-shadow: 0 0 10px var(--accent-glow);
    z-index: 1;
    pointer-events: none;
  }
  .now-line i {
    position: absolute;
    top: 2px;
    left: -4px;
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: var(--accent);
  }
</style>
