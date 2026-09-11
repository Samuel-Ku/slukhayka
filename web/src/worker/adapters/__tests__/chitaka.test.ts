/**
 * spec-50 T5 — тести порту ChitakaAdapter
 */
import { describe, expect, it } from 'vitest'
import { chitakaAdapter, listingCards } from '../chitaka'
import listingHtml from '../../fixtures/chitaka-listing.html?raw'
import bookPage from '../../fixtures/chitaka-book-page.html?raw'

const BOOK_URL = 'https://chitaka.com.ua/knigi/1984/'

describe('chitaka adapter', () => {
  it('listing cards parse lazyload covers, nav anchors never enter', () => {
    const cards = listingCards(listingHtml)
    expect(cards).toHaveLength(2)
    expect(cards[0]).toMatchObject({
      title: '1984',
      url: 'https://chitaka.com.ua/knigi/1984/',
      coverImageUrl: 'https://chitaka.com.ua/wp-content/uploads/2022/10/1984-237x362.jpg',
    })
    expect(cards[1]?.title).toBe('Фарбований лис')
  })

  it('book page parses the native audio tag into one track', () => {
    const detail = chitakaAdapter.parseBookPage(bookPage, BOOK_URL)
    expect(detail?.title).toBe('«1984»')
    expect(detail?.author).toBe('Джордж Орвелл')
    expect(detail?.coverImageUrl).toBe('https://chitaka.com.ua/wp-content/uploads/2022/10/1984-og.jpg')
    expect(detail?.language).toBe('uk')
    expect(detail?.chapters).toHaveLength(1)
    expect(detail?.chapters[0]).toMatchObject({
      title: '«1984»',
      streamUrl: 'https://chitaka.com.ua/wp-content/uploads/2022/10/1984.mp3',
    })
  })

  it('text-only page is honestly empty - the audio-only boundary', () => {
    const detail = chitakaAdapter.parseBookPage('<title>«Есеї» Хтось</title>', BOOK_URL)
    expect(detail?.chapters).toEqual([])
    expect(detail?.title).toBe('«Есеї»')
  })
})
