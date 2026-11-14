<script lang="ts">
  import SkeletonBox from './SkeletonBox.svelte';
  interface Props {
    lines?: number;
    lineHeight?: string;
    lastWidth?: string;
  }
  let { lines = 3, lineHeight = '0.9em', lastWidth = '60%' }: Props = $props();
</script>

<div class="text" aria-hidden="true">
  {#each Array(lines) as _, i (i)}
    <SkeletonBox height={lineHeight} width={i === lines - 1 && lines > 1 ? lastWidth : '100%'} radius="6px" />
  {/each}
</div>

<style>
  .text {
    display: grid;
    gap: 0.55em;
  }
</style>
