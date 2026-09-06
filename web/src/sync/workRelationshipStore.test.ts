import { describe, expect, it } from 'vitest'
import { InMemoryWorkRelationshipStore } from './workRelationshipStore'
import { WorkRelationshipCodec, documentIdForRelationship } from './workRelationships'
import type { RemoteWorkRelationship } from './workRelationships'

function row(mergeKey: string, state: RemoteWorkRelationship['state'], serverMs: number): RemoteWorkRelationship {
  return { mergeKey, state, title: 'Книга', author: 'Автор', updatedAtServerMs: serverMs }
}

describe('InMemoryWorkRelationshipStore', () => {
  it('push writes the codec document, returns the server stamp; the doc never carries a client clock', async () => {
    const store = new InMemoryWorkRelationshipStore()
    const stamp = await store.push('u1', row('k', 'entry', 0))
    expect(stamp).not.toBeNull()
    expect(stamp!).toBeGreaterThan(0)
    const doc = store.documents.get(documentIdForRelationship('u1', 'k'))!
    expect(doc[WorkRelationshipCodec.FIELD_STATE]).toBe('entry')
    expect(doc[WorkRelationshipCodec.FIELD_UPDATED_AT]).toBe(stamp)
  })

  it('pull decodes the stored document and misses honestly when absent', async () => {
    const store = new InMemoryWorkRelationshipStore()
    expect(await store.pull('u1', 'k')).toBeNull()
    await store.push('u1', row('k', 'tombstone', 0))
    expect((await store.pull('u1', 'k'))?.state).toBe('tombstone')
  })

  it('pullAll reads only the listener\u2019s own rows (uid-prefixed key space)', async () => {
    const store = new InMemoryWorkRelationshipStore()
    await store.push('u1', row('k1', 'entry', 0))
    await store.push('u1', row('k2', 'tombstone', 0))
    await store.push('u2', row('k3', 'entry', 0))
    const own = await store.pullAll('u1')
    expect(own.map((r) => r.mergeKey).sort()).toEqual(['k1', 'k2'])
  })

  it('seed plants server-vouched rows for tests', () => {
    const store = new InMemoryWorkRelationshipStore()
    store.seed('u1', row('k', 'entry', 555))
    const doc = store.documents.get(documentIdForRelationship('u1', 'k'))!
    expect(doc[WorkRelationshipCodec.FIELD_UPDATED_AT]).toBe(555)
  })

  it('a closed-shape document with an unknown extra field fails closed on pull', async () => {
    const store = new InMemoryWorkRelationshipStore()
    const id = documentIdForRelationship('u1', 'k')
    store.documents.set(id, {
      ...WorkRelationshipCodec.toDocument('u1', row('k', 'entry', 5)),
      updatedAt: 5,
      rogue: true,
    })
    expect(await store.pull('u1', 'k')).toBeNull()
  })
})
