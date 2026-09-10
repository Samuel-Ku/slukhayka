/**
 * spec-50 T5 — порт ChitakaAdapter (chitaka.com.ua, server-fetch).
 * Lazyload listing cards (`book-image` + `recomend-book-title` anchors) and
 * the native `<audio>` book page with a direct same-host mp3 (T1: `Range` →
 * 206). Books are single-file — one track carries the book's own title.
 * No server-side search through query strings (robots `Disallow: *?*`) — no
 * `search` member, the honest absence, exactly like the Kotlin adapter.
 */
import { absoluteUrl, attr, parseHtml, qs, qsa, text, type BookDetail, type CatalogCard, type CatalogSection, type ParsedCatalog, type SourceAdapter } from '../types'
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

const BASE = SOURCE_METADATA.chitaka.homeUrl

export const chitakaAdapter: SourceAdapter = {
  id: 'chitaka',
  displayName: 'Читака',
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
    const audio = audioUrlFrom(html)
    return {
      url: pageUrl,
      title,
      author,
      language: 'uk',
      coverImageUrl: ogMeta(html, 'og:image') ?? undefined,
      genres: [],
      chapters: audio === null ? [] : [{ title, streamUrl: absoluteUrl(audio, BASE) }],
      otherNarrations: [],
      relatedBooks: [],
    }
  },
}

/**
 * Listing cards grouped by href: the cover and title anchors share the book
 * URL (cover first, title after). Covers ride lazyload `data-src` (the
 * `src` is a placeholder SVG). Nav/menu anchors never enter — only the two
 * book-card classes. A card with no readable title is junk — dropped.
 */
export function listingCards(html: string): CatalogCard[] {
  if (html.trim() === '') return []
  const doc = parseHtml(html)
  const titles = new Map<string, string>()
  const covers = new Map<string, string>()
  for (const anchor of qsa(doc, 'a[href]')) {
    const cls = attr(anchor, 'class')
    if (!cls.includes('book-image') && !cls.includes('recomend-book-title')) continue
    const url = attr(anchor, 'href')
    if (url === '') continue
    const label = attr(anchor, 'title') !== '' ? attr(anchor, 'title') : text(anchor)
    if (label.trim() !== '' && !titles.has(url)) titles.set(url, label.trim())
    const dataSrc = attr(qs(anchor, 'img') ?? anchor, 'data-src')
    if (dataSrc !== '' && !covers.has(url)) covers.set(url, dataSrc)
  }
  const cards: CatalogCard[] = []
  for (const [url, title] of titles) {
    const cover = covers.get(url) ?? ''
    cards.push({
      url: absoluteUrl(url, BASE),
      title,
      author: '',
      ...(cover !== '' ? { coverImageUrl: absoluteUrl(cover, BASE) } : {}),
    })
  }
  return cards
}

/**
 * `«Title» Author ⭐️ …` (og:title preferred, `<title>` fallback). The store
 * suffix is cut — the author is what precedes it. No `»` → whole text.
 */
export function titleAndAuthorFrom(html: string): { title: string; author: string } {
  const raw = (ogMeta(html, 'og:title') ?? titleTag(html)).trim()
  const close = raw.indexOf('»')
  if (close < 0) return { title: raw, author: '' }
  return {
    title: raw.slice(0, close + 1).trim(),
    author: raw.slice(close + 1).split('⭐')[0]?.trim() ?? '',
  }
}

/**
 * The native audio tag's mp3; absent → not an audiobook page (the
 * audio-only boundary — text books share the path but render no audio).
 */
export function audioUrlFrom(html: string): string | null {
  const doc = parseHtml(html)
  const audio = qs(doc, 'audio.lib_book_audio') ?? qs(doc, 'audio')
  if (audio === null) return null
  const src = attr(qs(audio, 'source') ?? audio, 'src')
  return src === '' ? null : src
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
