<script lang="ts">
  import SkeletonBox from './SkeletonBox.svelte';
  import SkeletonText from './SkeletonText.svelte';
  let { count = 5 }: { count?: number } = $props();
</script>

<ul aria-hidden="true">
  {#each Array(count) as _, i (i)}
    <li>
      <div class="still"><SkeletonBox aspect="16 / 9" radius="var(--radius-md)" /></div>
      <div class="txt">
        <SkeletonBox width="68%" height="16px" radius="6px" />
        <SkeletonText lines={2} lineHeight="0.75em" lastWidth="54%" />
      </div>
      <SkeletonBox width="36px" height="36px" radius="50%" />
    </li>
  {/each}
</ul>

<style>
  ul {
    list-style: none;
    margin: 0;
    padding: 0 16px;
    display: grid;
    gap: 14px;
  }
  li {
    display: grid;
    grid-template-columns: clamp(116px, 34vw, 196px) 1fr auto;
    gap: 12px;
    align-items: center;
  }
  .txt {
    display: grid;
    gap: 9px;
    min-width: 0;
  }
</style>
