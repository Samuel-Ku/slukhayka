/**
 * spec-47 T6 — тести порту ChytayloAdapter
 */
import { describe, expect, it } from 'vitest'
import { chytayloAdapter, listingCards, playerPropsFrom } from '../chytaylo'
import listingHtml from '../../fixtures/chytaylo-listing.html?raw'
import bookPage from '../../fixtures/chytaylo-book-page.html?raw'

const LISTING_URL = 'https://chytaylo.com.ua/audiobooks'
const BOOK_URL = 'https://chytaylo.com.ua/books/dzheyn-eyr'

describe('chytaylo adapter', () => {
  it('book page parses JSON-LD metadata and the escaped tracks payload', () => {
    const detail = chytayloAdapter.parseBookPage(bookPage, BOOK_URL)
    expect(detail?.title).toBe('Джейн Ейр')
    expect(detail?.author).toBe('Шарлотта Бронте')
    expect(detail?.coverImageUrl).toBe(
      'https://chytaylo.com.ua/api/uploads/book-cover-1788808046290-897490821aa755e6.webp',
    )
    // The page's own BCP-47 claim («uk-UA»), normalized — never guessed.
    expect(detail?.language).toBe('uk')
    expect(detail?.chapters).toHaveLength(2)
    expect(detail?.chapters[0]).toMatchObject({
      title: 'Частина 1',
      streamUrl: 'https://chytaylo.com.ua/api/audio-local/book-dzheyn-eyr-part-001-76af7d7b3a3b.mp3',
    })
    expect(detail?.chapters[1]?.streamUrl).toBe(
      'https://chytaylo.com.ua/api/audio-local/book-dzheyn-eyr-part-002-f9444e385fd9.mp3',
    )
    // The «Про що книга» container's text; the sections after it never leak in.
    expect(detail?.descriptionHtml).toContain('Джейн Ейр рано лишається без батьків')
    expect(detail?.descriptionHtml).toContain('Едвард Рочестер приваблює Джейн')
    expect(detail?.descriptionHtml).not.toContain('Схожі книги')
  })

  it('playerPropsFrom decodes the escaped payload once', () => {
    const props = playerPropsFrom(bookPage)
    expect(props).not.toBeNull()
    expect(props?.tracks).toHaveLength(2)
    expect(props?.tracks[0]).toEqual({
      title: 'Частина 1',
      url: '/api/audio-local/book-dzheyn-eyr-part-001-76af7d7b3a3b.mp3',
    })
    // The decoded window starts at the tracks key — bookTitle PRECEDES it in
    // the payload, so the fallback is never visible (the Kotlin adapter has
    // the same window semantics; the JSON-LD name is authoritative). The
    // coverUrl fallback, which FOLLOWS the tracks, does resolve.
    expect(props?.bookTitle).toBe('')
    expect(props?.coverUrl).toBe('/api/uploads/book-cover-1788808046290-897490821aa755e6.webp')
  })

  it('page without the tracks payload is honestly empty - the audio-only boundary', () => {
    // A text-book page of the same site (spec-47's content boundary): no
    // escaped tracks payload — nothing playable, chapters honestly empty.
    const textBookPage = `
      <title>Текстова книга • Читайло</title>
      <script type="application/ld+json">[{"@context":"https://schema.org","@type":"Book","name":"Текстова книга","author":{"@type":"Person","name":"Хтось"},"inLanguage":"uk-UA"}]</script>
      <div class="reader-content">Читати онлайн…</div>
    `
    const detail = chytayloAdapter.parseBookPage(textBookPage, 'https://chytaylo.com.ua/books/tekstova')
    expect(detail?.chapters).toEqual([])
    expect(playerPropsFrom(textBookPage)).toBeNull()
  })

  it('listing parses title author cards with covers and no invented authors', () => {
    const catalog = chytayloAdapter.parseCatalog(listingHtml, LISTING_URL)
    expect(catalog).not.toBeNull()
    expect(catalog?.sections[0]?.id).toBe('audiobooks')
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(3)
    expect(cards[0]).toMatchObject({
      title: 'Джакомо Джойс',
      author: 'Джеймс Джойс',
      url: 'https://chytaylo.com.ua/books/dzhakomo-dzhoys',
      coverImageUrl: 'https://chytaylo.com.ua/api/uploads/book-cover-1788939378809-04d9f36346755a4c.webp',
    })
    expect(cards[1]).toMatchObject({ title: 'Дари волхвів', author: 'О. Генрі' })
    // A card without the author div never gets a fake author.
    expect(cards[2]).toMatchObject({ title: 'Збірка без автора', author: '' })
  })

  it('non-listing articles never become cards', () => {
    const cards = listingCards(listingHtml)
    expect(cards).toHaveLength(3)
    expect(cards.some((card) => card.title === 'Схожа книга')).toBe(false)
    expect(cards.some((card) => card.url.endsWith('skhozha-knyga'))).toBe(false)
  })

  it('blank html never throws and stays absent', () => {
    const detail = chytayloAdapter.parseBookPage('', BOOK_URL)
    expect(detail).not.toBeNull()
    expect(detail?.title).toBe('')
    expect(detail?.chapters).toEqual([])
    expect(chytayloAdapter.parseCatalog('', LISTING_URL)).toBeNull()
  })

  it('registration identity - ukrainian, direct, no search member', () => {
    expect(chytayloAdapter.id).toBe('chytaylo')
    expect(chytayloAdapter.displayName).toBe('Читайло')
    expect(chytayloAdapter.baseUrl).toBe('https://chytaylo.com.ua')
    // No server-side search endpoint (T1/T3 probes) — the honest absence.
    expect((chytayloAdapter as { search?: unknown }).search).toBeUndefined()
  })
})