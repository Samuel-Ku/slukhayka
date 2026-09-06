/**
 * Shared vitest setup (#579).
 *
 * jsdom ≥ 29 delegates `window.localStorage` to Node's built-in webstorage,
 * which exists only behind the `--localstorage-file` CLI flag. Without the
 * flag every jsdom test that touches localStorage crashes before its first
 * assertion — an environment regression, not a product one. When the host
 * provides no storage, fall back to an in-memory Storage with the real
 * semantics the UI tests rely on (set/get/remove/length), so the suite runs
 * identically on machines with and without the flag.
 */
if (typeof globalThis.localStorage === 'undefined') {
  const backing = new Map<string, string>()
  const storage: Storage = {
    get length(): number {
      return backing.size
    },
    clear(): void {
      backing.clear()
    },
    getItem(key: string): string | null {
      return backing.has(key) ? (backing.get(key) as string) : null
    },
    key(index: number): string | null {
      return Array.from(backing.keys())[index] ?? null
    },
    removeItem(key: string): void {
      backing.delete(key)
    },
    setItem(key: string, value: string): void {
      backing.set(key, String(value))
    },
  }
  globalThis.localStorage = storage
  const jsdomWindow = (globalThis as { window?: { localStorage?: Storage } }).window
  if (jsdomWindow && typeof jsdomWindow.localStorage === 'undefined') {
    jsdomWindow.localStorage = storage
  }
}
