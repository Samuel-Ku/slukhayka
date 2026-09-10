/**
 * spec-47 T6 — порт ChytayloAdapter (chytaylo.com.ua, server-fetch).
 * Next.js SSR: the listing's `<article class="group min-w-0">` cards, the
 * schema.org Book JSON-LD and the ESCAPED player-props payload embedded in
 * the React server-component text (one escape layer: every quote is `\"`).
 * The page's own payload decides the audio-only boundary — no `tracks`
 * array means not an audiobook, chapters honestly empty.
 */
import { absoluteUrl, attr, parseHtml, qs, qsa, text, type BookDetail, type CatalogCard, type CatalogSection, type ParsedCatalog, type SourceAdapter } from '../types'
import { normalizeLanguage } from '../language'
import { SOURCE_METADATA } from '../sourceMetadata'

const HOME_SECTION_ID = 'home'

function sectionIdFromUrl(pageUrl: string): string {
  try {
    const segments = new URL(pageUrl).pathname.split('/').filter((s) => s !== '')
    return segments.length === 0 ? HOME_SECTION_ID : segments.join('-')
  } catch {
    return HOME_SECTION_ID
  }
}

const BASE = SOURCE_METADATA.chytaylo.homeUrl

// The escaped `\"tracks\":[` key of the server-component payload — the bytes
// as the live page renders them (one escape layer).
const ESCAPED_TRACKS_KEY = '\\"tracks\\":['

// The fully decoded key the plain-JSON scan needs.
const PLAIN_TRACKS_KEY = '"tracks":['

// The JSON-LD script opener the Book block sits behind.
const LD_SCRIPT = 'script[type="application/ld+json"]'

// The listing's annotation heading («Про що книга»).
const DESCRIPTION_HEADING = 'Про що книга'

interface PlayerTrack { title: string; url: string }

interface PlayerProps {
  tracks: PlayerTrack[]
  bookTitle: string
  coverUrl: string
}

interface BookLd {
  name: string
  author: string
  cover: string | null
  inLanguage: string
}

export const chytayloAdapter: SourceAdapter = {
  id: 'chytaylo',
  displayName: 'Читайло',
  baseUrl: BASE,
  parseCatalog(html, pageUrl): ParsedCatalog | null {
    const cards = listingCards(html)
    if (cards.length === 0) return null
    const section: CatalogSection = { id: sectionIdFromUrl(pageUrl), title: '', url: pageUrl, cards }
    return { sections: [section] }
  },
  parseBookPage(html, pageUrl): BookDetail {
    if (html.trim() === '') {
      return { url: pageUrl, title: '', author: '', genres: [], chapters: [], otherNarrations: [], relatedBooks: [] }
    }
    const ld = bookJsonLd(html)
    const player = playerPropsFrom(html)
    const ldCover = ld?.cover
    const playerCover = player?.coverUrl
    const coverImageUrl = (ldCover !== null && ldCover !== undefined && ldCover !== '')
      ? absoluteUrl(ldCover, BASE)
      : playerCover !== undefined && playerCover !== ''
        ? absoluteUrl(playerCover, BASE)
        : ogMeta(html, 'og:image') ?? undefined
    return {
      url: pageUrl,
      title: ld?.name ?? player?.bookTitle ?? '',
      author: ld?.author ?? '',
      // The page's own BCP-47 claim («uk-UA»), normalized — never guessed.
      language: normalizeLanguage(ld?.inLanguage ?? null) ?? undefined,
      coverImageUrl,
      genres: [],
      // The «Про що книга» container's text, tags stripped. The sections
      // after the container never leak in.
      descriptionHtml: descriptionFrom(html) || undefined,
      chapters: (player?.tracks ?? []).map((track) => ({
        title: track.title,
        // Root-relative track URLs — prefixed with the site origin, once.
        streamUrl: absoluteUrl(track.url, BASE),
      })),
      // Measured absence: the page renders no per-book duration.
      otherNarrations: [],
      relatedBooks: [],
    }
  },
}

/** One listing card: url, cover, title, author — a card without the author div keeps it empty. */
export function listingCards(html: string): CatalogCard[] {
  if (html.trim() === '') return []
  const doc = parseHtml(html)
  const cards: CatalogCard[] = []
  for (const article of qsa(doc, 'article.group.min-w-0')) {
    const path = attr(qs(article, 'a[href^="/books/"]') ?? article, 'href')
    if (!path.startsWith('/books/')) continue
    // A card with no readable text is junk — dropped, never a slug row.
    const title = text(qs(article, '.line-clamp-2'))
    if (title === '') continue
    const author = text(qs(article, '.mt-1.line-clamp-2'))
    const cover = attr(qs(article, 'img') ?? article, 'src')
    cards.push({
      url: absoluteUrl(path, BASE),
      title,
      author,
      ...(cover !== '' ? { coverImageUrl: absoluteUrl(cover, BASE) } : {}),
    })
  }
  return cards
}

/** The decoded player props (tracks, bookTitle, coverUrl) of a book page. */
export function playerPropsFrom(html: string): PlayerProps | null {
  const key = html.indexOf(ESCAPED_TRACKS_KEY)
  if (key < 0) return null
  const decoded = jsonUnescapeWindow(html.slice(key))
  const arrayKeyAt = decoded.indexOf(PLAIN_TRACKS_KEY)
  if (arrayKeyAt < 0) return null
  // PLAIN_TRACKS_KEY ends WITH the opening bracket — start the scan on it.
  const array = balancedContent(decoded, arrayKeyAt + PLAIN_TRACKS_KEY.length - 1, '[', ']')
  if (array === null) return null
  const tracks: PlayerTrack[] = []
  let pos = 0
  for (;;) {
    const objStart = array.indexOf('{', pos)
    if (objStart < 0) break
    const obj = balancedContent(array, objStart, '{', '}')
    if (obj === null) break
    const url = quotedField(obj, 'url')
    const title = (quotedField(obj, 'title') ?? '').trim()
    if (url !== null && url !== '' && title !== '') tracks.push({ title, url })
    pos = objStart + obj.length
  }
  return {
    tracks,
    bookTitle: quotedField(decoded, 'bookTitle') ?? '',
    coverUrl: quotedField(decoded, 'coverUrl') ?? '',
  }
}

/** The schema.org `Book` block's fields (a plain JSON script — parsed, not guessed). */
function bookJsonLd(html: string): BookLd | null {
  const doc = parseHtml(html)
  for (const script of qsa(doc, LD_SCRIPT)) {
    const raw = text(script)
    if (raw === '') continue
    let parsed: unknown
    try {
      parsed = JSON.parse(raw)
    } catch {
      continue
    }
    const book = findBookObject(parsed)
    if (book === null) continue
    const name = book.name
    if (typeof name !== 'string' || name === '') continue
    const authorValue = book.author
    const author = typeof authorValue === 'object' && authorValue !== null
      ? (authorValue as { name?: unknown }).name
      : authorValue
    return {
      name,
      author: typeof author === 'string' ? author : '',
      cover: typeof book.image === 'string' && book.image.trim() !== '' ? book.image.trim() : null,
      inLanguage: typeof book.inLanguage === 'string' ? book.inLanguage : '',
    }
  }
  return null
}

/** The first object (of an array script) that claims `"@type":"Book"`. */
function findBookObject(parsed: unknown): Record<string, unknown> | null {
  const candidates: unknown[] = Array.isArray(parsed) ? parsed : [parsed]
  for (const candidate of candidates) {
    if (typeof candidate !== 'object' || candidate === null) continue
    const obj = candidate as Record<string, unknown>
    if (obj['@type'] === 'Book') return obj
  }
  return null
}

/**
 * The «Про що книга» annotation: the div that follows the heading in its
 * parent — the whole container, tags stripped. Absent section → ''.
 */
function descriptionFrom(html: string): string {
  const doc = parseHtml(html)
  const heading = qsa(doc, 'h2').find((h) => text(h).includes(DESCRIPTION_HEADING))
  if (heading === null || heading === undefined || heading.parent === null) return ''
  const siblings = heading.parent.children
  const index = siblings.indexOf(heading)
  for (let i = index + 1; i < siblings.length; i++) {
    const sibling = siblings[i]
    if (sibling.type === 'tag' && sibling.name === 'div') {
      return text(sibling)
    }
  }
  return ''
}

/** A `"name":"value"` string field in plain JSON, escapes decoded once. */
function quotedField(json: string, name: string): string | null {
  const match = new RegExp(`"${name}"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"`).exec(json)
  return match === null ? null : jsonUnescape(match[1] ?? '')
}

/**
 * Decodes ONE layer of JSON escaping over a server-component window:
 * `\"` → `"`, `\\` → `\`, `\/` → `/`; any other `\x` pair survives for the
 * per-value [jsonUnescape] pass (`\uXXXX` stays). Plain left-to-right pair
 * consumption — never a naive global replace.
 */
function jsonUnescapeWindow(s: string): string {
  let out = ''
  let i = 0
  while (i < s.length) {
    const c = s[i]
    if (c === '\\' && i + 1 < s.length) {
      const next = s[i + 1]
      if (next === '"' || next === '\\' || next === '/') {
        out += next
        i += 2
        continue
      }
    }
    out += c
    i++
  }
  return out
}

/** Unescapes a JSON string value (`\uXXXX`, `\\`, `\"`, `\/`, `\n\t\r`). */
function jsonUnescape(s: string): string {
  let out = ''
  let i = 0
  while (i < s.length) {
    const c = s[i]
    if (c !== '\\' || i + 1 >= s.length) {
      out += c
      i++
      continue
    }
    const next = s[i + 1]
    if (next === 'u') {
      const hex = s.slice(i + 2, i + 6)
      if (hex.length === 4 && /^[0-9a-fA-F]{4}$/.test(hex)) {
        out += String.fromCharCode(parseInt(hex, 16))
        i += 6
      } else {
        out += c
        i++
      }
      continue
    }
    const decoded: Record<string, string> = { '\\': '\\', '"': '"', '/': '/', n: '\n', t: '\t', r: '\r' }
    out += decoded[next] ?? next
    i += 2
  }
  return out
}

/** The substring from [start] (an [open] bracket) through its matching [close] bracket. */
function balancedContent(s: string, start: number, open: string, close: string): string | null {
  let depth = 0
  let inString = false
  let i = start
  while (i < s.length) {
    const c = s[i]
    if (inString) {
      if (c === '\\') {
        i++
      } else if (c === '"') {
        inString = false
      }
    } else if (c === '"') {
      inString = true
    } else if (c === open) {
      depth++
    } else if (c === close) {
      depth--
      if (depth === 0) return s.slice(start, i + 1)
    }
    i++
  }
  return null
}

function ogMeta(html: string, property: string): string | null {
  const el = qs(parseHtml(html), `meta[property="${property}"]`)
  if (el === null) return null
  const content = attr(el, 'content')
  return content === '' ? null : content
}