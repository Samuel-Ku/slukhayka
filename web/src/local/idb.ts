/**
 * #580 W0.2 — the small typed IndexedDB door for listener-owned data.
 *
 * R-W8: everything the listener cannot reconstruct (Listening State, and
 * later Library Entries, Tombstones, Person Bookmarks) lives in IndexedDB —
 * localStorage survives iOS Safari tab eviction only by luck and is capped
 * at ~5 MB everywhere. The wrapper is deliberately tiny: one open with a
 * declarative store list, versioned upgrades, one-request helpers. Every
 * failure degrades to `null`/`false` — never an exception (degrade-never,
 * the house rule of every local store).
 *
 * Tests inject `fake-indexeddb`; the browser uses the real `indexedDB`.
 */

export interface StoreSpec {
  name: string
  /** KeyPath options for `createObjectStore`; omit for out-of-line keys. */
  keyPath?: string | string[]
  /** Index specs created on every fresh store. */
  indexes?: Array<{ name: string; keyPath: string | string[] | Iterable<string> }>
}

export interface IdbDatabase {
  get<T>(store: string, key: string | number): Promise<T | null>
  getAll<T>(store: string): Promise<T[]>
  put<T>(store: string, value: T, key?: string | number): Promise<void>
  delete(store: string, key: string | number): Promise<void>
  clear(store: string): Promise<void>
  close(): void
}

function isSupported(): boolean {
  return typeof indexedDB !== 'undefined'
}

/** Opens (or upgrades) the database; resolves null when IDB is unavailable. */
export function openListenerDatabase(version = 1, stores: StoreSpec[] = []): Promise<IdbDatabase | null> {
  if (!isSupported()) return Promise.resolve(null)
  return new Promise((resolve) => {
    let request: IDBOpenDBRequest
    try {
      request = indexedDB.open('slukhayka-listener', version)
    } catch {
      resolve(null)
      return
    }
    request.onupgradeneeded = () => {
      const db = request.result
      for (const spec of stores) {
        const store = db.objectStoreNames.contains(spec.name)
          ? request.transaction!.objectStore(spec.name)
          : db.createObjectStore(spec.name, spec.keyPath !== undefined ? { keyPath: spec.keyPath } : undefined)
        for (const index of spec.indexes ?? []) {
          if (!store.indexNames.contains(index.name)) {
            store.createIndex(index.name, index.keyPath as string | string[])
          }
        }
      }
    }
    request.onsuccess = () => {
      const raw = request.result
      resolve({
        get<T>(store: string, key: string | number): Promise<T | null> {
          return new Promise((done) => {
            try {
              const r = raw.transaction(store, 'readonly').objectStore(store).get(key)
              r.onsuccess = () => done((r.result as T | undefined) ?? null)
              r.onerror = () => done(null)
            } catch {
              done(null)
            }
          })
        },
        getAll<T>(store: string): Promise<T[]> {
          return new Promise((done) => {
            try {
              const r = raw.transaction(store, 'readonly').objectStore(store).getAll()
              r.onsuccess = () => done((r.result as T[]) ?? [])
              r.onerror = () => done([])
            } catch {
              done([])
            }
          })
        },
        put<T>(store: string, value: T, key?: string | number): Promise<void> {
          return new Promise((done) => {
            try {
              const r = key === undefined
                ? raw.transaction(store, 'readwrite').objectStore(store).put(value)
                : raw.transaction(store, 'readwrite').objectStore(store).put(value, key)
              r.onsuccess = () => done()
              r.onerror = () => done()
            } catch {
              done()
            }
          })
        },
        delete(store: string, key: string | number): Promise<void> {
          return new Promise((done) => {
            try {
              const r = raw.transaction(store, 'readwrite').objectStore(store).delete(key)
              r.onsuccess = () => done()
              r.onerror = () => done()
            } catch {
              done()
            }
          })
        },
        clear(store: string): Promise<void> {
          return new Promise((done) => {
            try {
              const r = raw.transaction(store, 'readwrite').objectStore(store).clear()
              r.onsuccess = () => done()
              r.onerror = () => done()
            } catch {
              done()
            }
          })
        },
        close(): void {
          raw.close()
        },
      })
    }
    request.onerror = () => resolve(null)
    request.onblocked = () => resolve(null)
  })
}
