/**
 * #614 (Web playback T2) — the ONE transition rule for Next, Previous and the
 * media adapter's natural `ended`, pinned through the external playback
 * interface (`AudioEngine`) over a controlled media adapter (FakeAudio) and an
 * injected Listening State store.
 *
 * The regression this ticket names: `nextChapter()`/`prevChapter()`/`onEnded()`
 * called `loadBook()` WITHOUT `forceChapter`, so the persisted Listening State
 * snapshot overrode the ±1 step — the listener pressed Next and got the Chapter
 * they were already on back. Every transition here is an explicit Chapter
 * intent, so the snapshot can never override it. Boundaries are defined, and
 * the explicit Chapter pick of #611 keeps working.
 */
import { afterEach, describe, expect, it } from 'vitest'
import { AudioEngine } from '../audioEngine'
import {
  LocalListeningStateStore,
  type LocalListeningStateSnapshot,
  type StorageLike,
} from '../localState'
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
  removeAttribute(name: string): void { if (name === 'src') this.src = '' }
}

const CHAPTERS: Chapter[] = [
  { title: 'Розділ 1', streamUrl: 'https://audio.example/1.mp3', durationSeconds: 600 },
  { title: 'Розділ 2', streamUrl: 'https://audio.example/2.mp3', durationSeconds: 300 },
  { title: 'Розділ 3', streamUrl: 'https://audio.example/3.mp3', durationSeconds: 120 },
]

/** The last Chapter reports no duration — the Source gave none. */
const OPEN_ENDED: Chapter[] = [
  CHAPTERS[0],
  { title: 'Розділ 2', streamUrl: 'https://audio.example/2.mp3' },
]

const EDITION = 'ed-transitions'

const detail = (chapters: Chapter[] = CHAPTERS) => ({
  title: 'Книга',
  chapters,
  editionId: EDITION,
  workId: 'Книга|Автор',
})

function makeEngine(saved?: Partial<LocalListeningStateSnapshot>): {
  engine: AudioEngine
  audio: FakeAudio
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
  const audio = new FakeAudio()
  engine.attachAudio(audio as unknown as HTMLAudioElement)
  return { engine, audio, store }
}

describe('#614 — Next steps to the NEXT Chapter, not back to the snapshot', () => {
  it('steps forward after a pause persisted the current Chapter', async () => {
    const { engine, audio, store } = makeEngine()
    await engine.loadBook(detail(), 0)
    engine.pause() // the honest save moment: Chapter 0 is persisted
    expect(store.load(EDITION)).toMatchObject({ chapterIndex: 0 })

    engine.nextChapter()

    expect(engine.getState()).toMatchObject({
      status: 'playing',
      chapterIndex: 1,
      positionSeconds: 0,
    })
    expect(audio.src).toBe(CHAPTERS[1].streamUrl)
    // The transition is what a later pause persists — not the Chapter left behind.
    engine.pause()
    expect(store.load(EDITION)).toMatchObject({ chapterIndex: 1, positionSeconds: 0 })
  })

  it('starts the new Chapter at zero even when the snapshot remembers a position in it', async () => {
    const { engine } = makeEngine({
      chapterIndex: 1,
      positionSeconds: 200,
      lastPausedAtEpochMs: Date.now() - 60 * 60 * 1000,
    })
    await engine.loadBook(detail(), 0, { forceChapter: true })

    engine.nextChapter()

    // Not 188 (the snapshot's 200 minus the hour-long Smart Rewind): the
    // transition is an explicit Chapter pick, so the saved place is not read.
    expect(engine.getState()).toMatchObject({ chapterIndex: 1, positionSeconds: 0 })
  })
})

describe('#614 — Previous steps to the PREVIOUS Chapter, not back to the snapshot', () => {
  it('steps backward after a pause persisted the current Chapter', async () => {
    const { engine, audio, store } = makeEngine()
    await engine.loadBook(detail(), 2, { forceChapter: true })
    engine.pause()
    expect(store.load(EDITION)).toMatchObject({ chapterIndex: 2 })

    engine.prevChapter()

    expect(engine.getState()).toMatchObject({
      status: 'playing',
      chapterIndex: 1,
      positionSeconds: 0,
    })
    expect(audio.src).toBe(CHAPTERS[1].streamUrl)
    engine.pause()
    expect(store.load(EDITION)).toMatchObject({ chapterIndex: 1, positionSeconds: 0 })
  })

  it('restarts the FIRST Chapter instead of leaving the Edition', async () => {
    const { engine, store } = makeEngine()
    await engine.loadBook(detail(), 0, { forceChapter: true })
    engine.seek(90)
    expect(store.load(EDITION)).toMatchObject({ chapterIndex: 0, positionSeconds: 90 })

    engine.prevChapter()

    expect(engine.getState()).toMatchObject({ chapterIndex: 0, positionSeconds: 0 })
    expect(store.load(EDITION)).toMatchObject({ chapterIndex: 0, positionSeconds: 0 })
  })
})

describe('#614 — the natural end of a Chapter', () => {
  it('advances a non-last Chapter exactly one step, from zero', async () => {
    // A persisted Listening State exists (the listener paused on Chapter 0
    // earlier): the end of Chapter 0 must not be answered with Chapter 0.
    const { engine, audio } = makeEngine({ chapterIndex: 0, positionSeconds: 0 })
    await engine.loadBook(detail(), 0)

    audio.dispatchEvent(new Event('ended'))

    expect(engine.getState()).toMatchObject({
      status: 'playing',
      chapterIndex: 1,
      positionSeconds: 0,
    })
    expect(audio.src).toBe(CHAPTERS[1].streamUrl)

    // The next `ended` is the NEXT Chapter's end: one step again, never a skip.
    audio.dispatchEvent(new Event('ended'))
    expect(engine.getState()).toMatchObject({ chapterIndex: 2, positionSeconds: 0 })
  })

  it('completes a last Chapter with a known duration and persists the completion', async () => {
    const { engine, audio, store } = makeEngine()
    await engine.loadBook(detail(), 2, { forceChapter: true })

    audio.dispatchEvent(new Event('ended'))

    expect(engine.getState()).toMatchObject({
      status: 'paused',
      chapterIndex: 2,
      positionSeconds: CHAPTERS[2].durationSeconds,
      isCompleted: true,
    })
    expect(store.load(EDITION)).toMatchObject({
      chapterIndex: 2,
      positionSeconds: CHAPTERS[2].durationSeconds,
      isCompleted: true,
    })
  })

  it('completes a last Chapter that never reported a duration', async () => {
    const { engine, audio, store } = makeEngine()
    await engine.loadBook(detail(OPEN_ENDED), 1, { forceChapter: true })

    audio.dispatchEvent(new Event('ended'))

    // No duration to park on — completion does not depend on metadata that
    // the Source never gave, and the position is left where it honestly was.
    expect(engine.getState()).toMatchObject({
      status: 'paused',
      chapterIndex: 1,
      positionSeconds: 0,
      isCompleted: true,
    })
    expect(store.load(EDITION)).toMatchObject({ chapterIndex: 1, isCompleted: true })
  })
})

describe('#614 — boundaries are defined, not accidental', () => {
  it('Next on the last Chapter leaves Chapter, position and state untouched', async () => {
    const { engine, audio, store } = makeEngine()
    await engine.loadBook(detail(), 2, { forceChapter: true })
    engine.seek(45)
    engine.pause()
    const before = engine.getState()
    const srcBefore = audio.src
    const savedBefore = store.load(EDITION)

    engine.nextChapter()

    expect(engine.getState()).toEqual(before)
    expect(audio.src).toBe(srcBefore)
    expect(store.load(EDITION)).toEqual(savedBefore)
  })
})

describe('#614 — Media Session runs the same transitions as the Player', () => {
  afterEach(() => restoreNavigator())

  it('nexttrack/previoustrack reach the same Chapter, position and state', async () => {
    const captured = installFakeMediaSession()
    const { engine, audio } = makeEngine()
    await engine.loadBook(detail(), 0)
    engine.pause()

    captured.handlers.nexttrack?.()
    expect(engine.getState()).toMatchObject({ chapterIndex: 1, positionSeconds: 0, status: 'playing' })
    expect(audio.src).toBe(CHAPTERS[1].streamUrl)

    captured.handlers.previoustrack?.()
    expect(engine.getState()).toMatchObject({ chapterIndex: 0, positionSeconds: 0, status: 'playing' })
    expect(audio.src).toBe(CHAPTERS[0].streamUrl)
  })
})

describe('#614 — the explicit Chapter pick of #611 is untouched', () => {
  it('still starts the chosen Chapter from zero despite a saved place', async () => {
    const { engine } = makeEngine({
      chapterIndex: 0,
      positionSeconds: 200,
      lastPausedAtEpochMs: Date.now() - 60 * 60 * 1000,
    })
    await engine.loadBook(detail(), 0)

    expect(engine.jumpTo(2, 0)).toBe(true)
    expect(engine.getState()).toMatchObject({ chapterIndex: 2, positionSeconds: 0 })
    expect(engine.jumpTo(9, 0)).toBe(false)
  })
})

type MediaAction = 'play' | 'pause' | 'previoustrack' | 'nexttrack'

interface CapturedSession {
  handlers: Partial<Record<MediaAction, () => void>>
}

function installFakeMediaSession(): CapturedSession {
  const captured: CapturedSession = { handlers: {} }
  Object.defineProperty(globalThis, 'navigator', {
    value: {
      mediaSession: {
        metadata: null,
        setActionHandler(action: string, handler: (() => void) | null): void {
          captured.handlers[action as MediaAction] = handler ?? undefined
        },
      },
    },
    configurable: true,
    writable: true,
  })
  return captured
}

function restoreNavigator(): void {
  Object.defineProperty(globalThis, 'navigator', {
    value: undefined,
    configurable: true,
    writable: true,
  })
}
