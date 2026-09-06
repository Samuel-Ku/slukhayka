import { beforeEach, describe, expect, it } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { DomainStore } from '../local/domain'
import { WorkRelationshipController } from './workRelationshipController'
import { InMemoryWorkRelationshipStore } from './workRelationshipStore'
import type { RemoteWorkRelationship } from './workRelationships'

function row(mergeKey: string, state: RemoteWorkRelationship['state'], serverMs: number): RemoteWorkRelationship {
  return { mergeKey, state, title: 'Книга', author: 'Автор', updatedAtServerMs: serverMs }
}

beforeEach(() => {
  ;(globalThis as { indexedDB?: unknown }).indexedDB = new IDBFactory()
})

describe('WorkRelationshipController (web side)', () => {
  it('pushes a local favorite as an entry when linked and sync is on', async () => {
    const domain = new DomainStore()
    const store = new InMemoryWorkRelationshipStore()
    const controller = new WorkRelationshipController(
      () => 'uid-1',
      domain,
      store,
      () => true,
    )
    await domain.addLibraryEntry({ title: 'Книга', author: 'Автор' })
    const mergeKey = (await domain.libraryEntries())[0].mergeKey

    await controller.pushAfterChange(mergeKey)

    const remote = await store.pull('uid-1', mergeKey)
    expect(remote?.state).toBe('entry')
  })

  it('pushes a tombstone when the listener hides the Work', async () => {
    const domain = new DomainStore()
    const store = new InMemoryWorkRelationshipStore()
    const controller = new WorkRelationshipController(() => 'uid-1', domain, store, () => true)
    const mergeKey = 'k|a'
    await domain.tombstoneWork(mergeKey)

    await controller.pushAfterChange(mergeKey)

    expect((await store.pull('uid-1', mergeKey))?.state).toBe('tombstone')
  })

  it('writes nothing when unbound (local- profile), even with sync on', async () => {
    const domain = new DomainStore()
    const store = new InMemoryWorkRelationshipStore()
    const controller = new WorkRelationshipController(() => 'local-abc', domain, store, () => true)
    await domain.addLibraryEntry({ title: 'Книга', author: 'Автор' }, 0)
    const key = (await domain.libraryEntries())[0].mergeKey

    await controller.pushAfterChange(key)

    expect(store.documents.size).toBe(0)
  })

  it('stops mirroring immediately when sync is switched off mid-flight', async () => {
    const domain = new DomainStore()
    const store = new InMemoryWorkRelationshipStore()
    let enabled = true
    const controller = new WorkRelationshipController(
      () => 'uid-1',
      domain,
      store,
      () => enabled,
    )
    await domain.addLibraryEntry({ title: 'Книга', author: 'Автор' })
    const mergeKey = (await domain.libraryEntries())[0].mergeKey

    enabled = false
    await controller.pushAfterChange(mergeKey)

    expect(store.documents.size).toBe(0)
  })

  it('pulls remote rows over local through the shared LWW rule', async () => {
    const domain = new DomainStore()
    const store = new InMemoryWorkRelationshipStore()
    const controller = new WorkRelationshipController(() => 'uid-1', domain, store, () => true)
    store.seed('uid-1', row('k1', 'entry', 100))
    store.seed('uid-1', row('k2', 'tombstone', 200))
    await domain.addLibraryEntry({ title: 'Книга', author: 'Автор' })
    const staleKey = (await domain.libraryEntries())[0].mergeKey

    await controller.pullAndApply()

    expect((await domain.relationshipOf('k1'))?.state).toBe('entry')
    expect((await domain.relationshipOf('k2'))?.state).toBe('tombstone')
    // A newer local row keeps its state; a stale remote favorite cannot
    // resurrect the locally newer tombstone (LWW, tombstone ties).
    await domain.tombstoneWork('k1', 300)
    store.seed('uid-1', row('k1', 'entry', 100))
    await controller.pullAndApply()
    expect((await domain.relationshipOf('k1'))?.state).toBe('tombstone')
    void staleKey
  })

  it('union-merges at linking: pre-link local favorites survive, phone rows join, hides win ties', async () => {
    const domain = new DomainStore()
    const store = new InMemoryWorkRelationshipStore()
    const controller = new WorkRelationshipController(
      () => 'uid-1',
      domain,
      store,
      () => true,
    )

    // Pre-link: two local favorites (no server stamps) — then the listener
    // enters the Recovery Code and becomes uid-1.
    await domain.addLibraryEntry({ title: 'Книга', author: 'Автор' })
    await domain.addLibraryEntry({ title: 'Інша книга', author: 'Інший автор' })
    const entries = await domain.libraryEntries()
    const localKey = entries.find((e) => e.title === 'Книга')!.mergeKey
    const otherKey = entries.find((e) => e.title === 'Інша книга')!.mergeKey
    controller.setUid('uid-1')

    // The phone's cloud rows: one favorite, one hide of the locally loved Work.
    store.seed('uid-1', row('phone-fav', 'entry', 100))
    store.seed('uid-1', row(localKey, 'tombstone', 100))

    const { uploaded, applied } = await controller.mergeAtLinking()

    // The other pre-link favorite uploads (the account has no row for it);
    // the hidden one does not — the server-vouched row always stands.
    expect(uploaded).toBe(1)
    expect(applied.length).toBe(3)
    expect((await domain.relationshipOf('phone-fav'))?.state).toBe('entry')
    expect((await store.pull('uid-1', otherKey))?.state).toBe('entry')
    // The phone's deliberate hide beats the stale local favorite.
    expect((await domain.relationshipOf(localKey))?.state).toBe('tombstone')
  })

  it('mergeAtLinking and pullAndApply are no-ops when unbound or store-less', async () => {
    const domain = new DomainStore()
    const store = new InMemoryWorkRelationshipStore()
    const controller = new WorkRelationshipController(() => null, domain, store, () => true)
    expect((await controller.mergeAtLinking()).uploaded).toBe(0)
    expect((await controller.pullAndApply()).length).toBe(0)

    const controllerNoStore = new WorkRelationshipController(() => 'uid-1', domain, null, () => true)
    expect((await controllerNoStore.mergeAtLinking()).uploaded).toBe(0)
  })

  it('pushes tombstones as invisible-named rows when display data is unknown', async () => {
    const domain = new DomainStore()
    const store = new InMemoryWorkRelationshipStore()
    const controller = new WorkRelationshipController(() => 'uid-1', domain, store, () => true)
    // A hide from Огляд (no local Work row): only the mergeKey is known.
    await domain.tombstoneWork('unknown|work', 0)

    await controller.pushAfterChange('unknown|work')

    expect((await store.pull('uid-1', 'unknown|work'))?.state).toBe('tombstone')
  })
})
