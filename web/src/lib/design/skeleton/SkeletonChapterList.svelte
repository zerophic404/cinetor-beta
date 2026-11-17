<script lang="ts">
  import SkeletonBox from './SkeletonBox.svelte';
  let { count = 6 }: { count?: number } = $props();
</script>

<ul aria-hidden="true">
  {#each Array(count) as _, i (i)}
    <li>
      <SkeletonBox width="44px" height="44px" radius="var(--radius-sm)" />
      <div class="mid">
        <SkeletonBox width="58%" height="15px" radius="5px" />
        <SkeletonBox width="36%" height="11px" radius="4px" />
      </div>
      <SkeletonBox width="56px" height="24px" radius="var(--radius-pill)" />
    </li>
  {/each}
</ul>

<style>
  ul {
    list-style: none;
    margin: 0;
    padding: 0 16px;
    display: grid;
    gap: 10px;
  }
  li {
    display: grid;
    grid-template-columns: 44px 1fr auto;
    align-items: center;
    gap: 12px;
    padding: 10px 12px;
    border-radius: var(--radius-md);
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--divider);
  }
  .mid {
    display: grid;
    gap: 6px;
    min-width: 0;
  }
</style>
