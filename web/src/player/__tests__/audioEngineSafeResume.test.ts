/**
 * #619 (Web playback T4) — a resume rides Progress Sync without depending on it.
 * Pinned through the external playback interface (`AudioEngine`) over a
 * controlled media adapter (FakeAudio), a real `ProgressSyncController` and
 * injectable sync adapters (store/ledger/identity):
 *
 * - a silent Progress Sync is bounded by the controller's 2 s budget: the
 *   resume continues from the LOCAL Listening State instead of hanging;
 * - an explicit Chapter neither waits for nor triggers a pull (only a resume
 *   reads the cloud);
 * - a cloud answer that lands after a newer playback intent reaches neither
 *   the local mirror nor the Player (#617's generation);
 * - a profile switch or a sync-off during the pull makes the answer worthless;
 * - a remote answer inside the budget still wins the LWW comparison, so the
 *   safe path is not a silent "never sync".
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AudioEngine } from '../audioEngine'
import {
  LocalListeningStateStore,
  type LocalListeningStateSnapshot,
  type StorageLike,
} from '../localState'
import { ProgressSyncController, type SyncMirror } from '../../sync/controller'
import { InMemoryLedger, type ProgressSyncLedger } from '../../sync/ledger'
import { InMemoryProgressSyncStore, type ListenerProgressSyncStore } from '../../sync/store'
import type { RemoteListeningState } from '../../sync/policy'
import type { Chapter } from '../../worker/types'

class MapStorage implements StorageLike {
  private readonly map = new Map<string, string>()
  getItem(key: string): string | null {
    return this.map.get(key) ?? null
  }
  setItem(key: string, value: string): void {
    this.map.set(key, value)
  }
  removeItem(key: string): void {
    this.map.delete(key)
  }
}

/** The controlled media adapter: no real element, no real network. */
class FakeAudio extends EventTarget {
  src = ''
  currentTime = 0
  playbackRate = 1
  play(): Promise<void> {
    return Promise.resolve()
  }
  pause(): void {}
  load(): void {}
  removeAttribute(name: string): void {
    if (name === 'src') this.src = ''
  }
}

/** A Progress Sync backend that never answers — the silent cloud. */
class HangingStore implements ListenerProgressSyncStore {
  pullCalls = 0
  pull(): Promise<RemoteListeningState | null> {
    this.pullCalls += 1
    return new Promise(() => {})
  }
  push(): Promise<number | null> {
    return Promise.resolve(null)
  }
}

/** A store whose answer is withheld until the test delivers it. */
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
  answer(state: RemoteListeningState | null): void {
    this.pending.shift()?.(state)
  }
}

const CHAPTERS: Chapter[] = [
  { title: 'Розділ 1', streamUrl: 'https://audio.example/1.mp3', durationSeconds: 600 },
  { title: 'Розділ 2', streamUrl: 'https://audio.example/2.mp3', durationSeconds: 300 },
  { title: 'Розділ 3', streamUrl: 'https://audio.example/3.mp3', durationSeconds: 120 },
]

const EDITION = 'ed-safe-resume'

const detail = (editionId = EDITION) => ({
  title: 'Книга',
  chapters: CHAPTERS,
  editionId,
  workId: 'Книга|Автор',
})

function remote(overrides: Partial<RemoteListeningState> & { updatedAtServerMs: number }): RemoteListeningState {
  return {
    editionId: EDITION,
    chapterIndex: 0,
    positionSeconds: 0,
    isCompleted: false,
    preferredSpeed: null,
    ...overrides,
  }
}

/** The App.tsx mirror, over the injected local store (same shape, no React). */
function makeSync(
  local: LocalListeningStateStore,
  store: ListenerProgressSyncStore,
  options: { uid?: () => string | null; enabled?: () => boolean } = {},
): { controller: ProgressSyncController; ledger: ProgressSyncLedger } {
  const ledger = new InMemoryLedger()
  const mirror: SyncMirror = {
    editionIdForSync: (bookId: string) => bookId,
    progressByEdition: (editionId: string) => {
      const snap = local.load(editionId)
      if (!snap) return null
      return {
        editionId,
        chapterIndex: snap.chapterIndex,
        positionSeconds: snap.positionSeconds,
        isCompleted: snap.isCompleted,
        preferredSpeed: snap.preferredSpeed,
        updatedAtServerMs: 0,
      }
    },
    applyRemoteProgress: (_bookId: string, state: RemoteListeningState) => {
      local.save({
        editionId: state.editionId,
        chapterIndex: state.chapterIndex,
        positionSeconds: state.positionSeconds,
        isCompleted: state.isCompleted,
        preferredSpeed: state.preferredSpeed,
        lastPausedAtEpochMs: null,
      })
    },
  }
  const controller = new ProgressSyncController(
    { getUid: options.uid ?? (() => 'u1') },
    mirror,
    store,
    ledger,
    options.enabled ?? (() => true),
  )
  return { controller, ledger }
}

function makeEngine(
  local: LocalListeningStateStore,
  controller: ProgressSyncController,
): { engine: AudioEngine; audio: FakeAudio } {
  const engine = new AudioEngine({ relayBase: '/api', store: local, syncController: controller })
  const audio = new FakeAudio()
  engine.attachAudio(audio as unknown as HTMLAudioElement)
  return { engine, audio }
}

function makeLocal(saved?: Partial<LocalListeningStateSnapshot>): LocalListeningStateStore {
  const local = new LocalListeningStateStore(new MapStorage())
  if (saved) {
    local.save({
      editionId: EDITION,
      chapterIndex: 0,
      positionSeconds: 0,
      isCompleted: false,
      preferredSpeed: null,
      lastPausedAtEpochMs: null,
      ...saved,
    })
  }
  return local
}

afterEach(() => {
  vi.useRealTimers()
})

describe('#619 — a slow cloud never blocks the local resume', () => {
  it('resumes from the local Listening State at the 2 s budget', async () => {
    vi.useFakeTimers()
    const local = makeLocal({ chapterIndex: 2, positionSeconds: 100, preferredSpeed: 1.25 })
    const store = new HangingStore()
    const { controller } = makeSync(local, store)
    const { engine } = makeEngine(local, controller)

    let settled = false
    const load = engine.loadBook(detail(), 0).then((ok) => {
      settled = true
      return ok
    })

    await vi.advanceTimersByTimeAsync(1_000)
    expect(settled).toBe(false)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(settled).toBe(true)

    await expect(load).resolves.toBe(true)
    expect(engine.getState()).toMatchObject({
      status: 'playing',
      chapterIndex: 2,
      positionSeconds: 100,
      speed: 1.25,
    })
    engine.dispose()
  })

  it('an explicit Chapter starts at once and never touches the cloud', async () => {
    vi.useFakeTimers()
    const local = makeLocal({ chapterIndex: 2, positionSeconds: 100 })
    const store = new HangingStore()
    const { controller } = makeSync(local, store)
    const { engine } = makeEngine(local, controller)

    let settled = false
    const load = engine.loadBook(detail(), 1, { forceChapter: true }).then((ok) => {
      settled = true
      return ok
    })
    await vi.advanceTimersByTimeAsync(0)

    expect(settled).toBe(true)
    await expect(load).resolves.toBe(true)
    expect(store.pullCalls).toBe(0)
    expect(engine.getState()).toMatchObject({ chapterIndex: 1, positionSeconds: 0 })
    engine.dispose()
  })

  it('a remote state inside the budget still wins the LWW resume', async () => {
    const local = makeLocal()
    const store = new InMemoryProgressSyncStore()
    store.seed('u1', remote({ chapterIndex: 1, positionSeconds: 250, preferredSpeed: 1.5, updatedAtServerMs: 5_000 }))
    const { controller } = makeSync(local, store)
    const { engine } = makeEngine(local, controller)

    await expect(engine.loadBook(detail(), 0)).resolves.toBe(true)

    expect(engine.getState()).toMatchObject({ chapterIndex: 1, positionSeconds: 250, speed: 1.5 })
    expect(local.load(EDITION)).toMatchObject({ chapterIndex: 1, positionSeconds: 250 })
    engine.dispose()
  })
})

describe('#619 — an answer that lost its attempt is never applied', () => {
  it('a newer playback intent drops the old answer from the mirror and the Player', async () => {
    const local = makeLocal()
    const store = new DeferredStore()
    const { controller, ledger } = makeSync(local, store)
    const { engine } = makeEngine(local, controller)

    const superseded = engine.loadBook(detail('ed-A'), 0) // resume, parked on the pull
    const newer = engine.loadBook(detail('ed-B'), 0, { forceChapter: true })
    await expect(newer).resolves.toBe(true)

    store.answer(remote({ editionId: 'ed-A', chapterIndex: 3, positionSeconds: 300, updatedAtServerMs: 9_000 }))
    await expect(superseded).resolves.toBe(false)

    expect(local.load('ed-A')).toBeNull()
    expect(ledger.lastSyncedServerMs('ed-A')).toBeNull()
    expect(engine.getState()).toMatchObject({ editionId: 'ed-B', chapterIndex: 0, positionSeconds: 0 })
    engine.dispose()
  })

  it('a profile switch during the pull keeps the local place', async () => {
    const local = makeLocal({ chapterIndex: 1, positionSeconds: 50 })
    let uid: string | null = 'u1'
    const store = new DeferredStore()
    const { controller } = makeSync(local, store, { uid: () => uid })
    const { engine } = makeEngine(local, controller)

    const load = engine.loadBook(detail(), 0)
    uid = 'u2'
    store.answer(remote({ chapterIndex: 3, positionSeconds: 300, updatedAtServerMs: 9_000 }))
    await expect(load).resolves.toBe(true)

    expect(engine.getState()).toMatchObject({ chapterIndex: 1, positionSeconds: 50 })
    expect(local.load(EDITION)).toMatchObject({ chapterIndex: 1, positionSeconds: 50 })
    engine.dispose()
  })

  it('a sync-off during the pull keeps the local place', async () => {
    const local = makeLocal({ chapterIndex: 1, positionSeconds: 50 })
    let enabled = true
    const store = new DeferredStore()
    const { controller } = makeSync(local, store, { enabled: () => enabled })
    const { engine } = makeEngine(local, controller)

    const load = engine.loadBook(detail(), 0)
    enabled = false
    store.answer(remote({ chapterIndex: 3, positionSeconds: 300, updatedAtServerMs: 9_000 }))
    await expect(load).resolves.toBe(true)

    expect(engine.getState()).toMatchObject({ chapterIndex: 1, positionSeconds: 50 })
    engine.dispose()
  })
})

describe('#619 — honest moments still reach the store at once', () => {
  it('pause and seek push immediately through the sync adapter', async () => {
    const local = makeLocal()
    const store = new InMemoryProgressSyncStore()
    const { controller } = makeSync(local, store)
    const { engine } = makeEngine(local, controller)

    await engine.loadBook(detail(), 0, { forceChapter: true })
    engine.seek(42)
    engine.pause()
    // pushAfterSave is fire-and-forget; let its microtasks drain.
    await new Promise((resolve) => setTimeout(resolve, 0))

    await expect(store.pull('u1', EDITION)).resolves.toMatchObject({
      chapterIndex: 0,
      positionSeconds: 42,
      isCompleted: false,
    })
    engine.dispose()
  })

  it('completion pushes immediately', async () => {
    const local = makeLocal()
    const store = new InMemoryProgressSyncStore()
    const { controller } = makeSync(local, store)
    const { engine, audio } = makeEngine(local, controller)

    await engine.loadBook(detail(), CHAPTERS.length - 1, { forceChapter: true })
    audio.dispatchEvent(new Event('ended'))
    await new Promise((resolve) => setTimeout(resolve, 0))

    await expect(store.pull('u1', EDITION)).resolves.toMatchObject({ isCompleted: true })
    expect(local.load(EDITION)).toMatchObject({ isCompleted: true })
    engine.dispose()
  })

  it('an unlinked client writes no Listening State', async () => {
    const local = makeLocal()
    const store = new InMemoryProgressSyncStore()
    const { controller } = makeSync(local, store, { uid: () => null })
    const { engine } = makeEngine(local, controller)

    await engine.loadBook(detail(), 0, { forceChapter: true })
    engine.seek(42)
    engine.pause()
    await new Promise((resolve) => setTimeout(resolve, 0))

    await expect(store.pull('u1', EDITION)).resolves.toBeNull()
    engine.dispose()
  })
})
