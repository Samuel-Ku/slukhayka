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

export type SourceId = 'fourread' | 'sound-books' | 'audiobook-mp3' | 'lihtar' | 'sluhayua' | 'sluhay'

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
}

export interface UnifiedEdition {
  id: string
  narrator?: string
  durationSeconds?: number
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
  coverImageUrl?: string
  genres: string[]
  descriptionHtml?: string
  chapters: Chapter[]
  /** «Інші начитки»: other renditions of the same Work on this source. */
  otherNarrations: CatalogCard[]
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
