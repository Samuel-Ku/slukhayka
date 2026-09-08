/**
 * W3.1 (#557) — the web port of Android's `FeedSnapshotPolicy` (spec #462 /
 * #467, ADR-0019): the pure feed-snapshot freshness decision — how long one
 * persisted feed snapshot may answer the Огляд feed WITHOUT a network call.
 *
 * Pure function (the StreamHealPolicy convention): [needsNetwork] decides
 * from the feed key, the snapshot's fetched-at stamp, the current clock and
 * the explicit-refresh flag — nothing here touches IndexedDB, HTTP or the
 * system clock, so the test suite pins the decision with a fake clock.
 *
 * TTLs: новинки / new arrivals — 6 hours; catalog feeds — 24 hours. A
 * snapshot is stale at the EXACT expiry boundary: `now - fetchedAt < ttl` —
 * equal means stale (the same honest convention as the Edition Availability
 * Assertion).
 */

/** «Новинки» / new-arrivals feeds: six hours fresh. */
export const NEW_ARRIVALS_TTL_MS = 6 * 60 * 60 * 1000

/** Catalog feeds (catalogue enumeration, 4read homepage sections): 24 hours. */
export const CATALOG_TTL_MS = 24 * 60 * 60 * 1000

/** Feed key of the per-source new-arrivals feed («Новинки»). */
export const FEED_NEW_ARRIVALS = 'new-arrivals'

/** Feed key of a source's catalogue enumeration (the union input). */
export const FEED_CATALOG = 'catalog'

/** Feed key of the 4read homepage sections («Новинки»/«Цикли»/«Популярне»). */
export const FEED_HOMEPAGE_SECTIONS = 'homepage-sections'

/** The TTL of one feed kind. Unknown feed keys are catalog-grade (24 h). */
export function ttlMillisFor(feedKey: string): number {
  return feedKey === FEED_NEW_ARRIVALS ? NEW_ARRIVALS_TTL_MS : CATALOG_TTL_MS
}

/** Fresh strictly INSIDE the TTL — stale at the exact expiry boundary. */
export function isFresh(fetchedAt: number, nowMillis: number, ttlMillis: number): boolean {
  return nowMillis - fetchedAt < ttlMillis
}

/**
 * Whether the network must be hit: an explicit user refresh always fetches;
 * otherwise only a missing or stale snapshot does. A fresh snapshot answers
 * the feed entirely from the local snapshot.
 */
export function needsNetwork(
  feedKey: string,
  fetchedAt: number | null,
  nowMillis: number,
  forceRefresh = false,
): boolean {
  if (forceRefresh) return true
  if (fetchedAt === null) return true
  return !isFresh(fetchedAt, nowMillis, ttlMillisFor(feedKey))
}