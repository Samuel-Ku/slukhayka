/**
 * spec-43/T4 — порт LihtarAdapter
 */
import {
  absoluteUrl,
  attr,
  parseHtml,
  qs,
  type BookDetail,
  type CatalogCard,
  type CatalogSection,
  type Chapter,
  type ParsedCatalog,
  type SourceAdapter,
} from '../types'
import { SOURCE_METADATA } from '../sourceMetadata'

const BASE = SOURCE_METADATA.lihtar.homeUrl

const LISTEN_LINK = /href="(https:\/\/web\.lihtar\.in\.ua\/library\/[^"]+)"/i
const AUDIO_SRC = /<audio[^>]+src="(https:\/\/web\.lihtar\.in\.ua\/audio\/[^"]+)"/i
const BOOK_LINK_G = /href="(https:\/\/lihtar\.in\.ua\/biblioteka\/[a-z0-9-]+\/[a-z0-9-]+)"/g
const H1_TAG = /<h1[^>]*>([\s\S]*?)<\/h1>/i
const H1_TEXT = /<h1>([^<]+)<\/h1>/i
const H4_TAG = /<h4[^>]*>([\s\S]*?)<\/h4>/i

export const lihtarAdapter: SourceAdapter = {
  id: 'lihtar',
  displayName: 'Lihtar',
  baseUrl: BASE,
  parseCatalog(html, pageUrl): ParsedCatalog | null {
    const seen = new Set<string>()
    const cards: CatalogCard[] = []
    for (const m of html.matchAll(BOOK_LINK_G)) {
      const url = m[1]
      if (seen.has(url)) continue
      seen.add(url)
      cards.push({ url, title: slugTitle(url), author: '' })
    }
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
    const ogTitle = ogMeta(html, 'og:title')
    const h1 = H1_TEXT.exec(html)?.[1]?.trim() ?? null
    const decoded = decodeEntities(ogTitle ?? h1 ?? '')
    return {
      url: pageUrl,
      title: decoded.trim() === '' ? slugTitle(pageUrl) : decoded,
      author: authorFrom(html),
      coverImageUrl: coverFromPage(html),
      genres: [],
      chapters: [],
      otherNarrations: [],
    }
  },
}

export function playerUrlOf(html: string): string | null {
  return LISTEN_LINK.exec(html)?.[1] ?? null
}

export function parsePlayerPage(playerHtml: string, fallbackTitle: string): Chapter[] {
  const src = AUDIO_SRC.exec(playerHtml)?.[1]
  if (src === undefined) return []
  return [{ title: fallbackTitle.trim() === '' ? 'Аудіокнига' : fallbackTitle, streamUrl: src }]
}

function authorFrom(html: string): string {
  const h1 = H1_TAG.exec(html)
  if (h1 !== null) {
    const tail = html.slice(h1.index + h1[0].length - 1)
    const visible = stripTags(H4_TAG.exec(tail)?.[1] ?? '').trim()
    if (visible !== '') return decodeEntities(visible)
  }
  return decodeEntities((ogMeta(html, 'og:description') ?? '').trim())
}

function coverFromPage(html: string): string | undefined {
  const cover = ogMeta(html, 'og:image')?.trim()
  return cover === undefined || cover === '' ? undefined : absoluteUrl(cover, BASE)
}

function slugTitle(url: string): string {
  return titleFromSlug(beforeFirst(afterLast(url, '/'), '?'))
}

function titleFromSlug(slug: string): string {
  const spaced = slug.replaceAll('-', ' ').trim()
  if (spaced === '') return ''
  const head = spaced.charAt(0)
  return (head === head.toLowerCase() ? head.toUpperCase() : head) + spaced.slice(1)
}

function sectionIdFromUrl(pageUrl: string): string {
  try {
    return (
      new URL(pageUrl).pathname.split('/').filter((s) => s !== '').slice(-1).join('-') ||
      'biblioteka'
    )
  } catch {
    return 'biblioteka'
  }
}

function ogMeta(html: string, property: string): string | null {
  const el = qs(parseHtml(html), `meta[property="${property}"]`)
  if (el === null) return null
  const content = attr(el, 'content')
  return content === '' ? null : content
}

function decodeEntities(input: string): string {
  return input
    .replaceAll('&#039;', "'")
    .replaceAll('&#39;', "'")
    .replaceAll('&quot;', '"')
    .replaceAll('&amp;', '&')
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
