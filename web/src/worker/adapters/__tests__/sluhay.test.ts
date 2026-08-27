/**
 * spec-43/T4 — тести порту SluhayAdapter
 */
import { describe, expect, it } from 'vitest'
import { parsePlayerjsPlaylist, parsePosterRows, playlistUrlOf, sluhayAdapter } from '../sluhay'
import bookPage from '../../fixtures/sluhay-book-page.html?raw'
import bookPageMinimal from '../../fixtures/sluhay-book-page-minimal.html?raw'
import bookPageNoPlayer from '../../fixtures/sluhay-book-page-no-player.html?raw'
import categoryPage from '../../fixtures/sluhay-category.html?raw'
import homePage from '../../fixtures/sluhay-homepage.html?raw'
import playlistJson from '../../fixtures/sluhay-playlist.json?raw'

const BOOK_URL = 'https://sluhay.com/svitova-literatura/6150-dzho-aberkrombi-trohi-nenavisti.html'
const INLINE_PLAYLIST_URL = 'https://9giiu0g54k8c.redirectto.cc/s05/2/6/5/4/4/26544.pl.txt'

describe('sluhay adapter', () => {
  it('book page parses metadata and ordered chapters from the inline playlist', () => {
    const detail = sluhayAdapter.parseBookPage(bookPage, BOOK_URL)
    expect(detail?.title).toBe('Трохи ненависті')
    expect(detail?.author).toBe('Джо Аберкромбі')
    expect(detail?.narrator).toBeUndefined()
    expect(detail?.descriptionHtml).toBe(
      'Над Адуа зависочіли промислові труби, тож світ закипає, бо зароджується нова ера.',
    )
    expect(detail?.coverImageUrl).toBe(
      'https://sluhay.com/uploads/posts/books/6150/dzho-aberkrombi-trohi-nenavisti.webp',
    )
    expect(detail?.genres).toEqual([])
    expect(playlistUrlOf(bookPage)).toBe(INLINE_PLAYLIST_URL)
    const chapters = parsePlayerjsPlaylist(playlistJson)
    expect(chapters).toHaveLength(3)
    expect(chapters[0]).toMatchObject({
      title: 'Трохи ненависті 01',
      streamUrl: 'https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-0.mp3',
    })
    expect(chapters[2]?.streamUrl).toBe(
      'https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-2.mp3',
    )
  })

  it('captured html without a playlist yields metadata but no chapters', () => {
    const detail = sluhayAdapter.parseBookPage(bookPageNoPlayer, BOOK_URL)
    expect(detail?.title).toBe('Трохи ненависті')
    expect(detail?.author).toBe('Джо Аберкромбі')
    expect(detail?.chapters).toEqual([])
    expect(playlistUrlOf(bookPageNoPlayer)).toBeNull()
  })

  it('og title fallback splits when meta rows are absent', () => {
    const detail = sluhayAdapter.parseBookPage(
      bookPageMinimal,
      'https://sluhay.com/svitova-literatura/6066-x.html',
    )
    expect(detail?.title).toBe('Метаморфоза Землі')
    expect(detail?.author).toBe('Кларк Ештон Сміт')
    expect(detail?.coverImageUrl).toBe('https://sluhay.com/uploads/books/6066/cover.webp')
    expect(detail?.chapters).toEqual([])
  })

  it('blank html never throws and stays absent', () => {
    const detail = sluhayAdapter.parseBookPage('', BOOK_URL)
    expect(detail).not.toBeNull()
    expect(detail?.title).toBe('')
    expect(detail?.author).toBe('')
    expect(detail?.chapters).toEqual([])
  })

  it('garbage playlist json yields no chapters', () => {
    expect(parsePlayerjsPlaylist('not-json-at-all')).toEqual([])
  })

  it('homepage poster rows parse into native feed books', () => {
    const books = parsePosterRows(homePage, 10)
    expect(books).toHaveLength(2)
    expect(sluhayAdapter.id).toBe('sluhay')
    expect(books[0]).toMatchObject({
      title: 'Пасажир',
      author: 'Жан-Крістоф Гранже (Ґранже)',
      url: 'https://sluhay.com/svitova-literatura/6177-zhan-kristof-granzhe-pasazhir.html',
      coverImageUrl: 'https://sluhay.com/uploads/posts/books/6177/zhan-kristof-granzhe-pasazhir.webp',
    })
    expect(books[1]).toMatchObject({ title: 'З Ейнштейном у рюкзаку', author: 'Андрій Бачинський' })
  })

  it('feed respects the limit', () => {
    const books = parsePosterRows(homePage, 1)
    expect(books).toHaveLength(1)
    expect(books[0]?.title).toBe('Пасажир')
  })

  it('blank homepage never throws and yields no books', () => {
    expect(parsePosterRows('', 10)).toEqual([])
    expect(sluhayAdapter.parseCatalog('', 'https://sluhay.com/')).toBeNull()
  })

  it('catalogue parses category pages into cards', () => {
    const catalog = sluhayAdapter.parseCatalog(
      categoryPage,
      'https://sluhay.com/svitova-literatura/',
    )
    expect(catalog).not.toBeNull()
    expect(catalog?.sections[0]?.id).toBe('svitova-literatura')
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(2)
    expect(cards[0]).toMatchObject({ title: 'Дім шовку', author: 'Ентоні Горовіц' })
    expect(cards[1]).toMatchObject({ title: 'Чаклунський світ', author: 'Андре Нортон' })
    const homeCatalog = sluhayAdapter.parseCatalog(homePage, 'https://sluhay.com/')
    expect(homeCatalog?.sections[0]?.id).toBe('home')
    expect(homeCatalog?.sections[0]?.cards).toHaveLength(2)
  })
})
