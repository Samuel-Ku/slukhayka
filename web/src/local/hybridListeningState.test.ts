import { IDBFactory } from 'fake-indexeddb'
import { beforeEach, describe, expect, it } from 'vitest'
import {
  EVICTION_SUSPECT_MS,
  IdbListeningStateStore,
  listeningStateRecordKey,
} from './listeningState'
import { editionIdFromListeningKey, HybridListeningStateStorage, listeningStateKeyFor } from './hybridListeningState'
import type { LocalListeningStateSnapshot } from '../player/localState'

const DAY = 24 * 60 * 60 * 1_000

function snapshotOf(editionId: string, positionSeconds = 100): LocalListeningStateSnapshot {
  return {
    editionId,
    chapterIndex: 2,
    positionSeconds,
    isCompleted: false,
    preferredSpeed: null,
    lastPausedAtEpochMs: null,
  }
}

/** A deterministic in-memory Storage double with the real key-index contract. */
class MemoryStorage {
  private map = new Map<string, string>()
  getItem(key: string): string | null {
    return this.map.get(key) ?? null
  }
  setItem(key: string, value: string): void {
    this.map.set(key, value)
  }
  removeItem(key: string): void {
    this.map.delete(key)
  }
  get length(): number {
    return this.map.size
  }
  key(index: number): string | null {
    return Array.from(this.map.keys())[index] ?? null
  }
}

beforeEach(() => {
  ;(globalThis as { indexedDB?: unknown }).indexedDB = new IDBFactory()
})

describe('HybridListeningStateStorage', () => {
  it('serves synchronous reads from IndexedDB data after the boot gate', async () => {
    const idb = new IdbListeningStateStore()
    await idb.saveSnapshot(snapshotOf('ed-1'))
    const storage = new MemoryStorage()
    const hybrid = new HybridListeningStateStorage(idb, storage)

    // Before the gate resolves, reads are honest misses (the UI is gated anyway).
    expect(hybrid.getItem(listeningStateKeyFor('ed-1'))).toBeNull()

    await hybrid.whenBooted()
    const raw = hybrid.getItem(listeningStateKeyFor('ed-1'))
    expect(raw).not.toBeNull()
    expect(JSON.parse(raw!)).toMatchObject({ editionId: 'ed-1', positionSeconds: 100 })
  })

  it('buffers pre-boot writes and replays them over hydrated data', async () => {
    const idb = new IdbListeningStateStore()
    await idb.saveSnapshot(snapshotOf('ed-1', 100))
    const hybrid = new HybridListeningStateStorage(idb, new MemoryStorage())

    // A write lands before hydration (StrictMode double-effect, background tick).
    hybrid.setItem(listeningStateKeyFor('ed-1'), JSON.stringify(snapshotOf('ed-1', 250)))
    await hybrid.whenBooted()
    await hybrid.flushWrites()

    expect(JSON.parse(hybrid.getItem(listeningStateKeyFor('ed-1'))!).positionSeconds).toBe(250)
    expect((await idb.loadSnapshot('ed-1'))?.positionSeconds).toBe(250)
  })

  it('writes through to IndexedDB after boot', async () => {
    const idb = new IdbListeningStateStore()
    const hybrid = new HybridListeningStateStorage(idb, new MemoryStorage())
    await hybrid.whenBooted()

    hybrid.setItem(listeningStateKeyFor('ed-2'), JSON.stringify(snapshotOf('ed-2', 42)))
    expect(hybrid.getItem(listeningStateKeyFor('ed-2'))).not.toBeNull()
    await hybrid.flushWrites()

    expect((await idb.loadSnapshot('ed-2'))?.positionSeconds).toBe(42)
  })

  it('removeItem clears the synchronous view and the IndexedDB row', async () => {
    const idb = new IdbListeningStateStore()
    await idb.saveSnapshot(snapshotOf('ed-3'))
    const hybrid = new HybridListeningStateStorage(idb, new MemoryStorage())
    await hybrid.whenBooted()

    hybrid.removeItem(listeningStateKeyFor('ed-3'))
    expect(hybrid.getItem(listeningStateKeyFor('ed-3'))).toBeNull()
    await hybrid.flushWrites()

    expect(await idb.loadSnapshot('ed-3')).toBeNull()
  })

  it('runs the legacy migration: rows land in IndexedDB and leave localStorage', async () => {
    const storage = new MemoryStorage()
    storage.setItem('slukhayka.listening.ed-4', JSON.stringify(snapshotOf('ed-4', 7)))
    const idb = new IdbListeningStateStore()
    const hybrid = new HybridListeningStateStorage(idb, storage)
    await hybrid.whenBooted()

    expect(storage.getItem('slukhayka.listening.ed-4')).toBeNull()
    expect((await idb.loadSnapshot('ed-4'))?.positionSeconds).toBe(7)
    expect(JSON.parse(hybrid.getItem(listeningStateKeyFor('ed-4'))!).positionSeconds).toBe(7)
  })

  it('reports the eviction verdict from the boot outcome', async () => {
    const storage = new MemoryStorage()
    storage.setItem('slukhayka.idb.last_seen', String(Date.now() - EVICTION_SUSPECT_MS - DAY))
    const idb = new IdbListeningStateStore()
    const hybrid = new HybridListeningStateStorage(idb, storage, () => Date.now())
    const outcome = await hybrid.whenBooted()

    expect(outcome.evicted).toBe(true)
  })

  it('degrades to an empty in-memory store when IndexedDB is unavailable', async () => {
    ;(globalThis as { indexedDB?: unknown }).indexedDB = undefined
    const hybrid = new HybridListeningStateStorage(new IdbListeningStateStore(), new MemoryStorage())
    const outcome = await hybrid.whenBooted()

    expect(outcome.snapshots).toEqual([])
    expect(outcome.evicted).toBe(false)
    hybrid.setItem(listeningStateKeyFor('ed-5'), JSON.stringify(snapshotOf('ed-5')))
    // Session keeps working from memory alone; the write is a silent no-op on IDB.
    expect(hybrid.getItem(listeningStateKeyFor('ed-5'))).not.toBeNull()
  })

  it('maps a listening key back to its Edition id', () => {
    expect(editionIdFromListeningKey(listeningStateKeyFor('abc'))).toBe('abc')
    expect(listeningStateRecordKey('abc')).toBe('abc')
  })
})
