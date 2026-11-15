<script lang="ts">
  // Delete flow (docs/requirements-hardening.md §4): "Remove from list, or delete the file too?" with
  // three explicit buttons — Cancel / Remove entry / Delete file. "Delete file" really removes the
  // bytes (OPFS or IndexedDB pieces on the web, the real file on desktop).
  //
  // This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
  // scrape or hack anything without Mythbrix's explicit permission.
  import Sheet from '$lib/components/Sheet.svelte';
  import { downloads } from './manager.svelte';
  import { toasts } from '$lib/ui/toast.svelte';

  let {
    open = $bindable(false),
    id,
    ondeleted
  }: {
    open?: boolean;
    id: string;
    ondeleted?: () => void;
  } = $props();

  const r = $derived(downloads.get(id));
  /** Desktop native download → the file lives in the user's folder and can be kept. */
  const external = $derived(downloads.hasExternalFile(id));
  let busy = $state(false);

  async function go(deleteFile: boolean) {
    busy = true;
    try {
      await downloads.remove(id, { deleteFile });
      open = false;
      toasts.show(deleteFile ? 'Download deleted' : 'Removed from the list');
      ondeleted?.();
    } finally {
      busy = false;
    }
  }
</script>

<Sheet bind:open title="Delete this download?">
  {#if !r}
    <p class="muted">This download was already removed.</p>
    <div class="row"><button class="btn btn-ghost" onclick={() => (open = false)}>Close</button></div>
  {:else}
    <p class="q"><b>{r.title}</b>{#if r.subtitle}<span class="muted"> · {r.subtitle}</span>{/if}</p>
    <p class="muted">
      {#if external}
        Remove it from the list, or delete the file too? The file is in your download folder.
      {:else}
        Remove it from the list, or delete the file too? Downloaded videos are kept in private app storage; deleting frees that space immediately. A copy you saved elsewhere is never touched.
      {/if}
    </p>
    <div class="row">
      <button class="btn btn-ghost" disabled={busy} onclick={() => (open = false)}>Cancel</button>
      <button class="btn btn-ghost" disabled={busy} onclick={() => go(false)}>Remove entry</button>
      <button class="btn btn-ghost danger" disabled={busy} onclick={() => go(true)}>Delete file</button>
    </div>
    {#if !external}
      <p class="fine">On the web both options free the stored video — only the desktop app can keep the file and drop just the list entry.</p>
    {/if}
  {/if}
</Sheet>

<style>
  .q {
    margin: 0 0 6px;
  }
  .muted {
    color: var(--text-secondary);
    font-size: 0.88rem;
    margin: 0;
  }
  .fine {
    margin: 10px 0 0;
    font-size: 0.75rem;
    color: var(--text-secondary);
    opacity: 0.8;
  }
  .row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 16px;
  }
  .row .btn {
    flex: 1 1 auto;
  }
  .danger {
    color: #ff8a8a;
  }
</style>
