// Tauri beforeBuildCommand: build the SvelteKit web app at the ROOT path (BASE_PATH='') into web/build.
// Cross-platform (no `VAR= cmd` shell syntax, which cmd.exe on Windows can't run).
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = resolve(dirname(fileURLToPath(import.meta.url)), '../../web');
const env = { ...process.env, BASE_PATH: '', CINETOR_DESKTOP: '1' };
const run = (args) => {
  const r = spawnSync('npm', args, { cwd: web, env, stdio: 'inherit', shell: process.platform === 'win32' });
  if (r.status !== 0) process.exit(r.status ?? 1);
};
if (!existsSync(resolve(web, 'node_modules'))) run(['install', '--no-audit', '--no-fund']);
run(['run', 'build']);
