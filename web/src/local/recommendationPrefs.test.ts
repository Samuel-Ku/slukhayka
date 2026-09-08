/**
 * #586 W2.2 — the local Recommendation Preference store: Android's
 * `RecommendationPreferenceEntity` port (PK kind+targetKey, the
 * HIDE_WORK/REDUCE_SIMILAR/HIDE_AUTHOR dictionary), local-only, never
 * synced, reversible.
 */
import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { RecommendationPrefsStore } from './recommendationPrefs'

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
})

afterEach(() => {
  globalThis.indexedDB = new IDBFactory()
})

describe('RecommendationPrefsStore', () => {
  it('adds and lists preferences with Android\'s dictionary kinds', async () => {
    const store = new RecommendationPrefsStore()
    await store.add('HIDE_WORK', 'a|автор', 'a|автор')
    await store.add('REDUCE_SIMILAR', 'b|автор', 'b|автор')
    await store.add('HIDE_AUTHOR', 'автор', 'a|автор')

    const rows = await store.all()
    expect(rows).toHaveLength(3)
    expect(rows.map((row) => row.kind).sort()).toEqual(['HIDE_AUTHOR', 'HIDE_WORK', 'REDUCE_SIMILAR'])
    expect(rows.every((row) => row.targetKey !== '' && row.sourceWorkId !== '' && row.createdAt > 0)).toBe(true)
  })

  it('upserts at kind+targetKey (Android\'s primary key), newest createdAt wins', async () => {
    const store = new RecommendationPrefsStore()
    await store.add('HIDE_WORK', 'a|автор', 'a|автор')
    await new Promise((resolve) => setTimeout(resolve, 5))
    await store.add('HIDE_WORK', 'a|автор', 'a|автор')

    const rows = await store.all()
    expect(rows).toHaveLength(1)
    expect(rows[0]!.id).toBe('HIDE_WORK:a|автор')
  })

  it('removes one preference and leaves the rest', async () => {
    const store = new RecommendationPrefsStore()
    await store.add('HIDE_WORK', 'a|автор', 'a|автор')
    await store.add('HIDE_WORK', 'b|автор', 'b|автор')
    await store.remove('HIDE_WORK', 'a|автор')

    const rows = await store.all()
    expect(rows).toHaveLength(1)
    expect(rows[0]!.targetKey).toBe('b|автор')
    // Removing a missing row is a no-op.
    await store.remove('REDUCE_SIMILAR', 'missing|хтось')
    expect(await store.all()).toHaveLength(1)
  })

  it('hideWorkTargets is the «Не цікаво» set the composer filters by', async () => {
    const store = new RecommendationPrefsStore()
    await store.add('HIDE_WORK', 'a|автор', 'a|автор')
    await store.add('REDUCE_SIMILAR', 'b|автор', 'b|автор')

    expect(await store.hideWorkTargets()).toEqual(['a|автор'])
  })

  it('degrades to an empty list when IndexedDB is unavailable', async () => {
    const store = new RecommendationPrefsStore(() => Promise.resolve(null))
    expect(await store.all()).toEqual([])
    await store.add('HIDE_WORK', 'a|автор', 'a|автор') // no throw
    await store.remove('HIDE_WORK', 'a|автор') // no throw
  })
})