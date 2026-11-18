<script lang="ts" module>
  export interface CastMemberInput {
    id?: string | number;
    name: string;
    character?: string | null;
    role?: string | null;
    photo?: string | null;
    avatar?: string | null;
    profilePath?: string | null;
  }

  export type CastEntry = string | CastMemberInput;
</script>

<script lang="ts">
  import { catalog } from '$lib/data/app-catalog';
  import { DEVELOPER, TELEGRAM_CHANNEL_URL, TELEGRAM_GROUP_URL, THANKS_NAME, THANKS_URL } from '$lib/constants';
  import { VIDEO_SPACES, type Item } from '$lib/data/types';
  import { SkeletonBox } from '$lib/design/skeleton';
  import { imgFor } from '$lib/ui/img';
  import { dedupeItems, safeSpaceError } from '$lib/ui/space-model';
  import Poster from './Poster.svelte';
  import Sheet from './Sheet.svelte';

  interface Props {
    /** Cast members (`string[]` or `{ name, role, photo }[]`). Omit `<Credits />` for landing/about attribution. */
    cast?: readonly CastEntry[] | null;
    studio?: string | null;
    heading?: string;
  }

  interface NormalizedPerson {
    key: string;
    name: string;
    role: string;
    photo: string;
    initials: string;
  }

  interface CastSearchBridge {
    castWorks?: (personName: string) => Promise<Item[]>;
    search?: (personName: string) => Promise<Item[]>;
  }

  const rawProps: Props = $props();
  const isCastSection = $derived('cast' in rawProps || 'studio' in rawProps);

  function initialsOf(name: string): string {
    const parts = name.trim().split(/\s+/).filter(Boolean);
    if (!parts.length) return '?';
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return `${parts[0][0]}${parts[parts.length - 1][0]}`.toUpperCase();
  }

  function normalizeEntry(entry: CastEntry): NormalizedPerson | null {
    if (typeof entry === 'string') {
      const trimmed = entry.trim();
      if (!trimmed) return null;
      const asMatch = /^(.+?)\s+(?:as|—|–)\s+(.+)$/i.exec(trimmed);
      const name = (asMatch ? asMatch[1] : trimmed).trim();
      const role = (asMatch ? asMatch[2] : '').trim();
      if (!name) return null;
      return {
        key: name.toLowerCase(),
        name,
        role,
        photo: '',
        initials: initialsOf(name)
      };
    }
    const name = (entry.name ?? '').trim();
    if (!name) return null;
    const role = (entry.character ?? entry.role ?? '').trim();
    const rawPhoto = (entry.photo ?? entry.avatar ?? entry.profilePath ?? '').trim();
    return {
      key: name.toLowerCase(),
      name,
      role,
      photo: rawPhoto,
      initials: initialsOf(name)
    };
  }

  const people = $derived.by(() => {
    const list = rawProps.cast ?? [];
    const seen = new Set<string>();
    const out: NormalizedPerson[] = [];
    for (const raw of list) {
      const p = normalizeEntry(raw);
      if (!p || seen.has(p.key)) continue;
      seen.add(p.key);
      out.push(p);
    }
    return out;
  });

  const INITIAL_VISIBLE = 8;
  let showAllCast = $state(false);
  let brokenPhotos = $state.raw<Record<string, boolean>>({});

  let sheetOpen = $state(false);
  let selectedPerson = $state.raw<NormalizedPerson | null>(null);
  let works = $state.raw<Item[]>([]);
  let worksBusy = $state(false);
  let worksError = $state<string | null>(null);
  let fetchToken = 0;

  const shownPeople = $derived(showAllCast ? people : people.slice(0, INITIAL_VISIBLE));

  function markPhotoBroken(key: string) {
    if (brokenPhotos[key]) return;
    brokenPhotos = { ...brokenPhotos, [key]: true };
  }

  async function fetchWorksFor(person: NormalizedPerson) {
    const token = ++fetchToken;
    worksBusy = true;
    worksError = null;
    works = [];
    const cat = catalog as unknown as CastSearchBridge;
    try {
      let hits: Item[];
      if (typeof cat.castWorks === 'function') {
        const res = await cat.castWorks(person.name);
        hits = Array.isArray(res) ? res : [];
      } else if (typeof cat.search === 'function') {
        const res = await cat.search(person.name);
        hits = Array.isArray(res) ? res : [];
      } else {
        const [remote, localPool] = await Promise.all([
          catalog.remoteSearch(person.name).catch(() => [] as Item[]),
          catalog.pool(VIDEO_SPACES).catch(() => [] as Item[])
        ]);
        const needle = person.name.toLowerCase();
        const localHits = localPool.filter(
          (it) =>
            it.cast?.some((c) => c.toLowerCase().includes(needle)) ||
            it.title.toLowerCase().includes(needle)
        );
        hits = [...remote, ...localHits];
      }
      if (token !== fetchToken) return;
      works = dedupeItems(hits);
    } catch (e: unknown) {
      if (token !== fetchToken) return;
      worksError = safeSpaceError(e instanceof Error ? e.message : String(e));
    } finally {
      if (token === fetchToken) worksBusy = false;
    }
  }

  function openCast(person: NormalizedPerson) {
    selectedPerson = person;
    sheetOpen = true;
    void fetchWorksFor(person);
  }

  function handleFilmographyClick(e: MouseEvent) {
    if (e.target instanceof Element && e.target.closest('a[href]')) {
      sheetOpen = false;
    }
  }
</script>

{#if isCastSection}
  {#if people.length > 0 || rawProps.studio}
    <div class="cast-wrap">
      {#if rawProps.heading || rawProps.studio || people.length > INITIAL_VISIBLE}
        <div class="cast-head">
          {#if rawProps.heading}
            <span class="accent-bar" aria-hidden="true"></span>
            <h2 class="section-title cast-title">{rawProps.heading}</h2>
          {/if}
          {#if rawProps.studio}
            <span class="studio-pill">{rawProps.studio}</span>
          {/if}
          {#if people.length > INITIAL_VISIBLE}
            <button
              type="button"
              class="toggle-all"
              onclick={() => (showAllCast = !showAllCast)}
            >
              {showAllCast ? 'Show less' : `See all (${people.length})`}
            </button>
          {/if}
        </div>
      {/if}

      {#if people.length > 0}
        <div class="cast-grid">
          {#each shownPeople as person (person.key)}
            {@const hasPhoto = Boolean(person.photo && !brokenPhotos[person.key])}
            {@const avatarAttrs = hasPhoto ? imgFor(person.photo, 'avatar') : null}
            <button
              type="button"
              class="cast-card glass"
              onclick={() => openCast(person)}
              aria-label="View titles with {person.name}"
            >
              <span class="avatar" aria-hidden="true">
                {#if avatarAttrs?.src}
                  <img
                    referrerpolicy="no-referrer"
                    src={avatarAttrs.src}
                    srcset={avatarAttrs.srcset}
                    sizes={avatarAttrs.sizes}
                    alt=""
                    loading="lazy"
                    decoding="async"
                    width="52"
                    height="52"
                    onerror={() => markPhotoBroken(person.key)}
                  />
                {:else}
                  <span class="initials">{person.initials}</span>
                {/if}
              </span>
              <span class="person-meta">
                <b class="person-name">{person.name}</b>
                <small class="person-role">{person.role || 'View filmography'}</small>
              </span>
              <svg class="chev" width="15" height="15" viewBox="0 0 24 24" aria-hidden="true">
                <path
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2.1"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  d="M9 6l6 6-6 6"
                />
              </svg>
            </button>
          {/each}
        </div>
      {/if}
    </div>

    <Sheet bind:open={sheetOpen} title={selectedPerson?.name ?? 'Filmography'} wide>
      {#if selectedPerson}
        {@const heroPhoto = Boolean(selectedPerson.photo && !brokenPhotos[selectedPerson.key])}
        {@const heroAvatar = heroPhoto ? imgFor(selectedPerson.photo, 'avatar') : null}
        <div class="person-sheet">
          <div class="person-banner glass">
            <span class="avatar lg" aria-hidden="true">
              {#if heroAvatar?.src}
                <img
                  referrerpolicy="no-referrer"
                  src={heroAvatar.src}
                  srcset={heroAvatar.srcset}
                  sizes="72px"
                  alt=""
                  decoding="async"
                  width="68"
                  height="68"
                  onerror={() => selectedPerson && markPhotoBroken(selectedPerson.key)}
                />
              {:else}
                <span class="initials">{selectedPerson.initials}</span>
              {/if}
            </span>
            <div class="banner-copy">
              <h3>{selectedPerson.name}</h3>
              <p>
                {#if selectedPerson.role}
                  {selectedPerson.role} ·
                {/if}
                {#if worksBusy}
                  Searching catalog…
                {:else}
                  {works.length} {works.length === 1 ? 'playable title' : 'playable titles'}
                {/if}
              </p>
            </div>
          </div>

          {#if worksBusy}
            <div class="works-grid" aria-busy="true" aria-label="Loading filmography">
              {#each Array(6) as _, i (i)}
                <SkeletonBox aspect="2 / 3" radius="var(--radius-card)" />
              {/each}
            </div>
          {:else if worksError}
            <div class="works-empty" role="alert">
              <p>{worksError}</p>
              <button
                type="button"
                class="btn btn-ghost"
                onclick={() => selectedPerson && void fetchWorksFor(selectedPerson)}
              >
                Retry
              </button>
            </div>
          {:else if works.length === 0}
            <div class="works-empty">
              <p>No playable movies or series found for {selectedPerson.name} right now.</p>
            </div>
          {:else}
            <div class="works-grid" onclickcapture={handleFilmographyClick} role="presentation">
              {#each works as work (work.id)}
                <Poster item={work} />
              {/each}
            </div>
          {/if}
        </div>
      {/if}
    </Sheet>
  {/if}
{:else}
  <div class="credits">
    <p class="dev">Developed By {DEVELOPER}</p>
    <p>Thanks to <a href={THANKS_URL} target="_blank" rel="noopener noreferrer">{THANKS_NAME}</a></p>
    <p class="links">
      <a href={TELEGRAM_CHANNEL_URL} target="_blank" rel="noopener noreferrer">Telegram Channel</a>
      <span aria-hidden="true">·</span>
      <a href={TELEGRAM_GROUP_URL} target="_blank" rel="noopener noreferrer">Telegram Group</a>
    </p>
    <p class="tmdb">
      <a href="https://www.themoviedb.org/" target="_blank" rel="noopener noreferrer" aria-label="TMDB">
        <svg width="64" height="12" viewBox="0 0 120 22" aria-hidden="true">
          <rect width="120" height="22" rx="11" fill="#01b4e4" />
          <text x="60" y="15.5" text-anchor="middle" font-size="13" font-weight="800" font-family="system-ui, sans-serif" fill="#0d253f">TMDB</text>
        </svg>
      </a>
      This product uses the TMDB API but is not endorsed or certified by TMDB.
    </p>
  </div>
{/if}

<style>
  /* ==========================================================================
     Interactive Cast Cards & Cast Filmography Sheet
     ========================================================================== */
  .cast-wrap {
    margin-top: 4px;
  }
  .cast-head {
    display: flex;
    align-items: center;
    gap: 9px;
    margin: 0 16px 8px;
  }
  .toggle-all {
    margin-left: auto;
    padding: 4px 10px;
    border: 0;
    border-radius: var(--radius-pill);
    background: none;
    color: var(--accent);
    font-size: 0.82rem;
    font-weight: 700;
    cursor: pointer;
  }
  .accent-bar {
    flex: none;
    width: 3px;
    height: 16px;
    border-radius: 2px;
    background: var(--accent);
    box-shadow: 0 0 10px color-mix(in srgb, var(--accent) 50%, transparent);
  }
  .cast-title {
    flex: 1;
    min-width: 0;
    margin: 0;
    font-size: 1.05rem;
    font-weight: 750;
    color: var(--text-primary);
  }
  .studio-pill {
    padding: 3px 10px;
    border-radius: var(--radius-pill);
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    color: var(--text-secondary);
    font-size: 0.74rem;
    font-weight: 650;
  }

  .cast-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(clamp(196px, 44vw, 248px), 1fr));
    gap: 10px;
    padding: 0 16px;
  }
  .cast-card {
    display: flex;
    align-items: center;
    gap: 12px;
    width: 100%;
    padding: 9px 12px 9px 9px;
    border: 0;
    border-radius: 16px;
    text-align: left;
    cursor: pointer;
    color: var(--text-primary);
    transition:
      transform calc(var(--dur-short) * var(--dur-scale)) var(--ease-emphasized),
      box-shadow calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard),
      background calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard);
  }
  .cast-card:hover {
    transform: translateY(-2px);
    background: color-mix(in srgb, var(--tint) 8%, var(--glass-fill));
  }
  .cast-card:focus-visible {
    outline: none;
    box-shadow: 0 0 0 2px var(--focus-ring), var(--glass-shadow);
  }
  .cast-card:active {
    transform: scale(0.97);
  }

  .avatar {
    flex: none;
    width: 48px;
    height: 48px;
    border-radius: 50%;
    overflow: hidden;
    display: grid;
    place-items: center;
    background: linear-gradient(145deg, color-mix(in srgb, var(--accent) 28%, var(--surface-elevated)), var(--surface));
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .avatar.lg {
    width: 64px;
    height: 64px;
  }
  .avatar img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
  .initials {
    font-size: 0.88rem;
    font-weight: 800;
    letter-spacing: 0.04em;
    color: var(--text-primary);
  }
  .avatar.lg .initials {
    font-size: 1.15rem;
  }

  .person-meta {
    flex: 1;
    min-width: 0;
    display: grid;
    gap: 2px;
  }
  .person-name {
    font-size: 0.88rem;
    font-weight: 700;
    line-height: 1.25;
    color: var(--text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .person-role {
    font-size: 0.74rem;
    color: var(--text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .chev {
    flex: none;
    color: var(--text-tertiary);
    transition: transform calc(var(--dur-short) * var(--dur-scale)) var(--ease-emphasized);
  }
  .cast-card:hover .chev {
    color: var(--accent);
    transform: translateX(2px);
  }

  .person-sheet {
    display: grid;
    gap: 16px;
    padding-top: 4px;
  }
  .person-banner {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 12px 14px;
    border-radius: 18px;
  }
  .banner-copy {
    min-width: 0;
  }
  .banner-copy h3 {
    margin: 0;
    font-size: 1.12rem;
    font-weight: 800;
    color: var(--text-primary);
  }
  .banner-copy p {
    margin: 3px 0 0;
    font-size: 0.82rem;
    color: var(--text-secondary);
  }

  .works-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(clamp(108px, 26vw, 148px), 1fr));
    gap: 18px 12px;
    align-items: start;
  }
  .works-grid :global(.card) {
    width: auto;
  }
  .works-empty {
    padding: 36px 16px;
    text-align: center;
    color: var(--text-secondary);
    display: grid;
    justify-items: center;
    gap: 12px;
  }
  .works-empty p {
    margin: 0;
  }

  /* ==========================================================================
     Landing / Studio Attribution Footer Mode (<Credits /> with no cast prop)
     ========================================================================== */
  .credits {
    text-align: center;
    color: var(--text-secondary);
    font-size: 0.9rem;
  }
  .credits p {
    margin: 4px 0;
  }
  .dev {
    color: var(--text-primary);
    font-weight: 700;
  }
  .credits a {
    color: var(--text-primary);
    text-decoration-color: var(--accent);
    text-underline-offset: 3px;
  }
  .credits a:hover {
    color: var(--accent);
  }
  .links {
    display: flex;
    justify-content: center;
    gap: 10px;
  }
  .tmdb {
    margin-top: 12px;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: center;
    gap: 8px;
    font-size: 0.75rem;
    color: var(--text-tertiary);
  }
  .tmdb a {
    display: inline-flex;
  }
</style>
