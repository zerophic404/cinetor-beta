<script lang="ts">
  interface Props {
    width?: string;
    height?: string;
    radius?: string;
    aspect?: string;
    class?: string;
  }
  let { width = '100%', height, radius = 'var(--radius-sm)', aspect, class: cls = '' }: Props = $props();
</script>

<div
  class="skeleton sk-box {cls}"
  aria-hidden="true"
  style:width
  style:height
  style:border-radius={radius}
  style:aspect-ratio={aspect}
></div>

<style>
  .sk-box {
    position: relative;
    overflow: hidden;
    background: var(--skeleton-base);
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--glass-stroke) 42%, transparent);
    contain: paint;
  }
  .sk-box::after {
    content: '';
    position: absolute;
    inset: 0;
    pointer-events: none;
    background: linear-gradient(
      100deg,
      transparent 0%,
      color-mix(in srgb, var(--skeleton-highlight) 52%, transparent) 30%,
      var(--skeleton-highlight) 50%,
      color-mix(in srgb, var(--skeleton-highlight) 52%, transparent) 70%,
      transparent 100%
    );
    transform: translate3d(-100%, 0, 0);
    will-change: transform;
    animation: sk-shimmer 1.3s linear infinite;
  }
  @keyframes sk-shimmer {
    from {
      transform: translate3d(-100%, 0, 0);
    }
    to {
      transform: translate3d(100%, 0, 0);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .sk-box::after {
      animation: none;
      transform: none;
      opacity: 0;
      will-change: auto;
    }
  }
  :global([data-motion='reduce']) .sk-box::after {
    animation: none !important;
    transform: none !important;
    opacity: 0;
    will-change: auto;
  }
</style>
