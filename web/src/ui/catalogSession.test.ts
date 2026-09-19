/**
 * #621 — behavioural tests through the ONE Source Catalog session interface.
 *
 * The session is driven directly (controlled promises, an in-memory cache, an
 * injected clock), so every claim is about the interface: a superseded intent
 * never reaches the state or the warm cache, the initial feed and the search
 * share the same 15 s budget, the cache stays scoped to its Source, and the
 * Content Language Preference is reprojected locally without a fetch.
 */
import { describe, expect, it, vi } from 'vitest'
import { warmKey } from '../api/warmCache'
import { NEW_ARRIVALS_TTL_MS } from './feedSnapshotPolicy'
import {
  appendWorks,
  CATALOG_REQUEST_BUDGET_MS,
  CATALOG_SEARCH_DEBOUNCE_MS,
  createCatalogSession,
  type CatalogSession,
  type CatalogSessionDeps,
} from './catalogSession'
import type { SourceId, UnifiedWork, UnifiedWorkPage } from '../worker/types'

interface FeedCall {
  cursor: string | undefined
  source: SourceId | undefined
  signal: AbortSignal
}

interface SearchCall {
  query: string
  source: SourceId | undefined
  signal: AbortSignal
}

interface CacheWrite {
  key: string
  value: unknown
  isCurrent: () => boolean
}

interface Harness {
  session: CatalogSession
  feedCalls: FeedCall[]
  searchCalls: SearchCall[]
  writes: CacheWrite[]
  seed(key: string, value: UnifiedWorkPage, savedAt: number): void
  settleFeed(index: number, page: UnifiedWorkPage | null): void
  settleSearch(index: number, page: UnifiedWorkPage | null): void
  setNow(value: number): void
}

function work(id: string, language?: string): UnifiedWork {
  return {
    id,
    mergeKey: id,
    title: id,
    author: 'Автор',
    editions: [{ id: `${id}-e`, language, narrator: 'Читець', sources: [{ sourceId: 'sluhay', url: `https://sluhay.com/${id}` }] }],
  }
}

function makeHarness(contentLanguages: readonly string[] = []): Harness {
  const feedCalls: FeedCall[] = []
  const searchCalls: SearchCall[] = []
  const writes: CacheWrite[] = []
  const feedResolvers: Array<(page: UnifiedWorkPage | null) => void> = []
  const searchResolvers: Array<(page: UnifiedWorkPage | null) => void> = []
  const entries = new Map<string, { key: string; value: UnifiedWorkPage; savedAt: number }>()
  let now = 1_700_000_000_000

  const deps: Partial<CatalogSessionDeps> = {
    feed: (cursor, source, signal) => {
      feedCalls.push({ cursor, source, signal })
      return new Promise((resolve) => { feedResolvers.push(resolve) })
    },
    search: (query, source, signal) => {
      searchCalls.push({ query, source, signal })
      return new Promise((resolve) => { searchResolvers.push(resolve) })
    },
    readEntry: async <T>(key: string) => {
      const entry = entries.get(key)
      return entry === undefined ? null : (entry as unknown as T)
    },
    writeEntry: async <T>(key: string, value: T, isCurrent: () => boolean) => {
      writes.push({ key, value, isCurrent })
      if (isCurrent()) entries.set(key, { key, value: value as unknown as UnifiedWorkPage, savedAt: now })
    },
    now: () => now,
  }

  return {
    session: createCatalogSession({ deps, contentLanguages }),
    feedCalls,
    searchCalls,
    writes,
    seed: (key, value, savedAt) => { entries.set(key, { key, value, savedAt }) },
    settleFeed: (index, page) => { feedResolvers[index]?.(page) },
    settleSearch: (index, page) => { searchResolvers[index]?.(page) },
    setNow: (value) => { now = value },
  }
}

/** Lets the pending microtask chain (cache read, request start) settle. */
async function flush(): Promise<void> {
  for (let tick = 0; tick < 20; tick += 1) await Promise.resolve()
}

const ids = (works: UnifiedWork[]): string[] => works.map((item) => item.id)

describe('#621 catalog session — one interface for the feed and the search', () => {
  it('drives the initial feed and the search through the same session', async () => {
    const harness = makeHarness()
    vi.useFakeTimers()
    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    expect(harness.feedCalls).toHaveLength(1)
    expect(harness.feedCalls[0]?.cursor).toBeUndefined()
    expect(harness.feedCalls[0]?.source).toBeUndefined()

    harness.settleFeed(0, { works: [work('a')] })
    await flush()
    expect(ids(harness.session.getState().feed.works)).toEqual(['a'])

    harness.session.setIntent({ source: 'sluhay', query: 'море' })
    await vi.advanceTimersByTimeAsync(CATALOG_SEARCH_DEBOUNCE_MS)
    await flush()
    expect(harness.searchCalls).toHaveLength(1)
    expect(harness.searchCalls[0]?.query).toBe('море')
    expect(harness.searchCalls[0]?.source).toBe('sluhay')
    vi.useRealTimers()
  })

  it('A → B → A is a new intent: the older success and failure never publish', async () => {
    const harness = makeHarness()
    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    expect(harness.feedCalls).toHaveLength(1)

    // Both earlier requests are aborted while they are still in flight.
    harness.session.setIntent({ source: 'sluhay', query: '' })
    await flush()
    expect(harness.feedCalls).toHaveLength(2)
    expect(harness.feedCalls[0]?.signal.aborted).toBe(true)

    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    expect(harness.feedCalls).toHaveLength(3)
    expect(harness.feedCalls[1]?.signal.aborted).toBe(true)
    // The new Source shows its loading state at once, with no old cards.
    expect(harness.session.getState().feed.phase).toBe('loading')
    expect(harness.session.getState().feed.works).toEqual([])

    // Neither the first A's success nor B's late success reaches the screen.
    harness.settleFeed(0, { works: [work('a1')] })
    harness.settleFeed(1, { works: [work('b')] })
    await flush()
    expect(harness.session.getState().feed.phase).toBe('loading')
    expect(harness.session.getState().feed.works).toEqual([])

    harness.settleFeed(2, { works: [work('a2')] })
    await flush()
    expect(ids(harness.session.getState().feed.works)).toEqual(['a2'])
  })

  it('a late failure of a superseded intent does not replace the new state', async () => {
    const harness = makeHarness()
    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    harness.session.setIntent({ source: 'sluhay', query: '' })
    await flush()
    harness.settleFeed(1, { works: [work('b')] })
    await flush()
    expect(harness.session.getState().feed.phase).toBe('ready')

    harness.settleFeed(0, null)
    await flush()
    expect(harness.session.getState().feed.phase).toBe('ready')
    expect(ids(harness.session.getState().feed.works)).toEqual(['b'])
  })
})

describe('#621 catalog session — cache provenance and generation', () => {
  it('a superseded response never queues a cache write', async () => {
    const harness = makeHarness()
    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    harness.session.setIntent({ source: 'sluhay', query: '' })
    await flush()

    harness.settleFeed(0, { works: [work('a1')] })
    await flush()
    expect(harness.writes).toHaveLength(0)
  })

  it('the queued write is re-checked before the transaction starts', async () => {
    const harness = makeHarness()
    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    harness.settleFeed(0, { works: [work('a1')] })
    await flush()

    expect(harness.writes).toHaveLength(1)
    expect(harness.writes[0]?.key).toBe(warmKey('catalog', 'all'))
    expect(harness.writes[0]?.isCurrent()).toBe(true)
    // The intent changes while the write is still queued (openDb in flight).
    harness.session.setIntent({ source: 'sluhay', query: '' })
    expect(harness.writes[0]?.isCurrent()).toBe(false)
  })

  it('keeps the cache scoped to its own Source on an offline fallback', async () => {
    const harness = makeHarness()
    harness.seed(warmKey('catalog', 'all'), { works: [work('all-1')] }, 1000)

    harness.session.setIntent({ source: 'sluhay', query: '' })
    await flush()
    harness.settleFeed(0, null)
    await flush()
    // No snapshot for sluhay → honest failure, never «all»'s cards.
    expect(harness.session.getState().feed.phase).toBe('failed')
    expect(harness.session.getState().feed.works).toEqual([])

    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    harness.settleFeed(1, null)
    await flush()
    const cachedState = harness.session.getState().feed
    expect(cachedState.phase).toBe('cached')
    expect(ids(cachedState.works)).toEqual(['all-1'])
    expect(cachedState.cachedAt).toBe(1000)
  })

  it('distinguishes a successful empty feed from a failure', async () => {
    const empty = makeHarness()
    empty.session.setIntent({ source: 'all', query: '' })
    await flush()
    empty.settleFeed(0, { works: [] })
    await flush()
    expect(empty.session.getState().feed.phase).toBe('ready')
    expect(empty.session.getState().feed.works).toEqual([])

    const failed = makeHarness()
    failed.session.setIntent({ source: 'all', query: '' })
    await flush()
    failed.settleFeed(0, null)
    await flush()
    expect(failed.session.getState().feed.phase).toBe('failed')
  })

  it('a fresh snapshot answers without a network call', async () => {
    const harness = makeHarness()
    harness.seed(warmKey('catalog', 'all'), { works: [work('snapshot')] }, 1000)
    harness.setNow(1000 + NEW_ARRIVALS_TTL_MS - 1)

    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    expect(harness.feedCalls).toHaveLength(0)
    expect(ids(harness.session.getState().feed.works)).toEqual(['snapshot'])
  })

  it('an explicit refresh bypasses the fresh snapshot', async () => {
    const harness = makeHarness()
    harness.seed(warmKey('catalog', 'all'), { works: [work('snapshot')] }, 1000)
    harness.setNow(1000 + 1)

    harness.session.setIntent({ source: 'all', query: '', forceRefresh: true })
    await flush()
    expect(harness.feedCalls).toHaveLength(1)
  })
})

describe('#621 catalog session — search threshold and debounce', () => {
  it('keeps the two-character threshold and the 400 ms debounce', async () => {
    const harness = makeHarness()
    vi.useFakeTimers()
    harness.session.setIntent({ source: 'all', query: 'м' })
    await flush()
    expect(harness.searchCalls).toHaveLength(0)
    expect(harness.session.getState().search.phase).toBe('idle')

    harness.session.setIntent({ source: 'all', query: 'мо' })
    await flush()
    expect(harness.session.getState().search.phase).toBe('pending')
    await vi.advanceTimersByTimeAsync(CATALOG_SEARCH_DEBOUNCE_MS - 1)
    expect(harness.searchCalls).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(1)
    await flush()
    expect(harness.searchCalls).toHaveLength(1)
    vi.useRealTimers()
  })

  it('an equal trimmed query does not restart the work', async () => {
    const harness = makeHarness()
    vi.useFakeTimers()
    harness.session.setIntent({ source: 'all', query: 'море' })
    await vi.advanceTimersByTimeAsync(CATALOG_SEARCH_DEBOUNCE_MS)
    await flush()
    expect(harness.searchCalls).toHaveLength(1)
    harness.settleSearch(0, { works: [work('море')] })
    await flush()

    harness.session.setIntent({ source: 'all', query: 'море ' })
    await vi.advanceTimersByTimeAsync(CATALOG_SEARCH_DEBOUNCE_MS)
    await flush()
    expect(harness.searchCalls).toHaveLength(1)
    expect(ids(harness.session.getState().search.works)).toEqual(['море'])
    vi.useRealTimers()
  })

  it('a successful empty search and a failed search are different states', async () => {
    const empty = makeHarness()
    vi.useFakeTimers()
    empty.session.setIntent({ source: 'all', query: 'море' })
    await vi.advanceTimersByTimeAsync(CATALOG_SEARCH_DEBOUNCE_MS)
    await flush()
    empty.settleSearch(0, { works: [] })
    await flush()
    expect(empty.session.getState().search.phase).toBe('ready')
    expect(empty.session.getState().search.works).toEqual([])
    vi.useRealTimers()

    const failed = makeHarness()
    vi.useFakeTimers()
    failed.session.setIntent({ source: 'all', query: 'море' })
    await vi.advanceTimersByTimeAsync(CATALOG_SEARCH_DEBOUNCE_MS)
    await flush()
    failed.settleSearch(0, null)
    await flush()
    expect(failed.session.getState().search.phase).toBe('failed')
    vi.useRealTimers()
  })
})

describe('#621 catalog session — the 15 s budget', () => {
  it('a hung feed becomes the honest failure after 15 s', async () => {
    const harness = makeHarness()
    vi.useFakeTimers()
    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    expect(harness.session.getState().feed.phase).toBe('loading')

    await vi.advanceTimersByTimeAsync(CATALOG_REQUEST_BUDGET_MS)
    await flush()
    expect(harness.session.getState().feed.phase).toBe('failed')
    expect(harness.feedCalls[0]?.signal.aborted).toBe(true)
    vi.useRealTimers()
  })

  it('a hung search becomes the honest failure after 15 s', async () => {
    const harness = makeHarness()
    vi.useFakeTimers()
    harness.session.setIntent({ source: 'all', query: 'море' })
    await vi.advanceTimersByTimeAsync(CATALOG_SEARCH_DEBOUNCE_MS)
    await flush()
    expect(harness.session.getState().search.phase).toBe('pending')

    await vi.advanceTimersByTimeAsync(CATALOG_REQUEST_BUDGET_MS)
    await flush()
    expect(harness.session.getState().search.phase).toBe('failed')
    vi.useRealTimers()
  })

  it('a timeout with a snapshot degrades to the labelled cached state', async () => {
    const harness = makeHarness()
    harness.seed(warmKey('catalog', 'all'), { works: [work('old')] }, 5000)
    harness.setNow(5000 + NEW_ARRIVALS_TTL_MS + 1)
    vi.useFakeTimers()
    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    expect(harness.session.getState().feed.phase).toBe('loading')

    await vi.advanceTimersByTimeAsync(CATALOG_REQUEST_BUDGET_MS)
    await flush()
    const state = harness.session.getState().feed
    expect(state.phase).toBe('cached')
    expect(ids(state.works)).toEqual(['old'])
    expect(state.cachedAt).toBe(5000)
    vi.useRealTimers()
  })
})

describe('#621 catalog session — language reprojection and dispose', () => {
  it('reprojects the loaded Works locally, without another fetch', async () => {
    const harness = makeHarness()
    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    harness.settleFeed(0, { works: [work('uk', 'uk'), work('unknown'), work('en', 'en')] })
    await flush()
    expect(harness.feedCalls).toHaveLength(1)

    harness.session.setContentLanguages(['uk'])
    // An Edition whose Language is unknown stays visible under any selection.
    expect(ids(harness.session.getState().visibleFeed)).toEqual(['uk', 'unknown'])
    expect(harness.feedCalls).toHaveLength(1)

    harness.session.setContentLanguages([])
    expect(ids(harness.session.getState().visibleFeed)).toEqual(['uk', 'unknown', 'en'])
    expect(harness.feedCalls).toHaveLength(1)
  })

  it('dispose clears the debounce timer and publishes nothing late', async () => {
    const harness = makeHarness()
    const seen: string[] = []
    harness.session.subscribe(() => seen.push(harness.session.getState().search.phase))
    vi.useFakeTimers()
    harness.session.setIntent({ source: 'all', query: 'море' })
    await flush()
    harness.session.dispose()
    await vi.advanceTimersByTimeAsync(CATALOG_REQUEST_BUDGET_MS + CATALOG_SEARCH_DEBOUNCE_MS)
    await flush()
    expect(harness.searchCalls).toHaveLength(0)
    expect(seen).not.toContain('ready')
    vi.useRealTimers()
  })

  it('a request in flight at dispose aborts and publishes nothing', async () => {
    const harness = makeHarness()
    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    const before = harness.session.getState().feed
    harness.session.dispose()
    expect(harness.feedCalls[0]?.signal.aborted).toBe(true)

    harness.settleFeed(0, { works: [work('late')] })
    await flush()
    expect(harness.session.getState().feed).toBe(before)
  })

  it('a StrictMode remount re-arms the same session after dispose', async () => {
    const harness = makeHarness()
    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    expect(harness.feedCalls).toHaveLength(1)

    // StrictMode: cleanup, then the same effects run again.
    harness.session.dispose()
    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    expect(harness.feedCalls).toHaveLength(2)
    harness.settleFeed(1, { works: [work('a')] })
    await flush()
    expect(ids(harness.session.getState().feed.works)).toEqual(['a'])
  })

  it('a late page of a superseded intent never appends or writes', async () => {
    const harness = makeHarness()
    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    harness.settleFeed(0, { works: [work('all-1')], nextCursor: 'cursor-2' })
    await flush()
    harness.writes.length = 0

    harness.session.loadMore()
    await flush()
    expect(harness.feedCalls[1]?.cursor).toBe('cursor-2')

    harness.session.setIntent({ source: 'sluhay', query: '' })
    await flush()
    harness.settleFeed(1, { works: [work('all-2')] })
    await flush()

    expect(harness.session.getState().feed.works).toEqual([])
    expect(harness.session.getState().feed.nextCursor).toBeNull()
    expect(harness.writes).toHaveLength(0)
  })
})

describe('#624 catalog session — safe pagination', () => {
  it('the observer and the manual action share one request and one append per cursor', async () => {
    const harness = makeHarness()
    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    harness.settleFeed(0, { works: [work('a')], nextCursor: 'cursor-2' })
    await flush()

    // Both callers fire for cursor-2 before the request is in flight:
    // single-flight coalesces them.
    harness.session.loadMore()
    harness.session.loadMore()
    await flush()
    expect(harness.feedCalls).toHaveLength(2)
    expect(harness.feedCalls[1]?.cursor).toBe('cursor-2')

    harness.settleFeed(1, { works: [work('b')], nextCursor: 'cursor-3' })
    await flush()
    expect(ids(harness.session.getState().feed.works)).toEqual(['a', 'b'])
  })

  it('a repeated next cursor stops auto-loading and keeps the cursor for an explicit retry', async () => {
    const harness = makeHarness()
    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    harness.settleFeed(0, { works: [work('a')], nextCursor: 'cursor-2' })
    await flush()
    harness.writes.length = 0

    harness.session.loadMore()
    await flush()
    // The source answers with the cursor it was asked for: no forward progress.
    harness.settleFeed(1, { works: [work('b')], nextCursor: 'cursor-2' })
    await flush()

    const stalled = harness.session.getState().feed
    expect(stalled.appendError).toBe(true)
    expect(stalled.nextCursor).toBe('cursor-2')
    // The no-progress page carries nothing new (the cursor did not move): the
    // Works on screen stay as they were and the cache keeps its last prefix.
    expect(ids(stalled.works)).toEqual(['a'])
    expect(harness.writes).toHaveLength(0)

    // The automatic path never walks the same cursor again.
    harness.session.loadMore()
    await flush()
    expect(harness.feedCalls).toHaveLength(2)

    // The listener retries the same page explicitly, and progress resumes.
    harness.session.retryAppend()
    await flush()
    expect(harness.feedCalls).toHaveLength(3)
    expect(harness.feedCalls[2]?.cursor).toBe('cursor-2')
    harness.settleFeed(2, { works: [work('b')], nextCursor: 'cursor-3' })
    await flush()
    const recovered = harness.session.getState().feed
    expect(recovered.appendError).toBe(false)
    expect(recovered.nextCursor).toBe('cursor-3')
    expect(ids(recovered.works)).toEqual(['a', 'b'])
  })

  it('a failed append keeps the Works and the cursor and retries the same page explicitly', async () => {
    const harness = makeHarness()
    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    harness.settleFeed(0, { works: [work('a')], nextCursor: 'cursor-2' })
    await flush()

    harness.session.loadMore()
    await flush()
    harness.settleFeed(1, null)
    await flush()

    const failed = harness.session.getState().feed
    expect(failed.appendError).toBe(true)
    expect(ids(failed.works)).toEqual(['a'])
    expect(failed.nextCursor).toBe('cursor-2')

    // Nothing auto-retries the failed page…
    harness.session.loadMore()
    await flush()
    expect(harness.feedCalls).toHaveLength(2)

    // …the explicit retry does, and success without a next cursor ends the list.
    harness.session.retryAppend()
    await flush()
    expect(harness.feedCalls).toHaveLength(3)
    expect(harness.feedCalls[2]?.cursor).toBe('cursor-2')
    harness.settleFeed(2, { works: [work('b')] })
    await flush()
    const recovered = harness.session.getState().feed
    expect(recovered.appendError).toBe(false)
    expect(ids(recovered.works)).toEqual(['a', 'b'])
    expect(recovered.nextCursor).toBeNull()
  })

  it('an append on a cached prefix keeps the cached provenance until a live refresh', async () => {
    const harness = makeHarness()
    harness.seed(warmKey('catalog', 'all'), { works: [work('old')], nextCursor: 'cursor-2' }, 5000)
    harness.setNow(5000 + NEW_ARRIVALS_TTL_MS + 1)
    harness.session.setIntent({ source: 'all', query: '' })
    await flush()
    harness.settleFeed(0, null)
    await flush()
    expect(harness.session.getState().feed.phase).toBe('cached')

    harness.session.loadMore()
    await flush()
    harness.settleFeed(1, { works: [work('live')] })
    await flush()

    const state = harness.session.getState().feed
    // The live page was appended, but the initial list is still the snapshot.
    expect(ids(state.works)).toEqual(['old', 'live'])
    expect(state.phase).toBe('cached')
    expect(state.cachedAt).toBe(5000)
  })
})

describe('#621 appendWorks', () => {
  it('keeps the cards the listener already saw exactly once', () => {
    expect(appendWorks([work('a'), work('b')], [work('b'), work('c')]).map((item) => item.id)).toEqual(['a', 'b', 'c'])
  })
})
