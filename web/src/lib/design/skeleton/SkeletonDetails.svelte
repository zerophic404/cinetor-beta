<script lang="ts">
  import SkeletonBox from './SkeletonBox.svelte';
  import SkeletonText from './SkeletonText.svelte';
</script>

<div class="details" aria-hidden="true">
  <SkeletonBox aspect="16 / 9" radius="0" />
  <div class="body">
    <SkeletonBox width="70%" height="28px" />
    <div class="chips">
      {#each Array(4) as _, i (i)}<SkeletonBox width="64px" height="24px" radius="var(--radius-pill)" />{/each}
    </div>
    <div class="btns">
      <SkeletonBox height="48px" radius="var(--radius-pill)" />
      <SkeletonBox height="48px" radius="var(--radius-pill)" />
    </div>
    <SkeletonText lines={4} />
  </div>
</div>

<style>
  .body {
    padding: 16px;
    display: grid;
    gap: 14px;
  }
  .chips {
    display: flex;
    gap: 8px;
  }
  .btns {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px;
  }
</style>
