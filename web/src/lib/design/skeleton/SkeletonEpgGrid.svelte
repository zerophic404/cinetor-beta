<script lang="ts">
  import SkeletonBox from './SkeletonBox.svelte';
  let { channels = 6, slots = 5 }: { channels?: number; slots?: number } = $props();
</script>

<div class="epg" aria-hidden="true">
  {#each Array(channels) as _, r (r)}
    <div class="line">
      <SkeletonBox width="64px" height="48px" radius="var(--radius-md)" />
      <div class="slots">
        {#each Array(slots) as _, c (c)}
          <SkeletonBox width={`${110 + ((r * 37 + c * 53) % 90)}px`} height="48px" radius="var(--radius-sm)" />
        {/each}
      </div>
    </div>
  {/each}
</div>

<style>
  .epg {
    display: grid;
    gap: 8px;
    padding: 0 16px;
    overflow: hidden;
  }
  .line {
    display: flex;
    gap: 8px;
  }
  .slots {
    display: flex;
    gap: 6px;
    flex: none;
  }
  .slots :global(.skeleton) {
    flex: none;
  }
</style>
