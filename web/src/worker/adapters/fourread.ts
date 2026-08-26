/**
 * spec-43/T3 — порт FourReadAdapter + CatalogParser + WebViewHtmlParser
 * (4read.org). Усі правила чисті й працюють на регексах, як еталон;
 * невпізнана розмітка деградує до меншого результату, ніколи не до
 * виключення. Плейлісти (.m3u/.txt) і iframe-плеєри потребують мережі —
 * їх збирає `buildBookDetail(html, url, resolve)`: воркер підставляє
 * транспорт, тести — мапу фіксчур.
 */
import { absoluteUrl, type BookDetail, type CatalogCard, type CatalogSection, type ParsedCatalog, type SourceAdapter } from '../types'

const SITE = 'https://4read.org'

export const fourread: SourceAdapter = {
  id: 'fourread',
  displayName: '4read',
  baseUrl: SITE,
  parseCatalog,
  parseBookPage: parseBookPageSync,
  search: parseSearchResults,
}

// --- Каталог ---------------------------------------------------------------

export function parseCatalog(html: string, pageUrl: string): ParsedCatalog | null {
  if (!html.trim()) return null
  if (normalize(pageUrl) === `${SITE}/`) return parseHomepage(html)

  const books = parsePosterBooks(html)
  const isSeries = pageUrl.includes('/xfsearch/cikl/')
  const section: CatalogSection = {
    id: isSeries ? 'series' : 'category',
    title: isSeries ? 'Цикл' : 'Книги',
    url: pageUrl,
    cards: books,
  }
  return {
    sections: books.length ? [section] : [],
    nextPageUrl: parseNextPageUrl(html) ?? undefined,
  }
}

function parseHomepage(html: string): ParsedCatalog {
  const sections: CatalogSection[] = []
  const cards: CatalogCard[] = []
  const seriesByUrl = new Map<string, CatalogCard>()

  for (const poster of splitPosters(html)) {
    const bookUrl = match(poster, /https:\/\/4read\.org\/\d+-[^"'<>]+\.html/)?.[0]
    const title = decodeEntities((match(poster, /class="poster__title line-clamp">([^<]+)<\/div>/)?.[1] ?? '').trim())
    if (!bookUrl || title.length < 2 || isPromoTitle(title)) continue

    const author = stripTags(match(poster, /class="poster__subtitle ws-nowrap">([\s\S]*?)<\/div>/)?.[1] ?? '')
      .replace(/\s+/g, ' ')
      .trim()
    const cover = toAbsolute(match(poster, /<img[^>]+src="([^"]+)"[^>]*>/)?.[1] ?? '')

    const series = match(poster, /class="poster__series anim"><a href="([^"]+)">([^<]+)<\/a>/)
    let seriesName: string | undefined
    if (series) {
      seriesName = decodeEntities(series[2].trim())
      if (!seriesByUrl.has(series[1])) {
        seriesByUrl.set(series[1], { url: series[1], title: seriesName, author: '', coverImageUrl: cover ?? undefined })
      }
    }
    const seriesPart = match(poster, /<div class="poster__label poster__label--blue">(\d+)<\/div>/)?.[1]

    cards.push({
      url: bookUrl,
      title,
      author,
      coverImageUrl: cover ?? undefined,
      seriesName,
      seriesPart: seriesPart ? Number(seriesPart) : undefined,
    })
  }

  if (cards.length) sections.push({ id: 'new-arrivals', title: 'Новинки', cards })
  if (seriesByUrl.size) sections.push({ id: 'series', title: 'Цикли', cards: [...seriesByUrl.values()] })

  const popular = parsePopularBooks(html)
  if (popular.length) sections.push({ id: 'popular', title: 'Популярне', cards: popular })

  return { sections }
}

/** Бічний блок «Популярне»: автора відновлено з alt обкладинки («Автор - Назва»). */
export function parsePopularBooks(html: string): CatalogCard[] {
  const titleBlock = /<div class="sb__title"[^>]*>[\s\S]*?Популярне<\/div>/.exec(html)
  if (!titleBlock) return []
  const gridOpen = '<div class="sb__content sb__grid">'
  const gridStart = html.indexOf(gridOpen, titleBlock.index + titleBlock[0].length)
  if (gridStart < 0) return []
  const afterGrid = gridStart + gridOpen.length
  const bounds = ['</aside>', '<div class="sb '].map((marker) => html.indexOf(marker, afterGrid)).filter((i) => i >= 0)
  const block = html.substring(afterGrid, bounds.length ? Math.min(...bounds) : html.length)

  const opener = '<a class="ftop-item'
  const starts: number[] = []
  let idx = block.indexOf(opener)
  while (idx >= 0) {
    starts.push(idx)
    idx = block.indexOf(opener, idx + opener.length)
  }

  const cards: CatalogCard[] = []
  for (let i = 0; i < starts.length; i++) {
    const chunk = block.substring(starts[i], i + 1 < starts.length ? starts[i + 1] : block.length)
    const bookUrl = match(chunk, /https:\/\/4read\.org\/\d+-[^"'<>]+\.html/)?.[0]
    const title = decodeEntities((match(chunk, /class="ftop-item__title[^"]*">([^<]+)<\/div>/)?.[1] ?? '').trim())
    if (!bookUrl || title.length < 2) continue
    const alt = match(chunk, /<img[^>]+alt="([^"]+)"/)?.[1] ?? ''
    const cover = toAbsolute(match(chunk, /<img[^>]+src="([^"]+)"/)?.[1] ?? '')
    const split = alt.lastIndexOf(' - ')
    cards.push({
      url: bookUrl,
      title,
      author: split > 0 ? alt.substring(0, split).trim() : '',
      coverImageUrl: cover ?? undefined,
    })
  }
  return cards
}

/** Наступна сторінка DLE-пагінації — найменший номер ≥2 у вікні після id="pagination". */
export function parseNextPageUrl(html: string): string | null {
  const start = html.indexOf('id="pagination"')
  if (start < 0) return null
  const scope = html.slice(start, start + 4000)
  const links = [...scope.matchAll(/href="([^"]*\/page\/(\d+)\/?)"/g)]
    .map((m) => ({ href: m[1], n: Number(m[2]) }))
    .filter((l) => l.n >= 2)
    .sort((a, b) => a.n - b.n)
  return links.length ? toAbsolute(links[0].href) : null
}

/** «Можливо, Тебе зацікавить:» — ті самі постери всередині секції. */
export function parseRelatedBooks(html: string): CatalogCard[] {
  const section = /<section class="sect pmovie__related[^"]*"[^>]*>([\s\S]*?)<\/section>/i.exec(html)
  return section ? parsePosterBooks(section[1]) : []
}

function parsePosterBooks(html: string): CatalogCard[] {
  const cards: CatalogCard[] = []
  for (const poster of splitPosters(html)) {
    const bookUrl = match(poster, /https:\/\/4read\.org\/\d+-[^"'<>]+\.html/)?.[0]
    const title = decodeEntities((match(poster, /class="poster__title line-clamp">([^<]+)<\/div>/)?.[1] ?? '').trim())
    if (!bookUrl || title.length < 2 || isPromoTitle(title)) continue
    const author = stripTags(match(poster, /class="poster__subtitle ws-nowrap">([\s\S]*?)<\/div>/)?.[1] ?? '')
      .replace(/\s+/g, ' ')
      .trim()
    const series = match(poster, /class="poster__series anim"><a href="([^"]+)">([^<]+)<\/a>/)
    cards.push({
      url: bookUrl,
      title,
      author,
      coverImageUrl: toAbsolute(match(poster, /<img[^>]+src="([^"]+)"[^>]*>/)?.[1] ?? '') ?? undefined,
      seriesName: series ? decodeEntities(series[2].trim()) : undefined,
      seriesPart: Number(match(poster, /<div class="poster__label poster__label--blue">(\d+)<\/div>/)?.[1]) || undefined,
    })
  }
  return cards
}

/**
 * Розбиття сторінки на постери: лише справжній контейнер має пробіл після
 * "poster" (`poster__desc` тощо ним не є) — інакше кожен постер дробиться.
 */
function splitPosters(html: string): string[] {
  const opener = '<div class="poster '
  const starts: number[] = []
  let idx = html.indexOf(opener)
  while (idx >= 0) {
    starts.push(idx)
    idx = html.indexOf(opener, idx + opener.length)
  }
  return starts.map((start, i) => html.substring(start, i + 1 < starts.length ? starts[i + 1] : html.length))
}

// --- Пошук (порт FourReadAdapter.search, spec-43/T4) ------------------------

const POSTER_START_G = /<div class="poster\b/gi
const SEARCH_LINK_RE = /href="(https?:\/\/4read\.org\/([^"]+)\.html)"/i
const SEARCH_TITLE_RE = /poster__title[^>]*>\s*([^<]+?)\s*</i
const SEARCH_SUBTITLE_G = /poster__subtitle[^>]*>\s*([\s\S]*?)\s*<\/div>/gi
const SEARCH_IMG_RE = /<img[^>]+src="([^"]+)"[^>]*>/i

/**
 * Порт `FourReadAdapter.search`: кожен хіт — блок `.poster`, справжня
 * кирилична назва в poster__title, справжній автор у першому непорожньому
 * poster__subtitle (другий несе годинник тривалості й після stripTags
 * порожніє). Справжні автори потрібні для крос-джерельного злиття.
 */
export function parseSearchResults(html: string, _pageUrl: string): CatalogCard[] {
  const starts: number[] = []
  POSTER_START_G.lastIndex = 0
  for (let m = POSTER_START_G.exec(html); m !== null; m = POSTER_START_G.exec(html)) {
    starts.push(m.index)
  }

  const seenSlugs = new Set<string>()
  const cards: CatalogCard[] = []
  for (let i = 0; i < starts.length; i++) {
    const block = html.substring(starts[i], i + 1 < starts.length ? starts[i + 1] : html.length)

    const link = SEARCH_LINK_RE.exec(block)
    if (!link) continue
    const slug = link[2]
    const rawTitle = (SEARCH_TITLE_RE.exec(block)?.[1] ?? '').trim()
    // index/page-заглушки, короткі «назви» та повтори слугів — сміття, не хіти.
    if (slug.includes('index') || slug.includes('page') || rawTitle.length < 3 || seenSlugs.has(slug)) {
      continue
    }
    seenSlugs.add(slug)

    let author = ''
    for (const subtitle of block.matchAll(SEARCH_SUBTITLE_G)) {
      const candidate = stripTags(subtitle[1]).trim()
      if (candidate !== '') {
        author = candidate
        break
      }
    }

    const rawCover = SEARCH_IMG_RE.exec(block)?.[1]
    cards.push({
      url: link[1],
      title: decodeEntities(rawTitle),
      author,
      coverImageUrl:
        rawCover !== undefined && rawCover.includes('/uploads/posts/')
          ? rawCover.startsWith('http')
            ? rawCover
            : `${SITE}${rawCover}`
          : undefined,
    })
  }
  return cards
}

// --- Сторінка книги ---------------------------------------------------------

/** Синхронна частина: метадані + прямі аудіо-посилання (без плейлістів/iframe). */
export function parseBookPageSync(html: string, pageUrl: string): BookDetail | null {
  if (!html.trim()) return null
  return { ...parseBookMeta(html, pageUrl), chapters: chaptersFromRefs(collectAudioRefs(html, pageUrl)) }
}

type BookMeta = Omit<BookDetail, 'chapters'>

/**
 * Повний складач із мережевими добірками — порт `WebViewHtmlParser.parse`
 * з інжектованим resolveContent: плейлісти й iframe-плеєри розв'язуються
 * через `resolve` (URL → тіло або null).
 */
export async function buildBookDetail(
  html: string,
  pageUrl: string,
  resolve: (url: string) => Promise<string | null>,
): Promise<BookDetail | null> {
  if (!html.trim()) return null
  const meta = parseBookMeta(html, pageUrl)

  const refs = collectAudioRefs(html, pageUrl)
  for (const frame of iframeSrcs(html)) {
    const body = await resolve(frame)
    if (body) refs.push(...collectAudioRefs(body, frame))
  }

  const expanded: string[] = []
  for (const ref of refs) {
    if (/\.m3u($|\?)|\.txt($|\?)/.test(ref)) {
      const content = await resolve(ref)
      expanded.push(...(content ? expandPlaylist(content) : [ref]))
    } else {
      expanded.push(ref)
    }
  }

  return { ...meta, chapters: chaptersFromRefs([...new Set(expanded)]) }
}

function chaptersFromRefs(refs: string[]) {
  return refs.map((streamUrl, i) => ({ title: `Глава ${i + 1}`, streamUrl }))
}

/** Розгортання тіла плейліста: playerjs-JSON (`file:"…"`) або простий m3u. */
export function expandPlaylist(content: string): string[] {
  const trimmed = content.trim()
  if (trimmed.startsWith('[{')) {
    return [...trimmed.matchAll(/file"\s*:\s*"([^"]+)"/gi)].map((m) => encodeUrl(m[1]))
  }
  return content
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.startsWith('http'))
    .map(encodeUrl)
}

// --- Внутрішні правила (порт WebViewHtmlParser/CatalogParser) ---------------

/** Аудіо зі сторінки: прямі mp3/…, /uploads-відносні, playerjs file:"…", <source>. */
export function collectAudioRefs(html: string, baseUrl: string): string[] {
  const out: string[] = []
  const origin = safeOrigin(baseUrl)

  for (const m of html.matchAll(/(https?:\/\/[^"'\s\n<>]+\.(?:mp3|m4a|ogg|aac|m3u8)(?:\?[^"'\s\n<>]*)?)/gi)) {
    if (!m[1].includes('favicon') && !m[1].includes('logo')) out.push(encodeUrl(m[1]))
  }
  for (const m of html.matchAll(/["'](\/uploads\/[^"'\s\n<>]+\.(?:mp3|m4a|ogg|aac|m3u8)(?:\?[^"'\s\n<>]*)?)["']/gi)) {
    out.push(encodeUrl(`${origin}${m[1]}`))
  }
  for (const m of html.matchAll(/file\s*:\s*["']([^"']+)["']/gi)) {
    const rawFile = m[1].replace('{v1}', `${SITE}/m3u/`)
    if (/\.mp3|\.m4a|\.m3u8|\.m3u|\.txt|\/audio\//.test(rawFile)) {
      for (const piece of rawFile.split(/[,;]/)) {
        const clean = piece.trim()
        if (clean.startsWith('http')) out.push(encodeUrl(clean))
        else if (clean.startsWith('/')) out.push(encodeUrl(`${SITE}${clean}`))
      }
    }
  }
  for (const m of html.matchAll(/<source[^>]+src=["']([^"']+)["']/gi)) {
    const src = m[1]
    out.push(encodeUrl(src.startsWith('http') ? src : `${SITE}${src.startsWith('/') ? '' : '/'}${src}`))
  }
  return out
}

export function iframeSrcs(html: string): string[] {
  const frames: string[] = []
  for (const m of html.matchAll(/<iframe[^>]+src=["']([^"']+)["']/gi)) {
    const full = absoluteUrl(m[1], SITE)
    if (full && !full.includes('facebook') && !full.includes('vk.com/widget')) frames.push(full)
  }
  return frames
}

/** Видимий текст запису `pmovie__list` за міткою («Автор:», «Читає:»). */
function pmovieText(html: string, label: string): string | null {
  const marker = new RegExp(`<span>\\s*${label}:\\s*</span>([\\s\\S]*?)</li>`, 'i').exec(html)?.[1]
  const clean = decodeEntities(stripTags(marker ?? '')).replace(/\s+/g, ' ').trim()
  return clean || null
}

function pmovieGenres(html: string): string[] {
  const marker = /<span>\s*Жанр:\s*<\/span>([\s\S]*?)<\/li>/i.exec(html)?.[1]
  if (!marker) return []
  const genres = [...marker.matchAll(/>([^<]+)<\/a>/g)].map((m) => m[1].trim()).filter((t) => t && t.toLowerCase() !== 'жанр')
  return genres.length > 1 ? genres.slice(1) : genres.slice(0, 1)
}

function coverFromPage(html: string): string | null {
  const og =
    match(html, /<meta\s+property="og:image"\s+content="([^"]+)"\s*>/i)?.[1] ??
    match(html, /<meta\s+content="([^"]+)"\s+property="og:image"/i)?.[1]
  if (og) return toAbsolute(og)
  const img =
    match(html, /<img[^>]+src="([^"]*uploads\/posts\/[^"]*)"/i)?.[1] ??
    match(html, /<img[^>]+src="(https?:\/\/4read\.org[^"]+\.(?:jpg|png|webp|jpeg))"/i)?.[1]
  return img ? toAbsolute(img) : null
}

/**
 * Повна анотація з контейнера `itemprop="description"`: абзаци до курсивних
 * хвостових міток, закриття контейнера рахується по глибині div-ів, найдеші
 * мітки обрізають раніше за порядок списку.
 */
function itempropDescription(html: string): string {
  const open = /<div\b[^>]*itemprop="description"[^>]*>/.exec(html)
  if (!open) return ''
  const bodyStart = open.index + open[0].length
  let depth = 1
  let close = -1
  const boundary = /<div\b|<\/div/g
  boundary.lastIndex = bodyStart
  let m: RegExpExecArray | null
  while ((m = boundary.exec(html)) !== null) {
    depth += m[0] === '</div' ? -1 : 1
    if (depth === 0) {
      close = m.index
      break
    }
  }
  if (close < 0) return ''

  const tailMarkers = ['Теги#', 'Теги', 'Телеграм канал', 'Подякувати', 'Ютуб канал', 'PayPal']
  const result: string[] = []
  for (const p of html.substring(bodyStart, close).matchAll(/<p[^>]*>([\s\S]*?)<\/p>/gi)) {
    const line = decodeEntities(stripTags(p[1], ' ')).replace(/\s+/g, ' ').trim()
    if (!line) continue
    const cuts = tailMarkers.map((marker) => line.indexOf(marker)).filter((i) => i >= 0)
    if (cuts.length) {
      const kept = line.substring(0, Math.min(...cuts)).trim()
      if (kept) result.push(kept)
      break
    }
    result.push(line)
    if (result.join('\n').length >= 1200) break
  }
  return result.join('\n').trim()
}

// --- Загальні помічники -------------------------------------------------------

function parseBookMeta(html: string, pageUrl: string): BookMeta {
  return {
    url: pageUrl,
    title: titleFromPage(html, pageUrl),
    author: pmovieText(html, 'Автор') ?? '',
    narrator: pmovieText(html, 'Читає') ?? '',
    coverImageUrl: coverFromPage(html) ?? undefined,
    genres: pmovieGenres(html),
    descriptionHtml: itempropDescription(html) || ogMeta(html, 'og:description')?.trim() || undefined,
    otherNarrations: parseRelatedBooks(html),
    totalDurationSeconds: pageDuration(html),
  }
}

/** Реальна повна тривалість зі сторінки (формати `10:57:18` / `53:42`); null — невідома. */
export function pageDuration(html: string): number | undefined {
  const raw =
    match(html, /(?:itemprop="duration"\s+content="|Триває:<\/span>\s*)(\d{1,2}:\d{2}(?::\d{2})?)/)?.[1]
  if (!raw) return undefined
  const parts = raw.split(':').map(Number)
  if (parts.some((n) => !Number.isFinite(n))) return undefined
  if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2]
  if (parts.length === 2) return parts[0] * 60 + parts[1]
  return undefined
}

function titleFromPage(html: string, pageUrl: string): string {
  const ogTitle = ogMeta(html, 'og:title')?.trim()
  // Правило «одна книга — одна картка»: сирий SEO-суфікс сайту не картка.
  const cleaned = ogTitle?.replace(/\s*-\s*АудіоКниги Українською\s*$/i, '').trim()
  if (cleaned) return cleaned
  const slug = pageUrl.split('/').pop()!.replace(/\.html$/, '')
  return (
    slug
      .split('-')
      .filter(Boolean)
      .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
      .join(' ') || 'Аудиокнига 4read'
  )
}

function ogMeta(html: string, property: string): string | null {
  return new RegExp(`<meta\\s+property="${property}"\\s+content="([^"]+)"`, 'i').exec(html)?.[1] ?? null
}

function stripTags(input: string, replacement = ''): string {
  return input.replace(/<[^>]*>/g, replacement)
}

function decodeEntities(input: string): string {
  return input
    .replace(/&#(\d+);/g, (_, code: string) => String.fromCodePoint(Number(code)))
    .replace(/&(amp|quot|apos|lt|gt);/g, (_, name: string) =>
      (({ amp: '&', quot: '"', apos: "'", lt: '<', gt: '>' }) as Record<string, string>)[name] ?? _,
    )
}

function isPromoTitle(title: string): boolean {
  const lower = title.toLowerCase()
  return lower.includes('реклам') || lower.includes('без рекламы') || lower.startsWith('топ-') || lower.startsWith('🔥')
}

function toAbsolute(src: string): string | null {
  if (!src.trim()) return null
  return src.startsWith('http') ? src : `${SITE}${src.startsWith('/') ? '' : '/'}${src}`
}

function normalize(url: string): string {
  return url.endsWith('/') ? url : `${url}/`
}

function safeOrigin(baseUrl: string): string {
  try {
    return new URL(baseUrl).origin
  } catch {
    return SITE
  }
}

function match(input: string, re: RegExp): RegExpExecArray | null {
  return re.exec(input)
}

const HEX = '0123456789ABCDEF'

/** JVM-сумісне percent-кодування: кожен байт ≥0x80 кодується безумовно. */
export function encodeUrl(url: string): string {
  const allowed = "@#&=*+-_.,:!?()/~'%"
  let out = ''
  for (const b of new TextEncoder().encode(url)) {
    if (b < 0x80) {
      const ch = String.fromCharCode(b)
      out += /[A-Za-z0-9]/.test(ch) || allowed.includes(ch) ? ch : `%${HEX[b >> 4]}${HEX[b & 0xf]}`
    } else {
      out += `%${HEX[b >> 4]}${HEX[b & 0xf]}`
    }
  }
  return out
}
