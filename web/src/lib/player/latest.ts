// "Latest wins" runner: each run aborts the previous one (Review Focus 1). Pure, unit tested.

export class Superseded extends Error {
  constructor() {
    super('superseded');
    this.name = 'Superseded';
  }
}

export class LatestWins {
  private gen = 0;
  private ctrl: AbortController | null = null;
  private key: string | null = null;

  get currentKey(): string | null {
    return this.key;
  }

  /** Run `fn` for `key`, aborting whatever ran before. Resolves undefined when superseded/aborted. */
  async run<T>(key: string, fn: (signal: AbortSignal, isCurrent: () => boolean) => Promise<T>): Promise<T | undefined> {
    this.ctrl?.abort();
    const ctrl = new AbortController();
    const gen = ++this.gen;
    this.ctrl = ctrl;
    this.key = key;
    const isCurrent = () => gen === this.gen && !ctrl.signal.aborted;
    try {
      const v = await fn(ctrl.signal, isCurrent);
      return isCurrent() ? v : undefined;
    } catch (e) {
      if (!isCurrent()) return undefined;
      throw e;
    }
  }

  cancel(key?: string): void {
    if (key !== undefined && key !== this.key) return;
    this.ctrl?.abort();
    this.ctrl = null;
    this.key = null;
    this.gen++;
  }
}
