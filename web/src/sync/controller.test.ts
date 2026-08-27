import { describe, expect, it } from 'vitest'
import { ProgressSyncController, type SyncMirror, type SyncIdentity } from './controller'
import { InMemoryLedger } from './ledger'
import { InMemoryProgressSyncStore } from './store'
import type { RemoteListeningState } from './policy'

function state(overrides: Partial<RemoteListeningState> & { updatedAtServerMs: number }): RemoteListeningState {
  return {
    editionId: 'ed-1',
    chapterIndex: 2,
    positionSeconds: 120,
    isCompleted: false,
    preferredSpeed: 1.25,
    ...overrides,
  }
}

function mirrorWith(local: RemoteListeningState | null, editionId = 'ed-1'): SyncMirror & { applied: RemoteListeningState[] } {
  const applied: RemoteListeningState[] = []
  return {
    applied,
    editionIdForSync: (bookId: string) => (bookId === 'book-1' || bookId === editionId ? editionId : null),
    progressByEdition: (id: string) => (id === editionId ? local : null),
    applyRemoteProgress: (_bookId: string, remote: RemoteListeningState) => {
      applied.push(remote)
    },
  }
}

function identity(uid: string | null): SyncIdentity {
  return { getUid: () => uid }
}

describe('ProgressSyncController — pull', () => {
  it('pulls only strictly newer server states', async () => {
    const store = new InMemoryProgressSyncStore()
    const ledger = new InMemoryLedger()
    const m = mirrorWith(null)
    const c = new ProgressSyncController(identity('u1'), m, store, ledger, () => true)

    store.seed('u1', state({ updatedAtServerMs: 5000 }))
    await c.pullBeforeResume('book-1')
    expect(m.applied.length).toBe(1)

    // equal stamp — already seen, no second apply
    await c.pullBeforeResume('book-1')
    expect(m.applied.length).toBe(1)

    // older remote — no apply
    store.clear()
    store.seed('u1', state({ updatedAtServerMs: 4000 }))
    ledger.recordSyncedServerMs('ed-1', 5000)
    const m2 = mirrorWith(null)
    const c2 = new ProgressSyncController(identity('u1'), m2, store, ledger, () => true)
    await c2.pullBeforeResume('book-1')
    expect(m2.applied.length).toBe(0)
  })

  it('does not pull for local-… profiles', async () => {
    const store = new InMemoryProgressSyncStore()
    store.seed('u1', state({ updatedAtServerMs: 9000 }))
    const ledger = new InMemoryLedger()
    const m = mirrorWith(null)
    const c = new ProgressSyncController(identity('local-abc'), m, store, ledger, () => true)
    await c.pullBeforeResume('book-1')
    expect(m.applied.length).toBe(0)
  })

  it('respects the enabled switch mid-flight', async () => {
    let enabled = true
    const store = new InMemoryProgressSyncStore()
    store.seed('u1', state({ updatedAtServerMs: 9000 }))
    const ledger = new InMemoryLedger()
    const m = mirrorWith(null)
    const c = new ProgressSyncController(identity('u1'), m, store, ledger, () => enabled)
    enabled = false
    await c.pullBeforeResume('book-1')
    expect(m.applied.length).toBe(0)
  })
})

describe('ProgressSyncController — push', () => {
  it('pushes immediate saves at once, throttles periodic ticks', async () => {
    const ledger = new InMemoryLedger()
    const local = state({ updatedAtServerMs: 0 })
    const m = mirrorWith(local)
    const store = new InMemoryProgressSyncStore()
    const c = new ProgressSyncController(identity('u1'), m, store, ledger, () => true, () => 0)

    await c.pushAfterSave('book-1', true)
    expect(ledger.lastPushAttemptMs('ed-1')).toBe(0)

    // non-immediate tick inside window — no second push
    const c2 = new ProgressSyncController(identity('u1'), m, store, ledger, () => true, () => 30_000)
    await c2.pushAfterSave('book-1', false)
    // ledger attempt stays at 0
    expect(ledger.lastPushAttemptMs('ed-1')).toBe(0)

    // past window — pushes
    const c3 = new ProgressSyncController(identity('u1'), m, store, ledger, () => true, () => 60_001)
    await c3.pushAfterSave('book-1', false)
    expect(ledger.lastPushAttemptMs('ed-1')).toBe(60_001)
  })

  it('does not push for local-… profiles', async () => {
    const ledger = new InMemoryLedger()
    const m = mirrorWith(state({ updatedAtServerMs: 0 }))
    const store = new InMemoryProgressSyncStore()
    const c = new ProgressSyncController(identity('local-xyz'), m, store, ledger, () => true)
    await c.pushAfterSave('book-1', true)
    expect(ledger.lastPushAttemptMs('ed-1')).toBeNull()
  })
})
