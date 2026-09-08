/**
 * #580 W0.2 — the hybrid facade that lets the existing synchronous engine
 * keep its `StorageLike` shape while the data actually lives in IndexedDB
 * (R-W8, «доступ через наявні StorageLike-подібні інтерфейси»).
 *
 * Boot gate: `main.tsx` awaits `whenBooted()` before the first render, so
 * listeners never see a half-hydrated screen. Reads before the gate are
 * honest misses; writes before the gate are buffered in memory and replayed
 * over the hydrated data at boot (a pre-boot write is the freshest fact the
 * app knows — it must not be lost to a StrictMode double-effect race).
 *
 * localStorage keeps only small settings and the migration/heartbeat
 * markers; the `slukhayka.listening.*` rows migrate into IndexedDB on the
 * first boot and never return.
 */
import {
  bootListenerStorage,
  requestPersistentStorage,
  type BootOutcome,
  type ListenerDatabase,
} from './listeningState'
import { listeningStateKey, parseSnapshotValue, type StorageLike } from '../player/localState'

/** The canonical key the synchronous facade answers on. */
export function listeningStateKeyFor(editionId: string): string {
  return listeningStateKey(editionId)
}

/** Maps a listening key back to its Edition id; other keys return null. */
export function editionIdFromListeningKey(key: string): string | null {
  const prefix = 'slukhayka.listening.'
  if (!key.startsWith(prefix)) return null
  const editionId = key.slice(prefix.length)
  return editionId === '' ? null : editionId
}

export class HybridListeningStateStorage implements StorageLike {
  private memory = new Map<string, string>()
  private booted: Promise<BootOutcome> | null = null

  constructor(
    private readonly store: ListenerDatabase,
    private readonly storage: StorageLike,
    private readonly now: () => number = () => Date.now(),
  ) {}

  /** Awaits boot (persistence request + migration + hydration). Idempotent — every caller shares one run. */
  whenBooted(): Promise<BootOutcome> {
    if (this.booted === null) {
      this.booted = requestPersistentStorage()
        .catch(() => false)
        .then(() =>
          bootListenerStorage({
            store: this.store,
            storage: this.storage,
            now: this.now,
          }),
        )
        .then((outcome) => {
          // Hydrate the synchronous view with every valid snapshot.
          for (const snapshot of outcome.snapshots) {
            this.memory.set(listeningStateKeyFor(snapshot.editionId), JSON.stringify(snapshot))
          }
          // Replay buffered pre-boot writes over the hydrated data — the newest
          // local fact wins (they were written after the IDB rows were saved).
          for (const [key, value] of this.pending) {
            this.memory.set(key, value)
            this.writeThrough(key, value)
          }
          this.pending.clear()
          return outcome
        })
    }
    return this.booted
  }

  private pending = new Map<string, string>()
  private inflight = new Set<Promise<void>>()

  private writeThrough(key: string, value: string): void {
    const editionId = editionIdFromListeningKey(key)
    if (editionId === null) return
    let parsed: unknown
    try {
      parsed = JSON.parse(value)
    } catch {
      return
    }
    const snapshot = parseSnapshotValue(parsed)
    if (snapshot === null) return
    const write = this.store.saveSnapshot(snapshot).then(() => {
      this.inflight.delete(write)
    })
    this.inflight.add(write)
  }

  /** Awaits every in-flight write-through; the test seam for async durability. */
  async flushWrites(): Promise<void> {
    while (this.inflight.size > 0) {
      await Promise.all([...this.inflight])
    }
  }

  getItem(key: string): string | null {
    return this.memory.get(key) ?? null
  }

  setItem(key: string, value: string): void {
    this.memory.set(key, value)
    if (this.booted === null) {
      // Pre-boot write: buffer; the gate replays it over hydrated data.
      this.pending.set(key, value)
      return
    }
    this.writeThrough(key, value)
  }

  removeItem(key: string): void {
    this.memory.delete(key)
    this.pending.delete(key)
    const editionId = editionIdFromListeningKey(key)
    if (editionId !== null && this.booted !== null) void this.store.clearSnapshot(editionId)
  }

  /**
   * #591 W5.2 — «Скинути позиції прослуховування»: the exact scope of one
   * honest clear. The memory mirror, the pre-boot buffer AND the IndexedDB
   * rows all go — the engine's next save starts a fresh book. Settings,
   * bookmarks, library rows and preferences are untouched (they live in
   * other stores). Degrade-never: an IDB failure still empties the
   * synchronous view, so the UI never claims a reset that did not happen.
   */
  async clearListeningSnapshots(): Promise<void> {
    for (const key of Array.from(this.memory.keys())) {
      if (editionIdFromListeningKey(key) !== null) this.memory.delete(key)
    }
    this.pending.clear()
    if (this.booted !== null) await this.store.clearAllSnapshots()
  }
}
