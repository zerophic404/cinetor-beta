<script lang="ts">
  import { base } from '$app/paths';
  import Sheet from '$lib/components/Sheet.svelte';
  import { install } from './install.svelte';

  let { label = 'Install app', cls = 'btn btn-ghost' }: { label?: string; cls?: string } = $props();
</script>

{#if install.available}
  <button class={cls} onclick={() => install.install()}>
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="6" y="2.5" width="12" height="19" rx="3" /><path d="M12 7v7m0 0l-3-3m3 3l3-3" /></svg>
    {label}
  </button>
{/if}

<Sheet bind:open={install.iosSheet} title="Add Cinetor to your Home Screen">
  <ol class="steps">
    <li>
      <span class="ill" aria-hidden="true">
        <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#0a84ff" stroke-width="1.8"><path d="M12 15V3.5m0 0L8 7.5m4-4l4 4" /><path d="M7 10.5H5.5v10h13v-10H17" /></svg>
      </span>
      <span>Tap the <b>Share</b> button in Safari's toolbar.</span>
    </li>
    <li>
      <span class="ill" aria-hidden="true">
        <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="4" y="4" width="16" height="16" rx="4" /><path d="M12 8.5v7M8.5 12h7" /></svg>
      </span>
      <span>Scroll and choose <b>Add to Home Screen</b>.</span>
    </li>
    <li>
      <span class="ill logo" aria-hidden="true"><img src="{base}/brand/icon-192.png" alt="" width="34" height="34" /></span>
      <span>Tap <b>Add</b> — Cinetor opens full-screen like a native app, with offline downloads.</span>
    </li>
  </ol>
  <p class="note">Tip: iOS can clear web storage for apps not opened in a while — open Cinetor now and then to keep downloads.</p>
  <div class="arrow" aria-hidden="true">⬇</div>
</Sheet>

<style>
  .steps {
    list-style: none;
    margin: 4px 0 0;
    padding: 0;
    display: grid;
    gap: 10px;
    counter-reset: s;
  }
  .steps li {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 12px;
    border-radius: 16px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .ill {
    flex: none;
    display: grid;
    place-items: center;
    width: 52px;
    height: 52px;
    border-radius: 14px;
    background: rgb(255 255 255 / 0.08);
  }
  .ill.logo img {
    border-radius: 9px;
  }
  .note {
    font-size: 0.78rem;
    color: var(--text-secondary);
  }
  .arrow {
    text-align: center;
    font-size: 1.4rem;
    color: #0a84ff;
    animation: bob 1.2s ease-in-out infinite;
  }
  @keyframes bob {
    50% {
      transform: translateY(6px);
    }
  }
</style>
