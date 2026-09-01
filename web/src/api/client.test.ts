import { describe, expect, it } from 'vitest'
import { dedupeWorks } from './client'
import { appendCatalogPage } from '../ui/Catalog'
import { cardResultState } from '../ui/Catalog'

describe('dedupeWorks', () => {
  it('keeps distinct Source editions of the same Work', () => {
    const groups = dedupeWorks([
      { id: 'fourread', displayName: '4read', cards: [{ url: 'https://4read.org/a', title: 'Книга', author: 'Автор' }] },
      { id: 'sluhay', displayName: 'Sluhay', cards: [{ url: 'https://sluhay.com/a', title: 'Книга', author: 'Автор' }] },
    ])

    expect(groups).toHaveLength(2)
    expect(groups.flatMap((group) => group.cards)).toHaveLength(2)
  })
})

describe('card action terminal states', () => {
  const playable = { chapters: [{ title: '1', streamUrl: 'https://example.test/1.mp3' }] } as never
  const missing = { chapters: [] } as never

  it('does not treat an HTTP-shaped response without audio as playable', () => {
    expect(cardResultState(playable, false, true)).toBe('ready')
    expect(cardResultState(missing, false, true)).toBe('audio-missing')
  })

  it('keeps no-network, temporary failure and session-required distinct', () => {
    expect(cardResultState(null, false, false)).toBe('no-network')
    expect(cardResultState(null, false, true)).toBe('temporary-failure')
    expect(cardResultState(missing, true, true)).toBe('browser-required')
  })
})

describe('appendCatalogPage', () => {
  it('keeps seen card order and appends only new cursor cards', () => {
    const result = appendCatalogPage(
      [{ id: 'new', title: 'Нове', cards: [{ url: 'a', title: 'A', author: '' }] }],
      [{ id: 'new', title: 'Нове', cards: [{ url: 'a', title: 'A', author: '' }, { url: 'b', title: 'B', author: '' }] }],
    )

    expect(result[0].cards.map((card) => card.url)).toEqual(['a', 'b'])
  })
})
