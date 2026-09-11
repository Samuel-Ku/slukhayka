import type { SourceId } from '../worker/types'
import { isScamSourceKey, SOURCE_METADATA } from '../worker/sourceMetadata'

/**
 * A cached page only proves that its metadata was once visible. It does not
 * carry a browser session, so a session-gated Source must be verified again
 * before its audio controls are exposed.
 */
export function canPlayBookFromDisplayedDetail(source: SourceId, showingCachedBook: boolean): boolean {
  // #741: a scam source (4read) is never playable — neither from a cached
  // page nor from a fresh one.
  if (isScamSourceKey(source)) return false
  return !showingCachedBook || !sourceNeedsBrowserSession(source)
}

export function sourceNeedsBrowserSession(source: SourceId): boolean {
  // A scam source (4read) is never a session door: its audio is not the book.
  if (isScamSourceKey(source)) return false
  return SOURCE_METADATA[source]?.browserSessionRequired ?? false
}
