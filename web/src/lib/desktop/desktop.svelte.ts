// Desktop app state (Tauri): window maximized flag, mini-player mode, backdrop kind.
import { isTauriEnv } from './env';
import { currentWindow, invoke, listen } from './tauri';

class Desktop {
  isTauri = $state(false);
  mini = $state(false);
  maximized = $state(false);
  vibrancy = $state<'mica' | 'acrylic' | 'none'>('none');
  private wired = false;

  /** Call once from the root layout (browser only). */
  async init(): Promise<void> {
    if (this.wired || !isTauriEnv()) return;
    this.wired = true;
    this.isTauri = true;
    const html = document.documentElement;
    html.classList.add('tauri');
    html.classList.toggle('tauri-linux', /Linux/i.test(navigator.userAgent));
    try {
      this.vibrancy = await invoke<'mica' | 'acrylic' | 'none'>('vibrancy_kind');
    } catch {
      this.vibrancy = 'none';
    }
    html.classList.toggle('tauri-glass', this.vibrancy !== 'none');
    try {
      this.mini = await invoke<boolean>('is_mini_mode');
      await listen<boolean>('mini-mode', (on) => (this.mini = on));
      const w = await currentWindow();
      this.maximized = await w.isMaximized();
      await w.onResized(async () => {
        this.maximized = await w.isMaximized();
      });
    } catch {
      /* window API unavailable */
    }
  }

  async setMini(on: boolean): Promise<void> {
    if (!this.isTauri) return;
    try {
      this.mini = await invoke<boolean>('set_mini_mode', { enabled: on });
    } catch {
      this.mini = false;
    }
  }

  async minimize(): Promise<void> {
    await (await currentWindow()).minimize();
  }
  async toggleMaximize(): Promise<void> {
    await (await currentWindow()).toggleMaximize();
  }
  async close(): Promise<void> {
    await (await currentWindow()).close();
  }
  async startDrag(): Promise<void> {
    await (await currentWindow()).startDragging();
  }
}

export const desktop = new Desktop();
