/**
 * spec-51 (#697, T9, AC3) — the MIRROR test of Android's pinned hash vectors.
 *
 * The expected values are copied verbatim from the Kotlin guards:
 * `CollectionIdentityTest.kt:11-20` (voterKey) and `CuratorIdentityTest.kt:15-25`
 * (authorId). They are the cross-platform contract: if the browser and the
 * phone disagree by one byte they address different Firestore documents and a
 * vote written on one client is invisible on the other. Do NOT recompute these
 * from this module — that would test the code against itself.
 */
import { describe, expect, it } from 'vitest'
import { CollectionIdentity, CuratorIdentity } from './collectionIdentity'

describe('CuratorIdentity.authorId — Android CuratorIdentityTest vectors', () => {
  it('is the pinned lowercase hex sha256 of the uid', () => {
    expect(CuratorIdentity.authorId('test-uid-1')).toBe(
      '0e83e6fd82eb2043d313b8e60c6b7308ac6fbca98e950985867ee69766404352',
    )
    expect(CuratorIdentity.authorId('firebase-uid-abc')).toBe(
      '10f6f120daeddab1f80b3d1a6eff827ecd7ed6538e10a0169301cf8b308af39f',
    )
  })

  it('never leaks the raw uid and always yields 64 hex chars', () => {
    const uid = 'firebase-uid-abc'
    const id = CuratorIdentity.authorId(uid)
    expect(id.includes(uid)).toBe(false)
    expect(id).toHaveLength(64)
    expect(id).toMatch(/^[0-9a-f]{64}$/)
  })

  it('has no id and is not publishable for a blank uid', () => {
    expect(CuratorIdentity.authorId(null)).toBe('')
    expect(CuratorIdentity.authorId(undefined)).toBe('')
    expect(CuratorIdentity.authorId('   ')).toBe('')
    expect(CuratorIdentity.isPublishable(null)).toBe(false)
    expect(CuratorIdentity.isPublishable('test-uid-1')).toBe(true)
  })
})

describe('CollectionIdentity.voterKey — Android CollectionIdentityTest vectors', () => {
  it('is the pinned lowercase hex sha256 of uid + collectionId', () => {
    expect(CollectionIdentity.voterKey('test-uid-1', 'c1')).toBe(
      '59227a7cdf3aeba3d0b8cf3debadb35d64765c31f9cfa65e6de011bd76e19cd5',
    )
    expect(CollectionIdentity.voterKey('test-uid-1', 'collection-1')).toBe(
      '64ed916b9a00c462aad96d297f8755f500e7ec759b63cedc9e4db761f0e2cd35',
    )
  })

  it('gives the same person unrelated keys on two collections', () => {
    expect(CollectionIdentity.voterKey('uid', 'c1')).not.toBe(CollectionIdentity.voterKey('uid', 'c2'))
  })

  it('is never a votable key for a blank identity or collection', () => {
    expect(CollectionIdentity.voterKey(null, 'c1')).toBe('')
    expect(CollectionIdentity.voterKey(undefined, 'c1')).toBe('')
    expect(CollectionIdentity.voterKey('  ', 'c1')).toBe('')
    expect(CollectionIdentity.voterKey('uid', ' ')).toBe('')
  })
})
