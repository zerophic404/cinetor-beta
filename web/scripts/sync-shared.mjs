// Copies shared assets into the web app (no deps). Runs before dev/build/test.
import { cpSync, existsSync, mkdirSync, rmSync, copyFileSync } from 'node:fs';
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

copyDir(resolve(shared, 'mock'), resolve(web, 'static/mock'));
// Brand: shared/brand/out is gitignored at the repo root, so web/static/brand is the committed,
// web-optimized copy (iOS splash PNG -> JPEG, ~1 MB instead of 16 MB). Regenerate it locally with
// scripts/sync-brand.py after re-running shared/scripts/gen_icons.py.
copyFile(resolve(shared, 'generated/tokens.css'), resolve(web, 'src/lib/design/tokens.css'));
copyFile(resolve(shared, 'generated/tokens.ts'), resolve(web, 'src/lib/design/tokens.ts'));
console.log('sync-shared: mock + tokens copied');
