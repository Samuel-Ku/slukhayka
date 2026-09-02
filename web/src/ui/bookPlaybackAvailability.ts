import type { SourceId } from '../worker/types'
import { SOURCE_METADATA } from '../worker/sourceMetadata'

/**
 * A cached page only proves that its metadata was once visible. It does not
 * carry a browser session, so a session-gated Source must be verified again
 * before its audio controls are exposed.
 */
export function canPlayBookFromDisplayedDetail(source: SourceId, showingCachedBook: boolean): boolean {
  return !showingCachedBook || !sourceNeedsBrowserSession(source)
}

export function sourceNeedsBrowserSession(source: SourceId): boolean {
  return SOURCE_METADATA[source].browserSessionRequired
}
