import type { SourceId } from './types'

/** Opaque, stateless continuation for the next page of every participating Source. */
export interface WorkFeedCursor {
  offset: number
  pages: Partial<Record<SourceId, string>>
}

export function encodeWorkFeedCursor(cursor: WorkFeedCursor): string {
  const normalized: WorkFeedCursor = {
    offset: Math.max(0, Math.floor(cursor.offset)),
    pages: Object.fromEntries(
      Object.entries(cursor.pages)
        .filter(([, url]) => typeof url === 'string' && url.length > 0)
        .sort(([left], [right]) => left.localeCompare(right)),
    ) as Partial<Record<SourceId, string>>,
  }
  return btoa(JSON.stringify(normalized)).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '')
}

export function decodeWorkFeedCursor(raw: string | null): WorkFeedCursor | null {
  if (!raw || raw.length > 16_384) return null
  try {
    const padded = raw.replaceAll('-', '+').replaceAll('_', '/') + '='.repeat((4 - raw.length % 4) % 4)
    const candidate = JSON.parse(atob(padded)) as Partial<WorkFeedCursor>
    if (!Number.isFinite(candidate.offset) || candidate.offset! < 0 || !candidate.pages || typeof candidate.pages !== 'object') {
      return null
    }
    const pages = Object.fromEntries(
      Object.entries(candidate.pages).filter(([, url]) => typeof url === 'string' && url.length > 0),
    ) as Partial<Record<SourceId, string>>
    return { offset: Math.floor(candidate.offset!), pages }
  } catch {
    return null
  }
}
