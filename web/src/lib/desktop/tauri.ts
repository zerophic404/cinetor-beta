// Lazy @tauri-apps/api access — only ever loaded inside the desktop app (never in the PWA bundle path).
import { isTauriEnv } from './env';

type Core = typeof import('@tauri-apps/api/core');
type Ev = typeof import('@tauri-apps/api/event');
type Win = typeof import('@tauri-apps/api/window');

let coreP: Promise<Core> | null = null;
let evP: Promise<Ev> | null = null;
let winP: Promise<Win> | null = null;

export const core = (): Promise<Core> => (coreP ??= import('@tauri-apps/api/core'));
export const events = (): Promise<Ev> => (evP ??= import('@tauri-apps/api/event'));
export const windowApi = (): Promise<Win> => (winP ??= import('@tauri-apps/api/window'));

export async function invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  if (!isTauriEnv()) throw new Error('Not running in the Cinetor desktop app');
  return (await core()).invoke<T>(cmd, args);
}

export async function listen<T>(event: string, fn: (payload: T) => void): Promise<() => void> {
  const { listen } = await events();
  return listen<T>(event, (e) => fn(e.payload));
}

/** Local file → URL the webview can load (asset protocol, scope = Downloads/Cinetor). */
export async function fileUrl(path: string): Promise<string> {
  return (await core()).convertFileSrc(path);
}

export async function currentWindow() {
  return (await windowApi()).getCurrentWindow();
}
