<script lang="ts">
  import { page } from '$app/state';
  import type { NavTab } from '$lib/nav';
  import Icon from './Icon.svelte';
  import Logo from './Logo.svelte';

  interface Props {
    tabs: NavTab[];
    spaceLabel?: string | null;
    onSpaces: () => void;
  }
  let { tabs, spaceLabel = null, onSpaces }: Props = $props();

  function isActive(t: NavTab): boolean {
    const url = page.url;
    const [path, query] = t.href.split('?');
    if (query) {
      const want = new URLSearchParams(query).get('cat');
      const cur = url.searchParams.get('cat');
      // First category is active when no ?cat= is present.
      const first = tabs.find((x) => x.href.includes('?cat='));
      return url.pathname === path && (cur === want || (!cur && first?.id === t.id));
    }
    if (t.id === 'exit') return false;
    return t.exact ? url.pathname === path || url.pathname === path + '/' : url.pathname === path || url.pathname.startsWith(path + '/');
  }
</script>

<nav class="nav glass glass-bar" aria-label="Main">
  <button class="brand" onclick={onSpaces} aria-label="Switch space" aria-haspopup="dialog">
    <Logo size={34} />
    {#if spaceLabel}<span class="space-name">{spaceLabel}</span>{/if}
  </button>
  <ul>
    {#each tabs as t (t.id)}
      {@const active = isActive(t)}
      <li>
        <a href={t.href} aria-current={active ? 'page' : undefined} class:active>
          <span class="ic"><Icon name={t.icon} size={22} /></span>
          <span class="lbl">{t.label}</span>
        </a>
      </li>
    {/each}
  </ul>
</nav>

<style>
  .nav {
    position: fixed;
    z-index: 50;
    left: calc(10px + var(--safe-l));
    right: calc(10px + var(--safe-r));
    bottom: calc(10px + var(--safe-b));
    height: var(--nav-h);
    display: flex;
    align-items: center;
    padding: 0 6px;
    border-radius: 24px;
  }
  .brand {
    display: none;
    border: 0;
    background: none;
    padding: 6px;
    cursor: pointer;
    border-radius: 14px;
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex: 1;
    justify-content: space-around;
  }
  li {
    flex: 1;
    display: flex;
    justify-content: center;
  }
  a {
    position: relative;
    display: grid;
    justify-items: center;
    gap: 2px;
    min-width: 56px;
    padding: 6px 8px;
    border-radius: 16px;
    color: var(--text-secondary);
    text-decoration: none;
    font-size: 0.7rem;
    font-weight: 650;
    transition: color calc(var(--dur-medium) * var(--dur-scale)) var(--ease-standard);
  }
  a .ic {
    display: grid;
    place-items: center;
    width: 52px;
    height: 28px;
    border-radius: 99px;
    transition: background calc(var(--dur-medium) * var(--dur-scale)) var(--ease-emphasized);
  }
  a.active {
    color: var(--text-primary);
  }
  a.active .ic {
    background: linear-gradient(135deg, color-mix(in srgb, var(--accent) 85%, transparent), color-mix(in srgb, var(--accent-end) 75%, transparent));
    box-shadow: 0 6px 18px -6px var(--accent-glow);
  }
  .space-name {
    font-size: 0.65rem;
    font-weight: 700;
    color: var(--text-secondary);
  }

  /* ≥900px: side rail */
  @media (min-width: 900px) {
    .nav {
      left: calc(12px + var(--safe-l));
      right: auto;
      top: calc(12px + var(--safe-t));
      bottom: calc(12px + var(--safe-b));
      width: var(--rail-w);
      height: auto;
      flex-direction: column;
      padding: 14px 0;
      border-radius: 28px;
    }
    .brand {
      display: grid;
      justify-items: center;
      gap: 4px;
      margin-bottom: 18px;
    }
    ul {
      flex-direction: column;
      justify-content: flex-start;
      gap: 6px;
      flex: none;
    }
    li {
      flex: none;
    }
  }
</style>
