<script lang="ts">
  import SkeletonBox from './SkeletonBox.svelte';
  import SkeletonText from './SkeletonText.svelte';
</script>

<div class="details" aria-hidden="true">
  <div class="backdrop">
    <SkeletonBox aspect="16 / 9" radius="0" />
    <div class="fade"></div>
  </div>
  <div class="body">
    <SkeletonBox width="68%" height="28px" radius="8px" />
    <div class="chips">
      <SkeletonBox width="62px" height="24px" radius="var(--radius-pill)" />
      <SkeletonBox width="52px" height="24px" radius="var(--radius-pill)" />
      <SkeletonBox width="68px" height="24px" radius="var(--radius-pill)" />
      <SkeletonBox width="76px" height="24px" radius="var(--radius-pill)" />
    </div>
    <div class="btns">
      <SkeletonBox height="46px" radius="var(--radius-pill)" />
      <SkeletonBox height="46px" radius="var(--radius-pill)" />
    </div>
    <SkeletonText lines={3} lastWidth="64%" />
  </div>
</div>

<style>
  .backdrop {
    position: relative;
    max-height: 420px;
    overflow: hidden;
  }
  .fade {
    position: absolute;
    inset: 50% 0 0;
    background: linear-gradient(180deg, transparent, var(--bg));
    pointer-events: none;
  }
  .body {
    padding: 16px;
    display: grid;
    gap: 14px;
    max-width: 860px;
  }
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
  .btns {
    display: grid;
    grid-template-columns: 1.2fr 1fr;
    gap: 10px;
    max-width: 420px;
  }
</style>
