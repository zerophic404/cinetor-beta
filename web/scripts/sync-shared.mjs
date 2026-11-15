// Copies shared assets into the web app (no deps). Runs before dev/build/test.
import { cpSync, existsSync, mkdirSync, rmSync, copyFileSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const shared = resolve(web, '../shared');

function copyDir(from, to) {
  if (!existsSync(from)) throw new Error(`sync-shared: missing ${from}`);
  rmSync(to, { recursive: true, force: true });
  mkdirSync(dirname(to), { recursive: true });
  cpSync(from, to, { recursive: true });
}
function copyFile(from, to) {
  if (!existsSync(from)) throw new Error(`sync-shared: missing ${from}`);
  mkdirSync(dirname(to), { recursive: true });
  copyFileSync(from, to);
}

// Mock data ONLY for the isolated spaces that have no real source yet (Live TV & Sports, Music,
// Comics). Home / Anime / Drama / 18+ come from the streaming gateway — their mock JSON + images
// are not shipped (smaller build + precache).
const MOCK_SPACES = ['tv', 'music', 'comics'];
const mockOut = resolve(web, 'static/mock');
rmSync(mockOut, { recursive: true, force: true });
mkdirSync(mockOut, { recursive: true });
const assets = new Set();
for (const s of MOCK_SPACES) {
  const file = resolve(shared, 'mock', `${s}.json`);
  copyFile(file, resolve(mockOut, `${s}.json`));
  for (const m of readFileSync(file, 'utf8').matchAll(/"((?:images|subs)\/[^"]+)"/g)) assets.add(m[1]);
}
for (const a of assets) {
  const from = resolve(shared, 'mock', a);
  if (existsSync(from)) copyFile(from, resolve(mockOut, a));
}
console.log(`sync-shared: mock ${MOCK_SPACES.join('/')} + ${assets.size} assets`);
// Brand: shared/brand/out is gitignored at the repo root, so web/static/brand is the committed,
// web-optimized copy (iOS splash PNG -> JPEG, ~1 MB instead of 16 MB). Regenerate it locally with
// scripts/sync-brand.py after re-running shared/scripts/gen_icons.py.
copyFile(resolve(shared, 'generated/tokens.css'), resolve(web, 'src/lib/design/tokens.css'));
copyFile(resolve(shared, 'generated/tokens.ts'), resolve(web, 'src/lib/design/tokens.ts'));
console.log('sync-shared: tokens copied');
