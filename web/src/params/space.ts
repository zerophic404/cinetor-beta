import type { ParamMatcher } from '@sveltejs/kit';

/** Non-home spaces addressable at /app/<slug>. */
export const SLUGS = ['anime', 'drama', 'tv', 'music', 'comics', 'adult'] as const;

export const match: ParamMatcher = (param) => (SLUGS as readonly string[]).includes(param);
