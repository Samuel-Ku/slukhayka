/**
 * spec-43/T3+T4 — the shared data contract between the Worker's source
 * adapters and everything downstream (client UI, future search merge).
 * Shapes mirror the domain vocabulary (CONTEXT.md): a card is one Work
 * surface, a book page resolves one Edition's chapters.
 *
 * Parsing happens server-side in the Cloudflare Worker (ADR-0024), so the
 * HTML toolkit here is Worker-safe: htmlparser2 + css-select, no DOMParser.
 */
import { parseDocument } from 'htmlparser2'
import type { Document, AnyNode, Element } from 'domhandler'
import { selectAll, selectOne } from 'css-select'
import { getText, getAttributeValue } from 'domutils'

// Spec-47 T6 — the wave's two server-fetch sources join; ukrainianaudiobooks
// is deliberately ABSENT (Cloudflare-GATED, the worker has no WebView
// session — the honest absence per the spec).
export type SourceId = 'fourread' | 'sound-books' | 'audiobook-mp3' | 'lihtar' | 'sluhayua' | 'sluhay' | 'librivox' | 'audiobookcoua' | 'chytaylo' | 'knigionline' | 'chitaka'

export interface CatalogCard {
  /** Stable page URL of this Work on its Source. */
  url: string
  title: string
  author: string
  narrator?: string
  coverImageUrl?: string
  seriesName?: string
  seriesPart?: number
  /** Real duration carried by the listing row («Триває:»), when the source shows one. */
  durationSeconds?: number
  /**
   * BCP-47 content language of this narration (spec-45 #405); absent =
   * unknown (never guessed). A whole-language source doesn't tag every card:
   * the merge resolves it from the source's declared content language.
   */
  language?: string
  /**
   * Entry count carried by an index listing when the page shows one
   * (e.g. «Ім'я - N книг» on the Виконавці/Автори pages). Never guessed:
   * absent = the listing carried no count.
   */
  count?: number
  /**
   * W3.3 — genre page memberships observed at the Source (a poster ON a
   * genre page belongs to that genre by construction). Never guessed:
   * absent = no genre claim, and a Work with no claims never matches a
   * genre selection (Android `work_genres` semantics).
   */
  genres?: string[]
}

export interface CatalogSection {
  /** Machine id of the section within the source's own catalogue. */
  id: string
  title: string
  url?: string
  cards: CatalogCard[]
}

export interface ParsedCatalog {
  sections: CatalogSection[]
  /** Next page URL when the source paginates its feed; absent = end. */
  nextPageUrl?: string
}

/** One physical source attached to one Edition — never a cross-edition fallback. */
export interface UnifiedSource {
  sourceId: SourceId
  url: string
  /** Anonymous clean-probe metadata; never a cookie-bound stream URL. */
  availability?: 'available' | 'unavailable'
  verifiedAt?: number
}

export interface UnifiedEdition {
  id: string
  narrator?: string
  /** BCP-47 content language of the rendition (spec-45 #405); absent = unknown. */
  language?: string
  durationSeconds?: number
  chapterCount?: number
  isComplete?: boolean
  verifiedAt?: number
  sources: UnifiedSource[]
}

/** A bibliographic Work; narrator and progress remain Edition-owned. */
export interface UnifiedWork {
  id: string
  mergeKey: string
  title: string
  author: string
  coverImageUrl?: string
  editions: UnifiedEdition[]
  /**
   * W3.3 — genre page memberships the worker observed (union across the
   * source cards). Absent = no claim: the regular merged feed carries none
   * (genre pages are the only honest genre source).
   */
  genres?: string[]
}

export interface UnifiedWorkPage {
  works: UnifiedWork[]
  nextCursor?: string
}

export interface Chapter {
  title: string
  /** The physical track of this Edition's primary source (ADR-0007 pairing). */
  streamUrl: string
  durationSeconds?: number
}

export interface BookDetail {
  url: string
  title: string
  author: string
  narrator?: string
  /** BCP-47 content language of this rendition (spec-45 #405); absent = unknown. */
  language?: string
  coverImageUrl?: string
  genres: string[]
  descriptionHtml?: string
  chapters: Chapter[]
  /** «Інші начитки»: other renditions of the same Work on this source. */
  otherNarrations: CatalogCard[]
  /** W4.1 — «Можливо, Тебе зацікавить»: the page's own related-book posters. */
  relatedBooks: CatalogCard[]
  /** W4.1 — «У серії»: the series (cycle) this book belongs to, from the page's «Цикл:» row. */
  series?: { name: string; url: string; position?: number }
  /** W4.1 — the source's own rating score («pmovie__rating-score»), when the page declares one. */
  rating?: number
  /** The page's own full duration («Триває:»), when the source declares it — never a sum of unknowns. */
  totalDurationSeconds?: number
}

/**
 * One source's parsing face over raw payloads it knows how to read. All
 * functions are PURE — fetching stays in the Worker glue — and every parser
 * degrades honestly: anything it cannot understand comes back null/empty,
 * never an exception.
 */
export interface SourceAdapter {
  readonly id: SourceId
  readonly displayName: string
  /** The site root, used to resolve relative links found in payloads. */
  readonly baseUrl: string
  parseCatalog(html: string, pageUrl: string): ParsedCatalog | null
  parseBookPage(html: string, pageUrl: string): BookDetail | null
  /**
   * spec-43/T4 — best-effort search over an ALREADY-FETCHED result payload
   * (pure parse; the Worker owns the query URL and the transport). Optional:
   * a source without a usable search endpoint simply lacks the member —
   * exactly the Kotlin adapters whose `search` returns emptyList (spec-10 T1).
   */
  search?(html: string, pageUrl: string): CatalogCard[]
}

// --- HTML walking helpers shared by every adapter -------------------------

export function parseHtml(html: string): Document {
  return parseDocument(html)
}

export function qs<T extends AnyNode = Element>(root: AnyNode, selector: string): T | null {
  try {
    return (selectOne(selector, root) as T | null) ?? null
  } catch {
    return null
  }
}

export function qsa<T extends AnyNode = Element>(root: AnyNode, selector: string): T[] {
  try {
    return (selectAll(selector, root) as unknown as T[]) ?? []
  } catch {
    return []
  }
}

/** Attribute value or ''. Never throws on text/comment nodes. */
export function attr(node: AnyNode, name: string): string {
  if (node.type !== 'tag') return ''
  return getAttributeValue(node as Element, name) ?? ''
}

export function text(node: AnyNode | null): string {
  if (!node) return ''
  return getText(node).trim()
}

export function absoluteUrl(href: string | null | undefined, baseUrl: string): string {
  if (!href) return ''
  try {
    return new URL(href, baseUrl).toString()
  } catch {
    return ''
  }
}
