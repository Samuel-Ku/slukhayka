/**
 * spec-47 T6 — порт AudiobookCoUaAdapter (audiobook.co.ua, server-fetch).
 * WordPress: novinki grid + Playerjs playlist txt; the site's keyword form
 * does not filter server-side (T1 spike) — no `search` member, the honest
 * absence, exactly like the Kotlin adapter's empty search().
 */
import { attr, parseHtml, qs, qsa, text, type BookDetail, type CatalogCard, type CatalogSection, type ParsedCatalog, type SourceAdapter } from '../types'
import { SOURCE_METADATA } from '../sourceMetadata'
import { parsePlayerjsPlaylist } from './playerjs'

const HOME_SECTION_ID = 'home'

function sectionIdFromUrl(pageUrl: string): string {
  try {
    const segments = new URL(pageUrl).pathname.split('/').filter((s) => s !== '')
    return segments.length === 0 ? HOME_SECTION_ID : segments.join('-')
  } catch {
    return HOME_SECTION_ID
  }
}

const BASE = SOURCE_METADATA.audiobookcoua.homeUrl

// og:title = «Аудиокнига <Назва> - <Автор> скачать, слушать онлайн -
// Аудиокниги на украинском языке» — the known prefix, boilerplate chunk and
// site tail are stripped; the pair splits at the FIRST separator.
const OG_PREFIX = /^Аудиокнига\s+/
const OG_TAIL = / - Аудиокниги на украинском языке$/
const OG_BOILERPLATE = /\s+скачать, слушать онлайн\s*$/

const COVER_PATH = /wp-content\/uploads/

export const audiobookcouaAdapter: SourceAdapter = {
  id: 'audiobookcoua',
  displayName: 'Audiobook.co.ua',
  baseUrl: BASE,
  parseCatalog(html, pageUrl): ParsedCatalog | null {
    const cards = noveltiesCards(html)
    if (cards.length === 0) return null
    const section: CatalogSection = { id: sectionIdFromUrl(pageUrl), title: '', url: pageUrl, cards }
    return { sections: [section] }
  },
  parseBookPage(html, pageUrl): BookDetail {
    if (html.trim() === '') {
      return { url: pageUrl, title: '', author: '', genres: [], chapters: [], otherNarrations: [], relatedBooks: [] }
    }
    const { title, author } = titleAndAuthorFrom(html)
    return {
      url: pageUrl,
      title,
      author,
      coverImageUrl: ogMeta(html, 'og:image') ?? undefined,
      genres: [],
      // Measured absence (T1 spike): og:description is site boilerplate, not
      // an annotation — stays unused, never fabricated.
      chapters: [],
      otherNarrations: [],
      relatedBooks: [],
    }
  },
}

/**
 * One post-grid card: «Назва - Автор» title link, wp-content/uploads cover.
 * A card without the separator keeps the author empty (never invented).
 */
export function noveltiesCards(html: string): CatalogCard[] {
  if (html.trim() === '') return []
  const doc = parseHtml(html)
  const cards: CatalogCard[] = []
  for (const item of qsa(doc, '.item')) {
    const url = attr(qs(item, 'a') ?? item, 'href')
    if (!/^https:\/\/audiobook\.co\.ua\/.+/.test(url)) continue
    const label = text(qs(item, '.element.title a') ?? qs(item, '.title a'))
    if (label.length < 3) continue
    const { title, author } = splitTitleAuthor(label)
    const cover = qsa(item, 'img').map((img) => attr(img, 'src')).find((src) => COVER_PATH.test(src))
    cards.push({
      url,
      title,
      author,
      ...(cover ? { coverImageUrl: cover } : {}),
    })
  }
  return cards
}

/** The playlist URL from the `new Playerjs({…})` init call, `\/` unescaped. */
export function playlistUrlOf(html: string): string | null {
  const init = html.indexOf('new Playerjs(')
  if (init < 0) return null
  const window = html.slice(init, Math.min(html.length, init + 4096))
  const file = /"file"\s*:\s*"((?:[^"\\]|\\.)*)"/.exec(window)?.[1]
  return file === undefined ? null : file.replace(/\\\//g, '/')
}

export { parsePlayerjsPlaylist }

/** «Назва - Автор» → (title, author); no separator → empty author, never invented. */
export function splitTitleAuthor(label: string): { title: string; author: string } {
  const sep = label.indexOf(' - ')
  if (sep <= 0) return { title: label.trim(), author: '' }
  return { title: label.slice(0, sep).trim(), author: label.slice(sep + 3).trim() }
}

function titleAndAuthorFrom(html: string): { title: string; author: string } {
  const clean = (ogMeta(html, 'og:title') ?? '')
    .replace(OG_PREFIX, '')
    .replace(OG_TAIL, '')
    .replace(OG_BOILERPLATE, '')
    .trim()
  return splitTitleAuthor(clean)
}

function ogMeta(html: string, property: string): string | null {
  const el = qs(parseHtml(html), `meta[property="${property}"]`)
  if (el === null) return null
  const content = attr(el, 'content')
  return content === '' ? null : content
}