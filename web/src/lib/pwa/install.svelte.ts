// PWA install: captures `beforeinstallprompt` (Android/desktop Chromium) + iOS "Add to Home Screen" guide state.
interface BIPEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

export const isIOSDevice = (ua: string, maxTouch = 0): boolean => /iPad|iPhone|iPod/.test(ua) || (/Macintosh/.test(ua) && maxTouch > 1);

class Install {
  /** Deferred native prompt (Chromium). */
  canPrompt = $state(false);
  /** Already running as an installed app. */
  standalone = $state(false);
  ios = $state(false);
  installed = $state(false);
  iosSheet = $state(false);
  private evt: BIPEvent | null = null;
  private wired = false;

  wire(): void {
    if (this.wired || typeof window === 'undefined') return;
    this.wired = true;
    const nav = navigator as Navigator & { standalone?: boolean };
    this.standalone = matchMedia('(display-mode: standalone)').matches || nav.standalone === true;
    this.ios = isIOSDevice(navigator.userAgent, navigator.maxTouchPoints);
    window.addEventListener('beforeinstallprompt', (e) => {
      e.preventDefault();
      this.evt = e as BIPEvent;
      this.canPrompt = true;
    });
    window.addEventListener('appinstalled', () => {
      this.installed = true;
      this.canPrompt = false;
      this.evt = null;
    });
  }

  /** Show the install button? (native prompt available, or iOS Safari not yet installed) */
  get available(): boolean {
    return !this.standalone && !this.installed && (this.canPrompt || this.ios);
  }

  async install(): Promise<void> {
    if (this.evt) {
      const e = this.evt;
      this.evt = null;
      this.canPrompt = false;
      await e.prompt();
      const c = await e.userChoice.catch(() => ({ outcome: 'dismissed' as const }));
      if (c.outcome === 'accepted') this.installed = true;
      return;
    }
    if (this.ios) this.iosSheet = true;
  }
}

export const install = new Install();
