<script lang="ts">
  import SkeletonBox from './SkeletonBox.svelte';
  let { count = 6 }: { count?: number } = $props();
</script>

<ul aria-hidden="true">
  {#each Array(count) as _, i (i)}
    <li>
      <SkeletonBox width="40px" height="40px" radius="var(--radius-sm)" />
      <SkeletonBox width="55%" height="16px" />
      <SkeletonBox width="56px" height="14px" />
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
    grid-template-columns: 40px 1fr auto;
    align-items: center;
    gap: 12px;
    padding: 10px;
    border-radius: var(--radius-md);
    background: var(--glass-fill);
  }
</style>
