<script lang="ts">
  import SkeletonBox from './SkeletonBox.svelte';
  import SkeletonText from './SkeletonText.svelte';
</script>

<div class="hero" aria-hidden="true">
  <SkeletonBox height="100%" radius="var(--radius-xl)" />
  <div class="meta">
    <SkeletonBox width="55%" height="30px" radius="8px" />
    <div class="syn"><SkeletonText lines={2} /></div>
    <div class="btns">
      <SkeletonBox width="120px" height="44px" radius="var(--radius-pill)" />
      <SkeletonBox width="44px" height="44px" radius="var(--radius-pill)" />
    </div>
  </div>
</div>

<style>
  .hero {
    position: relative;
    margin: 0 16px;
    height: clamp(260px, 56vw, 520px);
  }
  .meta {
    position: absolute;
    left: 20px;
    right: 20px;
    bottom: 20px;
    display: grid;
    gap: 12px;
    max-width: 520px;
  }
  .syn {
    width: 80%;
  }
  .btns {
    display: flex;
    gap: 10px;
  }
</style>
