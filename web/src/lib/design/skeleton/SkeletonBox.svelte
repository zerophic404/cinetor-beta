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
  class="skeleton {cls}"
  aria-hidden="true"
  style:width
  style:height
  style:border-radius={radius}
  style:aspect-ratio={aspect}
></div>
