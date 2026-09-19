import { afterEach, describe, expect, it, vi } from 'vitest'
import { ProgressSyncController, type SyncMirror, type SyncIdentity } from './controller'
import { InMemoryLedger } from './ledger'
import { InMemoryProgressSyncStore, type ListenerProgressSyncStore } from './store'
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

/**
 * #619 — a store whose pull answer can be withheld and then delivered on
 * demand, so every "the world moved while the cloud was silent" race is
 * decided by the test instead of by timing.
 */
class DeferredStore implements ListenerProgressSyncStore {
  pullCalls = 0
  private readonly pending: Array<(state: RemoteListeningState | null) => void> = []

  pull(): Promise<RemoteListeningState | null> {
    this.pullCalls += 1
    return new Promise((resolve) => this.pending.push(resolve))
  }

  push(): Promise<number | null> {
    return Promise.resolve(null)
  }

  /** Answers the OLDEST unanswered pull. */
  answer(state: RemoteListeningState | null): void {
    this.pending.shift()?.(state)
  }
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

  it('an unlinked client pushes nothing', async () => {
    const ledger = new InMemoryLedger()
    const m = mirrorWith(state({ updatedAtServerMs: 0 }))
    const store = new InMemoryProgressSyncStore()
    const c = new ProgressSyncController(identity(null), m, store, ledger, () => true)
    await c.pushAfterSave('book-1', true)
    expect(ledger.lastPushAttemptMs('ed-1')).toBeNull()
  })
})

/**
 * #619 (Web playback T4) — the resume pull is BOUNDED and scoped to its
 * attempt. The races pinned here: a silent cloud held the resume forever; a
 * document for another Edition was applied under this one's key; the profile
 * or the playback intent changed while the pull was in flight and the stale
 * answer still landed in the local mirror.
 */
describe('#619 — the resume pull is bounded and scoped to its attempt', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('gives up at the budget instead of holding the resume', async () => {
    vi.useFakeTimers()
    const store = new DeferredStore()
    const c = new ProgressSyncController(identity('u1'), mirrorWith(null), store, new InMemoryLedger(), () => true)

    let settled = false
    void c.pullBeforeResume('book-1').then(() => {
      settled = true
    })

    await vi.advanceTimersByTimeAsync(1_999)
    expect(settled).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    expect(settled).toBe(true)
  })

  it('drops an answer that arrives after the budget', async () => {
    vi.useFakeTimers()
    const store = new DeferredStore()
    const ledger = new InMemoryLedger()
    const m = mirrorWith(null)
    const c = new ProgressSyncController(identity('u1'), m, store, ledger, () => true)

    const pull = c.pullBeforeResume('book-1')
    await vi.advanceTimersByTimeAsync(2_000)
    // The answer lands only after the budget has already expired.
    store.answer(state({ updatedAtServerMs: 9_000 }))
    await vi.advanceTimersByTimeAsync(0)
    await pull

    expect(m.applied.length).toBe(0)
    expect(ledger.lastSyncedServerMs('ed-1')).toBeNull()
  })

  it('never applies an answer for a different Edition', async () => {
    const store = new DeferredStore()
    const m = mirrorWith(null)
    const c = new ProgressSyncController(identity('u1'), m, store, new InMemoryLedger(), () => true)

    const pull = c.pullBeforeResume('book-1')
    store.answer(state({ editionId: 'ed-OTHER', updatedAtServerMs: 9_000 }))
    await pull

    expect(m.applied.length).toBe(0)
  })

  it('never applies an answer when the profile uid changed during the pull', async () => {
    const store = new DeferredStore()
    let uid = 'u1'
    const m = mirrorWith(null)
    const c = new ProgressSyncController({ getUid: () => uid }, m, store, new InMemoryLedger(), () => true)

    const pull = c.pullBeforeResume('book-1')
    uid = 'u2'
    store.answer(state({ updatedAtServerMs: 9_000 }))
    await pull

    expect(m.applied.length).toBe(0)
  })

  it('never applies an answer when sync was switched off during the pull', async () => {
    const store = new DeferredStore()
    let enabled = true
    const m = mirrorWith(null)
    const c = new ProgressSyncController(identity('u1'), m, store, new InMemoryLedger(), () => enabled)

    const pull = c.pullBeforeResume('book-1')
    enabled = false
    store.answer(state({ updatedAtServerMs: 9_000 }))
    await pull

    expect(m.applied.length).toBe(0)
  })

  it('never applies an answer that lost its playback attempt', async () => {
    const store = new DeferredStore()
    let current = true
    const m = mirrorWith(null)
    const c = new ProgressSyncController(identity('u1'), m, store, new InMemoryLedger(), () => true)

    const pull = c.pullBeforeResume('book-1', { isCurrent: () => current })
    current = false
    store.answer(state({ updatedAtServerMs: 9_000 }))
    await pull

    expect(m.applied.length).toBe(0)
  })
})
