/**
 * spec-43/T4 — порт SoundBooksAdapter
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

const BASE = SOURCE_METADATA['sound-books'].homeUrl

const PLAYLIST_URL = /file\s*:\s*"(https?:\/\/[^"]+\.m3u)"/i
const BOOK_LINK_G =
  /<a\s+[^>]*href="(https:\/\/sound-books\.net\/[^"]+\.html)"[^>]*>([\s\S]*?)<\/a>/gi
const COVER_TILE_G =
  /<a\s+class="short-img[^"]*"\s+href="(https:\/\/sound-books\.net\/[^"]+\.html)"[^>]*>\s*<img[^>]*(?:data-src|src)="([^"]+)"(?:[^>]*\s+alt="([^"]*)")?/gi
const AUTHOR_MARK = /Автор:\s*([^.<]{2,80})/i
const NARRATOR_MARK = /Читає:\s*([^.<]{2,80})/i
const JSONLD_AUTHOR = /"author"\s*:\s*"([^"]+)"/i
const GENRE_BLOCK = /Жанр:\s*(?:<\/b>\s*)?([\s\S]*?)<\/li>/i
const GENRE_LINK_G = /<a[^>]*>([^<]+)<\/a>/gi

interface TileAnchor {
  anchor: string
  hasSeparator: boolean
}

export const soundBooksAdapter: SourceAdapter = {
  id: 'sound-books',
  displayName: 'Sound-Books',
  baseUrl: BASE,
  parseCatalog(html, pageUrl): ParsedCatalog | null {
    let cards = parseTiles(html)
    if (cards.length === 0) return null
    // Порт cardExtras (SoundBooksAdapter.kt): тривалість рядка «Триває:»
    // приєднується до картки за її URL; жанри рядків прийде з UI-квітом T4.
    const durations = durationExtras(html)
    cards = cards.map((card) => {
      const durationSeconds = durations.get(card.url)
      return durationSeconds === undefined ? card : { ...card, durationSeconds }
    })
    const section: CatalogSection = { id: sectionIdFromUrl(pageUrl), title: '', url: pageUrl, cards }
    return { sections: [section] }
  },
  parseBookPage(html, pageUrl): BookDetail {
    const title = ogMeta(html, 'og:title') ?? slugTitle(pageUrl)
    const author = AUTHOR_MARK.exec(html)?.[1]?.trim() ?? JSONLD_AUTHOR.exec(html)?.[1]?.trim() ?? ''
    const narrator = NARRATOR_MARK.exec(html)?.[1]?.trim() ?? ''
    const descriptionHtml = ogMeta(html, 'og:description')?.trim() ?? ''
    const coverRaw = ogMeta(html, 'og:image')?.trim()
    return {
      url: pageUrl,
      title,
      author,
      narrator: narrator === '' ? undefined : narrator,
      coverImageUrl:
        coverRaw === undefined || coverRaw === '' ? undefined : absoluteUrl(coverRaw, BASE),
      genres: genresFrom(html),
      descriptionHtml: descriptionHtml === '' ? undefined : descriptionHtml,
      chapters: [],
      otherNarrations: [],
    }
  },
}

export function playlistUrlOf(html: string): string | null {
  return PLAYLIST_URL.exec(html)?.[1] ?? null
}

export function parseM3u(m3u: string): Chapter[] {
  return m3u
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.startsWith('http'))
    .map((stream, index) => {
      const name = beforeLast(afterLast(stream, '/'), '.').trim()
      return { title: name === '' ? `Глава ${index + 1}` : name, streamUrl: stream }
    })
}

/** Тривалість із блоків `short-item` («Триває:»), зчеплена за URL книги. */
function durationExtras(html: string): Map<string, number> {
  const out = new Map<string, number>()
  for (const part of html.split('<div class="short-item">').slice(1)) {
    const url = /href="(https:\/\/sound-books\.net\/[^"]+\.html)"/.exec(part)?.[1]
    const raw = /Триває:\s*(?:<\/[a-z]+>)?\s*(\d{1,2}:\d{2}(?::\d{2})?)/.exec(part)?.[1]
    if (!url || !raw) continue
    const parts = raw.split(':').map(Number)
    if (parts.some((n) => !Number.isFinite(n))) continue
    out.set(url, parts.length === 3 ? parts[0] * 3600 + parts[1] * 60 + parts[2] : parts[0] * 60 + parts[1])
  }
  return out
}

function parseTiles(html: string): CatalogCard[] {
  const covers = new Map<string, string>()
  const altTitles = new Map<string, string>()
  for (const m of html.matchAll(COVER_TILE_G)) {
    const url = m[1]
    if (!covers.has(url)) {
      const img = m[2]
      covers.set(url, img.startsWith('http') ? img : absoluteUrl(img, BASE))
    }
    const alt = (m[3] ?? '').trim()
    if (alt.length >= 3 && !altTitles.has(url)) altTitles.set(url, alt)
  }
  const best = new Map<string, TileAnchor>()
  for (const m of html.matchAll(BOOK_LINK_G)) {
    const url = m[1]
    const inner = m[2]
    if (inner.toLowerCase().includes('<img')) continue
    const anchor = inner.trim()
    if (anchor.length < 3) continue
    const hasSeparator = anchor.includes(' - ')
    const prev = best.get(url)
    if (prev === undefined || (hasSeparator && !prev.hasSeparator)) {
      best.set(url, { anchor, hasSeparator })
    }
  }
  for (const [url, alt] of altTitles) {
    if (!best.has(url)) best.set(url, { anchor: alt, hasSeparator: false })
  }
  const cards: CatalogCard[] = []
  for (const [url, tile] of best) {
    const sep = tile.hasSeparator ? tile.anchor.indexOf(' - ') : -1
    const titled = sep >= 0 ? tile.anchor.slice(0, sep).trim() : tile.anchor
    cards.push({
      url,
      title: titled === '' ? slugTitle(url) : titled,
      author: sep >= 0 ? tile.anchor.slice(sep + 3).trim() : '',
      coverImageUrl: covers.get(url),
    })
  }
  return cards
}

function genresFrom(html: string): string[] {
  const block = GENRE_BLOCK.exec(html)?.[1]
  if (block === undefined) return []
  return [...block.matchAll(GENRE_LINK_G)].map((m) => stripAudioPrefix(decodeEntities(m[1].trim())))
}

function stripAudioPrefix(genre: string): string {
  return genre.startsWith('Аудіокниги ') ? genre.slice('Аудіокниги '.length) : genre
}

function ogMeta(html: string, property: string): string | null {
  const el = qs(parseHtml(html), `meta[property="${property}"]`)
  if (el === null) return null
  const content = attr(el, 'content')
  return content === '' ? null : content
}

function slugTitle(url: string): string {
  const slug = beforeLast(afterLast(url, '/'), '.')
  return titleFromSlug(after(slug, '-', slug))
}

function titleFromSlug(slug: string): string {
  const spaced = slug.replaceAll('-', ' ').trim()
  if (spaced === '') return ''
  const head = spaced.charAt(0)
  return (head === head.toLowerCase() ? head.toUpperCase() : head) + spaced.slice(1)
}

function sectionIdFromUrl(pageUrl: string): string {
  try {
    return new URL(pageUrl).pathname.split('/').filter((s) => s !== '').join('-') || 'home'
  } catch {
    return 'home'
  }
}

function decodeEntities(input: string): string {
  return input
    .replaceAll('&#039;', "'")
    .replaceAll('&#39;', "'")
    .replaceAll('&quot;', '"')
    .replaceAll('&amp;', '&')
}

function after(s: string, delim: string, missing: string): string {
  const i = s.indexOf(delim)
  return i < 0 ? missing : s.slice(i + delim.length)
}

function afterLast(s: string, delim: string): string {
  const i = s.lastIndexOf(delim)
  return i < 0 ? s : s.slice(i + delim.length)
}

function beforeLast(s: string, delim: string): string {
  const i = s.lastIndexOf(delim)
  return i < 0 ? s : s.slice(0, i)
}
