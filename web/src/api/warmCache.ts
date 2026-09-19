/**
 * Small IndexedDB warm cache for parsed catalogue data. It intentionally
 * stores metadata only: source cookies, response headers and audio bytes
 * never cross this boundary. A stale cache is useful offline but never masks
 * a successful live response.
 *
 * #621 — writes of ONE key are serialised (see [writeWarm]): an older page
 * can never commit over a newer one, and a superseded intent can drop its
 * write right before the readwrite transaction starts.
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

/**
 * The per-key writer chain behind [writeWarm]. It holds only in-flight keys
 * and removes each tail once it settles, so it is a scheduling slot, not
 * another unbounded cache.
 */
const writeChains = new Map<string, Promise<void>>()

export async function writeWarm<T>(key: string, value: T, isCurrent?: () => boolean): Promise<void> {
  // #621 — ONE key, ONE ordered writer chain. IndexedDB orders readwrite
  // transactions by creation, and every `writeWarm` awaits its own `openDb`
  // first, so two unsynchronised writers of the same key can invert the call
  // order: an OLDER page may commit over a NEWER one. Queueing per key makes
  // the last issued write the last committed one.
  const previous = writeChains.get(key) ?? Promise.resolve()
  const write = previous.then(
    async () => {
      try {
        const db = await openDb()
        if (!db) return
        // Consulted immediately BEFORE the readwrite transaction starts: a
        // superseded intent (a #621 generation that changed while the write
        // was queued) drops its write without ever opening the transaction.
        if (isCurrent !== undefined && !isCurrent()) return
        await new Promise<void>((resolve) => {
          const request = db.transaction(STORE, 'readwrite').objectStore(STORE).put({ key, value, savedAt: Date.now() } satisfies WarmEntry<T>)
          request.onsuccess = () => resolve()
          request.onerror = () => resolve()
        })
      } catch {
        // degrade-never: a failed cache write is never a product failure
      }
    },
    async () => {
      // The previous writer's own failure never breaks the chain.
    },
  )
  writeChains.set(key, write)
  void write.finally(() => {
    if (writeChains.get(key) === write) writeChains.delete(key)
  })
  return write
}

export function warmKey(kind: 'catalog' | 'book' | 'availability', source: string, url = ''): string {
  return `${kind}|${source}|${url}`
}
