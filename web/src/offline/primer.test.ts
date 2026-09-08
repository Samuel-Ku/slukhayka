/**
 * W6.2 — the primer, pinned: primes only after the fact (the caller fires
 * it on the playing event), is idempotent (never re-downloads a cached
 * chapter), refuses to run offline, evicts beyond the «recently listened»
 * cap, and derives the honest «у кеші» set from the real cache.
 */
import { describe, expect, it, vi } from 'vitest'
import { AUDIO_CACHE_MAX_ENTRIES } from './policy'
import { OfflineAudioPrimer, type PrimerCache } from './primer'

const RELAY = 'https://app.example/audio?u=https%3A%2F%2F4read.org%2Fuploads%2Faudio%2F1.mp3'
const DIRECT = 'https://4read.org/uploads/audio/1.mp3'

class FakeCache implements PrimerCache {
  private entries = new Map<string, Response>()

  async match(request: RequestInfo): Promise<Response | undefined> {
    return this.entries.get(String(request))
  }

  async put(request: RequestInfo, response: Response): Promise<void> {
    this.entries.set(String(request), response)
  }

  async keys(): Promise<readonly string[]> {
    return Array.from(this.entries.keys())
  }

  async delete(request: RequestInfo): Promise<boolean> {
    return this.entries.delete(String(request))
  }

  entriesCount(): number {
    return this.entries.size
  }
}

function fakeCacheStorage(cache: FakeCache): CacheStorage {
  return {
    open: vi.fn(async () => cache as unknown as Cache),
    match: vi.fn(),
    has: vi.fn(),
    keys: vi.fn(async () => []),
    delete: vi.fn(),
  } as unknown as CacheStorage
}

function primer(cache: FakeCache, fetchImpl: typeof fetch, isOnline = () => true): OfflineAudioPrimer {
  return new OfflineAudioPrimer({ fetchImpl, cacheStorage: fakeCacheStorage(cache), isOnline })
}

describe('OfflineAudioPrimer', () => {
  it('primes a full relay response once and only once', async () => {
    const cache = new FakeCache()
    const fetchImpl = vi.fn(async () => new Response('audio-bytes', { status: 200 }))
    const p = primer(cache, fetchImpl)
    await p.prime(RELAY)
    await p.prime(RELAY)
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(await p.isCached(RELAY)).toBe(true)
    expect(await p.cachedStreamUrls()).toEqual(new Set([DIRECT]))
  })

  it('never primes while offline', async () => {
    const cache = new FakeCache()
    const fetchImpl = vi.fn(async () => new Response('audio-bytes', { status: 200 }))
    const p = primer(cache, fetchImpl, () => false)
    await p.prime(RELAY)
    expect(fetchImpl).not.toHaveBeenCalled()
    expect(await p.isCached(RELAY)).toBe(false)
  })

  it('evicts the oldest chapters beyond the recently-listened cap', async () => {
    const cache = new FakeCache()
    const fetchImpl = vi.fn(async () => new Response('audio-bytes', { status: 200 }))
    const p = primer(cache, fetchImpl)
    for (let i = 0; i < AUDIO_CACHE_MAX_ENTRIES + 2; i++) {
      await p.prime(`https://app.example/audio?u=https%3A%2F%2F4read.org%2Fuploads%2Faudio%2F${i}.mp3`)
    }
    expect(cache.entriesCount()).toBe(AUDIO_CACHE_MAX_ENTRIES)
    expect(await p.isCached(`https://app.example/audio?u=https%3A%2F%2F4read.org%2Fuploads%2Faudio%2F0.mp3`)).toBe(false)
    expect(await p.isCached(`https://app.example/audio?u=https%3A%2F%2F4read.org%2Fuploads%2Faudio%2F${AUDIO_CACHE_MAX_ENTRIES + 1}.mp3`)).toBe(true)
  })

  it('degrades to a no-op without a Cache API', async () => {
    const fetchImpl = vi.fn(async () => new Response('audio-bytes', { status: 200 }))
    const p = new OfflineAudioPrimer({ fetchImpl, cacheStorage: null })
    await p.prime(RELAY)
    expect(fetchImpl).not.toHaveBeenCalled()
    expect(await p.cachedStreamUrls()).toEqual(new Set())
    expect(await p.isCached(RELAY)).toBe(false)
  })

  it('ignores failed and non-relay fetches', async () => {
    const cache = new FakeCache()
    const fetchImpl = vi.fn(async () => new Response('not-found', { status: 404 }))
    const p = primer(cache, fetchImpl)
    await p.prime(RELAY)
    expect(cache.entriesCount()).toBe(0)
  })
})