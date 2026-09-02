/**
 * spec-43/T4 — порт SluhayAdapter
 */
import {
  absoluteUrl,
  attr,
  parseHtml,
  qs,
  qsa,
  text,
  type BookDetail,
  type CatalogCard,
  type CatalogSection,
  type ParsedCatalog,
  type SourceAdapter,
} from '../types'
import { SOURCE_METADATA } from '../sourceMetadata'

const BASE = SOURCE_METADATA.sluhay.homeUrl
const HOME_SECTION_ID = 'home'

const PLAYLIST_URL = /(https:\/\/[a-z0-9]+\.redirectto\.cc\/[^"'<>\s]+\.pl\.txt)/i
const IMG_DATA_SRC = /<img[^>]*data-src="([^"]*(?:uploads|books)[^"]*)"[^>]*>/i
const DATA_POSTER = /data-poster="([^"]*(?:uploads|books)[^"]*)"/i

export const sluhayAdapter: SourceAdapter = {
  id: 'sluhay',
  displayName: 'Sluhay',
  baseUrl: BASE,
  parseCatalog(html, pageUrl): ParsedCatalog | null {
    const cards = parsePosterRows(html, Number.MAX_SAFE_INTEGER)
    if (cards.length === 0) return null
    const section: CatalogSection = {
      id: sectionIdFromUrl(pageUrl),
      title: '',
      url: pageUrl,
      cards,
    }
    return { sections: [section] }
  },
  parseBookPage(html, pageUrl): BookDetail {
    if (html.trim() === '') {
      return { url: pageUrl, title: '', author: '', genres: [], chapters: [], otherNarrations: [] }
    }
    const ogRaw = ogMeta(html, 'og:title')
    const ogTitle = ogRaw === null ? '' : beforeFirst(ogRaw, ' »').trim()
    const metaTitle = metaRow(html, 'Назва')
    const metaAuthor = metaRow(html, 'Автор')
    const author = metaAuthor !== '' ? metaAuthor : afterLast(ogTitle, ' - ').trim()
    const fromMetaOrOg = metaTitle !== '' ? metaTitle : beforeLast(ogTitle, ' - ').trim()
    const title = fromMetaOrOg !== '' ? fromMetaOrOg : ogTitle
    const coverRaw = coverPath(html)
    const descriptionHtml = (ogMeta(html, 'og:description') ?? '').trim()
    return {
      url: pageUrl,
      title,
      author,
      narrator: undefined,
      coverImageUrl:
        coverRaw === null
          ? undefined
          : coverRaw.startsWith('http')
            ? coverRaw
            : absoluteUrl(coverRaw, BASE),
      genres: [],
      descriptionHtml: descriptionHtml === '' ? undefined : descriptionHtml,
      chapters: [],
      otherNarrations: [],
    }
  },
}

export function parsePosterRows(html: string, limit: number): CatalogCard[] {
  if (html.trim() === '') return []
  const cards: CatalogCard[] = []
  for (const poster of qsa(parseHtml(html), 'a.poster-item')) {
    if (cards.length >= limit) break
    const href = attr(poster, 'href')
    if (!/^https:\/\/sluhay\.com\/.+\.html$/.test(href)) continue
    const rawTitle = text(qs(poster, '.poster-item__title'))
    if (rawTitle.length < 3) continue
    const lastSep = rawTitle.lastIndexOf(' - ')
    const author = (lastSep >= 0 ? rawTitle.slice(lastSep + 3) : rawTitle).trim()
    const splitTitle = (lastSep >= 0 ? rawTitle.slice(0, lastSep) : rawTitle).trim()
    let coverImageUrl: string | undefined
    for (const img of qsa(poster, 'img[data-src]')) {
      const src = attr(img, 'data-src')
      if (src.startsWith('/uploads/')) {
        coverImageUrl = absoluteUrl(src, BASE)
        break
      }
    }
    cards.push({
      url: href,
      title: splitTitle === '' ? rawTitle : splitTitle,
      author,
      coverImageUrl,
    })
  }
  return cards
}

export function playlistUrlOf(html: string): string | null {
  return PLAYLIST_URL.exec(html)?.[1] ?? null
}

export { parsePlayerjsPlaylist } from './playerjs'

function metaRow(html: string, key: string): string {
  const row =
    new RegExp(`<li[^>]*>\\s*<span>${key}</span>\\s*<span>([\\s\\S]*?)</span>\\s*</li>`, 'i')
      .exec(html)?.[1] ?? ''
  return row === '' ? '' : stripTags(row).trim()
}

function coverPath(html: string): string | null {
  return IMG_DATA_SRC.exec(html)?.[1] ?? DATA_POSTER.exec(html)?.[1] ?? null
}

function sectionIdFromUrl(pageUrl: string): string {
  try {
    const segments = new URL(pageUrl).pathname.split('/').filter((s) => s !== '')
    return segments.length === 0 ? HOME_SECTION_ID : segments.join('-')
  } catch {
    return HOME_SECTION_ID
  }
}

function ogMeta(html: string, property: string): string | null {
  const el = qs(parseHtml(html), `meta[property="${property}"]`)
  if (el === null) return null
  const content = attr(el, 'content')
  return content === '' ? null : content
}

function stripTags(input: string): string {
  return input.replace(/<[^>]+>/g, '')
}

function beforeFirst(s: string, delim: string): string {
  const i = s.indexOf(delim)
  return i < 0 ? s : s.slice(0, i)
}

function afterLast(s: string, delim: string): string {
  const i = s.lastIndexOf(delim)
  return i < 0 ? s : s.slice(i + delim.length)
}

function beforeLast(s: string, delim: string): string {
  const i = s.lastIndexOf(delim)
  return i < 0 ? s : s.slice(0, i)
}
