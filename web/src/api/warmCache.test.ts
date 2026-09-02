import 'fake-indexeddb/auto'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { readWarmEntry, warmKey, writeWarm } from './warmCache'
import {
  AVAILABILITY_POLICY,
  availabilitySortRank,
  readAvailabilityAssertion,
  writeAvailabilityAssertion,
} from '../ui/catalogAvailability'

afterEach(() => vi.restoreAllMocks())

describe('IndexedDB warm cache', () => {
  it('keeps the cursor page with source provenance and save timestamp', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(123_456)
    const page = { works: [{ id: 'work-a', mergeKey: 'a', title: 'Книга', author: 'Автор', editions: [] }], nextCursor: 'cursor-2' }
    const key = warmKey('catalog', 'all', 'test-page')

    await writeWarm(key, page)
    await expect(readWarmEntry<typeof page>(key)).resolves.toEqual({ key, value: page, savedAt: 123_456 })
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
})
