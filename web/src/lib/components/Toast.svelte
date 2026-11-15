<script lang="ts">
  // Toast host: bottom-centre glass messages with one optional action (e.g. "Show downloads").
  // This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
  // scrape or hack anything without Mythbrix's explicit permission.
  import { toasts } from '$lib/ui/toast.svelte';
</script>

{#if toasts.list.length}
  <div class="host" role="status" aria-live="polite">
    {#each toasts.list as t (t.id)}
      <div class="toast glass">
        <span class="txt">{t.text}</span>
        {#if t.action}
          <button
            class="act"
            onclick={() => {
              t.action?.run();
              toasts.dismiss(t.id);
            }}>{t.action.label}</button>
        {/if}
        <button class="x" aria-label="Dismiss" onclick={() => toasts.dismiss(t.id)}>×</button>
      </div>
    {/each}
  </div>
{/if}

<style>
  .host {
    position: fixed;
    left: 50%;
    translate: -50% 0;
    bottom: calc(96px + var(--safe-b, 0px));
    z-index: 70;
    display: grid;
    gap: 8px;
    width: min(94vw, 440px);
    pointer-events: none;
  }
  .toast {
    pointer-events: auto;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 12px;
    border-radius: 16px;
    box-shadow: 0 16px 36px -14px rgb(0 0 0 / 0.8);
    animation: rise 0.22s var(--ease-emphasized, ease-out);
  }
  @keyframes rise {
    from {
      opacity: 0;
      translate: 0 12px;
    }
  }
  .txt {
    flex: 1;
    min-width: 0;
    font-size: 0.9rem;
  }
  .act {
    border: 0;
    background: none;
    color: var(--accent);
    font-weight: 800;
    font-size: 0.85rem;
    cursor: pointer;
    padding: 4px 6px;
  }
  .x {
    border: 0;
    background: none;
    color: var(--text-secondary);
    font-size: 1.1rem;
    line-height: 1;
    cursor: pointer;
  }
  @media (min-width: 900px) {
    .host {
      bottom: calc(24px + var(--safe-b, 0px));
    }
  }
</style>
