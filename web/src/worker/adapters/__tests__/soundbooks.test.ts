/**
 * spec-43/T4 — тести порту SoundBooksAdapter
 */
import { describe, expect, it } from 'vitest'
import { parseM3u, playlistUrlOf, soundBooksAdapter } from '../soundbooks'
import altlessCover from '../../fixtures/soundbooks-listing-altless-cover.html?raw'
import bookPage from '../../fixtures/soundbooks-book-page.html?raw'
import bookPageFull from '../../fixtures/soundbooks-book-page-full.html?raw'
import bookPageMinimal from '../../fixtures/soundbooks-book-page-minimal.html?raw'
import categoriesHome from '../../fixtures/soundbooks-categories.html?raw'
import fantastykaPage from '../../fixtures/soundbooks-category-fantastyka.html?raw'
import homepage from '../../fixtures/soundbooks-homepage.html?raw'
import homepageCardExtras from '../../fixtures/soundbooks-homepage-card-extras.html?raw'
import imageOnlyListing from '../../fixtures/soundbooks-listing-image-only.html?raw'
import lazyCoverHomepage from '../../fixtures/soundbooks-homepage-lazy-cover.html?raw'
import listingMinimal from '../../fixtures/soundbooks-listing-minimal.html?raw'
import m3u from '../../fixtures/soundbooks-playlist.m3u?raw'
import relativeCoverPage from '../../fixtures/soundbooks-book-page-relative-cover.html?raw'

const BOOK_URL = 'https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html'

describe('soundbooks adapter', () => {
  it('book page follows the m3u playlist to direct mp3 chapters', () => {
    const detail = soundBooksAdapter.parseBookPage(bookPage, BOOK_URL)
    expect(detail?.title).toBe('Темна матерія')
    expect(detail?.author).toBe('Блейк Крауч')
    expect(detail?.narrator).toBe('Pik CAH4E3')
    expect(detail?.descriptionHtml).toBe(
      'Роман Блейка Крауча про квантову фізику, паралельні світи та ціну вибору.',
    )
    expect(detail?.coverImageUrl).toBe(
      'https://sound-books.net/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp',
    )
    expect(detail?.otherNarrations).toEqual([])
    const chapters = parseM3u(m3u)
    expect(chapters).toHaveLength(2)
    expect(chapters[0]?.streamUrl).toBe('https://arch.sound-books.net/4111/Темна матерія-01.mp3')
    expect(chapters[1]?.streamUrl).toBe('https://arch.sound-books.net/4111/Темна матерія-02.mp3')
    expect(chapters[0]?.title).toBe('Темна матерія-01')
  })

  it('book page preserves genres from Жанр links with prefix stripped', () => {
    const detail = soundBooksAdapter.parseBookPage(bookPageFull, BOOK_URL)
    expect(detail?.genres).toEqual(['Зарубіжна література', 'Фантастика'])
    expect(detail?.title).toBe('Темна матерія')
    expect(detail?.author).toBe('Блейк Крауч')
    expect(detail?.narrator).toBe('Pik CAH4E3')
    expect(detail?.coverImageUrl).toBe(
      'https://sound-books.net/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp',
    )
  })

  it('book page without duration rating or series keeps them absent', () => {
    const detail = soundBooksAdapter.parseBookPage(bookPageMinimal, 'https://sound-books.net/y.html')
    expect(detail?.genres).toEqual([])
    expect(detail?.coverImageUrl).toBeUndefined()
    expect(detail?.chapters).toEqual([])
    expect(detail?.otherNarrations).toEqual([])
  })

  it('relative og-image is resolved against the site origin', () => {
    const detail = soundBooksAdapter.parseBookPage(relativeCoverPage, 'https://sound-books.net/z.html')
    expect(detail?.coverImageUrl).toBe(
      'https://sound-books.net/uploads/posts/2026-07/soniachna-mashyna.webp',
    )
  })

  it('book page without a playlist yields no chapters', () => {
    const noPlayer = '<html><body>no player</body></html>'
    const detail = soundBooksAdapter.parseBookPage(noPlayer, 'https://sound-books.net/x.html')
    expect(detail?.chapters).toEqual([])
    expect(playlistUrlOf(noPlayer)).toBeNull()
    expect(playlistUrlOf(bookPage)).toBe(
      'https://sound-books.net/uploads/public_files/2026-07/4111-krauch-bleik-temna-materiia.m3u',
    )
  })

  it('new feed splits title and author from the anchors', () => {
    const catalog = soundBooksAdapter.parseCatalog(homepage, 'https://sound-books.net/')
    expect(catalog).not.toBeNull()
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(2)
    expect(soundBooksAdapter.id).toBe('sound-books')
    expect(cards[0]).toMatchObject({
      title: 'Темна матерія',
      author: 'Блейк Крауч',
      url: BOOK_URL,
      coverImageUrl: 'https://sound-books.net/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp',
    })
    expect(cards[0]?.narrator).toBeUndefined()
    expect(cards[1]).toMatchObject({
      title: 'Статут внутрішньої служби Збройних Сил України',
      author: '',
    })
    expect(cards[1]?.coverImageUrl).toBeUndefined()
  })

  it('feed cards carry the cover tile and never a narrator', () => {
    const catalog = soundBooksAdapter.parseCatalog(homepageCardExtras, 'https://sound-books.net/')
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(2)
    expect(cards[0]).toMatchObject({
      title: 'Темна матерія',
      author: 'Блейк Крауч',
      url: BOOK_URL,
      coverImageUrl: 'https://sound-books.net/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp',
    })
    expect(cards[0]?.narrator).toBeUndefined()
    expect(cards[1]).toMatchObject({
      title: 'Статут внутрішньої служби Збройних Сил України',
      author: '',
    })
  })

  it('feed card without extras keeps fields empty', () => {
    const catalog = soundBooksAdapter.parseCatalog(listingMinimal, 'https://sound-books.net/')
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(1)
    expect(cards[0]).toMatchObject({
      title: 'Кінь-вогонь',
      author: 'Автор',
      coverImageUrl: 'https://sound-books.net/uploads/posts/2026-06/kin-vogon.webp',
    })
  })

  it('lazy cover tile never becomes the book title', () => {
    const catalog = soundBooksAdapter.parseCatalog(lazyCoverHomepage, 'https://sound-books.net/')
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(2)
    expect(cards[0]?.title.toLowerCase()).not.toContain('<img')
    expect(cards[0]).toMatchObject({
      title: 'Статут внутрішньої служби Збройних Сил України',
      author: '',
      coverImageUrl:
        'https://sound-books.net/uploads/posts/2026-07/statut-vnutrishnoi-sluzhby-zbroinykh-syl-ukrainy.webp',
    })
    expect(cards[1]).toMatchObject({ title: 'Пекельний фонограф', author: 'Роберт Блох' })
  })

  it('image-only listing falls back to the cover img alt', () => {
    const catalog = soundBooksAdapter.parseCatalog(imageOnlyListing, 'https://sound-books.net/')
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(1)
    expect(cards[0]).toMatchObject({
      title: 'Кінь-вогонь',
      coverImageUrl: 'https://sound-books.net/uploads/posts/2026-06/kin-vogon.webp',
    })
  })

  it('alt-less cover tile still yields the cover', () => {
    const catalog = soundBooksAdapter.parseCatalog(altlessCover, 'https://sound-books.net/')
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(1)
    expect(cards[0]).toMatchObject({
      title: 'Темна матерія',
      author: 'Блейк Крауч',
      coverImageUrl: 'https://sound-books.net/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp',
    })
  })

  it('catalogue parses category pages into books with covers', () => {
    expect(soundBooksAdapter.parseCatalog(categoriesHome, 'https://sound-books.net/')).toBeNull()
    const catalog = soundBooksAdapter.parseCatalog(
      fantastykaPage,
      'https://sound-books.net/fantastyka/',
    )
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(2)
    expect(cards[0]).toMatchObject({
      title: 'Сонячна машина',
      author: 'Володимир Винниченко',
      url: 'https://sound-books.net/fantastyka/3001-soniachna-mashyna.html',
      coverImageUrl: 'https://sound-books.net/uploads/posts/2026-07/soniachna-mashyna.webp',
    })
    expect(cards[1]).toMatchObject({ title: 'Тиха планета', author: '' })
  })

  it('blank or unrecognizable payload yields null', () => {
    expect(soundBooksAdapter.parseCatalog('', 'https://sound-books.net/')).toBeNull()
  })
})
