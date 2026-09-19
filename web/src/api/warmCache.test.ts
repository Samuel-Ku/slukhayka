import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { readWarmEntry, WARM_CACHE_TTL_MS, warmKey, writeWarm } from './warmCache'
import {
  AVAILABILITY_POLICY,
  availabilitySortRank,
  readAvailabilityAssertion,
  writeAvailabilityAssertion,
} from '../ui/catalogAvailability'

afterEach(() => vi.restoreAllMocks())

/**
 * #621 — holds the FIRST `indexedDB.open` success handler until released, so
 * the first writer's readwrite transaction can be created AFTER a later
 * writer's: the exact scheduling an unsynchronised `writeWarm` cannot
 * survive. `ready()` tells the test the held request already has a result.
 */
function holdFirstOpen(): { ready: () => boolean; release: () => void } {
  const instance = globalThis.indexedDB as IDBFactory
  const realOpen = instance.open.bind(instance)
  let armed = true
  let served = false
  let pendingRelease: (() => void) | null = null
  instance.open = ((name: string, version?: number) => {
    const real = realOpen(name, version)
    if (!armed) return real
    armed = false
    real.addEventListener('success', () => { served = true })
    const proxy = {} as IDBOpenDBRequest
    Object.defineProperty(proxy, 'result', { get: () => real.result })
    Object.defineProperty(proxy, 'error', { get: () => real.error })
    Object.defineProperty(proxy, 'onupgradeneeded', { set: (handler) => { real.onupgradeneeded = handler } })
    Object.defineProperty(proxy, 'onsuccess', {
      set: (handler) => {
        if (handler) pendingRelease = () => (handler as (event: unknown) => void).call(proxy, { target: proxy })
      },
    })
    Object.defineProperty(proxy, 'onerror', { set: (handler) => { real.onerror = handler } })
    return proxy
  }) as typeof instance.open
  return {
    ready: () => served && pendingRelease !== null,
    release: () => pendingRelease?.(),
  }
}

async function waitUntil(condition: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 500 && !condition(); attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

describe('IndexedDB warm cache', () => {
  it('keeps the cursor page with source provenance and save timestamp', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(123_456)
    const page = { works: [{ id: 'work-a', mergeKey: 'a', title: 'Книга', author: 'Автор', editions: [] }], nextCursor: 'cursor-2' }
    const key = warmKey('catalog', 'all', 'test-page')

    await writeWarm(key, page)
    await expect(readWarmEntry<typeof page>(key)).resolves.toEqual({ key, value: page, savedAt: 123_456 })
  })

  it('does not offer a warm catalogue after its offline TTL expires', async () => {
    const key = warmKey('catalog', 'all', 'ttl-page')
    vi.spyOn(Date, 'now').mockReturnValue(10_000)
    await writeWarm(key, { works: [] })
    vi.spyOn(Date, 'now').mockReturnValue(10_000 + WARM_CACHE_TTL_MS - 1)
    await expect(readWarmEntry(key, WARM_CACHE_TTL_MS)).resolves.not.toBeNull()
    vi.spyOn(Date, 'now').mockReturnValue(10_000 + WARM_CACHE_TTL_MS)
    await expect(readWarmEntry(key, WARM_CACHE_TTL_MS)).resolves.toBeNull()
  })

  it('stores an expiring Source×Edition verdict without URL or private session data', async () => {
    const assertion = {
      editionId: 'edition-a',
      sourceId: 'sound-books' as const,
      verdict: 'playing' as const,
      observedAt: 10_000,
    }
    await writeAvailabilityAssertion(assertion)
    const stored = await readAvailabilityAssertion(assertion.editionId, assertion.sourceId)

    expect(stored).toEqual(assertion)
    expect(JSON.stringify(stored)).not.toMatch(/url|cookie|token|history|listener/i)
    expect(availabilitySortRank(stored, assertion.observedAt + AVAILABILITY_POLICY.positiveTtlMs - 1)).toBe(0)
    expect(availabilitySortRank(stored, assertion.observedAt + AVAILABILITY_POLICY.positiveTtlMs)).toBe(1)
  })

  // #621 — a stale page must never commit over a newer one of the same key.
  it('orders two writes of ONE key — the older never lands over the newer', async () => {
    const original = globalThis.indexedDB
    globalThis.indexedDB = new IDBFactory()
    const held = holdFirstOpen()
    try {
      const key = warmKey('catalog', 'all')
      const older = writeWarm(key, { works: [{ id: 'older' }] })
      const newer = writeWarm(key, { works: [{ id: 'newer' }] })

      // Give the newer writer every chance to commit first; an ordered writer
      // simply queues behind the older one and still ends up last.
      await waitUntil(() => held.ready())
      await new Promise((resolve) => setTimeout(resolve, 20))
      held.release()
      await older
      await newer

      const entry = await readWarmEntry<{ works: Array<{ id: string }> }>(key)
      expect(entry?.value.works[0]?.id).toBe('newer')
    } finally {
      globalThis.indexedDB = original
    }
  })

  it('drops a queued write whose intent stopped being current', async () => {
    const key = warmKey('catalog', 'all', 'stale-intent')
    await writeWarm(key, { works: [{ id: 'stale' }] }, () => false)
    await expect(readWarmEntry(key)).resolves.toBeNull()
  })
})
