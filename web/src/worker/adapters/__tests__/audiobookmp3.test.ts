/**
 * spec-43/T4 — тести порту AudiobookMp3Adapter
 */
import { describe, expect, it } from 'vitest'
import { audiobookMp3Adapter, playlistUrlOf, parsePlayerjsPlaylist } from '../audiobookmp3'
import bookPage from '../../fixtures/audiobookmp3-book-page.html?raw'
import bookPageMinimal from '../../fixtures/audiobookmp3-book-page-minimal.html?raw'
import emDashFeed from '../../fixtures/audiobookmp3-homepage-emdash.html?raw'
import homepage from '../../fixtures/audiobookmp3-homepage.html?raw'
import mistoPage from '../../fixtures/audiobookmp3-book-page-misto.html?raw'
import playlistFallbacks from '../../fixtures/audiobookmp3-playlist-fallbacks.json?raw'
import playlistJson from '../../fixtures/audiobookmp3-playlist.json?raw'
import richHomepage from '../../fixtures/audiobookmp3-homepage-rich.html?raw'
import ukrLitPage from '../../fixtures/audiobookmp3-genre-ukr-literatura.html?raw'

const BOOK_URL = 'https://audiobook-mp3.com/uk-audio-6163-andrij-kokotjuha-klub-bojaguziv'

describe('audiobookmp3 adapter', () => {
  it('book page parses the playerjs JSON playlist into chapters', () => {
    const detail = audiobookMp3Adapter.parseBookPage(bookPage, BOOK_URL)
    expect(detail?.title).toBe('Клуб боягузів')
    expect(detail?.author).toBe('Андрій Кокотюха')
    expect(detail?.descriptionHtml).toBe(
      'Студентські страшилки обертаються справжньою грою на виживання.',
    )
    expect(playlistUrlOf(bookPage)).toBe(
      'https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/26720.pl.txt',
    )
    const chapters = parsePlayerjsPlaylist(playlistJson)
    expect(chapters).toHaveLength(2)
    expect(chapters[0]?.streamUrl).toBe(
      'https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/track-0.mp3',
    )
    expect(chapters[0]?.title).toBe('Роберт І. Говард 1 - Черепи серед Зірок')
    expect(chapters[1]?.title).toBe('Роберт І. Говард 2 - Правиця Долі')
  })

  it('book page preserves cover narrator and genres', () => {
    const detail = audiobookMp3Adapter.parseBookPage(bookPage, BOOK_URL)
    expect(detail?.coverImageUrl).toBe(
      'https://cdn.audiobook-mp3.com/audiobooks/uk/6/1/6/3/andrij-kokotjuha-klub-bojaguziv.webp',
    )
    expect(detail?.narrator).toBe('Олександр Ткаченко')
    expect(detail?.genres).toEqual(['Детектив', 'Містика'])
    expect(detail?.otherNarrations).toEqual([])
  })

  it('book page description is the visible blurb - never the og template', () => {
    const detail = audiobookMp3Adapter.parseBookPage(bookPage, BOOK_URL)
    expect(bookPage).toContain('Слухати аудіокниги онлайн — Клуб боягузів')
    expect(detail?.descriptionHtml).not.toContain('Слухати аудіокниги онлайн')
    expect(detail?.descriptionHtml).toBe(
      'Студентські страшилки обертаються справжньою грою на виживання.',
    )
  })

  it('playlist chapter titles strip the extension and fall back to Глава N', () => {
    const chapters = parsePlayerjsPlaylist(playlistFallbacks)
    expect(chapters).toHaveLength(4)
    expect(chapters.map((c) => c.title)).toEqual([
      'Роберт І. Говард 1 - Черепи серед Зірок',
      'Без розширення',
      'Глава 3',
      'Глава 4',
    ])
  })

  it('book page without a playlist yields no chapters', () => {
    const html = '<html><body>nope</body></html>'
    const detail = audiobookMp3Adapter.parseBookPage(html, 'https://audiobook-mp3.com/uk-audio-1-x')
    expect(detail?.chapters).toEqual([])
    expect(playlistUrlOf(html)).toBeNull()
  })

  it('book page without narrator or genres keeps them empty', () => {
    const detail = audiobookMp3Adapter.parseBookPage(bookPageMinimal, BOOK_URL)
    expect(detail?.title).toBe('Клуб боягузів')
    expect(detail?.author).toBe('Андрій Кокотюха')
    expect(detail?.narrator).toBeUndefined()
    expect(detail?.genres).toEqual([])
    expect(detail?.descriptionHtml).toBeUndefined()
    expect(detail?.coverImageUrl).toBeUndefined()
  })

  it('book page splits author and title from og-title and parses the real cover', () => {
    const detail = audiobookMp3Adapter.parseBookPage(
      mistoPage,
      'https://audiobook-mp3.com/uk-audio-794-valerjan-pidmogilnij-misto',
    )
    expect(detail?.title).toBe('Місто слухати онлайн аудіокнигу безкоштовно')
    expect(detail?.author).toBe('Валер’ян Підмогильний')
    expect(detail?.coverImageUrl).toBe(
      'https://cdn.audiobook-mp3.com/audiobooks/uk/7/9/4/valerjan-pidmogilnij-misto.jpg',
    )
  })

  it('feed splits an em-dash quoted anchor into author and title', () => {
    const catalog = audiobookMp3Adapter.parseCatalog(emDashFeed, 'https://audiobook-mp3.com/uk')
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(1)
    expect(cards[0]).toMatchObject({
      title: 'Місто',
      author: 'Валер’ян Підмогильний',
      url: 'https://audiobook-mp3.com/uk-audio-794-valerjan-pidmogilnij-misto',
      coverImageUrl:
        'https://cdn.audiobook-mp3.com/audiobooks/uk/7/9/4/valerjan-pidmogilnij-misto.jpg',
    })
  })

  it('new feed parses real cyrillic title and author from the anchors', () => {
    const catalog = audiobookMp3Adapter.parseCatalog(homepage, 'https://audiobook-mp3.com/uk')
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(3)
    expect(audiobookMp3Adapter.id).toBe('audiobook-mp3')
    expect(cards[0]).toMatchObject({
      title: 'Клуб боягузів',
      author: 'Андрій Кокотюха',
      url: BOOK_URL,
      coverImageUrl:
        'https://cdn.audiobook-mp3.com/audiobooks/uk/6/1/6/3/andrij-kokotjuha-klub-bojaguziv.webp',
    })
    expect(cards[0]?.narrator).toBeUndefined()
    expect(cards[1]).toMatchObject({ title: 'Жага до життя', author: 'Джек Лондон' })
    expect(cards[1]?.coverImageUrl).toBeUndefined()
    expect(cards[2]?.title).toBe('Дім твоєї мрії')
  })

  it('feed cards carry narrator from the listing', () => {
    const catalog = audiobookMp3Adapter.parseCatalog(richHomepage, 'https://audiobook-mp3.com/uk')
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(1)
    expect(cards[0]).toMatchObject({
      title: 'Соломон Кейн',
      author: 'Роберт Говард',
      narrator: 'Костянтин Шарков',
      coverImageUrl:
        'https://cdn.audiobook-mp3.com/audiobooks/uk/6/1/9/2/robert-govard-solomon-kejn.webp',
    })
  })

  it('catalogue enumerates genre pages into books with covers', () => {
    const catalog = audiobookMp3Adapter.parseCatalog(
      ukrLitPage,
      'https://audiobook-mp3.com/uk-genre-1-ukrayinska-literatura',
    )
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(2)
    expect(cards[0]).toMatchObject({
      title: 'Кобзар',
      author: 'Тарас Шевченко',
      url: 'https://audiobook-mp3.com/uk-audio-7001-taras-shevchenko-kobzar',
      coverImageUrl: 'https://cdn.audiobook-mp3.com/audiobooks/uk/7/0/0/1/kobzar.webp',
    })
    expect(cards[1]).toMatchObject({ title: 'Лісова пісня', author: 'Леся Українка' })
  })
})
