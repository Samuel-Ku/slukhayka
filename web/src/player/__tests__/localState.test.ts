import { describe, expect, it } from 'vitest'
import {
  BrowserStorage,
  LocalListeningStateStore,
  listeningStateKey,
  type LocalListeningStateSnapshot,
  type StorageLike,
} from '../localState'

/**
 * spec-43/T5 — Listening State persistence tests over a Map-backed fake:
 * round-trips per Edition, honest nulls for absence/corruption/wrong shape,
 * clear semantics. Save-on-pause stays a caller discipline (documented in
 * the module header); these tests pin only what the store guarantees.
 */

class MapStorage implements StorageLike {
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
}

const snapshot: LocalListeningStateSnapshot = {
  editionId: 'ed-1',
  chapterIndex: 3,
  positionSeconds: 120,
  isCompleted: false,
  preferredSpeed: 1.25,
  lastPausedAtEpochMs: 1_000,
}

describe('LocalListeningStateStore', () => {
  it('round-trips one Edition’s state', () => {
    const store = new LocalListeningStateStore(new MapStorage())
    store.save(snapshot)
    expect(store.load('ed-1')).toEqual(snapshot)
  })

  it('keys rows by Edition alone', () => {
    const store = new LocalListeningStateStore(new MapStorage())
    store.save(snapshot)
    expect(store.load('ed-2')).toBeNull()
  })

  it('overwrites on save — one row per Edition', () => {
    const store = new LocalListeningStateStore(new MapStorage())
    store.save(snapshot)
    store.save({ ...snapshot, positionSeconds: 200, lastPausedAtEpochMs: 2_000 })
    expect(store.load('ed-1')).toMatchObject({ positionSeconds: 200, lastPausedAtEpochMs: 2_000 })
  })

  it('returns null before anything is stored', () => {
    const store = new LocalListeningStateStore(new MapStorage())
    expect(store.load('ed-1')).toBeNull()
  })

  it('treats corrupted JSON as a miss, never a crash', () => {
    const storage = new MapStorage()
    storage.setItem(listeningStateKey('ed-1'), '{not json')
    expect(new LocalListeningStateStore(storage).load('ed-1')).toBeNull()
  })

  it.each([
    ['"just a string"', 'a non-object payload'],
    [JSON.stringify({ ...snapshot, chapterIndex: -1 }), 'a negative chapter'],
    [JSON.stringify({ ...snapshot, chapterIndex: 1.5 }), 'a fractional chapter'],
    [JSON.stringify({ ...snapshot, positionSeconds: -3 }), 'a negative position'],
    [JSON.stringify({ ...snapshot, isCompleted: 'yes' }), 'a non-boolean completion flag'],
    [JSON.stringify({ ...snapshot, preferredSpeed: 'fast' }), 'a non-number speed'],
    [JSON.stringify({ ...snapshot, editionId: '' }), 'an empty edition id'],
  ])('rejects %s (%s)', (raw) => {
    const storage = new MapStorage()
    storage.setItem(listeningStateKey('ed-1'), raw)
    expect(new LocalListeningStateStore(storage).load('ed-1')).toBeNull()
  })

  it('clear removes the row', () => {
    const store = new LocalListeningStateStore(new MapStorage())
    store.save(snapshot)
    store.clear('ed-1')
    expect(store.load('ed-1')).toBeNull()
  })
})

describe('BrowserStorage', () => {
  class FakeDomStorage implements Storage {
    private map = new Map<string, string>()
    readonly length = 0
    clear(): void {}
    getItem(key: string): string | null {
      return this.map.get(key) ?? null
    }
    key(_index: number): string | null {
      return null
    }
    removeItem(key: string): void {
      this.map.delete(key)
    }
    setItem(key: string, value: string): void {
      this.map.set(key, value)
    }
  }

  it('delegates to the underlying Storage implementation', () => {
    const store = new LocalListeningStateStore(new BrowserStorage(new FakeDomStorage()))
    store.save(snapshot)
    expect(store.load('ed-1')).toEqual(snapshot)
    store.clear('ed-1')
    expect(store.load('ed-1')).toBeNull()
  })
})
