/**
 * Small IndexedDB warm cache for parsed catalogue data. It intentionally
 * stores metadata only: source cookies, response headers and audio bytes
 * never cross this boundary. A stale cache is useful offline but never masks
 * a successful live response.
 */
const DB_NAME = 'slukhayka-warm-cache'
const STORE = 'responses'
const VERSION = 1

export type WarmEntry<T> = { key: string; value: T; savedAt: number }

/** A catalogue or public book projection is only an offline convenience. */
export const WARM_CACHE_TTL_MS = 6 * 60 * 60 * 1_000

function openDb(): Promise<IDBDatabase | null> {
  if (typeof indexedDB === 'undefined') return Promise.resolve(null)
  return new Promise((resolve) => {
    const request = indexedDB.open(DB_NAME, VERSION)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE)) request.result.createObjectStore(STORE, { keyPath: 'key' })
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => resolve(null)
  })
}

export async function readWarm<T>(key: string, maxAgeMs?: number): Promise<T | null> {
  return (await readWarmEntry<T>(key, maxAgeMs))?.value ?? null
}

/** Returns cache provenance as well as the value; callers must label it stale/offline. */
export async function readWarmEntry<T>(key: string, maxAgeMs?: number): Promise<WarmEntry<T> | null> {
  const db = await openDb()
  if (!db) return null
  return new Promise((resolve) => {
    const request = db.transaction(STORE, 'readonly').objectStore(STORE).get(key)
    request.onsuccess = () => {
      const entry = (request.result as WarmEntry<T> | undefined) ?? null
      resolve(entry !== null && maxAgeMs !== undefined && Date.now() - entry.savedAt >= maxAgeMs ? null : entry)
    }
    request.onerror = () => resolve(null)
  })
}

export async function writeWarm<T>(key: string, value: T): Promise<void> {
  const db = await openDb()
  if (!db) return
  await new Promise<void>((resolve) => {
    const request = db.transaction(STORE, 'readwrite').objectStore(STORE).put({ key, value, savedAt: Date.now() } satisfies WarmEntry<T>)
    request.onsuccess = () => resolve()
    request.onerror = () => resolve()
  })
}

export function warmKey(kind: 'catalog' | 'book' | 'availability', source: string, url = ''): string {
  return `${kind}|${source}|${url}`
}
