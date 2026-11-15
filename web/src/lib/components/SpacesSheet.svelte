<script lang="ts">
  import { goto } from '$app/navigation';
  import { visibleSpaceEntries, spaceHref } from '$lib/nav';
  import { adult } from '$lib/adult/store.svelte';
  import { base } from '$app/paths';
  import { profiles } from '$lib/profile/store.svelte';
  import { initials } from '$lib/profile/profiles';
  import Icon from './Icon.svelte';
  import Logo from './Logo.svelte';

  let { open = $bindable(false) }: { open?: boolean } = $props();
  let dialog = $state<HTMLDialogElement>();

  $effect(() => {
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    else if (!open && dialog.open) dialog.close();
  });

  function go(href: string) {
    open = false;
    goto(href);
  }
</script>

<dialog
  bind:this={dialog}
  class="sheet glass glass-sheet"
  aria-label="Spaces"
  onclose={() => (open = false)}
  onclick={(e) => e.target === dialog && (open = false)}
>
  <header>
    <Logo size={30} wordmark />
    <button class="x" onclick={() => (open = false)} aria-label="Close">✕</button>
  </header>
  <p class="hint">Isolated spaces have their own tabs and search — they never appear in global search.</p>
  <ul>
    <li>
      <button onclick={() => go(spaceHref('HOME'))}>
        <span class="ic"><Icon name="home" /></span>
        <span><b>Movies & Shows</b><small>Home · Anime · Drama</small></span>
      </button>
    </li>
    {#each visibleSpaceEntries(adult.enabled) as e (e.space)}
      <li>
        <button onclick={() => go(spaceHref(e.space))}>
          <span class="ic"><Icon name={e.icon} /></span>
          <span><b>{e.label}</b><small>{e.blurb}</small></span>
        </button>
      </li>
    {/each}
  </ul>
  <div class="foot">
    <button class="prof" onclick={() => go(`${base}/app/me`)} aria-label="Profiles: {profiles.active.name}">
      <span class="av" style:background={profiles.active.color}>{initials(profiles.active.name)}</span>
      <span>{profiles.active.name}{profiles.active.kids ? ' · Kids' : ''}</span>
    </button>
    <button class="lnk" onclick={() => go(`${base}/app/settings`)}>Settings</button>
    <button class="lnk" onclick={() => go(`${base}/app/about`)}>About</button>
  </div>
</dialog>

<style>
  .sheet {
    margin: auto auto calc(12px + var(--safe-b));
    width: min(520px, calc(100vw - 24px));
    max-height: 80dvh;
    padding: 18px;
    border: 0;
    color: var(--text-primary);
    border-radius: 28px;
  }
  .sheet[open] {
    animation: up calc(var(--dur-long) * var(--dur-scale)) var(--ease-emphasized);
  }
  .sheet::backdrop {
    background: rgb(0 0 0 / 0.55);
  }
  @keyframes up {
    from { transform: translateY(24px); opacity: 0; }
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
  }
  .x {
    border: 0;
    background: var(--glass-fill);
    width: 36px;
    height: 36px;
    border-radius: 99px;
    cursor: pointer;
  }
  .hint {
    color: var(--text-secondary);
    font-size: 0.82rem;
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 8px;
  }
  li button {
    width: 100%;
    display: flex;
    gap: 14px;
    align-items: center;
    text-align: left;
    padding: 12px;
    border: 0;
    border-radius: 18px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    cursor: pointer;
  }
  li button:hover {
    background: color-mix(in srgb, var(--accent) 16%, transparent);
  }
  .ic {
    display: grid;
    place-items: center;
    width: 44px;
    height: 44px;
    border-radius: 14px;
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
  }
  b {
    display: block;
  }
  small {
    color: var(--text-secondary);
  }
  .foot {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 14px;
  }
  .prof {
    flex: 1;
    display: flex;
    align-items: center;
    gap: 10px;
    border: 0;
    background: none;
    cursor: pointer;
    font-weight: 650;
    text-align: left;
  }
  .av {
    width: 34px;
    height: 34px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    font-size: 0.8rem;
    font-weight: 800;
    color: #fff;
  }
  .lnk {
    border: 0;
    background: var(--glass-fill);
    border-radius: 99px;
    padding: 8px 14px;
    cursor: pointer;
    font-weight: 600;
  }
</style>
