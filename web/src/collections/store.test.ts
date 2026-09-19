/**
 * spec-51 (#697, T9) — the reading seam's contract: hidden collections never
 * reach a public surface, the rail is ranked by the shared rule, an empty read
 * is an honest empty (not a failure), and a blank query is not a query.
 */
import { describe, expect, it } from 'vitest'
import { CollectionModeration, decodePublishedCollection, type PublishedCollection } from './collectionModel'
import { CollectionIdentity } from './collectionIdentity'
import { InMemoryCollectionsStore } from './store'

function collection(overrides: Partial<PublishedCollection> = {}): PublishedCollection {
  const decoded = decodePublishedCollection({
    authorId: 'author-a',
    collectionId: 'c1',
    pseudonym: 'Книголюб',
    title: 'Космос',
    bookIds: ['work-1'],
    reasons: ['бо'],
    ratingSum: 0,
    ratingCount: 0,
    publishedAt: 1,
  })
  if (decoded === null) throw new Error('fixture must decode')
  return { ...decoded, ...overrides }
}

describe('InMemoryCollectionsStore (#692/#693)', () => {
  it('reads only the VISIBLE collections that contain the book', async () => {
    const store = new InMemoryCollectionsStore()
    const visible = collection({ collectionId: 'visible', bookIds: ['work-1'] })
    const other = collection({ collectionId: 'other', bookIds: ['work-2'] })
    const hidden = collection({ collectionId: 'hidden', bookIds: ['work-1'], hidden: true })
    store.seed(visible)
    store.seed(other)
    store.seed(hidden)

    const result = await store.readContaining('work-1')
    expect(result.kind).toBe('data')
    if (result.kind !== 'data') return
    expect(result.collections.map((entry) => entry.collectionId)).toEqual(['visible'])
  })

  it('answers an honest empty for a book nobody curated, and for a blank id', async () => {
    const store = new InMemoryCollectionsStore()
    store.seed(collection({ collectionId: 'c1', bookIds: ['work-1'] }))
    expect(await store.readContaining('work-404')).toEqual({ kind: 'empty' })
    expect(await store.readContaining('   ')).toEqual({ kind: 'empty' })
  })

  it('ranks the rail and never shows a hidden collection', async () => {
    const store = new InMemoryCollectionsStore()
    const low = collection({ collectionId: 'low', ratingSum: 2, ratingCount: 1 })
    const high = collection({ collectionId: 'high', ratingSum: 10, ratingCount: 2 })
    const hiddenTop = collection({ collectionId: 'hidden', ratingSum: 5, ratingCount: 1, hidden: true })
    store.seed(low)
    store.seed(high)
    store.seed(hiddenTop)

    const rail = await store.topPublic(10)
    expect(rail.map((entry) => entry.collectionId)).toEqual(['high', 'low'])
    expect(await store.topPublic(0)).toEqual([])
  })

  it('reads a curator profile as their visible collections only', async () => {
    const store = new InMemoryCollectionsStore()
    store.seed(collection({ collectionId: 'mine', authorId: 'curator-1' }))
    store.seed(collection({ collectionId: 'hidden-mine', authorId: 'curator-1', hidden: true }))
    store.seed(collection({ collectionId: 'stranger', authorId: 'curator-2' }))

    const visible = await store.visibleBy('curator-1')
    expect(visible.map((entry) => entry.collectionId)).toEqual(['mine'])
    expect(await store.visibleBy('  ')).toEqual([])
  })
})

describe('InMemoryCollectionsStore votes (#694)', () => {
  it('creates ONE vote and a re-vote REPLACES the previous stars', async () => {
    const store = new InMemoryCollectionsStore()
    store.seed(collection({ collectionId: 'c1' }))
    const key = CollectionIdentity.voterKey('uid-1', 'c1')
    const documentId = 'author-a-c1'

    expect(await store.vote(documentId, key, 5)).toBe(true)
    expect(store.votes.size).toBe(1)
    expect(await store.myVote(key)).toBe(5)

    // The re-vote lands on the SAME document: never a second vote, never a
    // doubled count — the aggregate only carries the latest stars.
    expect(await store.vote(documentId, key, 2)).toBe(true)
    expect(store.votes.size).toBe(1)
    expect(await store.myVote(key)).toBe(2)

    const result = await store.readContaining('work-1')
    expect(result.kind).toBe('data')
    if (result.kind !== 'data') return
    expect(result.collections[0].ratingCount).toBe(1)
    expect(result.collections[0].ratingSum).toBe(2)
  })

  it('two different listeners add up: two documents, count two', async () => {
    const store = new InMemoryCollectionsStore()
    store.seed(collection({ collectionId: 'c1' }))
    await store.vote('author-a-c1', CollectionIdentity.voterKey('uid-1', 'c1'), 5)
    await store.vote('author-a-c1', CollectionIdentity.voterKey('uid-2', 'c1'), 1)
    expect(store.votes.size).toBe(2)
    const result = await store.readContaining('work-1')
    if (result.kind !== 'data') throw new Error('expected data')
    expect(result.collections[0].ratingSum).toBe(6)
    expect(result.collections[0].ratingCount).toBe(2)
  })

  it('refuses an unknown collection, bad stars and blank keys — changing nothing', async () => {
    const store = new InMemoryCollectionsStore()
    store.seed(collection({ collectionId: 'c1' }))
    const key = CollectionIdentity.voterKey('uid-1', 'c1')

    expect(await store.vote('author-a-missing', key, 5)).toBe(false)
    expect(await store.vote('author-a-c1', key, 0)).toBe(false)
    expect(await store.vote('author-a-c1', key, 6)).toBe(false)
    expect(await store.vote('author-a-c1', '  ', 5)).toBe(false)
    expect(await store.vote('  ', key, 5)).toBe(false)

    expect(store.votes.size).toBe(0)
    expect(await store.myVote(key)).toBeNull()
    const result = await store.readContaining('work-1')
    if (result.kind !== 'data') throw new Error('expected data')
    expect(result.collections[0].ratingSum).toBe(0)
    expect(result.collections[0].ratingCount).toBe(0)
  })

  it('has no own vote for a blank key', async () => {
    const store = new InMemoryCollectionsStore()
    expect(await store.myVote('  ')).toBeNull()
  })
})

describe('InMemoryCollectionsStore complaints (#696)', () => {
  it('counts one complaint per person and hides the collection on the third unique one', async () => {
    const store = new InMemoryCollectionsStore()
    store.seed(collection({ collectionId: 'c1' }))
    const documentId = 'author-a-c1'

    expect(await store.report(documentId, CollectionIdentity.voterKey('uid-1', 'c1'))).toBe(true)
    expect(await store.report(documentId, CollectionIdentity.voterKey('uid-2', 'c1'))).toBe(true)
    let result = await store.readContaining('work-1')
    if (result.kind !== 'data') throw new Error('expected data')
    expect(result.collections[0].reportCount).toBe(2)
    expect(result.collections[0].hidden).toBe(false)

    expect(await store.report(documentId, CollectionIdentity.voterKey('uid-3', 'c1'))).toBe(true)
    expect(store.reports.size).toBe(3)
    expect(await store.readContaining('work-1')).toEqual({ kind: 'empty' })
    expect(await store.topPublic(10)).toEqual([])
  })

  it('accepts a duplicate complaint idempotently and never counts it twice', async () => {
    const store = new InMemoryCollectionsStore()
    store.seed(collection({ collectionId: 'c1' }))
    const documentId = 'author-a-c1'
    const key = CollectionIdentity.voterKey('uid-1', 'c1')

    expect(await store.report(documentId, key)).toBe(true)
    expect(await store.report(documentId, key)).toBe(true)
    expect(store.reports.size).toBe(1)
    const result = await store.readContaining('work-1')
    if (result.kind !== 'data') throw new Error('expected data')
    expect(result.collections[0].reportCount).toBe(1)
    expect(result.collections[0].hidden).toBe(false)
  })

  it('refuses an unknown collection and blank keys — and never hides anything', async () => {
    const store = new InMemoryCollectionsStore()
    store.seed(collection({ collectionId: 'c1' }))
    expect(await store.report('author-a-missing', CollectionIdentity.voterKey('uid-1', 'c1'))).toBe(false)
    expect(await store.report('author-a-c1', '  ')).toBe(false)
    expect(store.reports.size).toBe(0)
    expect(await store.readContaining('work-1')).not.toEqual({ kind: 'empty' })
  })

  it('pins the hide threshold to the spec value (3)', () => {
    expect(CollectionModeration.HIDE_THRESHOLD).toBe(3)
    expect(CollectionModeration.nextHidden(false, 0)).toBe(false)
    expect(CollectionModeration.nextHidden(false, 2)).toBe(true)
    // hidden is ONE-WAY: it can never be turned back off.
    expect(CollectionModeration.nextHidden(true, 0)).toBe(true)
  })
})
