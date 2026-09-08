/**
 * W4.1 — the NarrationRating codec, pinned to the Kotlin fixture rules:
 * `${workId}_${uid}_${editionId}` identity (one rating per listener per
 * Edition), bounded writes, fail-closed reads.
 */
import { describe, expect, it } from 'vitest'
import {
  isWritableNarrationRating,
  NarrationRatingCodec,
  narrationRatingDocumentId,
  type NarrationRating,
} from './narrationRatingModel'

const rating: NarrationRating = {
  workId: 'кобзар|тарас шевченко',
  uid: 'uid-1',
  editionId: 'abc123',
  rating: 5,
  createdAt: 1_000,
}

describe('narrationRatingDocumentId', () => {
  it('is the deterministic `${workId}_${uid}_${editionId}` key', () => {
    expect(narrationRatingDocumentId('w', 'u', 'e')).toBe('w_u_e')
    expect(NarrationRatingCodec.documentId(rating)).toBe('кобзар|тарас шевченко_uid-1_abc123')
  })
})

describe('isWritableNarrationRating', () => {
  it('requires real identity and an in-range rating', () => {
    expect(isWritableNarrationRating(rating)).toBe(true)
    expect(isWritableNarrationRating({ ...rating, workId: '' })).toBe(false)
    expect(isWritableNarrationRating({ ...rating, uid: '  ' })).toBe(false)
    expect(isWritableNarrationRating({ ...rating, editionId: '' })).toBe(false)
    expect(isWritableNarrationRating({ ...rating, rating: 0 })).toBe(false)
    expect(isWritableNarrationRating({ ...rating, rating: 3.5 })).toBe(false)
  })
})

describe('NarrationRatingCodec', () => {
  it('round-trips a valid document and carries editedAt only when present', () => {
    expect(NarrationRatingCodec.fromMap(NarrationRatingCodec.toMap(rating))).toEqual(rating)
    const edited = NarrationRatingCodec.fromMap(NarrationRatingCodec.toMap({ ...rating, editedAt: 2_000 }))
    expect(edited?.editedAt).toBe(2_000)
  })

  it('is fail-closed on corrupt documents', () => {
    const base = NarrationRatingCodec.toMap(rating)
    expect(NarrationRatingCodec.fromMap({ ...base, rating: 6 })).toBeNull()
    expect(NarrationRatingCodec.fromMap({ ...base, rating: '5' })).toBeNull()
    expect(NarrationRatingCodec.fromMap({ ...base, editionId: 7 })).toBeNull()
    expect(NarrationRatingCodec.fromMap({ ...base, workId: '' })).toBeNull()
    expect(NarrationRatingCodec.fromMap({ ...base, createdAt: 'x' })).toBeNull()
    expect(NarrationRatingCodec.fromMap({})).toBeNull()
  })

  it('truncates over-limit identity strings on write', () => {
    const map = NarrationRatingCodec.toMap({ ...rating, workId: 'w'.repeat(500), editionId: 'e'.repeat(300) })
    expect(map.workId).toHaveLength(300)
    expect(map.editionId).toHaveLength(200)
  })
})