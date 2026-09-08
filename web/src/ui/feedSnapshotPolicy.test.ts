/**
 * W3.1 — the FeedSnapshotPolicy port pinned to Android's decision table
 * (spec #462/#467): TTL constants, the exact-expiry boundary and the
 * needsNetwork matrix. Pure, fake clock — no IndexedDB, no HTTP.
 */
import { describe, expect, it } from 'vitest'
import {
  CATALOG_TTL_MS,
  FEED_CATALOG,
  FEED_NEW_ARRIVALS,
  isFresh,
  needsNetwork,
  NEW_ARRIVALS_TTL_MS,
  ttlMillisFor,
} from './feedSnapshotPolicy'

describe('FeedSnapshotPolicy TTLs', () => {
  it('pins the Android constants', () => {
    expect(NEW_ARRIVALS_TTL_MS).toBe(6 * 60 * 60 * 1000)
    expect(CATALOG_TTL_MS).toBe(24 * 60 * 60 * 1000)
  })

  it('new arrivals are six-hour grade, everything else catalog grade', () => {
    expect(ttlMillisFor(FEED_NEW_ARRIVALS)).toBe(NEW_ARRIVALS_TTL_MS)
    expect(ttlMillisFor(FEED_CATALOG)).toBe(CATALOG_TTL_MS)
    expect(ttlMillisFor('homepage-sections')).toBe(CATALOG_TTL_MS)
    expect(ttlMillisFor('unknown-feed')).toBe(CATALOG_TTL_MS)
  })
})

describe('FeedSnapshotPolicy isFresh', () => {
  it('is fresh strictly inside the TTL', () => {
    expect(isFresh(0, NEW_ARRIVALS_TTL_MS - 1, NEW_ARRIVALS_TTL_MS)).toBe(true)
  })

  it('is stale at the exact expiry boundary', () => {
    expect(isFresh(0, NEW_ARRIVALS_TTL_MS, NEW_ARRIVALS_TTL_MS)).toBe(false)
    expect(isFresh(0, CATALOG_TTL_MS, CATALOG_TTL_MS)).toBe(false)
  })

  it('is stale past the boundary', () => {
    expect(isFresh(0, NEW_ARRIVALS_TTL_MS + 1, NEW_ARRIVALS_TTL_MS)).toBe(false)
  })
})

describe('FeedSnapshotPolicy needsNetwork', () => {
  const NOW = 1_000_000_000_000

  it('a missing snapshot always needs the network', () => {
    expect(needsNetwork(FEED_NEW_ARRIVALS, null, NOW)).toBe(true)
    expect(needsNetwork(FEED_CATALOG, null, NOW)).toBe(true)
  })

  it('a fresh snapshot answers without the network', () => {
    expect(needsNetwork(FEED_NEW_ARRIVALS, NOW - 1, NOW)).toBe(false)
    expect(needsNetwork(FEED_CATALOG, NOW - 1, NOW)).toBe(false)
  })

  it('a snapshot exactly at the boundary needs the network', () => {
    expect(needsNetwork(FEED_NEW_ARRIVALS, NOW - NEW_ARRIVALS_TTL_MS, NOW)).toBe(true)
    expect(needsNetwork(FEED_CATALOG, NOW - CATALOG_TTL_MS, NOW)).toBe(true)
  })

  it('a stale snapshot needs the network', () => {
    expect(needsNetwork(FEED_NEW_ARRIVALS, NOW - NEW_ARRIVALS_TTL_MS - 1, NOW)).toBe(true)
  })

  it('an explicit refresh always fetches, even a fresh snapshot', () => {
    expect(needsNetwork(FEED_NEW_ARRIVALS, NOW - 1, NOW, true)).toBe(true)
    expect(needsNetwork(FEED_CATALOG, null, NOW, true)).toBe(true)
  })
})