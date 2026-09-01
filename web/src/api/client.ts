/**
 * spec-43/T3 — the Web Client's door into the Web Transport. Every call
 * degrades honestly: a failure or `{ok:false}` comes back null and the UI
 * shows its empty/error state, never fabricated data.
 */
import type { BookDetail, CatalogCard, ParsedCatalog, SourceId, UnifiedWorkPage } from '../worker/types'

interface Envelope<T> {
  ok: boolean
  data?: T
  reason?: string
}

async function call<T>(path: string): Promise<T | null> {
  try {
    const response = await fetch(path)
    if (!response.ok) return null
    const envelope = (await response.json()) as Envelope<T>
    return envelope.ok && envelope.data !== undefined ? envelope.data : null
  } catch {
    return null
  }
}

export type SearchGroup = { id: string; displayName: string; cards: CatalogCard[] }

/** Normalized key for cross-source Work deduplication. */
export function workKey(card: Pick<CatalogCard, 'title' | 'author'>): string {
  const norm = (s: string): string =>
    s
      .toLowerCase()
      .replace(/\s*-\s*аудіокниги українською\s*$/i, '')
      .replace(/\s+/g, ' ')
      .trim()
  return `${norm(card.title)}|${norm(card.author)}`
}

/**
 * Removes transport duplicates inside one Source only. Similar cards from
 * different Sources remain separate Editions: collapsing them to a "first"
 * card used to silently discard a narration and made its Play action
 * unreachable.
 */
export function dedupeWorks(groups: SearchGroup[]): SearchGroup[] {
  const seen = new Set<string>()
  return groups
    .map((group) => ({
      ...group,
      cards: group.cards.filter((card) => {
        const key = `${group.id}|${card.url}`
        if (seen.has(key)) return false
        seen.add(key)
        return true
      }),
    }))
    .filter((group) => group.cards.length > 0)
}

export interface Api {
  catalog(source: SourceId, url?: string): Promise<ParsedCatalog | null>
  book(source: SourceId, url: string): Promise<BookDetail | null>
  search(source: SourceId, query: string): Promise<CatalogCard[] | null>
  searchAll(query: string): Promise<SearchGroup[] | null>
  workFeed(cursor?: string, source?: SourceId): Promise<UnifiedWorkPage | null>
  workSearch(query: string, source?: SourceId): Promise<UnifiedWorkPage | null>
}

export const api: Api = {
  catalog: (source, url) =>
    call<ParsedCatalog>(`/api/catalog?source=${source}${url ? `&url=${encodeURIComponent(url)}` : ''}`),
  book: (source, url) => call<BookDetail>(`/api/book?source=${source}&url=${encodeURIComponent(url)}`),
  search: (source, query) =>
    call<CatalogCard[]>(`/api/search?source=${source}&q=${encodeURIComponent(query)}`),
  searchAll: (query) => call<SearchGroup[]>(`/api/search-all?q=${encodeURIComponent(query)}`),
  workFeed: (cursor, source) =>
    call<UnifiedWorkPage>(`/api/work-feed?${cursor ? `cursor=${encodeURIComponent(cursor)}&` : ''}${source ? `source=${source}` : ''}`),
  workSearch: (query, source) =>
    call<UnifiedWorkPage>(`/api/work-search?q=${encodeURIComponent(query)}${source ? `&source=${source}` : ''}`),
}
