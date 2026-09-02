import { describe, expect, it } from 'vitest'
import { appendWorks, cardResultState } from './Catalog'

const emptyBook = {
  url: 'https://source.example/book',
  title: 'Книга',
  author: 'Автор',
  genres: [],
  chapters: [],
  otherNarrations: [],
}

describe('web catalog card terminal states', () => {
  it('keeps no-network and temporary source failure distinct', () => {
    expect(cardResultState(null, false, false)).toBe('no-network')
    expect(cardResultState(null, false, true)).toBe('temporary-failure')
  })

  it('keeps session-required and missing audio distinct', () => {
    expect(cardResultState(emptyBook, true, true)).toBe('browser-required')
    expect(cardResultState(emptyBook, false, true)).toBe('audio-missing')
  })

  it('requires at least one resolved chapter before playback is ready', () => {
    expect(cardResultState({
      ...emptyBook,
      chapters: [{ title: 'Розділ', streamUrl: 'https://audio.example/1.mp3' }],
    }, false, true)).toBe('ready')
  })

  it('appends unseen Works without moving the current session order', () => {
    const work = (id: string) => ({ id, mergeKey: id, title: id, author: 'Автор', editions: [] })
    expect(appendWorks([work('a'), work('b')], [work('b'), work('c')]).map((item) => item.id))
      .toEqual(['a', 'b', 'c'])
  })
})
