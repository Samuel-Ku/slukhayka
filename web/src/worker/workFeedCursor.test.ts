import { describe, expect, it } from 'vitest'
import { decodeWorkFeedCursor, encodeWorkFeedCursor } from './workFeedCursor'

describe('work feed cursor', () => {
  it('round-trips per-source next pages and the bounded Work offset', () => {
    const encoded = encodeWorkFeedCursor({
      offset: 30,
      pages: {
        fourread: 'https://4read.org/page/2/',
        sluhayua: 'https://sluhay.com.ua/page/2',
      },
    })

    expect(decodeWorkFeedCursor(encoded)).toEqual({
      offset: 30,
      pages: {
        fourread: 'https://4read.org/page/2/',
        sluhayua: 'https://sluhay.com.ua/page/2',
      },
    })
  })

  it('refuses malformed and oversized cursors instead of fetching arbitrary pages', () => {
    expect(decodeWorkFeedCursor('not-a-cursor')).toBeNull()
    expect(decodeWorkFeedCursor('x'.repeat(16_385))).toBeNull()
  })
})
