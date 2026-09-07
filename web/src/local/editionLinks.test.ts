/**
 * #584 W1.2 — the edition_links store over fake-indexeddb: roundtrip,
 * per-Work reads, deletion cleanup.
 */
import 'fake-indexeddb/auto'
import { describe, expect, it } from 'vitest'
import { EditionLinkStore } from './editionLinks'
import { EDITION_LINKS_STORE, LISTENER_DB_VERSION, LISTENER_STORES } from './schema'
import { openListenerDatabase } from './idb'

function freshStore(): EditionLinkStore {
  return new EditionLinkStore(
    () => openListenerDatabase(LISTENER_DB_VERSION, LISTENER_STORES),
    () => 42,
  )
}

describe('EditionLinkStore', () => {
  it('round-trips a link', async () => {
    const store = freshStore()
    await store.link({ editionId: 'ed1', mergeKey: 'a|b', narrator: 'Диктор', language: 'uk', durationSeconds: 300, chapterDurations: [100, 200] })
    const links = await store.linksFor('a|b')
    expect(links).toHaveLength(1)
    expect(links[0]).toMatchObject({ editionId: 'ed1', mergeKey: 'a|b', durationSeconds: 300 })
    expect(links[0]!.updatedAt).toBe(42)
  })

  it('upserts by editionId — the newest write wins', async () => {
    const store = freshStore()
    await store.link({ editionId: 'ed1', mergeKey: 'a|b', narrator: '', language: '', durationSeconds: null, chapterDurations: null })
    await store.link({ editionId: 'ed1', mergeKey: 'a|b', narrator: 'Диктор', language: 'uk', durationSeconds: 300, chapterDurations: [300] })
    const links = await store.linksFor('a|b')
    expect(links).toHaveLength(1)
    expect(links[0]!.narrator).toBe('Диктор')
  })

  it('removes exactly one Work’s links on cleanup', async () => {
    const store = freshStore()
    await store.link({ editionId: 'ed1', mergeKey: 'a|b', narrator: '', language: '', durationSeconds: null, chapterDurations: null })
    await store.link({ editionId: 'ed2', mergeKey: 'c|d', narrator: '', language: '', durationSeconds: null, chapterDurations: null })
    await store.removeForMerge('a|b')
    expect(await store.linksFor('a|b')).toEqual([])
    expect(await store.linksFor('c|d')).toHaveLength(1)
  })

  it('degrades to empty reads when IDB is unavailable', async () => {
    const store = new EditionLinkStore(() => Promise.resolve(null))
    expect(await store.all()).toEqual([])
    expect(await store.linksFor('a|b')).toEqual([])
    await expect(store.link({ editionId: 'e', mergeKey: 'm', narrator: '', language: '', durationSeconds: null, chapterDurations: null })).resolves.toBeUndefined()
  })

  it('the schema carries the edition_links store at v2', () => {
    expect(LISTENER_DB_VERSION).toBe(2)
    expect(LISTENER_STORES.some((spec) => spec.name === EDITION_LINKS_STORE)).toBe(true)
  })
})
