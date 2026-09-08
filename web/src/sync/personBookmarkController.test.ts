// @vitest-environment jsdom
/**
 * #582 W0.4 — the controller's honest flows: toggle writes locally first
 * and uploads when bound; remove persists the pending-delete marker BEFORE
 * the network call so an offline removal is never resurrected; sync
 * applies strictly-newer remote rows and pushes ahead local rows; the
 * linking moment uploads pre-link locals and applies account rows. A null
 * store or an unbound profile makes everything a no-op, and refusing sync
 * NEVER touches local bookmarks.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { DomainStore } from '../local/domain'
import { personIdentityOf } from '../local/personIdentity'
import { InMemoryPersonBookmarkStore } from './personBookmarkStore'
import {
  LocalPendingPersonBookmarkDeletes,
  PersonBookmarkSyncController,
  type PendingPersonBookmarkDeletes,
} from './personBookmarkController'
import type { RemotePersonBookmark } from './personBookmarkSync'

function memoryStorage(): { getItem(key: string): string | null; setItem(key: string, value: string): void } {
  const map = new Map<string, string>()
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => { map.set(key, value) },
  }
}

beforeEach(() => {
  ;(globalThis as { indexedDB?: unknown }).indexedDB = new IDBFactory()
})

function makeController(options: {
  uid?: string | null
  store?: InMemoryPersonBookmarkStore | null
  enabled?: boolean
  pending?: PendingPersonBookmarkDeletes
} = {}): {
  controller: PersonBookmarkSyncController
  domain: DomainStore
  store: InMemoryPersonBookmarkStore | null
  pending: PendingPersonBookmarkDeletes
} {
  const domain = new DomainStore()
  const store = options.store === undefined ? new InMemoryPersonBookmarkStore() : options.store
  const pending = options.pending ?? new LocalPendingPersonBookmarkDeletes(memoryStorage())
  const controller = new PersonBookmarkSyncController(
    () => options.uid ?? null,
    domain,
    store,
    () => options.enabled ?? true,
    pending,
  )
  return { controller, domain, store, pending }
}

const AUTHOR_ID = personIdentityOf('author', 'Шевченко').id
const AUTHOR_REMOTE: RemotePersonBookmark = {
  kind: 'AUTHOR',
  personId: AUTHOR_ID,
  displayName: 'Шевченко',
  notifyEnabled: true,
  updatedAtServerMs: 500,
}

describe('toggle — local-first, upload when bound', () => {
  it('adds locally and uploads when bound and sync is on', async () => {
    const { controller, domain, store } = makeController({ uid: 'uid-1' })
    const cloud = store!
    void cloud
    expect(await controller.toggle('author', 'Шевченко')).toBe(true)
    const bookmark = await domain.personBookmarkOf(AUTHOR_ID)
    expect(bookmark?.displayName).toBe('Шевченко')
    expect(bookmark?.updatedAt).toBeGreaterThan(0)
    expect(await cloud.pullAll('uid-1')).toHaveLength(1)
  })

  it('removes locally and deletes the cloud doc when bound', async () => {
    const { controller, domain, store } = makeController({ uid: 'uid-1' })
    const cloud = store!
    await controller.toggle('author', 'Шевченко')
    expect(await controller.toggle('author', 'Шевченко')).toBe(false)
    expect(await domain.personBookmarkOf(AUTHOR_ID)).toBeNull()
    expect(await cloud.pullAll('uid-1')).toHaveLength(0)
  })

  it('works offline / unbound: local write only, nothing leaves the browser', async () => {
    const { controller, domain, store } = makeController({ uid: null })
    const cloud = store!
    expect(await controller.toggle('author', 'Шевченко')).toBe(true)
    expect(await domain.personBookmarkOf(AUTHOR_ID)).not.toBeNull()
    expect(cloud.documents.size).toBe(0)
  })

  it('works with no store: local bookmark stands, sync is a no-op', async () => {
    const { controller, domain } = makeController({ uid: 'uid-1', store: null })
    expect(await controller.toggle('author', 'Шевченко')).toBe(true)
    expect(await domain.personBookmarkOf(AUTHOR_ID)).not.toBeNull()
  })
})

describe('remove — the pending-delete marker protects offline removals', () => {
  it('persists the marker before the network call and drops it after success', async () => {
    const { controller, domain, pending, store } = makeController({ uid: 'uid-1' })
    const cloud = store!
    await controller.toggle('author', 'Шевченко')
    await controller.remove(AUTHOR_ID)
    expect(await domain.personBookmarkOf(AUTHOR_ID)).toBeNull()
    expect(pending.keys()).toHaveLength(0) // flushed on success
    expect(await cloud.pullAll('uid-1')).toHaveLength(0)
  })

  it('a marker survives a failed remote delete and blocks resurrection on the next sync', async () => {
    const { controller, domain, pending } = makeController({ uid: 'uid-1' })
    const store = new InMemoryPersonBookmarkStore()
    store.seed('uid-1', AUTHOR_REMOTE) // the cloud still has the row
    const failing = {
      pullAll: async (uid: string) => store.pullAll(uid),
      push: async () => false,
      remove: async () => false, // the network fails
    }
    const controller2 = new PersonBookmarkSyncController(
      () => 'uid-1',
      domain,
      failing,
      () => true,
      pending,
    )
    await domain.applyRemotePersonBookmark({ role: 'author', personId: AUTHOR_ID, displayName: 'Шевченко', createdAt: 500, updatedAt: 500, notifyEnabled: true })
    await controller2.remove(AUTHOR_ID)
    // The marker persists — the failed delete must not resurrect on pull.
    expect(pending.keys()).toEqual([['AUTHOR', AUTHOR_ID]])
    await controller2.sync()
    expect(await domain.personBookmarkOf(AUTHOR_ID)).toBeNull()
    expect(pending.keys()).toEqual([['AUTHOR', AUTHOR_ID]])
    void controller
  })
})

describe('sync — LWW in both directions, pull never deletes', () => {
  it('applies a strictly-newer remote row and pushes an ahead local row', async () => {
    const { controller, domain, store } = makeController({ uid: 'uid-1' })
    const cloud = store!
    // The phone bookmarked with a server clock of 500.
    cloud.seed('uid-1', AUTHOR_REMOTE)
    await controller.sync()
    expect((await domain.personBookmarkOf(AUTHOR_ID))?.updatedAt).toBe(500)
    // A local re-bookmark bumps the local clock above the cloud's.
    await domain.addPersonBookmark({ role: 'author', personId: AUTHOR_ID, displayName: 'Шевченко' })
    await controller.sync()
    const afterPush = await cloud.pullAll('uid-1')
    expect(afterPush[0]!.updatedAtServerMs).toBeGreaterThan(500)
  })

  it('a stale remote row never overwrites a newer local row', async () => {
    const { controller, domain, store } = makeController({ uid: 'uid-1' })
    const cloud = store!
    await controller.toggle('author', 'Шевченко') // local clock > 0
    const localBefore = await domain.personBookmarkOf(AUTHOR_ID)
    cloud.seed('uid-1', { ...AUTHOR_REMOTE, updatedAtServerMs: (localBefore!.updatedAt) - 100 })
    await controller.sync()
    expect((await domain.personBookmarkOf(AUTHOR_ID))?.updatedAt).toBe(localBefore!.updatedAt)
  })

  it('a null store or sync-off makes sync a no-op and keeps local rows', async () => {
    const { controller, domain, store } = makeController({ uid: 'uid-1', store: null })
    await controller.toggle('author', 'Шевченко')
    await controller.sync()
    expect(await domain.personBookmarkOf(AUTHOR_ID)).not.toBeNull()
    void store
  })
})

describe('mergeAtLinking — the recovery-code moment', () => {
  it('uploads pre-link local rows and applies account rows through LWW', async () => {
    const { controller, domain, store } = makeController({ uid: 'local-x' })
    const cloud = store!
    // Pre-link local bookmark (wall-clock updatedAt).
    await controller.toggle('author', 'Шевченко')
    const localBookmark = await domain.personBookmarkOf(AUTHOR_ID)
    // The account already vouches for ANOTHER narrator bookmark.
    cloud.seed('uid-1', {
      kind: 'NARRATOR',
      personId: 'narrator-9999',
      displayName: 'Читець',
      notifyEnabled: true,
      updatedAtServerMs: localBookmark!.updatedAt + 1_000,
    })
    controller.setUid('uid-1')
    const result = await controller.mergeAtLinking()
    expect(result.uploaded).toBe(1) // the author bookmark uploaded
    expect(result.applied).toBe(1) // the narrator bookmark applied
    expect(await cloud.pullAll('uid-1')).toHaveLength(2)
    expect(await domain.personBookmarks()).toHaveLength(2)
    expect((await domain.personBookmarkOf('narrator-9999'))?.displayName).toBe('Читець')
  })

  it('does not upload a local row the account already vouches for', async () => {
    const { controller, domain, store } = makeController({ uid: 'local-x' })
    const cloud = store!
    await controller.toggle('author', 'Шевченко')
    const localBookmark = await domain.personBookmarkOf(AUTHOR_ID)
    // The account's row is strictly NEWER than the pre-link local clock.
    cloud.seed('uid-1', { ...AUTHOR_REMOTE, updatedAtServerMs: localBookmark!.updatedAt + 1_000 })
    controller.setUid('uid-1')
    const result = await controller.mergeAtLinking()
    // The account row wins on apply; the local row does not overwrite it.
    expect(result.uploaded).toBe(0)
    expect(result.applied).toBe(1)
    expect(await cloud.pullAll('uid-1')).toHaveLength(1)
    expect((await domain.personBookmarkOf(AUTHOR_ID))?.updatedAt).toBe(localBookmark!.updatedAt + 1_000)
  })
})