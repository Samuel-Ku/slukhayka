/**
 * W6.2 (#593) — the page side of the streaming cache: after a chapter
 * actually starts playing, the CURRENT chapter's relay URL is fetched in
 * full and stored in the bounded audio cache (AUDIO_CACHE_NAME). No
 * download manager, no prefetching of whole books — «нещодавно слухане»
 * only, one chapter at a time, idempotent (already cached → no refetch).
 *
 * The service worker serves this cache only when the network failed
 * (ADR-0024 untouched). Everything degrades to a no-op — a missing Cache
 * API, an offline browser, a refused fetch — never an exception.
 */
import {
  AUDIO_CACHE_MAX_ENTRIES,
  AUDIO_CACHE_NAME,
  evictToMax,
  relayTargetOf,
  shouldCacheResponse,
} from './policy'

export interface PrimerDeps {
  fetchImpl?: typeof fetch
  cacheStorage?: CacheStorage | null
  isOnline?: () => boolean
}

function defaultIsOnline(): boolean {
  return typeof navigator === 'undefined' || navigator.onLine !== false
}

/** The thin Cache API surface the primer uses (injectable in tests). */
export interface PrimerCache {
  match(request: RequestInfo): Promise<Response | undefined>
  put(request: RequestInfo, response: Response): Promise<void>
  /** The real Cache returns Request objects — keys() normalizes them. */
  keys(): Promise<readonly (Request | string)[]>
  delete(request: RequestInfo): Promise<boolean>
}

export class OfflineAudioPrimer {
  private readonly fetchImpl: typeof fetch
  private readonly cacheStorage: CacheStorage | null
  private readonly isOnline: () => boolean

  constructor(deps: PrimerDeps = {}) {
    this.fetchImpl = deps.fetchImpl ?? ((...args) => fetch(...args))
    this.cacheStorage = deps.cacheStorage === undefined ? (typeof caches !== 'undefined' ? caches : null) : deps.cacheStorage
    this.isOnline = deps.isOnline ?? defaultIsOnline
  }

  /**
   * Primes one chapter (its relay URL) if it isn't cached already. The
   * caller guarantees the chapter was actually listened to — this method
   * only refuses to re-download and refuses to run offline.
   */
  async prime(relayUrl: string): Promise<void> {
    if (!this.isOnline()) return
    const cache = await this.readyCache()
    if (cache === null) return
    try {
      const existing = await cache.match(relayUrl)
      if (existing !== undefined) return
      const response = await this.fetchImpl(relayUrl)
      if (!shouldCacheResponse(relayUrl, response.status, response.headers.get('Range'))) return
      await cache.put(relayUrl, response.clone())
      await this.evictIfOver(cache)
    } catch {
      // degrade-never: a refused prime is not a failure the player must know
    }
  }

  /** True only when the cache actually holds this relay URL. */
  async isCached(relayUrl: string): Promise<boolean> {
    const cache = await this.readyCache()
    if (cache === null) return false
    try {
      return (await cache.match(relayUrl)) !== undefined
    } catch {
      return false
    }
  }

  /** The direct stream URLs currently cached — the honest «у кеші» badges. */
  async cachedStreamUrls(): Promise<Set<string>> {
    const cache = await this.readyCache()
    if (cache === null) return new Set()
    try {
      const keys = await cache.keys()
      const urls = new Set<string>()
      for (const key of keys) {
        const target = relayTargetOf(String(key))
        if (target !== null) urls.add(target)
      }
      return urls
    } catch {
      return new Set()
    }
  }

  private async readyCache(): Promise<PrimerCache | null> {
    if (this.cacheStorage === null) return null
    try {
      return await this.cacheStorage.open(AUDIO_CACHE_NAME)
    } catch {
      return null
    }
  }

  private async evictIfOver(cache: PrimerCache): Promise<void> {
    try {
      const keys = (await cache.keys()).map(String)
      for (const stale of evictToMax(keys, AUDIO_CACHE_MAX_ENTRIES)) {
        await cache.delete(stale)
      }
    } catch {
      // degrade-never
    }
  }
}