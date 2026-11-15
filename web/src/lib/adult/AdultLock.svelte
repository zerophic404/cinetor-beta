<script lang="ts">
  import type { Snippet } from 'svelte';
  import { base } from '$app/paths';
  import { adult } from './store.svelte';
  import PinPad from './PinPad.svelte';

  let { children }: { children: Snippet } = $props();
</script>

{#if !adult.enabled}
  <section class="lock glass">
    <h1>18+ section is off</h1>
    <p>Turn it on in Settings → Content with an age confirmation and a 4-digit PIN.</p>
    <a class="btn btn-primary" href="{base}/app/settings#content">Open settings</a>
  </section>
{:else if !adult.unlocked}
  <section class="lock glass">
    <PinPad title="18+ · Enter PIN" hint="Required once per session. Locks again after 5 minutes in the background." onSubmit={async (p) => ((await adult.unlock(p)) ? true : 'Wrong PIN')} />
  </section>
{:else}
  {@render children()}
{/if}

<style>
  .lock {
    max-width: 420px;
    margin: 40px auto;
    padding: 26px;
    text-align: center;
    border-radius: 26px;
  }
  .lock p {
    color: var(--text-secondary);
  }
</style>
