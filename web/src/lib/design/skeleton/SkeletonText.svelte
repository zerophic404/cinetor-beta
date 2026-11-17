<script lang="ts">
  import SkeletonBox from './SkeletonBox.svelte';
  interface Props {
    lines?: number;
    lineHeight?: string;
    lastWidth?: string;
  }
  let { lines = 3, lineHeight = '0.9em', lastWidth = '60%' }: Props = $props();

  function widthFor(index: number, total: number): string {
    if (total <= 1) return '100%';
    if (index === total - 1) return lastWidth;
    if (index % 2 === 1) return '92%';
    return '100%';
  }
</script>

<div class="text" aria-hidden="true">
  {#each Array(lines) as _, i (i)}
    <SkeletonBox height={lineHeight} width={widthFor(i, lines)} radius="6px" />
  {/each}
</div>

<style>
  .text {
    display: grid;
    gap: 0.55em;
  }
</style>
