import { describe, expect, it } from 'vitest'
import {
  DOCUMENT_ID_MAX,
  RELATIONSHIP_STATE_TOMBSTONE,
  WorkRelationshipCodec,
  documentIdForRelationship,
  mergeRelationshipRows,
} from './workRelationships'
import type { RemoteWorkRelationship } from './workRelationships'

function row(overrides: Partial<RemoteWorkRelationship> & { mergeKey: string; state: RemoteWorkRelationship['state'] }): RemoteWorkRelationship {
  return {
    updatedAtServerMs: 100,
    title: 'Книга',
    author: 'Автор',
    ...overrides,
  }
}

describe('mergeRelationshipRows (LWW by server time, tombstone ties — ADR-0034)', () => {
  it('a strictly newer incoming row wins regardless of state', () => {
    const local = row({ mergeKey: 'k', state: 'entry', updatedAtServerMs: 100 })
    const incoming = row({ mergeKey: 'k', state: 'tombstone', updatedAtServerMs: 200 })
    expect(mergeRelationshipRows(local, incoming)).toBe(incoming)
    expect(mergeRelationshipRows(incoming, local)).toBe(incoming)
  })

  it('an older incoming row never resurrects a newer tombstone', () => {
    const local = row({ mergeKey: 'k', state: 'tombstone', updatedAtServerMs: 300 })
    const incoming = row({ mergeKey: 'k', state: 'entry', updatedAtServerMs: 200 })
    expect(mergeRelationshipRows(local, incoming)).toBe(local)
  })

  it('a server-time tie goes to the tombstone from either side', () => {
    const local = row({ mergeKey: 'k', state: 'entry', updatedAtServerMs: 100 })
    const incoming = row({ mergeKey: 'k', state: 'tombstone', updatedAtServerMs: 100 })
    expect(mergeRelationshipRows(local, incoming)).toBe(incoming)
    expect(mergeRelationshipRows(incoming, local)).toBe(incoming)
  })

  it('a tie between two entries and between two tombstones prefers the incoming display data (freshest claim)', () => {
    const local = row({ mergeKey: 'k', state: 'entry', updatedAtServerMs: 100, title: 'Старе' })
    const incoming = row({ mergeKey: 'k', state: 'entry', updatedAtServerMs: 100, title: 'Нове' })
    expect(mergeRelationshipRows(local, incoming)).toBe(incoming)
    const localT = row({ mergeKey: 'k', state: 'tombstone', updatedAtServerMs: 100 })
    const incomingT = row({ mergeKey: 'k', state: 'tombstone', updatedAtServerMs: 100, title: 'Нове' })
    expect(mergeRelationshipRows(localT, incomingT)).toBe(incomingT)
  })

  it('union-merge at linking: local favorites merge beside remote rows', () => {
    const localRows = [
      row({ mergeKey: 'k1', state: 'entry', updatedAtServerMs: 0 }),
      row({ mergeKey: 'k2', state: 'entry', updatedAtServerMs: 50 }),
    ]
    const remoteRows = [
      row({ mergeKey: 'k2', state: 'tombstone', updatedAtServerMs: 100 }),
      row({ mergeKey: 'k3', state: 'entry', updatedAtServerMs: 100 }),
    ]
    const merged = mergeRelationshipRows(localRows, remoteRows)
    expect(merged.map((r) => `${r.mergeKey}:${r.state}`)).toEqual([
      'k1:entry', // pre-link favorite survives the linking
      'k2:tombstone', // phone's hide beats the stale local favorite
      'k3:entry', // phone's favorite arrives
    ])
  })

  it('union-merge prefers tombstones on a clock tie (local unlinked rows carry 0)', () => {
    const localRows = [row({ mergeKey: 'k', state: 'entry', updatedAtServerMs: 0 })]
    const remoteRows = [row({ mergeKey: 'k', state: 'tombstone', updatedAtServerMs: 0 })]
    expect(mergeRelationshipRows(localRows, remoteRows)[0].state).toBe(RELATIONSHIP_STATE_TOMBSTONE)
  })
})

describe('documentIdForRelationship', () => {
  it('mirrors the listening_state key shape: {uid}_{mergeKey}', () => {
    expect(documentIdForRelationship('abc', 'титул|автор')).toBe('abc_титул|автор')
    expect(documentIdForRelationship('abc', 'x'.repeat(DOCUMENT_ID_MAX))).toHaveLength('abc_'.length + DOCUMENT_ID_MAX)
  })
})

describe('WorkRelationshipCodec', () => {
  it('round-trips a row and never fabricates updatedAt on write', () => {
    const source = row({ mergeKey: 'k', state: 'entry', updatedAtServerMs: 777 })
    const doc = WorkRelationshipCodec.toDocument('uid-1', source)
    expect(doc[WorkRelationshipCodec.FIELD_UPDATED_AT]).toBeUndefined()
    expect(doc[WorkRelationshipCodec.FIELD_UID]).toBe('uid-1')
    const back = WorkRelationshipCodec.fromDocument({ ...doc, [WorkRelationshipCodec.FIELD_UPDATED_AT]: 777 })
    expect(back).toEqual({ ...source, updatedAtServerMs: 777 })
  })

  it('rejects malformed documents as an honest miss', () => {
    const good = WorkRelationshipCodec.toDocument('u', row({ mergeKey: 'k', state: 'tombstone' }))
    expect(WorkRelationshipCodec.fromDocument(null)).toBeNull()
    expect(WorkRelationshipCodec.fromDocument({ ...good, state: 'erased' })).toBeNull()
    expect(WorkRelationshipCodec.fromDocument({ ...good, mergeKey: '' })).toBeNull()
    expect(WorkRelationshipCodec.fromDocument({ ...good, title: '' })).toBeNull()
    expect(WorkRelationshipCodec.fromDocument({ ...good, updatedAt: 0 })).toBeNull()
    expect(WorkRelationshipCodec.fromDocument({ ...good, updatedAt: 'soon' })).toBeNull()
    expect(WorkRelationshipCodec.fromDocument({ ...good, extra: 1 })).toBeNull()
  })

  it('tolerates Firestore Timestamp objects on read-back', () => {
    const good = WorkRelationshipCodec.toDocument('u', row({ mergeKey: 'k', state: 'entry' }))
    const doc = { ...good, updatedAt: { toMillis: () => 12345 } }
    expect(WorkRelationshipCodec.fromDocument(doc)?.updatedAtServerMs).toBe(12345)
  })
})
