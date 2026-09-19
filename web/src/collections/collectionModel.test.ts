/**
 * spec-51 (#697, T9) — the web reader's contract with the shared
 * `curator_collections` documents: field-for-field decoding, the text hygiene
 * Android applies on write, the honest average and the ONE shared ordering.
 * The expected values come from the spec (#692/#694), not from the code under
 * test: no votes → no average, three-way tie-break → deterministic order.
 */
import { describe, expect, it } from 'vitest'
import {
  CollectionLimits,
  CollectionRanking,
  CollectionRating,
  cleanCollectionText,
  collectionDocumentId,
  decodePublishedCollection,
  type PublishedCollection,
} from './collectionModel'

function document(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    authorId: 'a'.repeat(64),
    collectionId: 'uuid-1',
    pseudonym: 'Книголюб',
    title: 'Космос',
    description: 'Про зорі та планети',
    bookIds: ['work-1', 'work-2'],
    reasons: ['бо космос', 'бо зорі'],
    ratingSum: 9,
    ratingCount: 2,
    hidden: false,
    reportCount: 0,
    items: [
      { bookId: 'work-1', title: 'Марсіянин', author: 'Енді Вейр', coverUrl: 'https://cdn.example/m.jpg', reason: 'бо космос' },
    ],
    publishedAt: 1_700_000_000_000,
    ...overrides,
  }
}

function published(overrides: Partial<PublishedCollection> = {}): PublishedCollection {
  const decoded = decodePublishedCollection(document())
  if (decoded === null) throw new Error('fixture must decode')
  return { ...decoded, ...overrides }
}

describe('decodePublishedCollection (#692)', () => {
  it('decodes the shared document field-for-field', () => {
    const decoded = decodePublishedCollection(document())
    expect(decoded).toMatchObject({
      authorId: 'a'.repeat(64),
      collectionId: 'uuid-1',
      pseudonym: 'Книголюб',
      title: 'Космос',
      description: 'Про зорі та планети',
      bookIds: ['work-1', 'work-2'],
      reasons: ['бо космос', 'бо зорі'],
      ratingSum: 9,
      ratingCount: 2,
      hidden: false,
      reportCount: 0,
      publishedAt: 1_700_000_000_000,
    })
    expect(decoded?.items).toEqual([
      { bookId: 'work-1', title: 'Марсіянин', author: 'Енді Вейр', coverUrl: 'https://cdn.example/m.jpg', reason: 'бо космос' },
    ])
  })

  it('is a miss when the document cannot address a real collection', () => {
    expect(decodePublishedCollection(null)).toBeNull()
    expect(decodePublishedCollection(document({ authorId: '   ' }))).toBeNull()
    expect(decodePublishedCollection(document({ collectionId: '' }))).toBeNull()
    expect(decodePublishedCollection(document({ title: '  ' }))).toBeNull()
    expect(decodePublishedCollection(document({ title: 42 }))).toBeNull()
  })

  it('never fabricates a negative aggregate — a hostile number decodes to the honest zero', () => {
    const decoded = decodePublishedCollection(document({ ratingSum: -50, ratingCount: -3, reportCount: -1 }))
    expect(decoded?.ratingSum).toBe(0)
    expect(decoded?.ratingCount).toBe(0)
    expect(decoded?.reportCount).toBe(0)
  })

  it('keeps reasons positionally aligned: short lists pad, never shift', () => {
    const decoded = decodePublishedCollection(document({ bookIds: ['w1', 'w2', 'w3'], reasons: ['тільки перша'] }))
    expect(decoded?.reasons).toEqual(['тільки перша', '', ''])
  })

  it('drops a malformed display snapshot, never half-shows it', () => {
    const decoded = decodePublishedCollection(
      document({ items: [{ bookId: 'ok', title: 'Добра' }, { title: 'Без bookId' }, 'не об’єкт'] }),
    )
    expect(decoded?.items).toEqual([{ bookId: 'ok', title: 'Добра', author: '', coverUrl: undefined, reason: '' }])
  })

  it('keeps a legacy document (no items) honest: the composition is the book ids', () => {
    const decoded = decodePublishedCollection(document({ items: [], bookIds: ['w1'], reasons: ['бо'] }))
    expect(decoded?.items).toEqual([])
    expect(decoded?.bookIds).toEqual(['w1'])
  })
})

describe('collection text hygiene (#689/#697)', () => {
  it('removes links, collapses whitespace and trims', () => {
    expect(cleanCollectionText('Дивись https://spam.example/x тут', CollectionLimits.MAX_TITLE_LEN)).toBe('Дивись тут')
    expect(cleanCollectionText('  два   слова  ', CollectionLimits.MAX_TITLE_LEN)).toBe('два слова')
    expect(cleanCollectionText('www.example.com', CollectionLimits.MAX_TITLE_LEN)).toBe('')
    expect(cleanCollectionText(null, CollectionLimits.MAX_TITLE_LEN)).toBe('')
  })

  it('truncates to the field limit without leaving a trailing space', () => {
    const long = `${'а'.repeat(CollectionLimits.MAX_TITLE_LEN)} хвіст`
    const cleaned = cleanCollectionText(long, CollectionLimits.MAX_TITLE_LEN)
    expect(cleaned).toHaveLength(CollectionLimits.MAX_TITLE_LEN)
    expect(cleaned.endsWith(' ')).toBe(false)
    expect(cleaned).toBe('а'.repeat(CollectionLimits.MAX_TITLE_LEN))
  })

  it('a title that is only a link decodes to a miss, not an empty headline', () => {
    expect(decodePublishedCollection(document({ title: 'https://spam.example/collection' }))).toBeNull()
  })
})

describe('CollectionRating (#694)', () => {
  it('has no average without real votes', () => {
    expect(CollectionRating.average(0, 0)).toBeNull()
    expect(CollectionRating.average(7, 0)).toBeNull()
    expect(CollectionRating.average(0, 3)).toBeNull()
  })

  it('is the real mean of the stored aggregate', () => {
    expect(CollectionRating.average(9, 2)).toBe(4.5)
    expect(CollectionRating.average(5, 1)).toBe(5)
  })

  it('accepts only whole stars 1..5', () => {
    expect(CollectionRating.isValidStars(1)).toBe(true)
    expect(CollectionRating.isValidStars(5)).toBe(true)
    expect(CollectionRating.isValidStars(0)).toBe(false)
    expect(CollectionRating.isValidStars(6)).toBe(false)
    expect(CollectionRating.isValidStars(3.5)).toBe(false)
  })

  it('applyVote adds a first vote and REPLACES a re-vote (Kotlin applyVote)', () => {
    // First vote: the aggregate grows by the new stars and one vote.
    expect(CollectionRating.applyVote(0, 0, null, 5)).toEqual([5, 1])
    // Re-vote: the previous stars and count are removed first — never doubled.
    expect(CollectionRating.applyVote(5, 1, 5, 2)).toEqual([2, 1])
    // A third listener stacks on the aggregate, keeping one vote per person.
    expect(CollectionRating.applyVote(2, 1, null, 4)).toEqual([6, 2])
    // An impossible stored aggregate never goes negative.
    expect(CollectionRating.applyVote(0, 0, 5, 3)).toEqual([3, 1])
  })

  it('applyVote refuses stars outside 1..5', () => {
    expect(() => CollectionRating.applyVote(0, 0, null, 0)).toThrow()
    expect(() => CollectionRating.applyVote(0, 0, null, 6)).toThrow()
  })
})

describe('CollectionRanking (#692/#693)', () => {
  it('orders by real average, then votes, then newest, then document id', () => {
    const fiveOneVote = published({ collectionId: 'a', ratingSum: 5, ratingCount: 1, publishedAt: 100 })
    const fiveTwoVotes = published({ collectionId: 'b', ratingSum: 10, ratingCount: 2, publishedAt: 100 })
    const three = published({ collectionId: 'c', ratingSum: 3, ratingCount: 1, publishedAt: 100 })
    const unvoted = published({ collectionId: 'd', ratingSum: 0, ratingCount: 0, publishedAt: 999 })

    const top = CollectionRanking.top([unvoted, three, fiveOneVote, fiveTwoVotes])
    expect(top.map((collection) => collection.collectionId)).toEqual(['b', 'a', 'c', 'd'])
  })

  it('breaks a full tie deterministically by document id', () => {
    const first = published({ collectionId: 'aaa', ratingSum: 4, ratingCount: 1, publishedAt: 5 })
    const second = published({ collectionId: 'bbb', ratingSum: 4, ratingCount: 1, publishedAt: 5 })
    const top = CollectionRanking.top([second, first])
    expect(top.map(collectionDocumentId)).toEqual([collectionDocumentId(first), collectionDocumentId(second)])
  })

  it('is a shelf, not an archive: default limit 10, degenerate limit empty', () => {
    const many = Array.from({ length: 12 }, (_, index) =>
      published({ collectionId: `c${index}`, ratingSum: index + 1, ratingCount: 1, publishedAt: 1 }),
    )
    expect(CollectionRanking.top(many)).toHaveLength(10)
    expect(CollectionRanking.top(many, 3)).toHaveLength(3)
    expect(CollectionRanking.top(many, 0)).toEqual([])
    expect(CollectionRanking.top(many, -1)).toEqual([])
  })
})
