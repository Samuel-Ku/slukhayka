/**
 * spec-50 T5 — порт KnigiOnlineAdapter (knigi-online.com.ua, server-fetch).
 * WordPress post-cards (`/audioknyha-/` URLs are the audio claims), og:*
 * book metadata and the AudioIgniter `data-tracks-url` block; the playlist
 * itself is plain JSON with direct same-host mp3s (T1: `Range` → 206).
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

const BASE = SOURCE_METADATA.knigionline.homeUrl

export const knigionlineAdapter: SourceAdapter = {
  id: 'knigionline',
  displayName: 'Knigi-Online',
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
    const { title, author } = titleAndAuthorFrom(html)
    return {
      url: pageUrl,
      title,
      author,
      // The page's own locale claim (`uk_UA`), normalized; the catalogue
      // is verified Ukrainian, so the adapter default holds when absent.
      language: normalizeLanguage(ogMeta(html, 'og:locale')) ?? 'uk',
      coverImageUrl: ogMeta(html, 'og:image') ?? undefined,
      genres: [],
      descriptionHtml: ogMeta(html, 'og:description') ?? undefined,
      // Chapters ride the playlist endpoint — the registry's buildBook
      // expands them via playlistUrlOf below (audiobookcoua pattern).
      chapters: [],
      otherNarrations: [],
      relatedBooks: [],
    }
  },
  search(html): CatalogCard[] {
    return listingCards(html)
  },
}

/**
 * One post-card block: the `«Title» Author` anchor, the cover, the book URL.
 * Ebook cards (no `/audioknyha-/` prefix) never enter — mixed site.
 */
export function listingCards(html: string): CatalogCard[] {
  if (html.trim() === '') return []
  const doc = parseHtml(html)
  const cards: CatalogCard[] = []
  for (const card of qsa(doc, 'div.post-card--vertical')) {
    const anchor = qs(card, '.post-card__title a')
    const url = attr(anchor ?? card, 'href')
    if (!url.includes('/audioknyha-')) continue
    const { title, author } = splitTitleAuthor(text(anchor))
    // A card with no readable title is junk — dropped, never a slug row.
    if (title === '') continue
    const cover = attr(qs(card, '.post-card__thumbnail img') ?? card, 'src')
    cards.push({
      url: absoluteUrl(url, BASE),
      title,
      author,
      ...(cover !== '' ? { coverImageUrl: absoluteUrl(cover, BASE) } : {}),
    })
  }
  return cards
}

/** `Аудіокнига «Title» Author` (og:title) or bare `«Title» Author`. */
export function titleAndAuthorFrom(html: string): { title: string; author: string } {
  const raw = (ogMeta(html, 'og:title') ?? titleTag(html)).replace(/^Аудіокнига\s+/, '').trim()
  return splitTitleAuthor(raw)
}

/** `«Title» Author` → (title, author); no `»` → whole text is the title. */
export function splitTitleAuthor(label: string): { title: string; author: string } {
  const close = label.indexOf('»')
  if (close < 0) return { title: label.trim(), author: '' }
  return { title: label.slice(0, close + 1).trim(), author: label.slice(close + 1).trim() }
}

/** The AudioIgniter tracks endpoint of a book page; absent → not playable. */
export function playlistUrlOf(html: string): string | null {
  const match = /data-tracks-url="([^"]+)"/.exec(html)
  const url = match === null ? '' : (match[1] ?? '')
  return url === '' ? null : url
}

export interface PlaylistTrack { title: string; url: string }

/** The plain-JSON playlist; broken JSON → empty, never a throw. */
export function playlistTracks(body: string): PlaylistTrack[] {
  let parsed: unknown
  try {
    parsed = JSON.parse(body)
  } catch {
    return []
  }
  if (!Array.isArray(parsed)) return []
  const tracks: PlaylistTrack[] = []
  for (const entry of parsed) {
    if (typeof entry !== 'object' || entry === null) continue
    const record = entry as Record<string, unknown>
    const url = record['audio']
    const title = record['title']
    if (typeof url !== 'string' || url === '' || typeof title !== 'string' || title.trim() === '') continue
    tracks.push({ title: title.trim(), url })
  }
  return tracks
}

function titleTag(html: string): string {
  const match = /<title>([^<]*)/.exec(html)
  return match === null ? '' : (match[1] ?? '').trim()
}

function ogMeta(html: string, property: string): string | null {
  const el = qs(parseHtml(html), `meta[property="${property}"]`)
  if (el === null) return null
  const content = attr(el, 'content')
  return content === '' ? null : content
}
