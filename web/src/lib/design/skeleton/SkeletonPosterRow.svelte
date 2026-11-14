<script lang="ts">
  import SkeletonBox from './SkeletonBox.svelte';
  interface Props {
    count?: number;
    landscape?: boolean;
    title?: boolean;
  }
  let { count = 7, landscape = false, title = true }: Props = $props();
</script>

<div class="row" aria-hidden="true">
  {#if title}<div class="t"><SkeletonBox width="160px" height="18px" radius="6px" /></div>{/if}
  <div class="h-scroll">
    {#each Array(count) as _, i (i)}
      <div class="card" class:landscape>
        <SkeletonBox aspect={landscape ? '16 / 9' : '2 / 3'} radius="var(--radius-card)" />
      </div>
    {/each}
  </div>
</div>

<style>
  .t {
    margin: 18px 16px 10px;
  }
  .h-scroll {
    overflow: hidden;
  }
  .card {
    flex: none;
    width: clamp(108px, 28vw, 168px);
  }
  .card.landscape {
    width: clamp(220px, 60vw, 320px);
  }
</style>
