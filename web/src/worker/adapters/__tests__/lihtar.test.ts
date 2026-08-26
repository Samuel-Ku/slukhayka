/**
 * spec-43/T4 — тести порту LihtarAdapter
 */
import { describe, expect, it } from 'vitest'
import { lihtarAdapter, parsePlayerPage, playerUrlOf } from '../lihtar'
import andriykoPage from '../../fixtures/lihtar-andriyko-page.html?raw'
import bookPage from '../../fixtures/lihtar-book-page.html?raw'
import longAuthorPage from '../../fixtures/lihtar-book-page-long-author.html?raw'
import categoryChildPage from '../../fixtures/lihtar-category-dytjacha.html?raw'
import libraryPage from '../../fixtures/lihtar-library.html?raw'
import playerCharivni from '../../fixtures/lihtar-player-charivni.html?raw'
import playerPage from '../../fixtures/lihtar-player-page.html?raw'
import richBookPage from '../../fixtures/lihtar-book-page-rich.html?raw'

const BOJAHUZ_URL = 'https://lihtar.in.ua/biblioteka/dytjacha-literatura/bojahuz'
const CHARIVNI_URL = 'https://lihtar.in.ua/biblioteka/dytjacha-literatura/charivni-istorii-nashoho-lisu'

describe('lihtar adapter', () => {
  it('book page resolves metadata and follows the listen link to the direct mp3', () => {
    const detail = lihtarAdapter.parseBookPage(bookPage, BOJAHUZ_URL)
    expect(detail?.title).toBe('Боягуз')
    expect(detail?.author).toBe('Микола Стеценко')
    expect(detail?.coverImageUrl).toBe(
      'https://lihtar.in.ua/images/biblioteka/85/w_bojahuz-101.jpg',
    )
    expect(detail?.descriptionHtml).toBeUndefined()
    expect(detail?.chapters).toEqual([])
    expect(playerUrlOf(bookPage)).toBe(
      'https://web.lihtar.in.ua/library/dytjacha-literatura/mykola-stecenko-bojahuz/bojahuz',
    )
    const chapters = parsePlayerPage(playerPage, detail?.title ?? '')
    expect(chapters).toHaveLength(1)
    expect(chapters[0]).toMatchObject({
      title: 'Боягуз',
      streamUrl:
        'https://web.lihtar.in.ua/audio/library/854/-dlja-ditey-slukhaty-onlayn-bojahuzdytjacha-literatura-0nmcgoa6zik-converted.mp3',
    })
  })

  it('book page keeps the cover and the h4 author when the page carries them', () => {
    const detail = lihtarAdapter.parseBookPage(richBookPage, CHARIVNI_URL)
    expect(detail?.title).toBe('Чарівні історії нашого лісу')
    expect(detail?.author).toBe('Ольга Гура')
    expect(detail?.coverImageUrl).toBe(
      'https://lihtar.in.ua/images/biblioteka/79/header_charivni-istorii-nashoho-lisu.png',
    )
    expect(detail?.descriptionHtml).toBeUndefined()
    const chapters = parsePlayerPage(playerCharivni, detail?.title ?? '')
    expect(chapters).toHaveLength(1)
    expect(chapters[0]?.streamUrl).toBe(
      'https://web.lihtar.in.ua/audio/library/79/charivni-istorii-nashoho-lisu-converted.mp3',
    )
  })

  it('author is the real h4 subtitle - a long og description is never truncated', () => {
    const detail = lihtarAdapter.parseBookPage(
      longAuthorPage,
      'https://lihtar.in.ua/biblioteka/dytjacha-literatura/stezhka-do-sertsia',
    )
    expect(detail?.title).toBe('Стежка до серця')
    expect(detail?.author).toBe('Олександра Коваленко')
    expect(detail?.descriptionHtml).toBeUndefined()
  })

  it('entities in the og description author decode', () => {
    const detail = lihtarAdapter.parseBookPage(
      andriykoPage,
      'https://lihtar.in.ua/biblioteka/dytjacha-literatura/andriyko-ta-shakhove-korolivstvo',
    )
    expect(detail?.title).toBe('Андрійко та шахове королівство')
    expect(detail?.author).toBe("Наталія Дев'ятко")
  })

  it('book page without a listen link yields no chapters', () => {
    const html = '<html><body>nope</body></html>'
    const detail = lihtarAdapter.parseBookPage(html, 'https://lihtar.in.ua/x')
    expect(detail?.chapters).toEqual([])
    expect(playerUrlOf(html)).toBeNull()
  })

  it('category pages parse into slug-titled catalogue cards', () => {
    const catalog = lihtarAdapter.parseCatalog(
      categoryChildPage,
      'https://lihtar.in.ua/biblioteka/dytjacha-literatura',
    )
    expect(catalog).not.toBeNull()
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards.map((c) => c.url)).toEqual([
      'https://lihtar.in.ua/biblioteka/dytjacha-literatura/bojahuz',
      'https://lihtar.in.ua/biblioteka/dytjacha-literatura/andriyko-ta-shakhove-korolivstvo',
      'https://lihtar.in.ua/biblioteka/dytjacha-literatura/zahublena-stinka',
    ])
    expect(lihtarAdapter.id).toBe('lihtar')
  })

  it('the library landing without book links yields no catalogue', () => {
    expect(
      lihtarAdapter.parseCatalog(libraryPage, 'https://lihtar.in.ua/biblioteka'),
    ).toBeNull()
  })
})
