/**
 * spec-50 T5 — тести порту KnigiOnlineAdapter
 */
import { describe, expect, it } from 'vitest'
import { knigionlineAdapter, listingCards, playlistTracks, playlistUrlOf, splitTitleAuthor } from '../knigionline'
import searchHtml from '../../fixtures/knigionline-search.html?raw'
import bookPage from '../../fixtures/knigionline-book-page.html?raw'
import playlistJson from '../../fixtures/knigionline-playlist.json?raw'

const BOOK_URL = 'https://knigi-online.com.ua/audioknyha-toreadory-z-vasiukivky-vsevolod-nestayko/'

describe('knigionline adapter', () => {
  it('search cards keep audiobooks only - the ebook card never enters', () => {
    const cards = listingCards(searchHtml)
    expect(cards).toHaveLength(1)
    expect(cards[0]).toMatchObject({
      title: '«Пригоди Тома Соєра»',
      author: 'Марк Твен',
      url: 'https://knigi-online.com.ua/audioknyha-pryhody-toma-soiera-mark-tven/',
    })
  })

  it('search parses the same cards from a result payload', () => {
    const cards = knigionlineAdapter.search?.(searchHtml, 'https://knigi-online.com.ua/?s=test') ?? []
    expect(cards).toHaveLength(1)
    expect(cards[0]?.url).toBe('https://knigi-online.com.ua/audioknyha-pryhody-toma-soiera-mark-tven/')
  })

  it('splitTitleAuthor splits at the closing guillemet, never invented', () => {
    expect(splitTitleAuthor('«Пригоди Тома Соєра» Марк Твен')).toEqual({
      title: '«Пригоди Тома Соєра»',
      author: 'Марк Твен',
    })
    expect(splitTitleAuthor('Без лапок узагалі')).toEqual({ title: 'Без лапок узагалі', author: '' })
  })

  it('book page parses og metadata and the tracks-url block', () => {
    const detail = knigionlineAdapter.parseBookPage(bookPage, BOOK_URL)
    expect(detail?.title).toBe('«Тореадори з Васюківки»')
    expect(detail?.author).toBe('Всеволод Нестайко')
    expect(detail?.coverImageUrl).toBe('https://knigi-online.com.ua/wp-content/uploads/2024/09/Toreadory-og.jpg')
    expect(detail?.language).toBe('uk')
    expect(playlistUrlOf(bookPage)).toBe('https://knigi-online.com.ua/?audioigniter_playlist_id=531')
  })

  it('playlist JSON parses tracks in order with direct mp3s', () => {
    const tracks = playlistTracks(playlistJson)
    expect(tracks).toHaveLength(2)
    expect(tracks[0]).toEqual({
      title: '1',
      url: 'https://knigi-online.com.ua/wp-content/uploads/2024/09/01.-Toreadory-z-Vasiukivky.mp3',
    })
    expect(tracks[1]?.title).toBe('2')
  })

  it('broken playlist JSON is honestly empty, never a throw', () => {
    expect(playlistTracks('not json {{{')).toEqual([])
    expect(playlistTracks('')).toEqual([])
  })

  it('book page without tracks-url is honestly empty', () => {
    const detail = knigionlineAdapter.parseBookPage('<title>Аудіокнига «Порожня» Хтось</title>', BOOK_URL)
    expect(detail?.chapters).toEqual([])
    expect(detail?.url).toBe(BOOK_URL)
  })
})
