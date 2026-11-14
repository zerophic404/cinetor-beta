<script lang="ts">
  import type { Snippet } from 'svelte';

  let { open = $bindable(false), title, wide = false, children }: { open?: boolean; title: string; wide?: boolean; children: Snippet } = $props();
  let dialog = $state<HTMLDialogElement>();

  $effect(() => {
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    else if (!open && dialog.open) dialog.close();
  });
</script>

<dialog
  bind:this={dialog}
  class="sheet glass glass-sheet"
  class:wide
  aria-label={title}
  onclose={() => (open = false)}
  onclick={(e) => e.target === dialog && (open = false)}
>
  <header>
    <h2>{title}</h2>
    <button class="x" onclick={() => (open = false)} aria-label="Close">✕</button>
  </header>
  {#if open}{@render children()}{/if}
</dialog>

<style>
  .sheet {
    margin: auto auto calc(12px + var(--safe-b));
    width: min(560px, calc(100vw - 24px));
    max-height: 86dvh;
    overflow: auto;
    padding: 16px 18px 20px;
    border: 0;
    color: var(--text-primary);
    border-radius: 28px;
    overscroll-behavior: contain;
  }
  .sheet.wide {
    width: min(720px, calc(100vw - 24px));
  }
  .sheet[open] {
    animation: up calc(var(--dur-long) * var(--dur-scale)) var(--ease-emphasized);
  }
  .sheet::backdrop {
    background: rgb(0 0 0 / 0.6);
  }
  @keyframes up {
    from {
      transform: translateY(24px);
      opacity: 0;
    }
  }
  @media (min-width: 900px) {
    .sheet {
      margin: auto;
    }
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    margin-bottom: 8px;
  }
  h2 {
    margin: 0;
    font-size: 1.1rem;
  }
  .x {
    flex: none;
    border: 0;
    background: var(--glass-fill);
    width: 36px;
    height: 36px;
    border-radius: 99px;
    cursor: pointer;
  }
</style>
