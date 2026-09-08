import { describe, expect, it } from 'vitest'
import {
  buildBookDetail,
  collectAudioRefs,
  expandPlaylist,
  fourread,
  parseGenreNav,
  parseNextPageUrl,
  parsePeopleList,
  parsePopularBooks,
  parsePmovieCycle,
  parseRatingScore,
  parseRelatedBooks,
  parseTop100,
} from '../fourread'

const HOMEPAGE = `
<div class="poster has-overlay">
  <div class="poster__img">
    <img src="/uploads/posts/2026-06/medium/cover.webp">
    <div class="poster__series anim"><a href="https://4read.org/xfsearch/cikl/cycle/">Цикл назви</a></div>
    <div class="poster__label poster__label--blue">2</div>
  </div>
  <div class="poster__desc order-last">
    <a href="https://4read.org/7611-neostannij-bij.html" class="poster__link">
      <div class="poster__title line-clamp">Неостанній бій</div></a>
    <div class="poster__subtitle ws-nowrap">Костянтин Шелест</div>
  </div>
</div>
<div class="poster second">
  <div class="poster__desc order-last">
    <a href="https://4read.org/7589-vkrady-mene-zaraz.html" class="poster__link">
      <div class="poster__title line-clamp">Вкради мене зараз!</div></a>
    <div class="poster__subtitle ws-nowrap">Сергій Оріанець</div>
  </div>
</div>
<div class="sb__title">Аудіокниги жанру: Популярне</div>
<div class="sb__content sb__grid">
  <a class="ftop-item d-flex ai-center has-overlay" href="https://4read.org/7894-pasazhyr.html">
    <div class="ftop-item__img img-fit-cover"><img src="/uploads/posts/x.webp" alt="Жан-Крістоф Ґранже - Пасажир"></div>
    <div class="ftop-item__desc flex-grow-1">
      <div class="ftop-item__title poster__title line-clamp">Пасажир</div>
      <div class="ftop-item__meta poster__subtitle line-clamp">Детектив</div>
    </div>
  </a>
</div>
`

const BOOK_PAGE = `
<html><head>
<meta property="og:title" content="Неостанній бій - АудіоКниги Українською">
<meta property="og:image" content="https://4read.org/uploads/posts/2026-06/medium/neostannij-bij.webp">
<meta property="og:description" content="Коротка анотація з мета-тега.">
</head><body>
<h1 class="title">Неостанній бій</h1>
<ul class="pmovie__list">
  <li><span> Автор: </span><a href="#">Костянтин Шелест</a></li>
  <li><span> Читає: </span>Олександр Волох</li>
  <li><span> Жанр: </span><a href="#">Аудіокниги</a>, <a href="#">Фентезі</a>, <a href="#">Бойовик</a></li>
  <li><span> Цикл: </span><a href="https://4read.org/xfsearch/cikl/xyz/">Трохи ненависті</a><meta itemprop="position" content="volumeNumber" value="1"></li>
</ul>
<div class="pmovie__rating-score"> 4.7 </div>
<div itemprop="description"><p>Перший абзац повної анотації про події книги.</p>
<p>Другий абзац теж про книгу.</p>
<p>Телеграм канал автора t.me/x</p></div>
<section class="sect pmovie__related carou">
  <div class="poster ">
    <div class="poster__desc order-last">
      <a href="https://4read.org/7000-inshe.html" class="poster__link"><div class="poster__title line-clamp">Інша книга</div></a>
      <div class="poster__subtitle ws-nowrap">Інший автор</div>
    </div>
  </div>
</section>
<script>var player = new Playerjs({file:"{v1}7589/playlist.txt"});</script>
<iframe src="/player/123.html"></iframe>
</body></html>
`

const PLAYLIST_TXT = `https://4read.org/uploads/audio/7589/01.mp3
https://4read.org/uploads/audio/7589/02.mp3
`
const IFRAME_HTML = '<audio><source src="/uploads/files/a.mp3"></audio>'

describe('fourread catalog', () => {
  it('splits the homepage into Новинки, Цикли and Популярне', () => {
    const parsed = fourread.parseCatalog(HOMEPAGE, 'https://4read.org/')
    expect(parsed).not.toBeNull()
    const sections = parsed!.sections
    expect(sections.map((s) => s.id)).toEqual(['new-arrivals', 'series', 'popular'])
    expect(sections[0].cards[0]).toMatchObject({
      title: 'Неостанній бій',
      author: 'Костянтин Шелест',
      seriesName: 'Цикл назви',
      seriesPart: 2,
      coverImageUrl: 'https://4read.org/uploads/posts/2026-06/medium/cover.webp',
    })
    expect(sections[1].cards).toHaveLength(1)
    expect(sections[2].cards[0]).toMatchObject({ title: 'Пасажир', author: 'Жан-Крістоф Ґранже' })
  })

  it('a category page yields one section plus DLE pagination', () => {
    const html = HOMEPAGE + '<div class="pagination " id="pagination"><a href="/fentezi/page/2/">2</a><a href="/fentezi/page/3/">3</a></div>'
    const parsed = fourread.parseCatalog(html, 'https://4read.org/fentezi/')
    expect(parsed!.sections[0].cards.length).toBeGreaterThan(0)
    expect(parsed!.nextPageUrl).toBe('https://4read.org/fentezi/page/2/')
  })

  it('empty markup degrades to null / empty, never a throw', () => {
    expect(fourread.parseCatalog('', 'https://4read.org/')).toBeNull()
    expect(parsePopularBooks('<html></html>')).toEqual([])
    expect(parseNextPageUrl('<html></html>')).toBeNull()
    expect(parseRelatedBooks('<html></html>')).toEqual([])
    expect(parsePmovieCycle('<html></html>')).toBeNull()
    expect(parseRatingScore('<html></html>')).toBeUndefined()
  })
})

describe('fourread book page (sync part)', () => {
  it('extracts meta: title without suffix, author, narrator, genres kept specific, related', () => {
    const detail = fourread.parseBookPage(BOOK_PAGE, 'https://4read.org/7611-neostannij-bij.html')!
    expect(detail.title).toBe('Неостанній бій')
    expect(detail.author).toBe('Костянтин Шелест')
    expect(detail.narrator).toBe('Олександр Волох')
    expect(detail.genres).toEqual(['Фентезі', 'Бойовик'])
    expect(detail.coverImageUrl).toContain('neostannij-bij.webp')
    // W4.1 — honest split: the pmovie__related posters ride «Можливо, Тебе
    // зацікавить», otherNarrations stays empty (no rendition registry yet).
    expect(detail.otherNarrations).toEqual([])
    expect(detail.relatedBooks[0]).toMatchObject({ title: 'Інша книга', author: 'Інший автор' })
    expect(detail.series).toEqual({ name: 'Трохи ненависті', url: 'https://4read.org/xfsearch/cikl/xyz/', position: 1 })
    expect(detail.rating).toBe(4.7)
    expect(detail.descriptionHtml).toBe('Перший абзац повної анотації про події книги.\nДругий абзац теж про книгу.')
  })

  it('collects direct and relative audio refs with JVM-safe encoding', () => {
    const refs = collectAudioRefs(
      '<audio src="https://cdn.example.org/a-1.mp3?x=1"></audio>' +
        "<script>file: '/uploads/audio/9/Глава 01.mp3'</script>",
      'https://4read.org/7611-x.html',
    )
    expect(refs[0]).toBe('https://cdn.example.org/a-1.mp3?x=1')
    expect(refs[1]).toBe('https://4read.org/uploads/audio/9/%D0%93%D0%BB%D0%B0%D0%B2%D0%B0%2001.mp3')
  })

  it('{v1} obfuscation resolves through the m3u prefix and playlist expands', async () => {
    const detail = await buildBookDetail(BOOK_PAGE, 'https://4read.org/7611-neostannij-bij.html', async (url) => {
      if (url.includes('/m3u/')) return `${PLAYLIST_TXT}`
      if (url.includes('/player/')) return IFRAME_HTML
      return null
    })
    expect(detail).not.toBeNull()
    // {v1} → https://4read.org/m3u/7589/playlist.txt → resolve → two mp3 lines;
    // iframe adds its own relative source.
    const urls = detail!.chapters.map((c) => c.streamUrl)
    expect(urls.filter((u) => u.includes('/uploads/audio/7589/')).length).toBeGreaterThanOrEqual(2)
    expect(urls.some((u) => u.endsWith('/uploads/files/a.mp3'))).toBe(true)
    expect(detail!.chapters.every((c) => !c.streamUrl.includes('{v1}'))).toBe(true)
  })

  it('expandPlaylist reads both playerjs JSON and plain m3u bodies', () => {
    expect(expandPlaylist('[{"title":"Глава 1","file":"https://4read.org/uploads/audio/7589/01.mp3"}]'))
      .toEqual(['https://4read.org/uploads/audio/7589/01.mp3'])
    expect(expandPlaylist(PLAYLIST_TXT)).toHaveLength(2)
  })
})

describe('fourread indexes (W3.2)', () => {
  it('top-100 linek cards parse into ranked books with real duration', () => {
    const html = `
      <div class="sect__content count-items">
        <div class="linek d-flex ai-center has-overlay card">
          <div class="linek__img img-fit-cover"><img src="/uploads/posts/2026-02/medium/x.webp" loading="lazy"></div>
          <div class="linek__desc flex-grow-1">
            <a href="https://4read.org/6945-dzho-aberkrombi-chorti.html"><div class="linek__title ws-nowrap">Чорти - Джо Аберкромбі</div></a>
            <div class="linek__meta ws-nowrap"><span>Триває:</span> 21:42:42</div>
          </div>
        </div>
        <div class="linek d-flex ai-center has-overlay card">
          <div class="linek__img img-fit-cover"><img src="/uploads/posts/2026-01/medium/y.webp" loading="lazy"></div>
          <div class="linek__desc flex-grow-1">
            <a href="https://4read.org/7001-vkradi-mene-zaraz.html"><div class="linek__title ws-nowrap">Вкради мене... Зараз! - Сергій Оріанець</div></a>
            <div class="linek__meta ws-nowrap"><span>Триває:</span> 8:05:00</div>
          </div>
        </div>
      </div>
    `
    const cards = parseTop100(html)

    expect(cards).toHaveLength(2)
    // «Title - Author» split at the LAST separator.
    expect(cards[0]).toMatchObject({
      url: 'https://4read.org/6945-dzho-aberkrombi-chorti.html',
      title: 'Чорти',
      author: 'Джо Аберкромбі',
      coverImageUrl: 'https://4read.org/uploads/posts/2026-02/medium/x.webp',
      durationSeconds: 21 * 3600 + 42 * 60 + 42,
    })
    // A title that itself contains « - » keeps it intact.
    expect(cards[1].title).toBe('Вкради мене... Зараз!')
    expect(cards[1].author).toBe('Сергій Оріанець')
    expect(cards[1].durationSeconds).toBe(8 * 3600 + 5 * 60)
  })

  it('people list parses narrators and authors with book counts', () => {
    const readers = `
      <h1>Усі виконавці:</h1>
      <div style="font-size:16px!important;"><ul>
        <li><a href="/xfsearch/chitaet/Ада Роговцева/" target="_blank">Ада Роговцева - 23 книги</a></li>
        <li><a href="/xfsearch/chitaet/Аліна Лукащук/" target="_blank">Аліна Лукащук - 8 книг</a></li>
        <li><a href="/xfsearch/chitaet/Аза Власова/" target="_blank">Аза Власова - 1 книга</a></li>
      </ul></div>
    `
    const people = parsePeopleList(readers)

    expect(people).toHaveLength(3)
    expect(people[0]).toMatchObject({
      url: 'https://4read.org/xfsearch/chitaet/Ада Роговцева/',
      title: 'Ада Роговцева',
      count: 23,
    })
    expect(people[2].count).toBe(1)
  })

  it('parseCatalog routes the index pages to their own sections', () => {
    const top = fourread.parseCatalog('<div class="linek d-flex ai-center has-overlay card"><a href="https://4read.org/1-a.html"><div class="linek__title ws-nowrap">А - Б</div></a></div>', 'https://4read.org/top-100.html')
    expect(top?.sections[0]?.id).toBe('top100')
    expect(top?.sections[0]?.cards).toHaveLength(1)

    const readers = fourread.parseCatalog('<li><a href="/xfsearch/chitaet/Ім\'я/">Ім\'я - 5 книг</a></li>', 'https://4read.org/readers.html')
    expect(readers?.sections[0]?.id).toBe('people')
    expect(readers?.sections[0]?.title).toBe('Виконавці')
    expect(readers?.sections[0]?.cards[0]?.count).toBe(5)

    const avtors = fourread.parseCatalog('<li><a href="/xfsearch/avtor/Ім\'я/">Ім\'я - 2 книги</a></li>', 'https://4read.org/avtors.html')
    expect(avtors?.sections[0]?.title).toBe('Автори')
  })

  it('top100 and people lists degrade to empty on malformed html', () => {
    expect(parseTop100('')).toEqual([])
    expect(parseTop100('<html><body><p>no linek</p></body></html>')).toEqual([])
    expect(parsePeopleList('')).toEqual([])
    expect(parsePeopleList('<html><body><p>no people</p></body></html>')).toEqual([])
    // A person label without a count still parses (honest: no count).
    expect(parsePeopleList('<li><a href="/xfsearch/chitaet/Ім\'я/">Ім\'я</a></li>')[0]?.count).toBeUndefined()
  })
})

describe('fourread genre nav (W3.3)', () => {
  it('parses the homepage sidebar into absolute genre links, skipping ЗНО and «Додати книгу»', () => {
    const html = `
      <aside class="cols__right">
        <div class="sb js-this-in-mobile-menu">
          <div class="sb__title"><span class="fal fa-book"></span>Аудіокниги жанру:</div>
          <ul class="sb__content sb__nav">
            <li><a href="/kazka/">Казка</a></li>
            <li><a href="/fentezi/">Фентезі</a></li>
            <li><a href="/dytlit/">Дитячі</a></li>
            <li><a href="/zno-ukrajinska-literatura.html">ЗНО</a></li>
            <li><a href="/addnews.html"><i class="fal fa-pencil-square-o" style="color:red"></i> Додати книгу</a></li>
          </ul>
        </div>
      </aside>
    `
    const genres = parseGenreNav(html)

    expect(genres).toHaveLength(3)
    expect(genres[0]).toMatchObject({ title: 'Казка', url: 'https://4read.org/kazka/' })
    expect(genres[1]).toMatchObject({ title: 'Фентезі', url: 'https://4read.org/fentezi/' })
    expect(genres[2]).toMatchObject({ title: 'Дитячі', url: 'https://4read.org/dytlit/' })
  })

  it('exposes the genre nav as the homepage «Жанри» section and degrades on malformed html', () => {
    const home = fourread.parseCatalog(
      '<ul class="sb__content sb__nav"><li><a href="/kazka/">Казка</a></li></ul>',
      'https://4read.org/',
    )
    expect(home?.sections.find((s) => s.id === 'genres')?.cards).toHaveLength(1)

    expect(parseGenreNav('')).toEqual([])
    expect(parseGenreNav('<html><body><p>no nav</p></body></html>')).toEqual([])
    expect(parseGenreNav('<ul class="sb__content sb__nav"></ul>')).toEqual([])
  })
})
