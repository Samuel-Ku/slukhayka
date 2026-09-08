/**
 * W5.1 — the bookmarks store, pinned: the pure validator (corrupt rows are
 * skipped), the newest-first order, and the IDB round-trip through the
 * same fake-indexeddb seam as the rest of the listener database.
 */
import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it } from 'vitest'
import { openListenerDatabase } from '../../local/idb'
import { LISTENER_DB_VERSION, LISTENER_STORES, PLAYER_BOOKMARKS_STORE } from '../../local/schema'
import { parsePlayerBookmarkValue, PlayerBookmarksStore, sortBookmarksNewestFirst } from '../bookmarks'

function freshStore(now: () => number = () => 1000): PlayerBookmarksStore {
  return new PlayerBookmarksStore(
    () => openListenerDatabase(LISTENER_DB_VERSION, LISTENER_STORES),
    now,
  )
}

beforeEach(async () => {
  const db = await openListenerDatabase(LISTENER_DB_VERSION, LISTENER_STORES)
  await db?.clear(PLAYER_BOOKMARKS_STORE)
  db?.close()
})

describe('parsePlayerBookmarkValue', () => {
  it('accepts a well-formed record', () => {
    const bookmark = {
      id: 'abc',
      workId: 'Книга|Автор',
      editionId: 'ed-1',
      chapterIndex: 2,
      chapterTitle: 'Розділ 3',
      timestampSeconds: 61,
      note: 'Думка',
      createdAt: 42,
    }
    expect(parsePlayerBookmarkValue(bookmark)).toEqual(bookmark)
  })

  it('rejects corrupt shapes', () => {
    const base = {
      id: 'abc',
      workId: 'Книга|Автор',
      editionId: 'ed-1',
      chapterIndex: 2,
      chapterTitle: 'Розділ 3',
      timestampSeconds: 61,
      note: 'Думка',
      createdAt: 42,
    }
    expect(parsePlayerBookmarkValue(null)).toBeNull()
    expect(parsePlayerBookmarkValue({ ...base, id: '' })).toBeNull()
    expect(parsePlayerBookmarkValue({ ...base, chapterIndex: -1 })).toBeNull()
    expect(parsePlayerBookmarkValue({ ...base, chapterIndex: 1.5 })).toBeNull()
    expect(parsePlayerBookmarkValue({ ...base, timestampSeconds: '61' })).toBeNull()
    expect(parsePlayerBookmarkValue({ ...base, workId: '' })).toBeNull()
  })
})

describe('sortBookmarksNewestFirst', () => {
  it('orders newest first and does not mutate the input', () => {
    const older = { id: 'a', workId: 'w', editionId: 'e', chapterIndex: 0, chapterTitle: '1', timestampSeconds: 1, note: '', createdAt: 1 }
    const newer = { ...older, id: 'b', createdAt: 2 }
    const input = [older, newer]
    expect(sortBookmarksNewestFirst(input).map((b) => b.id)).toEqual(['b', 'a'])
    expect(input.map((b) => b.id)).toEqual(['a', 'b'])
  })
})

describe('PlayerBookmarksStore', () => {
  it('round-trips add → forWork, newest first', async () => {
    const store = freshStore(() => 1000)
    const first = await store.add({ workId: 'w', editionId: 'e', chapterIndex: 1, chapterTitle: 'Розділ 2', timestampSeconds: 10, note: '' })
    const store2 = freshStore(() => 2000)
    const second = await store2.add({ workId: 'w', editionId: 'e', chapterIndex: 2, chapterTitle: 'Розділ 3', timestampSeconds: 20, note: 'цитата' })
    expect(first).not.toBeNull()
    expect(second).not.toBeNull()
    const list = await freshStore().forWork('w')
    expect(list.map((b) => b.id)).toEqual([second!.id, first!.id])
    expect(list[0]!.note).toBe('цитата')
  })

  it('scopes by work and edition', async () => {
    const store = freshStore()
    await store.add({ workId: 'w1', editionId: 'e1', chapterIndex: 0, chapterTitle: '1', timestampSeconds: 1, note: '' })
    await store.add({ workId: 'w2', editionId: 'e1', chapterIndex: 0, chapterTitle: '1', timestampSeconds: 1, note: '' })
    expect((await store.forWork('w1')).length).toBe(1)
    expect((await store.forEdition('e1')).length).toBe(2)
  })

  it('remove deletes exactly the bookmark', async () => {
    const store = freshStore()
    const a = await store.add({ workId: 'w', editionId: 'e', chapterIndex: 0, chapterTitle: '1', timestampSeconds: 1, note: '' })
    await store.add({ workId: 'w', editionId: 'e', chapterIndex: 1, chapterTitle: '2', timestampSeconds: 2, note: '' })
    await store.remove(a!.id)
    const list = await store.forWork('w')
    expect(list).toHaveLength(1)
    expect(list[0]!.id).not.toBe(a!.id)
  })

  it('skips corrupt records instead of crashing the list', async () => {
    const db = await openListenerDatabase(LISTENER_DB_VERSION, LISTENER_STORES)
    await db!.put(PLAYER_BOOKMARKS_STORE, { id: 'corrupt', workId: 42 })
    db!.close()
    const store = freshStore()
    await store.add({ workId: 'w', editionId: 'e', chapterIndex: 0, chapterTitle: '1', timestampSeconds: 1, note: '' })
    const list = await store.forWork('w')
    expect(list).toHaveLength(1)
  })
})