import { IDBFactory } from 'fake-indexeddb'
import { beforeEach, describe, expect, it } from 'vitest'
import { openListenerDatabase, type IdbDatabase } from './idb'
import {
  bootListenerStorage,
  collectLegacyListeningRows,
  EVICTION_SUSPECT_MS,
  IdbListeningStateStore,
  ListeningStateMigrator,
  type ListenerDatabase,
} from './listeningState'
import type { LocalListeningStateSnapshot } from '../player/localState'

const DAY = 24 * 60 * 60 * 1_000

function snapshotOf(editionId: string, positionSeconds = 120): LocalListeningStateSnapshot {
  return {
    editionId,
    chapterIndex: 2,
    positionSeconds,
    isCompleted: false,
    preferredSpeed: 1.25,
    lastPausedAtEpochMs: 1_700_000_000_000,
  }
}

/** A tiny Storage double with real index access (like the browser's Storage). */
function memoryStorage(): Storage {
  const backing = new Map<string, string>()
  return {
    get length(): number {
      return backing.size
    },
    clear: () => backing.clear(),
    getItem: (key) => (backing.has(key) ? (backing.get(key) as string) : null),
    key: (index) => Array.from(backing.keys())[index] ?? null,
    removeItem: (key) => void backing.delete(key),
    setItem: (key, value) => void backing.set(key, String(value)),
  }
}

function fakeOpen(): () => Promise<IdbDatabase | null> {
  return () => {
    // Each store instance gets an isolated faked factory per database name.
    return openListenerDatabase(1, [
      { name: 'listening_state', keyPath: 'editionId', indexes: [{ name: 'updatedAt', keyPath: 'updatedAt' }] },
    ])
  }
}

let storage: Storage

beforeEach(() => {
  // Node has no built-in indexedDB global; install a fresh fake per test
  // so databases never leak between cases.
  ;(globalThis as { indexedDB?: unknown }).indexedDB = new IDBFactory()
  storage = memoryStorage()
})

describe('IdbListeningStateStore', () => {
  it('saves and loads a snapshot by Edition alone', async () => {
    const store = new IdbListeningStateStore(fakeOpen())
    await store.saveSnapshot(snapshotOf('edition-a'))
    expect(await store.loadSnapshot('edition-a')).toMatchObject({ editionId: 'edition-a', positionSeconds: 120 })
  })

  it('rejects corrupt records as an honest miss, never a crash', async () => {
    const store = new IdbListeningStateStore(fakeOpen())
    const db = await fakeOpen()()
    await db!.put('listening_state', { editionId: 'bad', snapshot: { nope: true } })
    expect(await store.loadSnapshot('bad')).toBeNull()
    db!.close()
  })

  it('degrades to an empty store when IndexedDB is unavailable', async () => {
    const store = new IdbListeningStateStore(async () => null)
    await store.saveSnapshot(snapshotOf('edition-a'))
    expect(await store.loadSnapshot('edition-a')).toBeNull()
    expect(await store.allSnapshots()).toEqual([])
  })

  it('clears one Edition without touching others', async () => {
    const store = new IdbListeningStateStore(fakeOpen())
    await store.saveSnapshot(snapshotOf('edition-a'))
    await store.saveSnapshot(snapshotOf('edition-b'))
    await store.clearSnapshot('edition-a')
    expect(await store.loadSnapshot('edition-a')).toBeNull()
    expect(await store.loadSnapshot('edition-b')).not.toBeNull()
  })
})

describe('ListeningStateMigrator', () => {
  it('copies legacy rows into IDB and removes them only after read-back confirmation', async () => {
    const store = new IdbListeningStateStore(fakeOpen())
    const legacyA: LocalListeningStateSnapshot = { ...snapshotOf('edition-a'), positionSeconds: 615 }
    storage.setItem('slukhayka.listening.edition-a', JSON.stringify(legacyA))
    storage.setItem('slukhayka.listening.edition-b', JSON.stringify(snapshotOf('edition-b')))

    const migrated = await new ListeningStateMigrator(store, storage).migrate()
    expect(migrated).toBe(2)
    expect(await store.loadSnapshot('edition-a')).toMatchObject({ positionSeconds: 615 })
    expect(await store.loadSnapshot('edition-b')).not.toBeNull()
    expect(storage.getItem('slukhayka.listening.edition-a')).toBeNull()
    expect(storage.getItem('slukhayka.listening.edition-b')).toBeNull()
  })

  it('is loss-free when IDB confirm fails: the legacy row survives for a retry', async () => {
    const real = new IdbListeningStateStore(fakeOpen())
    const failing: ListenerDatabase = {
      loadSnapshot: async () => null, // read-back never confirms
      saveSnapshot: (snapshot) => real.saveSnapshot(snapshot),
      clearSnapshot: (editionId) => real.clearSnapshot(editionId),
      allSnapshots: () => real.allSnapshots(),
      clearAllSnapshots: () => real.clearAllSnapshots(),
    }
    storage.setItem('slukhayka.listening.edition-a', JSON.stringify(snapshotOf('edition-a')))

    const migrated = await new ListeningStateMigrator(failing, storage).migrate()
    expect(migrated).toBe(0)
    expect(storage.getItem('slukhayka.listening.edition-a')).not.toBeNull()

    // Next boot with a working store completes the migration.
    const second = await new ListeningStateMigrator(real, storage).migrate()
    expect(second).toBe(1)
    expect(storage.getItem('slukhayka.listening.edition-a')).toBeNull()
  })

  it('removes unparseable legacy rows without blocking the rest', async () => {
    const store = new IdbListeningStateStore(fakeOpen())
    storage.setItem('slukhayka.listening.broken', '{nope')
    storage.setItem('slukhayka.listening.edition-a', JSON.stringify(snapshotOf('edition-a')))
    const migrated = await new ListeningStateMigrator(store, storage).migrate()
    expect(migrated).toBe(1)
    expect(storage.getItem('slukhayka.listening.broken')).toBeNull()
    expect(await store.loadSnapshot('edition-a')).not.toBeNull()
  })

  it('runs once: the migrated marker stops repeated scans', async () => {
    const store = new IdbListeningStateStore(fakeOpen())
    storage.setItem('slukhayka.listening.edition-a', JSON.stringify(snapshotOf('edition-a')))
    const migrator = new ListeningStateMigrator(store, storage)
    expect(await migrator.migrate()).toBe(1)
    expect(await migrator.migrate()).toBe(0)
  })
})

describe('bootListenerStorage', () => {
  it('hydrates the synchronous engine store after migration', async () => {
    const store = new IdbListeningStateStore(fakeOpen())
    storage.setItem('slukhayka.listening.edition-a', JSON.stringify(snapshotOf('edition-a')))
    const outcome = await bootListenerStorage({ store, storage })
    expect(outcome.evicted).toBe(false)
    expect(outcome.snapshots).toHaveLength(1)
    expect(outcome.snapshots[0]).toMatchObject({ editionId: 'edition-a' })
  })

  it('reports an honest eviction after a long absence with an empty store and a past heartbeat', async () => {
    const store = new IdbListeningStateStore(fakeOpen())
    const past = Date.now() - EVICTION_SUSPECT_MS - DAY
    storage.setItem('slukhayka.idb.last_seen', String(past))
    const outcome = await bootListenerStorage({ store, storage, now: () => Date.now() })
    expect(outcome.evicted).toBe(true)
    expect(outcome.snapshots).toEqual([])
  })

  it('does not cry eviction on a first run (no heartbeat yet) even with an empty store', async () => {
    const store = new IdbListeningStateStore(fakeOpen())
    const outcome = await bootListenerStorage({ store, storage })
    expect(outcome.evicted).toBe(false)
  })

  it('does not cry eviction when data survived a long absence', async () => {
    const store = new IdbListeningStateStore(fakeOpen())
    await store.saveSnapshot(snapshotOf('edition-a'))
    const past = Date.now() - EVICTION_SUSPECT_MS - DAY
    storage.setItem('slukhayka.idb.last_seen', String(past))
    const outcome = await bootListenerStorage({ store, storage })
    expect(outcome.evicted).toBe(false)
    expect(outcome.snapshots).toHaveLength(1)
  })

  it('stamps a fresh heartbeat on every boot', async () => {
    const store = new IdbListeningStateStore(fakeOpen())
    await bootListenerStorage({ store, storage, now: () => 1_000 })
    expect(storage.getItem('slukhayka.idb.last_seen')).toBe('1000')
    await bootListenerStorage({ store, storage, now: () => 2_000 })
    expect(storage.getItem('slukhayka.idb.last_seen')).toBe('2000')
  })

  it('the migration done-marker is never counted as a legacy row (#591)', () => {
    const storage = {
      getItem: (key: string) => (key === 'slukhayka.listening.migrated_to_idb' ? '1' : null),
      removeItem: () => undefined,
      length: 1,
      key: () => 'slukhayka.listening.migrated_to_idb',
    }
    expect(collectLegacyListeningRows(storage)).toEqual([])
  })

  it('clearAllSnapshots removes every snapshot row, nothing else (#591)', async () => {
    const store = new IdbListeningStateStore(fakeOpen())
    await store.saveSnapshot(snapshotOf('edition-a'))
    await store.saveSnapshot(snapshotOf('edition-b'))
    expect(await store.allSnapshots()).toHaveLength(2)

    await store.clearAllSnapshots()

    expect(await store.allSnapshots()).toHaveLength(0)
    expect(await store.loadSnapshot('edition-a')).toBeNull()
    expect(await store.loadSnapshot('edition-b')).toBeNull()
  })
})
