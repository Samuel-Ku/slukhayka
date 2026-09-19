/**
 * #617 (Web playback T3) — every playback intent is its own media ATTEMPT.
 * Pinned through the external playback interface (`AudioEngine`) over a
 * controlled media adapter that models a browser element: each new resource
 * load starts a media session, and an aborted session can no longer deliver
 * `playing`/`ended`/`error`.
 *
 * The races this ticket names:
 * - a repeated `attachAudio` (React StrictMode double-effect) used to register
 *   anonymous listeners again, so ONE `ended` advanced two Chapters;
 * - `onEnded()` / `attemptPlaying()` / `attemptErrored()` were not bound to the
 *   attempt generation, so a queued event from the previous attempt (even the
 *   identical URL reloaded) drove the current one;
 * - a `pause` during loading did not cancel autoplay — a late `playing`
 *   confirmed the load and could restart sound;
 * - a browser autoplay refusal was swallowed, so the UI showed a fake playing
 *   state and the await burned its whole timeout;
 * - a failed prepare persisted its never-reached position over the last
 *   confirmed Listening State;
 * - a `loadBook` suspended on a cloud pull could clobber a newer load.
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AudioEngine } from '../audioEngine'
import {
  LocalListeningStateStore,
  type LocalListeningStateSnapshot,
  type StorageLike,
} from '../localState'
import type { Chapter } from '../../worker/types'
import type { ProgressSyncController } from '../../sync/controller'

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

/**
 * The controlled media adapter. It models the two browser behaviours this
 * ticket depends on: a `src` change or an explicit `load()` starts a NEW media
 * session, and starting one aborts the previous resource — events already
 * queued for it are never delivered (`flushQueued` forces delivery, exactly to
 * prove the engine aborted the session).
 */
class ControllableAudio extends EventTarget {
  currentTime = 0
  playbackRate = 1
  loads = 0
  playImpl: () => Promise<void> = () => Promise.resolve()
  private source = ''
  private session = 0
  private readonly queued: string[] = []

  get src(): string {
    return this.source
  }
  set src(value: string) {
    if (value === this.source) return
    this.source = value
    if (value !== '') this.startSession()
  }

  load(): void {
    this.loads += 1
    this.startSession()
  }

  removeAttribute(name: string): void {
    if (name === 'src') this.source = ''
  }

  pause(): void {}

  play(): Promise<void> {
    return this.playImpl()
  }

  /** A media event of the session that is current right now. */
  emit(type: string): void {
    this.dispatchEvent(new Event(type))
  }

  /** An event whose task was queued before a later media-session switch. */
  queue(type: string): void {
    this.queued.push(type)
  }

  /** The browser delivers queued tasks — even for an aborted resource. */
  flushQueued(): void {
    const pending = [...this.queued]
    this.queued.length = 0
    for (const type of pending) this.dispatchEvent(new Event(type))
  }

  private startSession(): void {
    this.session += 1
    this.queued.length = 0
  }
}

const CHAPTERS: Chapter[] = [
  { title: 'Розділ 1', streamUrl: 'https://audio.example/1.mp3', durationSeconds: 600 },
  { title: 'Розділ 2', streamUrl: 'https://audio.example/2.mp3', durationSeconds: 300 },
  { title: 'Розділ 3', streamUrl: 'https://audio.example/3.mp3', durationSeconds: 120 },
]

const EDITION = 'ed-attempts'

const detail = (editionId = EDITION) => ({
  title: 'Книга',
  chapters: CHAPTERS,
  editionId,
  workId: 'Книга|Автор',
})

function makeEngine(saved?: Partial<LocalListeningStateSnapshot>): {
  engine: AudioEngine
  audio: ControllableAudio
  store: LocalListeningStateStore
} {
  const store = new LocalListeningStateStore(new MapStorage())
  if (saved) {
    store.save({
      editionId: EDITION,
      chapterIndex: 0,
      positionSeconds: 0,
      isCompleted: false,
      preferredSpeed: null,
      lastPausedAtEpochMs: null,
      ...saved,
    })
  }
  const engine = new AudioEngine({ relayBase: '/api', store })
  const audio = new ControllableAudio()
  engine.attachAudio(audio as unknown as HTMLAudioElement)
  return { engine, audio, store }
}

afterEach(() => {
  vi.useRealTimers()
})

describe('#617 — attach is idempotent; detach/dispose unbind', () => {
  it('a doubled attach does not multiply listeners: one ended advances ONE Chapter', async () => {
    const { engine, audio } = makeEngine()
    // React StrictMode mounts the effect twice on the SAME element.
    engine.attachAudio(audio as unknown as HTMLAudioElement)
    engine.attachAudio(audio as unknown as HTMLAudioElement)
    await engine.loadBook(detail(), 0, { forceChapter: true })

    audio.emit('ended')

    expect(engine.getState()).toMatchObject({ chapterIndex: 1, positionSeconds: 0 })
  })

  it('detach removes listeners and stops the ticker', async () => {
    vi.useFakeTimers()
    const { engine, audio } = makeEngine()
    await engine.loadBook(detail(), 0, { forceChapter: true })

    engine.detachAudio()
    vi.advanceTimersByTime(3_000)

    // The ticker is gone: the engine position no longer advances on its own.
    expect(engine.getState().positionSeconds).toBe(0)
    audio.emit('ended')
    expect(engine.getState().chapterIndex).toBe(0)
  })

  it('dispose removes listeners, the ticker and the sleep-timer interval', async () => {
    vi.useFakeTimers()
    const { engine, audio } = makeEngine()
    await engine.loadBook(detail(), 0, { forceChapter: true })
    engine.setSleepTimer(5)

    engine.dispose()
    vi.advanceTimersByTime(3_000)

    expect(engine.getState().positionSeconds).toBe(0)
    audio.emit('ended')
    expect(engine.getState().chapterIndex).toBe(0)
    expect(engine.getSleepTimerState()).toMatchObject({ remainingSeconds: 300 })
  })
})

describe('#617 — an old attempt’s events cannot drive the current one', () => {
  it('replaying the IDENTICAL URL aborts the old session; its ended cannot advance the replay', async () => {
    const { engine, audio } = makeEngine()
    await engine.loadBook(detail(), 0, { forceChapter: true })
    audio.queue('ended') // the first session's end is queued, not yet delivered

    await engine.loadBook(detail(), 0, { forceChapter: true }) // the SAME Chapter and URL again
    const loadsAfterReplay = audio.loads

    audio.flushQueued()

    expect(engine.getState()).toMatchObject({ chapterIndex: 0, status: 'playing' })
    // The engine explicitly reloaded the element, even for the identical URL.
    expect(loadsAfterReplay).toBeGreaterThan(0)
  })

  it('a stale error cannot spend the fresh attempt’s one relay fallback', async () => {
    const { engine, audio } = makeEngine()
    await engine.loadBook(detail(), 0, { forceChapter: true })
    audio.queue('error')

    await engine.loadBook(detail(), 0, { forceChapter: true })
    audio.flushQueued()

    expect(engine.getState()).toMatchObject({ status: 'playing', attemptKind: 'direct' })
    expect(audio.src).toBe(CHAPTERS[0].streamUrl)
  })

  it('a stale playing cannot confirm the newer await', async () => {
    const { engine, audio } = makeEngine()
    audio.playImpl = () => new Promise<void>(() => {}) // autoplay stays in flight
    const first = engine.loadBookAndAwaitPlaying(detail(), 0, 10_000)
    audio.queue('playing') // attempt 1 is about to be superseded
    const second = engine.loadBookAndAwaitPlaying(detail(), 0, 10_000)

    await expect(first).resolves.toBe(false)

    let secondSettled = false
    void second.then(() => {
      secondSettled = true
    })
    audio.flushQueued() // the browser delivers the superseded attempt's playing
    await Promise.resolve()
    expect(secondSettled).toBe(false)

    audio.emit('playing') // the genuine playing of the current attempt
    await expect(second).resolves.toBe(true)
  })

  it('a load suspended on a cloud pull cannot clobber a newer load', async () => {
    const store = new LocalListeningStateStore(new MapStorage())
    const pendingPulls: Array<() => void> = []
    const syncController = {
      pullBeforeResume: () => new Promise<void>((resolve) => pendingPulls.push(resolve)),
      pushAfterSave: () => Promise.resolve(),
    } as unknown as ProgressSyncController
    const engine = new AudioEngine({ relayBase: '/api', store, syncController })
    const audio = new ControllableAudio()
    engine.attachAudio(audio as unknown as HTMLAudioElement)

    // #619 — the pull belongs to a RESUME; an explicit Chapter never starts
    // one, so this race is built on two resume loads.
    const first = engine.loadBook(detail('ed-A'), 0)
    const second = engine.loadBook(detail('ed-B'), 2)
    // The superseded load resolves LAST — it must not win.
    pendingPulls[1]!()
    pendingPulls[0]!()

    await expect(first).resolves.toBe(false)
    await expect(second).resolves.toBe(true)
    expect(engine.getState()).toMatchObject({ editionId: 'ed-B', chapterIndex: 2 })
  })
})

describe('#617 — pause during loading cancels autoplay', () => {
  it('a late playing neither confirms the load nor restarts the sound', async () => {
    const { engine, audio } = makeEngine()
    audio.playImpl = () => new Promise<void>(() => {}) // still loading
    let settled = false
    const result = engine
      .loadBookAndAwaitPlaying(detail(), 0, 10_000)
      .then((playing) => {
        settled = true
        return playing
      })
    await Promise.resolve()

    engine.pause()
    await expect(result).resolves.toBe(false)
    expect(settled).toBe(true)

    audio.emit('playing') // the browser finally starts the aborted resource
    expect(engine.getState().status).toBe('paused')
  })
})

describe('#617 — browser autoplay refusal is not a Source failure', () => {
  it('parks into manual Play without waiting the budget and without spending the relay', async () => {
    vi.useFakeTimers()
    const { engine, audio } = makeEngine()
    audio.playImpl = () => Promise.reject(Object.assign(new Error('autoplay'), { name: 'NotAllowedError' }))

    let settled = false
    const result = engine
      .loadBookAndAwaitPlaying(detail(), 0, 10_000)
      .then((playing) => {
        settled = true
        return playing
      })
    await vi.advanceTimersByTimeAsync(0)

    // Resolved from the refusal itself, not from the 10 s budget running out.
    expect(settled).toBe(true)
    await expect(result).resolves.toBe(false)
    expect(engine.getState()).toMatchObject({ status: 'paused', attemptKind: 'direct' })
    expect(audio.src).not.toContain('/api/audio')

    // The relay budget is intact: the manual Play's own failure still gets
    // exactly one direct → relay fallback.
    audio.playImpl = () => Promise.resolve()
    engine.play()
    audio.emit('error')
    expect(engine.getState().attemptKind).toBe('relay')
    expect(audio.src).toContain('/api/audio')
  })

  it('a real media rejection still spends the fallback and ends unavailable', async () => {
    const { engine, audio } = makeEngine()
    audio.playImpl = () => Promise.reject(new Error('decode failed'))

    await expect(engine.loadBookAndAwaitPlaying(detail(), 0, 10_000)).resolves.toBe(false)

    expect(engine.getState().status).toBe('unavailable')
  })

  it('a direct failure gets exactly ONE relay fallback, in order', async () => {
    const { engine, audio } = makeEngine()
    const tried: string[] = []
    audio.playImpl = () => {
      tried.push(audio.src)
      return Promise.reject(new Error('dead stream'))
    }

    await expect(engine.loadBookAndAwaitPlaying(detail(), 0, 10_000)).resolves.toBe(false)

    expect(tried).toEqual([
      CHAPTERS[0].streamUrl,
      '/api/audio?u=https%3A%2F%2Faudio.example%2F1.mp3',
    ])
    expect(engine.getState().status).toBe('unavailable')
  })

  it('a query-bearing signed locator is never handed to the relay', async () => {
    const { engine, audio } = makeEngine()
    const tried: string[] = []
    audio.playImpl = () => {
      tried.push(audio.src)
      return Promise.reject(new Error('dead stream'))
    }
    const signed = { ...detail(), chapters: [{ title: 'Розділ 1', streamUrl: 'https://audio.example/1.mp3?X-Amz-Signature=private' }] }

    await expect(engine.loadBookAndAwaitPlaying(signed, 0, 10_000)).resolves.toBe(false)

    expect(tried).toEqual(['https://audio.example/1.mp3?X-Amz-Signature=private'])
    expect(tried.every((url) => !url.includes('/api/audio'))).toBe(true)
    expect(engine.getState().status).toBe('unavailable')
  })
})

describe('#617 — a failed prepare keeps the last confirmed position', () => {
  const CONFIRMED: Partial<LocalListeningStateSnapshot> = { chapterIndex: 1, positionSeconds: 300 }

  it('a give-up (direct and relay both dead) restores the confirmed snapshot', async () => {
    const { engine, audio, store } = makeEngine(CONFIRMED)
    await engine.loadBook(detail(), 2, { forceChapter: true })

    audio.emit('error') // direct → relay
    audio.emit('error') // relay → honest give-up

    expect(engine.getState().status).toBe('unavailable')
    expect(store.load(EDITION)).toMatchObject(CONFIRMED)
  })

  it('a prepare that never reaches playing on time does not overwrite the confirmed snapshot', async () => {
    vi.useFakeTimers()
    const { engine, audio, store } = makeEngine(CONFIRMED)
    audio.playImpl = () => new Promise<void>(() => {})

    const result = engine.loadBookAndAwaitPlaying(detail(), 2, 5_000)
    await vi.advanceTimersByTimeAsync(5_000)

    await expect(result).resolves.toBe(false)
    expect(store.load(EDITION)).toMatchObject(CONFIRMED)
  })

  it('keeps a place confirmed DURING the attempt when that attempt later fails', async () => {
    const { engine, audio, store } = makeEngine(CONFIRMED)
    await engine.loadBook(detail(), 2, { forceChapter: true })
    audio.emit('playing') // this attempt really played
    engine.seek(42)
    engine.pause()
    expect(store.load(EDITION)).toMatchObject({ chapterIndex: 2, positionSeconds: 42 })

    audio.emit('error') // direct → relay
    audio.emit('error') // relay → honest give-up

    expect(engine.getState().status).toBe('unavailable')
    // The place reached with sound is NOT the pre-attempt baseline.
    expect(store.load(EDITION)).toMatchObject({ chapterIndex: 2, positionSeconds: 42 })
  })
})
