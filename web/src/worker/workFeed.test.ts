import { describe, expect, it } from 'vitest'
import { mergeWorkFeed, rankEditionsForPlayback } from './workFeed'

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

  it('splits one Work into language-scoped Editions and claims the source language', () => {
    // The uk card carries no language of its own: the source's declared
    // content language (uk) applies. The en card overrides it per book — the
    // same Work + same narrator still become two Editions, one Work.
    const page = mergeWorkFeed([
      { sourceId: 'sound-books', cards: [{ url: 'https://sound-books.net/a', title: 'Книга', author: 'Автор', narrator: 'Читець' }] },
      { sourceId: 'sound-books', cards: [{ url: 'https://sound-books.net/a-en', title: 'Книга', author: 'Автор', narrator: 'Читець', language: 'en' }] },
    ])

    expect(page.works).toHaveLength(1)
    expect(page.works[0].editions).toHaveLength(2)
    expect(page.works[0].editions.map((edition) => edition.language)).toEqual(['uk', 'en'])
    expect(page.works[0].editions[0].id).not.toBe(page.works[0].editions[1].id)
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

  it('ranks a verified Direct complete Edition before fresher incomplete alternatives', () => {
    const ranked = rankEditionsForPlayback([
      {
        id: 'fresh-incomplete',
        narrator: 'Читець Б',
        verifiedAt: 9_000,
        sources: [{ sourceId: 'sluhayua', url: 'https://b', verifiedAt: 9_000 }],
      },
      {
        id: 'verified-complete',
        narrator: 'Читець А',
        isComplete: true,
        chapterCount: 12,
        sources: [{ sourceId: 'sound-books', url: 'https://a', availability: 'available', verifiedAt: 1_000 }],
      },
      {
        id: 'browser-only',
        narrator: 'Читець В',
        isComplete: true,
        sources: [{ sourceId: 'fourread', url: 'https://4read.org/c', availability: 'available', verifiedAt: 10_000 }],
      },
    ])

    expect(ranked.map((edition) => edition.id)).toEqual(['verified-complete', 'browser-only', 'fresh-incomplete'])
  })
})
