// App-wide catalog: TMDB (Home/Anime/Drama) when a token was baked in at build time, mock otherwise.
// Isolated spaces (TV/Music/Comics/Adult) are always mock (enforced by Catalog).
import { Catalog } from './source';
import { ColorCache } from './color';
import { idbCache } from './idb';
import { colorSampleUrl } from './images';
import { repo } from './repo';
import { TmdbCatalogSource, TmdbClient } from './tmdb';

const token: string = typeof __TMDB_TOKEN__ !== 'undefined' ? __TMDB_TOKEN__ : '';
export const persist = idbCache();

/** Dominant colors (64px canvas sample of a small thumbnail), memory LRU + IndexedDB. */
export const colors = new ColorCache({ persist });

export const tmdbSource = token
  ? new TmdbCatalogSource({
      client: new TmdbClient({ token }),
      persist,
      colorOf: (poster) => colors.peek(colorSampleUrl(poster, repo.asset.bind(repo)))
    })
  : null;

export const catalog = new Catalog({ mock: repo, tmdb: tmdbSource });
