// <img> attrs for an item path at the current perf tier (reactive when read in templates).
import { imgAttrs, type ImageKind, type ImgAttrs } from '$lib/data/images';
import { repo } from '$lib/data/repo';
import { perf } from '$lib/perf/tier.svelte';

export const imgFor = (path: string, kind: ImageKind): ImgAttrs => imgAttrs(path, kind, perf.flags.tier, (p) => repo.asset(p));
