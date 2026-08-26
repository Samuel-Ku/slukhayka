/**
 * spec-43/T4 — порт SluhayuaAdapter
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

const BASE = 'https://sluhay.com.ua'

const TITLE_SUFFIX = /\s*\.\s*Слухай аудіокнигу онлайн\s*$/i
const NARRATOR_MARK = /читає\s+([^.,]+)/i
const PAGE_GENRE_BLOCK = /Жанр:<\/span>([\s\S]*?)<\/div>/i
const LINK_TEXT_G = /<a[^>]*>([^<]+)<\/a>/gi
const PLAYLIST_BLOCK = /var\s+playlist\s*=\s*([^;]+);/i
const PLAYLIST_ITEM_G = /\[\s*"\d+"\s*,\s*\d+\s*\]/g
const RAIL_TOKEN_G =
  /cardRollCategoryDescription[^>]*>\s*([^<]+)|titlePreviewText">[\s\S]*?<\/span>\s*<a\s+href="\/(\d+:[^"]+)"[^>]*>([^<]+)<\/a>/gi
const BR_TAG_G = /<br\s*\/?>/gi
const ANY_TAG_G = /<[^>]+>/g
const DIV_ITEMPROP_OPEN = /<div\b[^>]*itemprop="description"[^>]*>/i
const DIV_BOUNDARY_G = /<div\b|<\/div/gi
const DESCRIPTION_CUT_MARKERS = ['Автор озвучки:', 'Автор озвучки :'] as const

export const sluhayuaAdapter: SourceAdapter = {
  id: 'sluhayua',
  displayName: 'Sluhay UA',
  baseUrl: BASE,
  parseCatalog(jsonText, pageUrl): ParsedCatalog | null {
    let parsed: unknown
    try {
      parsed = JSON.parse(jsonText)
    } catch {
      return null
    }
    if (!isRecord(parsed) || !Array.isArray(parsed['cards'])) return null
    const cards = parsed['cards'].filter(isRecord).map(cardFromRecord)
    const section: CatalogSection = { id: 'allcards', title: '', url: pageUrl, cards }
    return { sections: [section] }
  },
  parseBookPage(html, pageUrl): BookDetail {
    const cleanOg = cleanOgTitle(html)
    const title = cleanOg === null ? '' : afterFirst(cleanOg, ' - ', cleanOg).trim()
    const author =
      cleanOg !== null && cleanOg.includes(' - ') ? beforeFirst(cleanOg, ' - ').trim() : ''
    return {
      url: pageUrl,
      title,
      author,
      narrator: narratorFromPage(html),
      coverImageUrl: coverFromPage(html),
      genres: genresFromPage(html),
      descriptionHtml: descriptionFromPage(html) || undefined,
      chapters: [],
      otherNarrations: relatedFromPage(html, pageUrl),
    }
  },
}

export function chapterCountOf(html: string): number {
  const block = PLAYLIST_BLOCK.exec(html)?.[1]
  if (block === undefined) return 0
  return [...block.matchAll(PLAYLIST_ITEM_G)].length
}

export function chaptersFromPlayResponses(responses: readonly string[]): Chapter[] {
  const chapters: Chapter[] = []
  for (const response of responses) {
    const stream = response.trim()
    if (stream === '' || stream === '0' || stream === '404' || !stream.startsWith('http')) break
    chapters.push({ title: `Глава ${chapters.length + 1}`, streamUrl: stream })
  }
  return chapters
}

function cardFromRecord(record: Record<string, unknown>): CatalogCard {
  const id = typeof record['_id'] === 'number' ? String(record['_id']) : asString(record['_id'])
  const slug = asString(record['slug'])
  const bookName = asString(record['bookName'])
  const fallback = beforeFirst(asString(record['title']), ' - ').trim()
  const cover = asString(record['kindSrc'])
  return {
    url: `${BASE}/${id}:${slug}`,
    title: bookName.trim() === '' ? fallback : bookName,
    author: firstStringElement(record['bookAuthor']),
    narrator: firstStringElement(record['audioAuthor']) || undefined,
    coverImageUrl:
      cover.trim() === ''
        ? undefined
        : cover.startsWith('http')
          ? cover
          : absoluteUrl(cover, BASE),
  }
}

function cleanOgTitle(html: string): string | null {
  const og = ogMeta(html, 'og:title')
  if (og === null) return null
  return og.replace(TITLE_SUFFIX, '').trim()
}

function narratorFromPage(html: string): string | undefined {
  const match = NARRATOR_MARK.exec(ogMeta(html, 'og:description') ?? '')?.[1]?.trim()
  return match === undefined || match === '' ? undefined : match
}

function coverFromPage(html: string): string | undefined {
  const og = ogMeta(html, 'og:image')
  return og === null ? undefined : og.replaceAll('//uploads', '/uploads')
}

function genresFromPage(html: string): string[] {
  const block = PAGE_GENRE_BLOCK.exec(html)?.[1]
  if (block === undefined) return []
  return [...block.matchAll(LINK_TEXT_G)]
    .map((m) => decodeEntities(m[1].trim()))
    .filter((genre) => genre !== '')
}

function descriptionFromPage(html: string): string {
  const ogFallback = (ogMeta(html, 'og:description') ?? '').trim()
  const container = itempropDescriptionContainer(html)
  if (container === null) return ogFallback
  const raw = html.slice(container[0], container[1])
  const kept = cutAtEarliestMarker(raw, DESCRIPTION_CUT_MARKERS) ?? raw
  const cleaned = kept
    .replace(BR_TAG_G, '\n')
    .replace(ANY_TAG_G, ' ')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
    .join('\n')
  return cleaned === '' ? ogFallback : cleaned
}

function itempropDescriptionContainer(html: string): [number, number] | null {
  const open = DIV_ITEMPROP_OPEN.exec(html)
  if (open === null) return null
  const bodyStart = open.index + open[0].length
  let depth = 1
  DIV_BOUNDARY_G.lastIndex = bodyStart
  for (let m = DIV_BOUNDARY_G.exec(html); m !== null; m = DIV_BOUNDARY_G.exec(html)) {
    if (m[0].charAt(1) === '/') {
      depth -= 1
      if (depth === 0) return [bodyStart, m.index]
    } else {
      depth += 1
    }
  }
  return null
}

function cutAtEarliestMarker(input: string, markers: readonly string[]): string | null {
  const lower = input.toLowerCase()
  let earliest = -1
  for (const marker of markers) {
    const idx = lower.indexOf(marker.toLowerCase())
    if (idx >= 0 && (earliest < 0 || idx < earliest)) earliest = idx
  }
  return earliest >= 0 ? input.slice(0, earliest).trim() : null
}

function relatedFromPage(html: string, selfUrl: string): CatalogCard[] {
  const selfId = beforeFirst(afterLast(selfUrl, '/'), ':')
  let rail: string | null = null
  const seen = new Set<string>()
  const related: CatalogCard[] = []
  for (const token of html.matchAll(RAIL_TOKEN_G)) {
    const header = token[1] ?? ''
    if (header !== '') {
      rail = decodeEntities(header).trim()
      continue
    }
    if (rail === null) continue
    const href = token[2] ?? ''
    if (beforeFirst(href, ':') === selfId || seen.has(href)) continue
    seen.add(href)
    const anchor = decodeEntities((token[3] ?? '').trim()).trim()
    const sep = anchor.indexOf(' - ')
    related.push({
      url: `${BASE}/${href}`,
      title: sep >= 0 ? anchor.slice(sep + 3).trim() : anchor,
      author: sep >= 0 ? anchor.slice(0, sep).trim() : '',
    })
  }
  return related
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function asString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function firstStringElement(value: unknown): string {
  if (!Array.isArray(value)) return ''
  for (const item of value) {
    if (typeof item === 'string') return item.trim()
  }
  return ''
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

function beforeFirst(s: string, delim: string): string {
  const i = s.indexOf(delim)
  return i < 0 ? s : s.slice(0, i)
}

function afterFirst(s: string, delim: string, missing: string): string {
  const i = s.indexOf(delim)
  return i < 0 ? missing : s.slice(i + delim.length)
}

function afterLast(s: string, delim: string): string {
  const i = s.lastIndexOf(delim)
  return i < 0 ? s : s.slice(i + delim.length)
}
