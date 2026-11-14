<script lang="ts">
  import { goto } from '$app/navigation';
  import Sheet from '$lib/components/Sheet.svelte';
  import { imgFor } from '$lib/ui/img';
  import { watchHref } from '$lib/ui/handoff';
  import { downloads } from './manager.svelte';
  import { chunkBytes, chunkDone, chunkSize, formatBytes, formatDuration, formatSpeed, percent } from './math';

  let { open = $bindable(false), id }: { open?: boolean; id: string } = $props();

  const r = $derived(downloads.get(id));
  const s = $derived(downloads.live[id]);
  const total = $derived(r ? r.totalBytes || r.estimatedBytes : 0);
  const pct = $derived(r ? (r.kind === 'adaptive' ? (r.fraction ?? 0) * 100 : percent(r.doneBytes, total)) : 0);
  const spark = $derived(s?.spark ?? []);
  const peak = $derived(Math.max(1, ...spark));
  const path = $derived.by(() => {
    if (!spark.length) return '';
    const w = 300;
    const h = 56;
    const step = w / Math.max(1, spark.length - 1);
    return spark.map((v, i) => `${i ? 'L' : 'M'}${(i * step).toFixed(1)} ${(h - (v / peak) * (h - 4) - 2).toFixed(1)}`).join(' ');
  });
  const activeConns = $derived(r && r.status === 'running' ? r.chunks.filter((c) => !chunkDone(c)).length : 0);
  const statusLabel: Record<string, string> = { queued: 'Queued', running: 'Downloading', paused: 'Paused', completed: 'Completed', failed: 'Failed', canceled: 'Canceled' };
  const ts = (t?: number) => (t ? new Date(t).toLocaleString() : '—');
  const storageLabel = $derived(
    !r
      ? ''
      : r.kind === 'adaptive'
        ? `shaka offline DB (IndexedDB)${r.offlineUri ? ` · ${r.offlineUri}` : ''}`
        : r.storage === 'idb'
          ? `IndexedDB pieces · cinetor-dl-pieces/${r.id}|*`
          : `OPFS · /cinetor-downloads/${encodeURIComponent(r.id).replace(/%/g, '_')}.*.part`
  );
  let retryIn = $state(0);
  $effect(() => {
    const at = s?.autoRetryAt;
    retryIn = at ? Math.max(0, Math.ceil((at - Date.now()) / 1000)) : 0;
  });

  function play() {
    if (!r) return;
    open = false;
    goto(watchHref(r.itemId, r.episodeId));
  }
  async function del() {
    open = false;
    await downloads.remove(id);
  }
</script>

<Sheet bind:open title="Download details" wide>
  {#if !r}
    <p class="muted">This download was removed.</p>
  {:else}
    {@const poster = r.poster ? imgFor(r.poster, 'poster') : null}
    <div class="top">
      {#if poster}<img class="poster" src={poster.src} alt="" width="72" height="108" />{/if}
      <div class="ti">
        <h3>{r.title}</h3>
        {#if r.subtitle}<p class="muted">{r.subtitle}</p>{/if}
        <p class="status s-{r.status}">{statusLabel[r.status]}{retryIn ? ` · auto-retry in ${retryIn}s` : ''}</p>
      </div>
      <div class="pct" aria-label="Progress">{pct.toFixed(pct >= 10 ? 0 : 1)}%</div>
    </div>

    <div class="bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow={Math.round(pct)}><i style:width="{pct}%"></i></div>
    <p class="bytes">{formatBytes(r.doneBytes)} / {total ? formatBytes(total) : '?'}{r.kind === 'adaptive' && r.status !== 'completed' ? ' (total estimated)' : ''}</p>

    <div class="stats">
      <div><span>Speed</span><b>{formatSpeed(s?.speed ?? 0)}</b></div>
      <div><span>Average</span><b>{formatSpeed(s?.avg ?? 0)}</b></div>
      <div><span>ETA</span><b>{r.status === 'running' ? formatDuration(s?.eta) : '—'}</b></div>
      <div><span>Elapsed</span><b>{formatDuration((s?.elapsedMs ?? r.elapsedMs) / 1000)}</b></div>
    </div>

    <figure class="spark" aria-label="Speed, last 60 seconds">
      <svg viewBox="0 0 300 56" preserveAspectRatio="none" aria-hidden="true">
        {#if path}
          <path d="{path} L300 56 L0 56 Z" class="area" />
          <path d={path} class="line" />
        {/if}
      </svg>
      <figcaption>Speed · last 60 s · peak {formatSpeed(peak > 1 ? peak : 0)}</figcaption>
    </figure>

    {#if r.kind === 'progressive'}
      <h4>Connections {r.status === 'running' ? `· ${activeConns} active` : ''} <small>({r.chunks.length || r.connections} × range{r.rangeSupported === false ? ', no Range support' : ''})</small></h4>
      <div class="chunks">
        {#each r.chunks as c, i (i)}
          {@const size = chunkSize(c)}
          <div class="chunk" title="#{i + 1}: {formatBytes(chunkBytes(c))} / {size ? formatBytes(size) : '?'}">
            <i style:width="{size ? Math.min(100, (chunkBytes(c) / size) * 100) : 0}%" class:done={chunkDone(c)}></i>
          </div>
        {:else}
          <p class="muted">Chunks are planned when the download starts.</p>
        {/each}
      </div>
    {:else}
      <h4>Segments</h4>
      <p class="muted">{r.segmentsDone ?? 0} downloaded{r.segmentsTotal ? ` of ≈ ${r.segmentsTotal}` : ''}</p>
    {/if}

    <dl class="info">
      <dt>Quality</dt><dd>{r.tracks.quality}{r.tracks.height ? ` · ${r.tracks.height}p` : ''}</dd>
      {#if r.tracks.codecs || r.tracks.bandwidth}<dt>Codec / bitrate</dt><dd>{r.tracks.codecs || '—'}{r.tracks.bandwidth ? ` · ${(r.tracks.bandwidth / 1e6).toFixed(2)} Mbps` : ''}</dd>{/if}
      <dt>Container</dt><dd>{r.container} · {r.mime}</dd>
      <dt>Audio</dt><dd>{r.tracks.audio ?? 'Default'}</dd>
      <dt>Subtitles</dt><dd>{r.tracks.texts.length ? r.tracks.texts.join(', ') : 'None'}</dd>
      <dt>Size</dt><dd>{total ? formatBytes(total) : '?'} {r.estimatedBytes ? `(estimated ${formatBytes(r.estimatedBytes)})` : ''}</dd>
      <dt>Stored in</dt><dd class="mono">{storageLabel}</dd>
      <dt>Source host</dt><dd class="mono">{r.host || '—'}</dd>
      <dt>Added</dt><dd>{ts(r.createdAt)}</dd>
      <dt>Started</dt><dd>{ts(r.startedAt)}</dd>
      <dt>Finished</dt><dd>{ts(r.finishedAt)}</dd>
      <dt>Retries</dt><dd>{r.retries}</dd>
      <dt>Last error</dt><dd class:errtxt={!!r.lastError}>{r.lastError ?? '—'}</dd>
    </dl>

    <div class="actions">
      {#if r.status === 'completed'}<button class="btn btn-primary" onclick={play}>▶ Play offline</button>{/if}
      {#if r.status === 'running' || r.status === 'queued'}<button class="btn btn-ghost" onclick={() => downloads.pause(id)}>Pause</button>{/if}
      {#if r.status === 'queued'}<button class="btn btn-ghost" onclick={() => downloads.prioritize(id)}>Move to top</button>{/if}
      {#if r.status === 'paused'}<button class="btn btn-primary" onclick={() => downloads.resume(id)}>Resume</button>{/if}
      {#if r.status === 'failed' || r.status === 'canceled'}<button class="btn btn-primary" onclick={() => downloads.retry(id)}>Retry</button>{/if}
      {#if r.status !== 'completed' && r.status !== 'canceled'}<button class="btn btn-ghost" onclick={() => downloads.cancel(id)}>Cancel</button>{/if}
      <button class="btn btn-ghost danger" onclick={del}>Delete</button>
    </div>
  {/if}
</Sheet>

<style>
  .top {
    display: flex;
    gap: 14px;
    align-items: center;
  }
  .poster {
    width: 72px;
    height: 108px;
    object-fit: cover;
    border-radius: 12px;
    flex: none;
  }
  .ti {
    flex: 1;
    min-width: 0;
  }
  h3 {
    margin: 0;
    font-size: 1.05rem;
  }
  .muted {
    margin: 2px 0;
    color: var(--text-secondary);
    font-size: 0.85rem;
  }
  .status {
    margin: 6px 0 0;
    font-weight: 700;
    font-size: 0.85rem;
  }
  .s-running {
    color: var(--accent);
  }
  .s-completed {
    color: var(--success);
  }
  .s-failed,
  .s-canceled {
    color: #ff8a8a;
  }
  .pct {
    font-size: 1.8rem;
    font-weight: 850;
    font-variant-numeric: tabular-nums;
  }
  .bar {
    height: 8px;
    margin-top: 14px;
    border-radius: 99px;
    background: var(--glass-fill);
    overflow: hidden;
  }
  .bar i {
    display: block;
    height: 100%;
    background: linear-gradient(90deg, var(--accent), var(--accent-end));
    transition: width 0.5s linear;
  }
  .bytes {
    margin: 6px 0 0;
    font-size: 0.8rem;
    color: var(--text-secondary);
    font-variant-numeric: tabular-nums;
  }
  .stats {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 8px;
    margin-top: 12px;
  }
  .stats div {
    display: grid;
    padding: 8px 10px;
    border-radius: 12px;
    background: var(--glass-fill);
  }
  .stats span {
    font-size: 0.7rem;
    color: var(--text-tertiary);
    text-transform: uppercase;
    letter-spacing: 0.08em;
  }
  .stats b {
    font-variant-numeric: tabular-nums;
    font-size: 0.9rem;
  }
  @media (max-width: 480px) {
    .stats {
      grid-template-columns: repeat(2, 1fr);
    }
  }
  .spark {
    margin: 12px 0 0;
  }
  .spark svg {
    width: 100%;
    height: 56px;
    display: block;
    border-radius: 10px;
    background: var(--glass-fill);
  }
  .area {
    fill: color-mix(in srgb, var(--accent) 22%, transparent);
  }
  .line {
    fill: none;
    stroke: var(--accent);
    stroke-width: 1.5;
    vector-effect: non-scaling-stroke;
  }
  figcaption {
    font-size: 0.7rem;
    color: var(--text-tertiary);
    margin-top: 4px;
  }
  h4 {
    margin: 14px 0 6px;
    font-size: 0.85rem;
  }
  h4 small {
    color: var(--text-tertiary);
    font-weight: 500;
  }
  .chunks {
    display: grid;
    gap: 4px;
  }
  .chunk {
    height: 6px;
    border-radius: 99px;
    background: var(--glass-fill);
    overflow: hidden;
  }
  .chunk i {
    display: block;
    height: 100%;
    background: var(--accent);
    transition: width 0.5s linear;
  }
  .chunk i.done {
    background: var(--success);
  }
  .info {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 4px 14px;
    margin: 14px 0 0;
    font-size: 0.8rem;
  }
  .info dt {
    color: var(--text-tertiary);
  }
  .info dd {
    margin: 0;
    color: var(--text-secondary);
    overflow-wrap: anywhere;
  }
  .mono {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 0.72rem;
  }
  .errtxt {
    color: #ff8a8a !important;
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 16px;
  }
  .danger {
    color: #ff8a8a;
  }
</style>
