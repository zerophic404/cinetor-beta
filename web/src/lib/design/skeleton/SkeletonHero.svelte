<script lang="ts">
  import SkeletonBox from './SkeletonBox.svelte';
  import SkeletonText from './SkeletonText.svelte';
</script>

<div class="hero" aria-hidden="true">
  <div class="wing left"><SkeletonBox height="100%" radius="var(--radius-lg)" /></div>
  <div class="stage">
    <SkeletonBox height="100%" radius="var(--radius-xl)" />
    <div class="scrim"></div>
    <div class="meta">
      <div class="pills">
        <SkeletonBox width="56px" height="22px" radius="var(--radius-pill)" />
        <SkeletonBox width="48px" height="22px" radius="var(--radius-pill)" />
        <SkeletonBox width="64px" height="22px" radius="var(--radius-pill)" />
      </div>
      <SkeletonBox width="62%" height="30px" radius="8px" />
      <div class="syn"><SkeletonText lines={2} lastWidth="72%" /></div>
      <div class="btns">
        <SkeletonBox width="128px" height="44px" radius="var(--radius-pill)" />
        <SkeletonBox width="108px" height="44px" radius="var(--radius-pill)" />
      </div>
    </div>
  </div>
  <div class="wing right"><SkeletonBox height="100%" radius="var(--radius-lg)" /></div>
  <div class="dots">
    <SkeletonBox width="22px" height="7px" radius="var(--radius-pill)" />
    <SkeletonBox width="8px" height="7px" radius="var(--radius-pill)" />
    <SkeletonBox width="8px" height="7px" radius="var(--radius-pill)" />
    <SkeletonBox width="8px" height="7px" radius="var(--radius-pill)" />
  </div>
</div>

<style>
  .hero {
    position: relative;
    margin: 0 16px;
    display: grid;
    gap: 10px;
  }
  .stage {
    position: relative;
    height: clamp(320px, 92vw, 430px);
    width: min(82%, 360px);
    margin: 0 auto;
    border-radius: var(--radius-xl);
    overflow: hidden;
  }
  .wing {
    position: absolute;
    top: 22px;
    height: calc(clamp(320px, 92vw, 430px) - 44px);
    width: 18%;
    opacity: 0.42;
    pointer-events: none;
  }
  .wing.left {
    left: -6px;
  }
  .wing.right {
    right: -6px;
  }
  .scrim {
    position: absolute;
    inset: 40% 0 0;
    background: linear-gradient(180deg, transparent, color-mix(in srgb, var(--bg) 78%, transparent));
    pointer-events: none;
  }
  .meta {
    position: absolute;
    left: 18px;
    right: 18px;
    bottom: 18px;
    display: grid;
    gap: 10px;
    max-width: 520px;
  }
  .pills {
    display: flex;
    gap: 6px;
  }
  .syn {
    display: none;
    width: 82%;
  }
  .btns {
    display: flex;
    gap: 10px;
  }
  .dots {
    display: flex;
    justify-content: center;
    gap: 6px;
  }

  @media (min-width: 768px) {
    .wing {
      display: none;
    }
    .stage {
      width: 100%;
      height: clamp(300px, 44vw, 500px);
    }
    .meta {
      left: 26px;
      right: 26px;
      bottom: 24px;
      gap: 12px;
    }
    .syn {
      display: block;
    }
  }
</style>
