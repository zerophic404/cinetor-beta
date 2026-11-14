// About page copy + links (single source; tested for exact wording).
import { DEVELOPER, TELEGRAM_CHANNEL_URL, TELEGRAM_GROUP_URL, THANKS_NAME, THANKS_URL, TMDB_URL } from './constants';

export const APP_VERSION = '0.4.0';

export interface ExtLink {
  label: string;
  href: string;
  target: '_blank';
  rel: 'noopener';
}

const ext = (label: string, href: string): ExtLink => ({ label, href, target: '_blank', rel: 'noopener' });

export const ABOUT = {
  name: 'Cinetor',
  version: APP_VERSION,
  community: [ext('Telegram Channel', TELEGRAM_CHANNEL_URL), ext('Telegram Group', TELEGRAM_GROUP_URL)],
  developedBy: `Developed By ${DEVELOPER}`,
  /** "Thanks to " + linked name — only the name is a link. */
  thanksPrefix: 'Thanks to ',
  thanks: ext(THANKS_NAME, THANKS_URL),
  tmdb: ext('TMDB', TMDB_URL),
  tmdbNotice: 'This product uses the TMDB API but is not endorsed or certified by TMDB.'
} as const;

/** Every external link rendered on the About page. */
export const aboutLinks = (): ExtLink[] => [...ABOUT.community, ABOUT.thanks, ABOUT.tmdb];
