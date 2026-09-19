/**
 * spec-51 (#697, T9) — the reading seam's contract: hidden collections never
 * reach a public surface, the rail is ranked by the shared rule, an empty read
 * is an honest empty (not a failure), and a blank query is not a query.
 */
import { describe, expect, it } from 'vitest'
import { decodePublishedCollection, type PublishedCollection } from './collectionModel'
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
