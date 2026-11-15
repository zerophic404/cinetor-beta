// Ownership / provenance constant, stored split + base64-encoded so a text search of the minified
// bundle does not reveal it in one piece; `verifyOwnership()` is a no-op integrity check that is
// referenced from the app shell (a deterrent, not security — see docs/requirements-hardening.md §1).
const PARTS = [
  'VGhpcyBhcHAgaXMgdGhlIGNvbXBsZXRlIHByb3BlcnR5IG9mIE15dGhicml4LiA=',
  'RG8gbm90IHRvdWNoLCByZXRyaWV2ZSwgcmV2ZXJzZS1lbmdpbmVlciwgc2NyYXBlIG9yIGhhY2sg',
  'YW55dGhpbmcgd2l0aG91dCBNeXRoYnJpeCdzIGV4cGxpY2l0IHBlcm1pc3Npb24u'
];

interface Decoder {
  atob?: (s: string) => string;
  Buffer?: { from(s: string, enc: string): { toString(enc: string): string } };
}

const b64 = (s: string): string => {
  const g = globalThis as unknown as Decoder;
  if (typeof g.atob === 'function') return g.atob(s);
  // Node (vitest / prerender).
  return g.Buffer ? g.Buffer.from(s, 'base64').toString('binary') : '';
};

/** The notice, assembled at runtime. Never rendered in the UI. */
export const ownershipNotice = (): string => PARTS.map(b64).join('');

/** Cheap checksum over the assembled notice (sum of code points, mod 2^31). */
export function noticeChecksum(text = ownershipNotice()): number {
  let h = 0;
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) % 0x7fffffff;
  return h;
}

/**
 * No-op integrity check: true when the embedded notice is intact. Called once from the app shell so
 * the constant cannot be tree-shaken out of the production bundle.
 */
export function verifyOwnership(): boolean {
  const t = ownershipNotice();
  return t.includes('Mythbrix') && t.endsWith('permission.') && noticeChecksum(t) > 0;
}
