/**
 * W4.1 — the ListenerReview codec, pinned to the Kotlin fixture rules:
 * deterministic `${workId}_${uid}` identity (one review per listener per
 * Work — the double-vote guard), bounded writes, fail-closed reads.
 */
import { describe, expect, it } from 'vitest'
import {
  isWritableReview,
  isValidRating,
  ListenerReviewCodec,
  ListenerReviewLimits,
  reviewDocumentId,
  type ListenerReview,
} from './reviewModel'

const review: ListenerReview = {
  workId: 'кобзар|тарас шевченко',
  uid: 'uid-1',
  authorName: 'Слухач',
  rating: 4,
  body: 'Чудова начитка!',
  editionTag: 'Олександр Волох',
  createdAt: 1_000,
}

describe('reviewDocumentId', () => {
  it('is the deterministic `${workId}_${uid}` key — the double-vote guard', () => {
    expect(reviewDocumentId('w', 'u')).toBe('w_u')
    expect(reviewDocumentId('книга|автор', 'слухач-1234')).toBe('книга|автор_слухач-1234')
    expect(ListenerReviewCodec.documentId(review)).toBe('кобзар|тарас шевченко_uid-1')
  })
})

describe('isValidRating / isWritableReview', () => {
  it('accepts exactly 1..5 integers', () => {
    expect(isValidRating(1)).toBe(true)
    expect(isValidRating(5)).toBe(true)
    expect(isValidRating(0)).toBe(false)
    expect(isValidRating(6)).toBe(false)
    expect(isValidRating(3.5)).toBe(false)
  })

  it('requires real identity and an in-range rating', () => {
    expect(isWritableReview(review)).toBe(true)
    expect(isWritableReview({ ...review, workId: '  ' })).toBe(false)
    expect(isWritableReview({ ...review, uid: '' })).toBe(false)
    expect(isWritableReview({ ...review, authorName: '' })).toBe(false)
    expect(isWritableReview({ ...review, rating: 0 })).toBe(false)
  })
})

describe('ListenerReviewCodec.toMap', () => {
  it('writes the full bounded shape', () => {
    const map = ListenerReviewCodec.toMap(review)
    expect(map).toEqual({
      workId: 'кобзар|тарас шевченко',
      uid: 'uid-1',
      authorName: 'Слухач',
      rating: 4,
      body: 'Чудова начитка!',
      editionTag: 'Олександр Волох',
      createdAt: 1_000,
    })
  })

  it('drops blank optionals and truncates over-limit strings', () => {
    const map = ListenerReviewCodec.toMap({
      ...review,
      body: '  ',
      editionTag: 'x'.repeat(300),
      authorName: 'n'.repeat(200),
    })
    expect(map.body).toBeUndefined()
    expect(map.editionTag).toBe('x'.repeat(ListenerReviewLimits.MAX_EDITION_TAG_LEN))
    expect(map.authorName).toBe('n'.repeat(ListenerReviewLimits.MAX_AUTHOR_LEN))
  })

  it('carries editedAt only when present', () => {
    expect(ListenerReviewCodec.toMap(review).editedAt).toBeUndefined()
    expect(ListenerReviewCodec.toMap({ ...review, editedAt: 2_000 }).editedAt).toBe(2_000)
  })
})

describe('ListenerReviewCodec.fromMap', () => {
  it('round-trips a valid document', () => {
    const decoded = ListenerReviewCodec.fromMap(ListenerReviewCodec.toMap(review))
    expect(decoded).toEqual(review)
  })

  it('is fail-closed on corrupt documents — a miss, never a crash', () => {
    const base = ListenerReviewCodec.toMap(review)
    expect(ListenerReviewCodec.fromMap({ ...base, rating: 3.5 })).toBeNull()
    expect(ListenerReviewCodec.fromMap({ ...base, rating: '4' })).toBeNull()
    expect(ListenerReviewCodec.fromMap({ ...base, rating: 9 })).toBeNull()
    expect(ListenerReviewCodec.fromMap({ ...base, workId: '' })).toBeNull()
    expect(ListenerReviewCodec.fromMap({ ...base, uid: 42 })).toBeNull()
    expect(ListenerReviewCodec.fromMap({ ...base, authorName: 'x'.repeat(500) })).toBeNull()
    expect(ListenerReviewCodec.fromMap({ ...base, body: 'x'.repeat(5_000) })).toBeNull()
    expect(ListenerReviewCodec.fromMap({ ...base, createdAt: 'yesterday' })).toBeNull()
    expect(ListenerReviewCodec.fromMap({ ...base, editedAt: 'later' })).toBeNull()
    expect(ListenerReviewCodec.fromMap({})).toBeNull()
  })

  it('normalizes blank optionals to absent', () => {
    const decoded = ListenerReviewCodec.fromMap({ ...ListenerReviewCodec.toMap(review), body: '  ' })
    expect(decoded?.body).toBeUndefined()
  })
})