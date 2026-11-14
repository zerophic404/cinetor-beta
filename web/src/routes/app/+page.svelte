<script lang="ts">
  import SpaceView from '$lib/components/SpaceView.svelte';
  import { page } from '$app/state';
</script>

<svelte:head><title>Home · Cinetor</title></svelte:head>

<SpaceView space="HOME" cat={page.url.searchParams.get('cat')} />
