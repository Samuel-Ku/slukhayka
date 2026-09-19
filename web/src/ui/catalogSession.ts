/**
 * #621 — ONE Source Catalog session for the initial feed AND the search.
 *
 * The session owns the selected Source, the normalized query, the intent
 * GENERATION, the feed Works + cursor, the search Works and the cache
 * provenance. Both the initial feed load and the search go through this one
 * interface; the Catalog component only renders its state.
 *
 * The contract, in one place:
 * - Every change of Source, normalized query, explicit refresh or dispose
 *   creates a NEW intent (generation). A repeat A → B → A is a new one.
 * - The generation is re-checked after EVERY await. Abort only saves work;
 *   it is never the proof of correctness.
 * - A late success, failure or finalization of a superseded intent changes
 *   neither the state nor the warm cache. The cache write is re-checked right
 *   before its readwrite transaction starts (`writeWarm`'s `isCurrent`).
 * - Feed and search carry the same 15 s budget; the timeout degrades to the
 *   ordinary failure / offline-cache path, never to an endless loading.
 * - A successful empty result, a failure and cached data are different
 *   states. Cache stays scoped to its own Source.
 * - Content Language Preference is a LOCAL projection: it never creates a
 *   generation, never starts a fetch, and an Edition with unknown Language
 *   stays visible.
 */

import { api } from '../api/client'
import { readWarmEntry, warmKey, writeWarm, type WarmEntry } from '../api/warmCache'
import { filterWorksByLanguage } from './contentLanguagePrefs'
import { FEED_CATALOG, FEED_NEW_ARRIVALS, needsNetwork } from './feedSnapshotPolicy'
import type { SourceId, UnifiedWork, UnifiedWorkPage } from '../worker/types'

/** «all» or one named Source — the feed scope the session owns. */
export type CatalogSource = 'all' | SourceId

/** The ticket keeps the search threshold at two characters. */
export const CATALOG_SEARCH_MIN_CHARS = 2
/** …and its debounce at 400 ms. */
export const CATALOG_SEARCH_DEBOUNCE_MS = 400
/** #621 — one honest budget for an initial feed page and for a search. */
export const CATALOG_REQUEST_BUDGET_MS = 15_000

export type CatalogFeedPhase = 'loading' | 'ready' | 'cached' | 'failed'
export type CatalogSearchPhase = 'idle' | 'pending' | 'ready' | 'failed'

export interface CatalogFeedState {
  /** loading | ready (possibly empty) | cached (offline provenance) | failed. */
  phase: CatalogFeedPhase
  works: UnifiedWork[]
  nextCursor: string | null
  /** The snapshot's savedAt when `phase === 'cached'`; null otherwise. */
  cachedAt: number | null
  loadingMore: boolean
  /**
   * A failed OR no-progress append. The cursor is kept, so the listener can
   * retry the same page explicitly; it also means auto-loading must not fire.
   */
  appendError: boolean
}

export interface CatalogSearchState {
  /** idle (below the threshold) | pending (debounce + request) | ready | failed. */
  phase: CatalogSearchPhase
  works: UnifiedWork[]
}

export interface CatalogSessionState {
  source: CatalogSource
  /** The query exactly as it was handed in (the field is the UI's business). */
  query: string
  /** The query the session works with: trimmed. Equal values never restart. */
  normalizedQuery: string
  feed: CatalogFeedState
  search: CatalogSearchState
  contentLanguages: readonly string[]
  /** The feed Works with the local Content Language Preference applied. */
  visibleFeed: UnifiedWork[]
  /** The search Works with the local Content Language Preference applied. */
  visibleSearch: UnifiedWork[]
}

/** The seams a test drives; every one of them has a real default. */
export interface CatalogSessionDeps {
  feed(cursor: string | undefined, source: SourceId | undefined, signal: AbortSignal): Promise<UnifiedWorkPage | null>
  search(query: string, source: SourceId | undefined, signal: AbortSignal): Promise<UnifiedWorkPage | null>
  readEntry<T>(key: string): Promise<WarmEntry<T> | null>
  writeEntry<T>(key: string, value: T, isCurrent: () => boolean): Promise<void>
  now(): number
}

export interface CatalogSessionOptions {
  deps?: Partial<CatalogSessionDeps>
  /** The persisted Content Language Preference the first render must honor. */
  contentLanguages?: readonly string[]
}

export interface CatalogSession {
  getState(): CatalogSessionState
  subscribe(listener: () => void): () => void
  /**
   * A new intent: the selected Source, the typed query and (once, from the
   * «Оновити» action) the explicit-refresh flag. An unchanged Source ×
   * normalized query without the flag is a no-op — an equal trimmed query
   * never restarts the work.
   */
  setIntent(intent: { source: CatalogSource; query: string; forceRefresh?: boolean }): void
  /**
   * One more page for the CURRENT generation and cursor. Observer and manual
   * action share this one attempt, so two calls for the same cursor during an
   * in-flight request coalesce into a single request and a single append.
   * After a failure — or after the source returns the same cursor, i.e. makes
   * no progress — the cursor stays put and `appendError` is set: the listener
   * retries the same page explicitly instead of auto-loading in a loop.
   */
  loadMore(): void
  /**
   * The listener's EXPLICIT retry of the page `loadMore` could not advance:
   * the same cursor is requested again. The automatic path refuses while
   * `appendError` is set, so only this call breaks the stall.
   */
  retryAppend(): void
  /** Local only: re-derives the visible Works, never starts a request. */
  setContentLanguages(selection: readonly string[]): void
  /** Aborts the request and the debounce timer; no late result publishes. */
  dispose(): void
}

const DEFAULT_DEPS: CatalogSessionDeps = {
  feed: (cursor, source, signal) => api.workFeed(cursor, source, signal),
  search: (query, source, signal) => api.workSearch(query, source, signal),
  readEntry: (key) => readWarmEntry(key),
  writeEntry: (key, value, isCurrent) => writeWarm(key, value, isCurrent),
  now: () => Date.now(),
}

/** The query the session works with: trimmed, exactly like the search today. */
export function normalizeCatalogQuery(query: string): string {
  return query.trim()
}

/** A legacy snapshot may be a bare Work array; both shapes read the same. */
function asPage(value: UnifiedWorkPage | UnifiedWork[]): UnifiedWorkPage {
  return Array.isArray(value) ? { works: value } : value
}

/** Appends a source cursor page without moving cards the listener already saw. */
export function appendWorks(current: UnifiedWork[], incoming: UnifiedWork[]): UnifiedWork[] {
  const known = new Set(current.map((work) => work.id))
  return [...current, ...incoming.filter((work) => !known.has(work.id))]
}

export function createCatalogSession(options: CatalogSessionOptions = {}): CatalogSession {
  const deps: CatalogSessionDeps = { ...DEFAULT_DEPS, ...options.deps }

  let generation = 0
  /** Whether the first intent has been armed (the initial state is loading). */
  let started = false
  let source: CatalogSource = 'all'
  let query = ''
  let contentLanguages: readonly string[] = [...(options.contentLanguages ?? [])]
  let feed: CatalogFeedState = { phase: 'loading', works: [], nextCursor: null, cachedAt: null, loadingMore: false, appendError: false }
  let search: CatalogSearchState = { phase: 'idle', works: [] }
  let controller: AbortController | null = null
  let debounce: ReturnType<typeof setTimeout> | null = null
  let listeners = new Set<() => void>()

  const build = (): CatalogSessionState => ({
    source,
    query,
    normalizedQuery: normalizeCatalogQuery(query),
    feed,
    search,
    contentLanguages,
    visibleFeed: filterWorksByLanguage(feed.works, contentLanguages),
    visibleSearch: filterWorksByLanguage(search.works, contentLanguages),
  })

  let snapshot = build()

  const emit = (): void => {
    snapshot = build()
    for (const listener of [...listeners]) listener()
  }

  const clearDebounce = (): void => {
    if (debounce !== null) clearTimeout(debounce)
    debounce = null
  }

  /**
   * Runs one attempt under the 15 s budget. The budget RACES the attempt and
   * aborts its controller: a transport that ignores the abort can never keep
   * the screen loading forever. A request aborted by a newer intent reports
   * the same honest failure; the generation check at the call site then
   * discards it.
   */
  const withBudget = async <T>(runController: AbortController, run: () => Promise<T | null>): Promise<T | null> => {
    let timer: ReturnType<typeof setTimeout> | null = null
    const budget = new Promise<null>((resolve) => {
      timer = setTimeout(() => {
        runController.abort()
        resolve(null)
      }, CATALOG_REQUEST_BUDGET_MS)
    })
    try {
      return await Promise.race([run(), budget])
    } catch {
      return null
    } finally {
      if (timer !== null) clearTimeout(timer)
      if (controller === runController) controller = null
    }
  }

  const stillCurrent = (current: number): boolean => current === generation

  const runFeed = (current: number, forceRefresh: boolean): void => {
    const intentSource = source
    const feedKey = intentSource === 'all' ? FEED_NEW_ARRIVALS : FEED_CATALOG
    const key = warmKey('catalog', intentSource)
    const runController = new AbortController()
    controller = runController
    // The snapshot read is part of the same attempt: a hung read is bounded by
    // the same budget, and it also has to be generation-checked.
    let cached: WarmEntry<UnifiedWorkPage | UnifiedWork[]> | null = null
    void withBudget<{ kind: 'snapshot' | 'live'; page: UnifiedWorkPage }>(runController, async () => {
      cached = await deps.readEntry<UnifiedWorkPage | UnifiedWork[]>(key)
      if (!stillCurrent(current)) return null
      if (cached !== null && !needsNetwork(feedKey, cached.savedAt, deps.now(), forceRefresh)) {
        return { kind: 'snapshot', page: asPage(cached.value) }
      }
      const page = await deps.feed(undefined, intentSource === 'all' ? undefined : intentSource, runController.signal)
      return page === null ? null : { kind: 'live', page }
    }).then((result) => {
      if (!stillCurrent(current)) return
      if (result?.kind === 'live') {
        feed = { phase: 'ready', works: result.page.works, nextCursor: result.page.nextCursor ?? null, cachedAt: null, loadingMore: false, appendError: false }
        emit()
        // The write is queued while THIS intent is still current, and
        // `writeWarm` re-checks it immediately before the transaction starts.
        void deps.writeEntry(key, result.page, () => stillCurrent(current))
        return
      }
      if (result?.kind === 'snapshot') {
        feed = { phase: 'ready', works: result.page.works, nextCursor: result.page.nextCursor ?? null, cachedAt: null, loadingMore: false, appendError: false }
        emit()
        return
      }
      if (cached !== null) {
        // Offline fallback (a failure OR the budget): the last snapshot of
        // THIS Source, labelled honestly.
        const cachedPage = asPage(cached.value)
        feed = { phase: 'cached', works: cachedPage.works, nextCursor: cachedPage.nextCursor ?? null, cachedAt: cached.savedAt, loadingMore: false, appendError: false }
        emit()
        return
      }
      feed = { phase: 'failed', works: [], nextCursor: null, cachedAt: null, loadingMore: false, appendError: false }
      emit()
    })
  }

  const runSearch = async (current: number): Promise<void> => {
    const normalized = normalizeCatalogQuery(query)
    if (normalized.length < CATALOG_SEARCH_MIN_CHARS) return
    const intentSource = source
    const runController = new AbortController()
    controller = runController

    const page = await withBudget(runController, () =>
      deps.search(normalized, intentSource === 'all' ? undefined : intentSource, runController.signal),
    )
    if (!stillCurrent(current)) return
    if (page === null) {
      search = { phase: 'failed', works: [] }
      emit()
      return
    }
    search = { phase: 'ready', works: page.works }
    emit()
  }

  const startGeneration = (forceRefresh: boolean): void => {
    generation += 1
    const current = generation
    clearDebounce()
    controller?.abort()
    controller = null
    const normalized = normalizeCatalogQuery(query)
    if (normalized.length < CATALOG_SEARCH_MIN_CHARS) {
      // A new Source must show its loading state at once, with no cards of
      // the previous one. Entering search mode leaves the feed untouched.
      search = { phase: 'idle', works: [] }
      feed = { phase: 'loading', works: [], nextCursor: null, cachedAt: null, loadingMore: false, appendError: false }
      emit()
      void runFeed(current, forceRefresh)
      return
    }
    search = { phase: 'pending', works: [] }
    emit()
    debounce = setTimeout(() => {
      debounce = null
      void runSearch(current)
    }, CATALOG_SEARCH_DEBOUNCE_MS)
  }

  /**
   * #624 — ONE append attempt for the current cursor, shared by the observer
   * and the manual action. `loadMore` is the automatic path; `retryAppend` is
   * the listener's explicit retry of the SAME page after a failure or after a
   * cursor that did not move. Both go through this function, so the in-flight
   * guard coalesces them into one request and one append.
   */
  const startAppend = (explicit: boolean): void => {
    if (normalizeCatalogQuery(query).length >= CATALOG_SEARCH_MIN_CHARS) return
    if (feed.nextCursor === null || feed.loadingMore || feed.phase === 'loading') return
    // Auto-loading never retries what just failed or stalled; only the
    // listener's explicit retry does.
    if (feed.appendError && !explicit) return
    const current = generation
    const cursor = feed.nextCursor
    const intentSource = source
    const runController = new AbortController()
    controller = runController
    feed = { ...feed, loadingMore: true, appendError: false }
    emit()
    void (async () => {
      const page = await withBudget(runController, () =>
        deps.feed(cursor, intentSource === 'all' ? undefined : intentSource, runController.signal),
      )
      // A page of a superseded intent (another Source, another query, a
      // refresh or dispose) never reaches the Works, the cursor or the cache.
      if (!stillCurrent(current)) return
      if (page === null) {
        feed = { ...feed, loadingMore: false, appendError: true }
        emit()
        return
      }
      // #624 — the source answered with the very cursor it was asked for: it
      // made no forward progress, so the «next» page is the page just read.
      // Stop instead of walking that cursor forever: keep the Works already on
      // screen, keep the cursor in place and surface the explicit retry.
      // (Same rule as the Android delta syncs: a page that does not move the
      // cursor carries nothing new.)
      if (page.nextCursor === cursor) {
        feed = { ...feed, loadingMore: false, appendError: true }
        emit()
        return
      }
      const combined = appendWorks(feed.works, page.works)
      const nextCursor = page.nextCursor ?? null
      feed = { ...feed, works: combined, nextCursor, loadingMore: false, appendError: false }
      emit()
      // Outside any state updater, and only for the Source it was asked for.
      const snapshotPage: UnifiedWorkPage = page.nextCursor === undefined
        ? { works: combined }
        : { works: combined, nextCursor: page.nextCursor }
      void deps.writeEntry(warmKey('catalog', intentSource), snapshotPage, () => stillCurrent(current))
    })()
  }

  return {
    getState: () => snapshot,
    subscribe: (listener) => {
      listeners.add(listener)
      return () => { listeners.delete(listener) }
    },
    setIntent: (intent) => {
      const normalized = normalizeCatalogQuery(intent.query)
      const sameIntent =
        started &&
        intent.source === source &&
        normalized === normalizeCatalogQuery(query) &&
        intent.forceRefresh !== true
      if (sameIntent) {
        // An equal trimmed query never restarts the work; only the raw text
        // of the field is remembered.
        if (intent.query !== query) {
          query = intent.query
          emit()
        }
        return
      }
      started = true
      source = intent.source
      query = intent.query
      startGeneration(intent.forceRefresh === true)
    },
    loadMore: () => startAppend(false),
    retryAppend: () => startAppend(true),
    setContentLanguages: (selection) => {
      const next = [...selection]
      if (next.length === contentLanguages.length && next.every((code, index) => code === contentLanguages[index])) return
      contentLanguages = next
      emit()
    },
    dispose: () => {
      generation += 1
      clearDebounce()
      controller?.abort()
      controller = null
      listeners.clear()
      // React StrictMode cleans the effects up and mounts them again on the
      // SAME session: the next setIntent must re-arm the feed even though the
      // Source and the query did not change.
      started = false
    },
  }
}
