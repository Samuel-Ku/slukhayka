/**
 * spec-43/T3 — the Web Client's door into the Web Transport. Every call
 * degrades honestly: a failure or `{ok:false}` comes back null and the UI
 * shows its empty/error state, never fabricated data.
 */
import type { BookDetail, ParsedCatalog } from '../worker/types'

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

export interface Api {
  catalog(source: 'fourread', url?: string): Promise<ParsedCatalog | null>
  book(source: 'fourread', url: string): Promise<BookDetail | null>
}

export const api: Api = {
  catalog: (source, url) =>
    call<ParsedCatalog>(`/api/catalog?source=${source}${url ? `&url=${encodeURIComponent(url)}` : ''}`),
  book: (source, url) => call<BookDetail>(`/api/book?source=${source}&url=${encodeURIComponent(url)}`),
}
