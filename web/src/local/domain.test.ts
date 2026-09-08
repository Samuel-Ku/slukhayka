import { IDBFactory } from 'fake-indexeddb'
import { beforeEach, describe, expect, it } from 'vitest'
import { mergeKeyFor } from '../sync/edition'
import { DomainStore, personIdFor, resolveRelationship, type WorkRelationshipRow } from './domain'

function row(overrides: Partial<WorkRelationshipRow> & { mergeKey: string; state: WorkRelationshipRow['state'] }): WorkRelationshipRow {
  return {
    updatedAtServerMs: 100,
    updatedAtLocalMs: 100,
    title: 'Книга',
    author: 'Автор',
    ...overrides,
  }
}

beforeEach(() => {
  ;(globalThis as { indexedDB?: unknown }).indexedDB = new IDBFactory()
})

describe('resolveRelationship (LWW, tombstone ties — ADR-0034)', () => {
  it('newer server time wins', () => {
    const local = row({ mergeKey: 'k', state: 'entry', updatedAtServerMs: 100 })
    const incoming = row({ mergeKey: 'k', state: 'tombstone', updatedAtServerMs: 200 })
    expect(resolveRelationship(local, incoming).state).toBe('tombstone')
  })

  it('older incoming never resurrects a newer tombstone', () => {
    const local = row({ mergeKey: 'k', state: 'tombstone', updatedAtServerMs: 300 })
    const incoming = row({ mergeKey: 'k', state: 'entry', updatedAtServerMs: 200 })
    expect(resolveRelationship(local, incoming).state).toBe('tombstone')
  })

  it('a server-time tie goes to the tombstone', () => {
    const local = row({ mergeKey: 'k', state: 'entry', updatedAtServerMs: 100 })
    const incoming = row({ mergeKey: 'k', state: 'tombstone', updatedAtServerMs: 100 })
    expect(resolveRelationship(local, incoming).state).toBe('tombstone')
  })

  it('a tie between two entries resolves by local write time', () => {
    const local = row({ mergeKey: 'k', state: 'entry', updatedAtServerMs: 100, updatedAtLocalMs: 50 })
    const incoming = row({ mergeKey: 'k', state: 'entry', updatedAtServerMs: 100, updatedAtLocalMs: 60 })
    expect(resolveRelationship(local, incoming)).toBe(incoming)
  })
})

describe('DomainStore', () => {
  it('adds a library entry and reads it back through the relationship projection', async () => {
    const store = new DomainStore()
    await store.addLibraryEntry({ title: 'Книга', author: 'Автор' })
    const entries = await store.libraryEntries()
    expect(entries).toHaveLength(1)
    expect(entries[0]).toMatchObject({ title: 'Книга', author: 'Автор' })
  })

  it('tombstoning hides the Work from entries without erasing the Work row', async () => {
    const store = new DomainStore()
    await store.addLibraryEntry({ title: 'Книга', author: 'Автор' })
    const relationship = await store.relationshipOf((await store.libraryEntries())[0].mergeKey)
    await store.tombstoneWork(relationship!.mergeKey, 200)
    expect(await store.libraryEntries()).toEqual([])
    expect((await store.tombstones()).length).toBe(1)
  })

  it('a tombstone blocks a stale entry write from resurrecting the Work', async () => {
    const store = new DomainStore()
    await store.addLibraryEntry({ title: 'Книга', author: 'Автор' })
    const mergeKey = (await store.libraryEntries())[0].mergeKey
    await store.tombstoneWork(mergeKey, 200)
    // A delayed sync row (older server time) must not resurrect the Work.
    await store.applyRelationship(row({ mergeKey, state: 'entry', updatedAtServerMs: 100 }))
    expect(await store.libraryEntries()).toEqual([])
  })

  it('an explicitly newer entry action resurrects a previously hidden Work', async () => {
    const store = new DomainStore()
    const mergeKey = mergeKeyFor('Книга', 'Автор')
    await store.tombstoneWork(mergeKey, 100)
    await store.addLibraryEntry({ title: 'Книга', author: 'Автор' }, 200)
    expect((await store.libraryEntries()).length).toBe(1)
    expect((await store.tombstones()).length).toBe(0)
  })

  it('person bookmarks key by role and deterministic id, and remove cleanly', async () => {
    const store = new DomainStore()
    await store.addPersonBookmark({ role: 'narrator', personId: personIdFor('narrator', 'читець'), displayName: 'Читець' })
    await store.addPersonBookmark({ role: 'author', personId: personIdFor('author', 'автор'), displayName: 'Автор' })
    expect((await store.personBookmarks()).length).toBe(2)
    expect((await store.personBookmarks('narrator')).map((bookmark) => bookmark.personId)).toEqual([personIdFor('narrator', 'читець')])
    await store.removePersonBookmark(personIdFor('author', 'автор'))
    expect((await store.personBookmarks()).length).toBe(1)
  })

  it('degrades to empty reads when IndexedDB is unavailable', async () => {
    const store = new DomainStore(async () => null)
    expect(await store.libraryEntries()).toEqual([])
    expect(await store.tombstones()).toEqual([])
    expect(await store.personBookmarks()).toEqual([])
    expect(await store.relationshipOf('k')).toBeNull()
  })
})
