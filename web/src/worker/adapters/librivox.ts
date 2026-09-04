/**
 * spec-45 (#405) T12 (#500) — librivox.org joins the web worker as the
 * English-language source. The MVP surfaces ENGLISH books only (the spec's
 * LibriVox start), one card per Work, alongside the Ukrainian sources.
 *
 * ## Two transports, ONE sourceId
 *
 * - **librivox.org JSON API** (`/api/feed/audiobooks/?format=json`) — the
 *   catalogue enumeration ([parseCatalog]): clean titles, durations, and the
 *   `url_zip_file` handle that carries the archive.org identifier. The API
 *   has no keyword search.
 * - **archive.org advanced search** — the `archive.org/details/librivoxaudio`
 *   MIRROR of the same recordings, used for keyword [search]. Cards returned
 *   from the mirror carry `archive.org/details/<identifier>` URLs but the
 *   SAME `sourceId = "librivox"`, so a book found on both transports merges
 *   into one card by the union's merge key (title|author), never a second
 *   catalogue row — "One book, one card".
 *
 * Every card claims `language = en` (records are English-filtered at the
 * parse level, then normalized through [normalizeLanguage] — the Android
 * LanguageCode rule).
 *
 * Book details are spec-45 T3's archive.org mirror pattern: the registry's
 * buildBook fetches `archive.org/metadata/<identifier>` and [buildBookDetail]
 * materializes the ordered VBR MP3 sections with their id3 titles, track
 * numbers and durations. The details-page HTML is never parsed — a URL that
 * is not an `archive.org/details/` page honestly reports nothing playable.
 */
import type {
  BookDetail,
  CatalogCard,
  CatalogSection,
  ParsedCatalog,
  SourceAdapter,
} from '../types'
import { SOURCE_METADATA } from '../sourceMetadata'
import { normalizeLanguage } from '../language'

const BASE = SOURCE_METADATA.librivox.homeUrl

/** How many catalogue records one feed request carries (cursor pagination). */
export const FEED_LIMIT = 30

/** The catalogue feed URL for one offset (the registry's catalogUrl seam). */
export function catalogUrlOf(offset = 0): string {
  return `${BASE}/api/feed/audiobooks/?format=json&limit=${FEED_LIMIT}&offset=${offset}`
}

export const librivoxAdapter: SourceAdapter = {
  id: 'librivox',
  displayName: 'LibriVox',
  baseUrl: BASE,
  parseCatalog(jsonText, pageUrl): ParsedCatalog | null {
    const books = apiBooksFrom(jsonText)
    if (books === null) return null
    const cards = books
      .map(cardFromApiBook)
      .filter((card): card is CatalogCard => card !== null)
    const section: CatalogSection = { id: 'librivox', title: 'LibriVox', url: pageUrl, cards }
    return { sections: [section], nextPageUrl: nextPageUrlOf(pageUrl, books.length) }
  },
  parseBookPage(): BookDetail | null {
    // The transport's buildBook resolves the real detail from the archive
    // metadata JSON; a page whose HTML reached us directly is never parsed
    // into a fabricated detail.
    return null
  },
  search: (jsonText) => archiveDocsFrom(jsonText),
}

// --- librivox.org API catalogue ---------------------------------------------

/** One `books[]` record of the JSON API feed. */
interface ApiBook {
  title: string
  language: string
  urlZipFile: string
  firstAuthor: string
  totaltimeSecs: number
}

function apiBooksFrom(jsonText: string): ApiBook[] | null {
  const parsed = parseJson(jsonText)
  if (parsed === null || !isRecord(parsed) || !Array.isArray(parsed['books'])) return null
  return parsed['books']
    .filter(isRecord)
    .map((record): ApiBook => ({
      title: asString(record['title']),
      language: asString(record['language']),
      urlZipFile: asString(record['url_zip_file']),
      firstAuthor: firstAuthorOf(record),
      totaltimeSecs: typeof record['totaltimesecs'] === 'number' ? record['totaltimesecs'] : 0,
    }))
}

/**
 * English records only (the LibriVox start of spec-45). The API reports the
 * language as a word ("English", "German", …) — the word itself is the
 * filter, and [normalizeLanguage] maps it to its BCP-47 tag, never guessed.
 * Cards carry the archive.org mirror page so ONE page shape serves both
 * transports (T3: playback reads the archive metadata).
 */
function cardFromApiBook(book: ApiBook): CatalogCard | null {
  if (book.language !== 'English') return null
  const identifier = archiveIdentifierOf(book.urlZipFile)
  if (book.title.trim() === '' || identifier === '') return null
  return {
    url: `https://archive.org/details/${identifier}`,
    title: book.title,
    author: book.firstAuthor,
    language: normalizeLanguage(book.language) ?? undefined,
    durationSeconds: book.totaltimeSecs > 0 ? book.totaltimeSecs : undefined,
  }
}

/** The archive identifier of a `url_zip_file` record: `…/compress/<id>/…`. */
function archiveIdentifierOf(raw: string): string {
  if (raw === '') return ''
  try {
    const segments = new URL(raw).pathname.split('/')
    return segments[1] === 'compress' && (segments[2] ?? '') !== '' ? segments[2] ?? '' : ''
  } catch {
    return ''
  }
}

function firstAuthorOf(record: Record<string, unknown>): string {
  const first = firstStringElement(record['authors'], 'first_name')
  const last = firstStringElement(record['authors'], 'last_name')
  return [first, last].filter((part) => part !== '').join(' ')
}

/** The API feed has no total; a full page means the catalogue continues. */
function nextPageUrlOf(pageUrl: string, received: number): string | undefined {
  if (received < FEED_LIMIT) return undefined
  try {
    const url = new URL(pageUrl)
    const offset = Number(url.searchParams.get('offset') ?? '0')
    url.searchParams.set('offset', String(Number.isFinite(offset) ? offset + received : received))
    return url.toString()
  } catch {
    return undefined
  }
}

// --- archive.org advanced search (the mirror transport) -------------------

function archiveDocsFrom(jsonText: string): CatalogCard[] {
  const parsed = parseJson(jsonText)
  if (parsed === null || !isRecord(parsed)) return []
  const response = isRecord(parsed['response']) ? parsed['response'] : null
  if (response === null || !Array.isArray(response['docs'])) return []
  return response['docs']
    .filter(isRecord)
    .map(cardFromDoc)
    .filter((card): card is CatalogCard => card !== null)
}

function cardFromDoc(doc: Record<string, unknown>): CatalogCard | null {
  const identifier = asString(doc['identifier'])
  const title = asString(doc['title'])
  if (identifier === '' || title.trim() === '') return null
  return {
    url: `https://archive.org/details/${identifier}`,
    title,
    author: asString(doc['creator']),
    language: normalizeLanguage(asString(doc['language'])) ?? undefined,
  }
}

/** Advanced-search URL over the librivoxaudio mirror collection. */
export function archiveSearchUrl(query: string, rows = 20): string {
  const clean = query.trim().replaceAll('"', '')
  // The quoted phrase keeps user words like "and"/"or" out of the archive's
  // query operators; the collection+language clause is the English gate.
  const q = `collection:librivoxaudio AND language:eng${clean === '' ? '' : ` AND "${clean}"`}`
  const params = new URLSearchParams()
  params.set('q', q)
  params.set('fl[]', 'identifier')
  params.set('fl[]', 'title')
  params.set('fl[]', 'creator')
  params.set('fl[]', 'language')
  params.set('rows', String(rows))
  params.set('output', 'json')
  return `https://archive.org/advancedsearch.php?${params.toString()}`
}

// --- book detail from the archive metadata JSON ----------------------------

/** The archive identifier when [pageUrl] is an `archive.org/details/` page. */
export function identifierOf(pageUrl: string): string | null {
  try {
    const segments = new URL(pageUrl).pathname.split('/')
    if (segments[1] !== 'details') return null
    const identifier = segments[2] ?? ''
    return identifier === '' ? null : identifier
  } catch {
    return null
  }
}

/**
 * A full book detail from `archive.org/metadata/<identifier>`. The ordered
 * chapters are the VBR MP3 files (the archive also stores 64/128 Kbps
 * duplicates, covers and playlists — never chapters); their id3 `title` is
 * the real section name, `track` the order and `length` the clock duration
 * (either `mm:ss`/`h:mm:ss` or decimal seconds — both appear in the wild).
 */
export function buildBookDetail(jsonText: string, pageUrl: string): BookDetail | null {
  const identifier = identifierOf(pageUrl)
  if (identifier === null) return null
  const parsed = parseJson(jsonText)
  if (parsed === null || !isRecord(parsed)) return null
  const meta = isRecord(parsed['metadata']) ? parsed['metadata'] : {}
  const files = Array.isArray(parsed['files'])
    ? parsed['files'].filter(isRecord)
    : []
  const chapters = files
    .filter((file) => asString(file['format']) === 'VBR MP3' && asString(file['name']) !== '')
    .sort((left, right) =>
      trackRank(left) - trackRank(right) ||
      asString(left['name']).localeCompare(asString(right['name'])),
    )
    .map((file) => {
      const name = asString(file['name'])
      return {
        title: asString(file['title']) !== '' ? asString(file['title']) : name.replace(/\.mp3$/i, ''),
        streamUrl: `https://archive.org/download/${identifier}/${encodeURIComponent(name)}`,
        durationSeconds: parseLengthSeconds(asString(file['length'])) ?? undefined,
      }
    })
  const description = asString(meta['description'])
  return {
    url: pageUrl,
    title: asString(meta['title']),
    author: asString(meta['creator']),
    language: normalizeLanguage(asString(meta['language'])) ?? undefined,
    coverImageUrl: `https://archive.org/download/${identifier}/__ia_thumb.jpg`,
    genres: [],
    descriptionHtml: description === '' ? undefined : description,
    chapters,
    otherNarrations: [],
  }
}

/** Files without a track number sort last, by name (the Android rule). */
function trackRank(file: Record<string, unknown>): number {
  const track = Number.parseInt(asString(file['track']), 10)
  return Number.isFinite(track) ? track : Number.MAX_SAFE_INTEGER
}

/** `19:39`, `1:02:33` or decimal seconds (`736.37`) → whole seconds. */
function parseLengthSeconds(raw: string): number | null {
  const value = raw.trim()
  if (value === '') return null
  if (value.includes(':')) {
    const parts = value.split(':')
    if (parts.length < 2 || parts.length > 3) return null
    const numbers = parts.map((part) => {
      const number = Number(part)
      return Number.isFinite(number) && number >= 0 ? number : null
    })
    if (numbers.some((number) => number === null)) return null
    const [hours, minutes, seconds] = numbers.length === 3 ? numbers : [0, ...numbers]
    return Math.round((hours ?? 0) * 3600 + (minutes ?? 0) * 60 + (seconds ?? 0))
  }
  const seconds = Number(value)
  return Number.isFinite(seconds) && seconds >= 0 ? Math.round(seconds) : null
}

// --- tiny JSON walking helpers ---------------------------------------------

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function asString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

/** The first non-empty value of [key] across an array of author records. */
function firstStringElement(value: unknown, key: string): string {
  if (!Array.isArray(value)) return ''
  for (const item of value) {
    if (isRecord(item) && typeof item[key] === 'string' && (item[key] as string).trim() !== '') {
      return (item[key] as string).trim()
    }
  }
  return ''
}