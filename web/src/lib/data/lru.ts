/** Tiny LRU map (insertion-ordered Map; get() refreshes recency). */
export class Lru<K, V> {
  private map = new Map<K, V>();
  constructor(readonly max: number) {}

  get size(): number {
    return this.map.size;
  }

  has(k: K): boolean {
    return this.map.has(k);
  }

  get(k: K): V | undefined {
    if (!this.map.has(k)) return undefined;
    const v = this.map.get(k) as V;
    this.map.delete(k);
    this.map.set(k, v);
    return v;
  }

  set(k: K, v: V): void {
    if (this.map.has(k)) this.map.delete(k);
    this.map.set(k, v);
    while (this.map.size > this.max) {
      const oldest = this.map.keys().next().value as K;
      this.map.delete(oldest);
    }
  }

  delete(k: K): void {
    this.map.delete(k);
  }

  clear(): void {
    this.map.clear();
  }
}
