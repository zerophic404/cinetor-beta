// Post-build: GitHub Pages serves `/x/` only from `x/index.html`, so `/cinetor-app/app/` 404'd
// (SvelteKit trailingSlash 'never' emits `app.html`). Duplicate every prerendered `x.html` as
// `x/index.html` → both `/app` and `/app/` return 200 with the same shell. No deps.
import { copyFileSync, existsSync, mkdirSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const build = resolve(dirname(fileURLToPath(import.meta.url)), '../build');
if (!existsSync(build)) {
  console.log('dup-index: no build dir, skipped');
  process.exit(0);
}
const SKIP = new Set(['index.html', '404.html']);
let n = 0;
function walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) {
      if (name === '_app' || name === 'mock' || name === 'brand') continue;
      walk(p);
    } else if (name.endsWith('.html') && !SKIP.has(name)) {
      const target = join(dir, name.slice(0, -5), 'index.html');
      if (existsSync(target)) continue;
      mkdirSync(dirname(target), { recursive: true });
      copyFileSync(p, target);
      n++;
    }
  }
}
walk(build);
console.log(`dup-index: ${n} trailing-slash copies (e.g. ${relative(build, join(build, 'app/index.html'))})`);
