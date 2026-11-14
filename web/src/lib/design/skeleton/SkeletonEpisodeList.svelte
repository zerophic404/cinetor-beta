<script lang="ts">
  import SkeletonBox from './SkeletonBox.svelte';
  import SkeletonText from './SkeletonText.svelte';
  let { count = 5 }: { count?: number } = $props();
</script>

<ul aria-hidden="true">
  {#each Array(count) as _, i (i)}
    <li>
      <div class="still"><SkeletonBox aspect="16 / 9" radius="var(--radius-md)" /></div>
      <div class="txt"><SkeletonBox width="70%" height="16px" /><SkeletonText lines={2} lineHeight="0.75em" /></div>
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
    grid-template-columns: clamp(120px, 36vw, 200px) 1fr;
    gap: 12px;
    align-items: center;
  }
  .txt {
    display: grid;
    gap: 10px;
  }
</style>
