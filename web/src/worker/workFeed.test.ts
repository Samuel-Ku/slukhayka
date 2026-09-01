import { describe, expect, it } from 'vitest'
import { mergeWorkFeed } from './workFeed'

describe('mergeWorkFeed', () => {
  it('merges a Work while preserving narrator Editions and stable source order', () => {
    const page = mergeWorkFeed([
      { sourceId: 'fourread', cards: [{ url: 'https://4read.org/a', title: 'Книга', author: 'Автор', narrator: 'Читець А' }] },
      { sourceId: 'sluhay', cards: [{ url: 'https://sluhay.com/a', title: 'Книга', author: 'Автор', narrator: 'Читець Б' }] },
    ])

    expect(page.works).toHaveLength(1)
    expect(page.works[0].editions.map((edition) => edition.narrator)).toEqual(['Читець А', 'Читець Б'])
    expect(page.works[0].editions[0].sources[0].sourceId).toBe('fourread')
  })

  it('uses a bounded cursor and keeps the remainder for the next page', () => {
    const input = Array.from({ length: 31 }, (_, n) => ({
      sourceId: 'fourread' as const,
      cards: [{ url: `https://4read.org/${n}`, title: `Книга ${n}`, author: 'Автор' }],
    }))
    const first = mergeWorkFeed(input, 0, 30)
    expect(first.works).toHaveLength(30)
    expect(first.nextCursor).toBe('30')
    expect(mergeWorkFeed(input, 30, 30).works).toHaveLength(1)
  })

  it('prefers a direct source only within the same selected Edition', () => {
    const page = mergeWorkFeed([
      { sourceId: 'fourread', cards: [{ url: 'https://4read.org/a', title: 'Книга', author: 'Автор', narrator: 'А' }] },
      { sourceId: 'sound-books', cards: [{ url: 'https://sound-books.net/a', title: 'Книга', author: 'Автор', narrator: 'А' }] },
    ])
    expect(page.works[0].editions[0].sources[0].sourceId).toBe('sound-books')
  })
})
