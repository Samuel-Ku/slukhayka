/**
 * spec-43/T4 — тести порту SluhayuaAdapter
 */
import { describe, expect, it } from 'vitest'
import { chapterCountOf, chaptersFromPlayResponses, sluhayuaAdapter } from '../sluhayua'
import bookDescriptionPage from '../../fixtures/sluhayua-book-description.html?raw'
import bookGenresPage from '../../fixtures/sluhayua-book-genres.html?raw'
import bookMultiChapterPage from '../../fixtures/sluhayua-book-multi-chapter.html?raw'
import bookOrphanRailPage from '../../fixtures/sluhayua-book-orphan-rail.html?raw'
import bookRelatedPage from '../../fixtures/sluhayua-book-related.html?raw'
import bookSingleFilePage from '../../fixtures/sluhayua-book-single-file.html?raw'
import cardsRichJson from '../../fixtures/sluhayua-cards-rich.json?raw'
import newEscapedJson from '../../fixtures/sluhayua-new-escaped.json?raw'
import newJson from '../../fixtures/sluhayua-new.json?raw'
import searchJson from '../../fixtures/sluhayua-search.json?raw'

const MULTI_URL = 'https://sluhay.com.ua/5931576:grigorij-kvitka-osnovjanenko-serdjeshna-oksana'

describe('sluhayua adapter', () => {
  it('search json parses cards with real metadata', () => {
    const catalog = sluhayuaAdapter.parseCatalog(
      searchJson,
      'https://sluhay.com.ua/find/allcards?search=%D0%A8%D0%B5%D0%B2%D1%87%D0%B5%D0%BD%D0%BA%D0%BE&page=1',
    )
    expect(catalog).not.toBeNull()
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(2)
    expect(cards[0]).toMatchObject({
      title: 'Єретик',
      author: 'Тарас Шевченко',
      narrator: 'Євгеній Янович',
      url: 'https://sluhay.com.ua/4508492:taras-shevchenko-Єretik',
      coverImageUrl: 'https://sluhay.com.ua/uploads/1569063231.png',
    })
    expect(sluhayuaAdapter.id).toBe('sluhayua')
    expect(catalog?.sections[0]?.id).toBe('allcards')
  })

  it('blank or malformed payload yields null', () => {
    expect(
      sluhayuaAdapter.parseCatalog('   ', 'https://sluhay.com.ua/find/allcards?page=1'),
    ).toBeNull()
    expect(sluhayuaAdapter.parseCatalog('not-json-at-all', 'https://sluhay.com.ua/x')).toBeNull()
  })

  it('new feed keeps blank author for collections', () => {
    const cards =
      sluhayuaAdapter.parseCatalog(
        newJson,
        'https://sluhay.com.ua/find/allcards?sort=time&order=desc&page=1',
      )?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(2)
    expect(cards[0]).toMatchObject({
      title: '10 історій від пса Патрона «Коли ти…»',
      author: '',
    })
    expect(cards[1]).toMatchObject({
      title: 'Левеня, яке навчилося ричати',
      author: 'Костянтин Бакаєвич',
    })
  })

  it('new feed unescapes uXXXX cyrillic titles from the live json', () => {
    const cards =
      sluhayuaAdapter.parseCatalog(
        newEscapedJson,
        'https://sluhay.com.ua/find/allcards?sort=time&order=desc&page=1',
      )?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(1)
    expect(cards[0]).toMatchObject({ title: 'Колобок', author: 'Українська народна казка' })
  })

  it('rich cards parse titles authors and narrators', () => {
    const cards =
      sluhayuaAdapter.parseCatalog(
        cardsRichJson,
        'https://sluhay.com.ua/find/allcards?sort=time&order=desc&page=1',
      )?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(2)
    expect(cards[0]).toMatchObject({ title: 'Книга А', author: 'Автор А', narrator: 'Диктор' })
    expect(cards[1]).toMatchObject({ title: 'Книга Б', author: 'Автор Б' })
  })

  it('book page follows the inline playlist count to Глава N chapters', () => {
    const detail = sluhayuaAdapter.parseBookPage(bookMultiChapterPage, MULTI_URL)
    expect(detail?.title).toBe('Сердешна Оксана')
    expect(detail?.author).toBe('Григорій Квітка-Основяненко')
    expect(detail?.narrator).toBe('Діана Гончаренко')
    expect(detail?.coverImageUrl).toBe('https://sluhay.com.ua/uploads/kvitka2.png')
    expect(detail?.descriptionHtml).toBe(
      'Аудіокнигу онлайн Сердешна Оксана, читає Діана Гончаренко. Цей твір був високо оцінений Т. Шевченком.',
    )
    expect(chapterCountOf(bookMultiChapterPage)).toBe(7)
    const streams = Array.from(
      { length: 7 },
      (_, i) => `https://mp3.sluhay.com.ua/Serdeshna/0${i + 1}.mp3`,
    )
    const chapters = chaptersFromPlayResponses(streams)
    expect(chapters).toHaveLength(7)
    expect(chapters[2]).toMatchObject({
      title: 'Глава 3',
      streamUrl: 'https://mp3.sluhay.com.ua/Serdeshna/03.mp3',
    })
  })

  it('book page prefers the full body itemprop blurb over the og template', () => {
    const detail = sluhayuaAdapter.parseBookPage(bookDescriptionPage, MULTI_URL)
    expect(detail?.descriptionHtml).toBe(
      '«Сердешна Оксана» — повість про перше кохання, яке випало на важкі часи.\nДругий абзац справжньої анотації.',
    )
  })

  it('book page carries genres from the Жанр row', () => {
    const detail = sluhayuaAdapter.parseBookPage(bookGenresPage, MULTI_URL)
    expect(detail?.genres).toEqual(['казка', 'поема'])
  })

  it('related books come from the card rolls excluding the book itself', () => {
    const detail = sluhayuaAdapter.parseBookPage(bookRelatedPage, MULTI_URL)
    const related = detail?.otherNarrations ?? []
    expect(related).toHaveLength(2)
    expect(related[0]).toMatchObject({
      title: 'Інша книга',
      author: 'Григорій Квітка-Основяненко',
      url: 'https://sluhay.com.ua/3444041:grigorij-kvitka-insha-knyga',
    })
    expect(related[1]).toMatchObject({ title: 'Анфіса – золоті коси', author: '' })
  })

  it('pages without genre rows or rails keep them empty', () => {
    const detail = sluhayuaAdapter.parseBookPage(bookOrphanRailPage, MULTI_URL)
    expect(detail?.genres).toEqual([])
    expect(detail?.otherNarrations).toEqual([])
  })

  it('single-file book yields one chapter', () => {
    const detail = sluhayuaAdapter.parseBookPage(
      bookSingleFilePage,
      'https://sluhay.com.ua/1965454:olga-kobilyanska-priroda',
    )
    expect(detail?.title).toBe('Природа')
    expect(detail?.author).toBe('Ольга Кобилянська')
    expect(detail?.narrator).toBe('Максим Тимченко')
    expect(chapterCountOf(bookSingleFilePage)).toBe(1)
    const chapters = chaptersFromPlayResponses(['https://mp3.sluhay.com.ua/Pryroda/Pryroda.mp3'])
    expect(chapters).toHaveLength(1)
    expect(chapters[0]?.streamUrl).toBe('https://mp3.sluhay.com.ua/Pryroda/Pryroda.mp3')
  })

  it('play loop stops on a 0 response', () => {
    const chapters = chaptersFromPlayResponses([
      'https://mp3.sluhay.com.ua/x/01.mp3',
      '0',
      'https://mp3.sluhay.com.ua/x/03.mp3',
    ])
    expect(chapters).toHaveLength(1)
    expect(chapters[0]?.title).toBe('Глава 1')
  })

  it('unplayable page yields no chapters and keeps the metadata', () => {
    const html = '<html><body>nope</body></html>'
    const detail = sluhayuaAdapter.parseBookPage(html, 'https://sluhay.com.ua/0:none')
    expect(detail?.chapters).toEqual([])
    expect(chapterCountOf(html)).toBe(0)
  })
})
