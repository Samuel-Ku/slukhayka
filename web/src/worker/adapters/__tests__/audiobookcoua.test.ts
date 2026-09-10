/**
 * spec-47 T6 — тести порту AudiobookCoUaAdapter
 */
import { describe, expect, it } from 'vitest'
import { audiobookcouaAdapter, noveltiesCards, playlistUrlOf, splitTitleAuthor } from '../audiobookcoua'
import { parsePlayerjsPlaylist } from '../playerjs'
import novinkiHtml from '../../fixtures/audiobookcoua-novinki.html?raw'
import bookPage from '../../fixtures/audiobookcoua-book-page.html?raw'
import playlistJson from '../../fixtures/audiobookcoua-playlist.json?raw'

const NOVINKI_URL = 'https://audiobook.co.ua/novinki-ozvuchivaniya/'
const BOOK_URL = 'https://audiobook.co.ua/pid-kupolom-stiven-king/'
const PLAYLIST_URL = 'https://audiobook.co.ua/playlist/pid-kupolom-stiven-king.txt'

describe('audiobookcoua adapter', () => {
  it('novinki grid parses Nazva-Author cards with covers and no invented authors', () => {
    const catalog = audiobookcouaAdapter.parseCatalog(novinkiHtml, NOVINKI_URL)
    expect(catalog).not.toBeNull()
    expect(catalog?.sections[0]?.id).toBe('novinki-ozvuchivaniya')
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(3)
    expect(cards[0]).toMatchObject({
      title: 'Ґолем',
      author: 'Ґустав Майрінк',
      url: 'https://audiobook.co.ua/golem-gustav-majrink/',
      coverImageUrl: 'https://audiobook.co.ua/wp-content/uploads/2026/09/golem-gustav-majrink.jpg',
    })
    expect(cards[1]).toMatchObject({ title: 'Кров і пісок', author: 'Вісенте Бласко Ібаньєс' })
    // A collection card without the separator never gets a fake author.
    expect(cards[2]).toMatchObject({ title: 'Збірка казок без автора', author: '' })
  })

  it('the post-grid search form never becomes a card', () => {
    const cards = noveltiesCards(novinkiHtml)
    expect(cards).toHaveLength(3)
    expect(cards.some((card) => card.title.toLowerCase().includes('поиск'))).toBe(false)
  })

  it('feed respects the limit', () => {
    expect(noveltiesCards(novinkiHtml).slice(0, 1)).toHaveLength(1)
  })

  it('blank grid never throws and yields no cards', () => {
    expect(noveltiesCards('')).toEqual([])
    expect(audiobookcouaAdapter.parseCatalog('', NOVINKI_URL)).toBeNull()
  })

  it('book page parses metadata and the escaped playlist url from the Playerjs init', () => {
    const detail = audiobookcouaAdapter.parseBookPage(bookPage, BOOK_URL)
    expect(detail?.title).toBe('Під куполом')
    expect(detail?.author).toBe('Стівен Кінг')
    expect(detail?.coverImageUrl).toBe(
      'https://audiobook.co.ua/wp-content/uploads/2023/12/pid-kupolom-stiven-king.jpg',
    )
    // The live page escapes the playlist URL's slashes (\/) — decoded once.
    expect(playlistUrlOf(bookPage)).toBe(PLAYLIST_URL)
    expect(detail?.chapters).toEqual([])
    // Measured absence (T1 spike): the page carries no real blurb.
    expect(detail?.descriptionHtml).toBeUndefined()
  })

  it('the playlist json yields ordered chapters with trimmed titles', () => {
    const chapters = parsePlayerjsPlaylist(playlistJson)
    expect(chapters).toHaveLength(10)
    // Leading spaces of the live track titles are trimmed; the title text
    // itself is kept as the source renders it (no renames).
    expect(chapters[0]).toMatchObject({
      title: '1   Під куполом - Стівен Кінг',
      streamUrl: 'https://archive.org/download/008_20231203_202312/001.mp3',
    })
    expect(chapters[9]?.streamUrl).toBe('https://archive.org/download/008_20231203_202312/010.mp3')
  })

  it('chapter without a title falls back to a numbered name', () => {
    const sparse = '[{"file": "https://archive.org/x/001.mp3"}, {"title": "Частина", "file": "https://archive.org/x/002.mp3"}]'
    const chapters = parsePlayerjsPlaylist(sparse)
    expect(chapters).toHaveLength(2)
    // The web worker's SHARED playerjs helper uses «Глава N» (as the sluhay
    // and audiobook-mp3 web ports do) — the Kotlin adapter's «Розділ N» is a
    // cosmetic render choice; counts and stream URLs are identical.
    expect(chapters[0]?.title).toBe('Глава 1')
    expect(chapters[1]).toMatchObject({ title: 'Частина', streamUrl: 'https://archive.org/x/002.mp3' })
  })

  it('garbage playlist json yields no chapters', () => {
    expect(parsePlayerjsPlaylist('not-json-at-all')).toEqual([])
  })

  it('page without a Playerjs init keeps metadata with no chapters', () => {
    const playerlessPage = `
      <title>Аудиокнига Кобзар - Тарас Шевченко скачать, слушать онлайн - Аудиокниги на украинском языке</title>
      <meta property="og:title" content="Аудиокнига Кобзар - Тарас Шевченко скачать, слушать онлайн - Аудиокниги на украинском языке">
      <meta property="og:image" content="https://audiobook.co.ua/wp-content/uploads/2020/01/kobzar.jpg">
    `
    const detail = audiobookcouaAdapter.parseBookPage(
      playerlessPage,
      'https://audiobook.co.ua/kobzar-taras-shevchenko/',
    )
    expect(detail?.title).toBe('Кобзар')
    expect(detail?.author).toBe('Тарас Шевченко')
    expect(detail?.chapters).toEqual([])
    expect(playlistUrlOf(playerlessPage)).toBeNull()
  })

  it('blank html never throws and stays absent', () => {
    const detail = audiobookcouaAdapter.parseBookPage('', BOOK_URL)
    expect(detail).not.toBeNull()
    expect(detail?.title).toBe('')
    expect(detail?.author).toBe('')
    expect(detail?.chapters).toEqual([])
  })

  it('the no-separator split keeps the author empty', () => {
    expect(splitTitleAuthor('Збірка казок без автора')).toEqual({ title: 'Збірка казок без автора', author: '' })
    expect(splitTitleAuthor('Ґолем - Ґустав Майрінк')).toEqual({ title: 'Ґолем', author: 'Ґустав Майрінк' })
  })

  it('registration identity - ukrainian, direct, and no search member', () => {
    expect(audiobookcouaAdapter.id).toBe('audiobookcoua')
    expect(audiobookcouaAdapter.displayName).toBe('Audiobook.co.ua')
    expect(audiobookcouaAdapter.baseUrl).toBe('https://audiobook.co.ua')
    // The site's keyword form does not filter server-side (T1 verdict) — the
    // adapter honestly lacks the search member, exactly the Kotlin empty search().
    expect((audiobookcouaAdapter as { search?: unknown }).search).toBeUndefined()
  })
})