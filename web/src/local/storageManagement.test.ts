/**
 * #591 W5.2 — the storage direction's honest measurements and clears,
 * pinned: byte formatting, the origin estimate, the audio cache size from
 * the real cache, the whole-cache clear, and the snapshot count that never
 * under-reports.
 */
import { describe, expect, it, vi } from 'vitest'
import { AUDIO_CACHE_NAME } from '../offline/policy'
import {
  audioCacheSize,
  clearAudioCache,
  estimateOriginUsage,
  formatBytes,
  listeningSnapshotCount,
  type ManagementCache,
  type ManagementCacheStorage,
} from './storageManagement'

class FakeCache implements ManagementCache {
  private entries = new Map<string, Response>()

  async matchAll(): Promise<readonly Response[]> {
    return Array.from(this.entries.values())
  }

  async keys(): Promise<readonly (Request | string)[]> {
    return Array.from(this.entries.keys())
  }

  async delete(request: RequestInfo | string): Promise<boolean> {
    return this.entries.delete(String(request))
  }

  put(key: string, response: Response): void {
    this.entries.set(key, response)
  }
}

function fakeCacheStorage(cache: FakeCache): ManagementCacheStorage {
  return {
    open: vi.fn(async () => cache),
    delete: vi.fn(async (name: string) => {
      if (name !== AUDIO_CACHE_NAME) return false
      return true
    }),
  }
}

describe('formatBytes', () => {
  it('formats human byte sizes and degrades to zero', () => {
    expect(formatBytes(0)).toBe('0 Б')
    expect(formatBytes(512)).toBe('512 Б')
    expect(formatBytes(1536)).toBe('1.5 КБ')
    expect(formatBytes(1_048_576)).toBe('1 МБ')
    expect(formatBytes(Number.NaN)).toBe('0 Б')
  })
})

describe('estimateOriginUsage', () => {
  it('returns the browser estimate when available', async () => {
    const usage = await estimateOriginUsage(async () => ({ usage: 1_000, quota: 10_000 }))
    expect(usage).toEqual({ used: 1_000, quota: 10_000 })
  })

  it('degrades to null when the API is missing or fails', async () => {
    expect(await estimateOriginUsage(async () => ({}))).toBeNull()
    expect(await estimateOriginUsage(async () => { throw new Error('no') })).toBeNull()
  })
})

describe('audioCacheSize', () => {
  it('sums the real cached bodies (bounded cache, honest floor)', async () => {
    const cache = new FakeCache()
    cache.put('a', new Response(new Uint8Array([1, 2, 3]), { status: 200 }))
    cache.put('b', new Response(new Uint8Array(7), { status: 200, headers: { 'Content-Length': '7' } }))
    expect(await audioCacheSize(fakeCacheStorage(cache))).toBe(10)
  })

  it('falls back to Content-Length when a body is unreadable', async () => {
    const cache = new FakeCache()
    const unreadable = new Response(new Uint8Array(5), { status: 200, headers: { 'Content-Length': '9' } })
    vi.spyOn(unreadable, 'clone').mockImplementation(() => {
      throw new Error('stream locked')
    })
    cache.put('a', unreadable)
    expect(await audioCacheSize(fakeCacheStorage(cache))).toBe(9)
  })

  it('returns zero without a Cache API', async () => {
    expect(await audioCacheSize(null)).toBe(0)
  })
})

describe('clearAudioCache', () => {
  it('deletes the whole audio cache and reports honestly', async () => {
    const storage = fakeCacheStorage(new FakeCache())
    expect(await clearAudioCache(storage)).toBe(true)
    expect(storage.delete).toHaveBeenCalledWith(AUDIO_CACHE_NAME)
    expect(await clearAudioCache(null)).toBe(false)
  })
})

describe('listeningSnapshotCount', () => {
  it('counts IDB rows plus legacy localStorage rows', async () => {
    const storage = {
      getItem: (key: string) => (key.startsWith('slukhayka.listening.') ? '{}' : null),
      removeItem: vi.fn(),
      length: 3,
      key: (index: number) => ['other', 'slukhayka.listening.legacy-1', 'slukhayka.listening.legacy-2'][index] ?? null,
    }
    const count = await listeningSnapshotCount({ idbCount: async () => 2, storage })
    expect(count).toBe(4)
  })

  it('still counts what it can when one source fails', async () => {
    const storage = { getItem: () => null, removeItem: vi.fn(), length: 0, key: () => null }
    expect(await listeningSnapshotCount({ idbCount: async () => { throw new Error('db closed') }, storage })).toBe(0)
  })
})