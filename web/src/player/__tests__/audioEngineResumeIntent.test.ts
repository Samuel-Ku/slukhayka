/**
 * #611 (Web playback T1) — the two Play intents, pinned through the external
 * playback interface (`AudioEngine`) over a controlled media adapter
 * (FakeAudio) and an injected Listening State store:
 *
 * - resume (a card's Play) continues the saved Chapter and position, with
 *   Smart Rewind applied exactly once from the persisted pause marker;
 * - a completed Edition plays again from its first Chapter;
 * - an explicit Chapter pick starts THAT Chapter from zero (the first one
 *   included) and never reads the saved place — the bug this ticket names,
 *   because the card and the first Chapter both pass index 0;
 * - an empty Edition or an explicit Chapter outside the list is refused
 *   honestly, leaving the active audio untouched.
 */
import { describe, expect, it } from 'vitest'
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
}

const CHAPTERS: Chapter[] = [
  { title: 'Розділ 1', streamUrl: 'https://audio.example/1.mp3', durationSeconds: 600 },
  { title: 'Розділ 2', streamUrl: 'https://audio.example/2.mp3', durationSeconds: 300 },
  { title: 'Розділ 3', streamUrl: 'https://audio.example/3.mp3', durationSeconds: 120 },
]

const EDITION = 'ed-resume'

const detail = () => ({ title: 'Книга', chapters: CHAPTERS, editionId: EDITION, workId: 'Книга|Автор' })

function makeEngine(saved?: Partial<LocalListeningStateSnapshot>): {
  engine: AudioEngine
  audio: FakeAudio
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
  return { engine, audio }
}

const AN_HOUR_AGO = () => Date.now() - 60 * 60 * 1000

describe('#611 — resume continues the Edition Listening State', () => {
  it('restores the saved Chapter and position with Smart Rewind exactly once', async () => {
    const { engine } = makeEngine({
      chapterIndex: 2,
      positionSeconds: 100,
      preferredSpeed: 1.25,
      lastPausedAtEpochMs: AN_HOUR_AGO(),
    })

    await expect(engine.loadBook(detail(), 0)).resolves.toBe(true)

    // 1 h pause → the 12 s medium tier, applied once (100 − 12), not twice.
    expect(engine.getState()).toMatchObject({
      status: 'playing',
      chapterIndex: 2,
      positionSeconds: 88,
      speed: 1.25,
      isCompleted: false,
    })
    // A second play() on an already-playing engine must not rewind again.
    engine.play()
    expect(engine.getState().positionSeconds).toBe(88)
  })

  it('starts the first Chapter when there is no history', async () => {
    const { engine } = makeEngine()
    await expect(engine.loadBook(detail(), 0)).resolves.toBe(true)
    expect(engine.getState()).toMatchObject({ status: 'playing', chapterIndex: 0, positionSeconds: 0 })
  })

  it('plays a completed Edition again from its first Chapter, keeping the speed', async () => {
    const { engine } = makeEngine({
      chapterIndex: 2,
      positionSeconds: 120,
      isCompleted: true,
      preferredSpeed: 1.5,
      lastPausedAtEpochMs: AN_HOUR_AGO(),
    })

    await expect(engine.loadBook(detail(), 0)).resolves.toBe(true)

    expect(engine.getState()).toMatchObject({
      status: 'playing',
      chapterIndex: 0,
      positionSeconds: 0,
      speed: 1.5,
      isCompleted: false,
    })
  })
})

describe('#611 — an explicit Chapter pick starts from zero', () => {
  it('ignores the saved place for a non-first Chapter', async () => {
    const { engine, audio } = makeEngine({
      chapterIndex: 0,
      positionSeconds: 200,
      lastPausedAtEpochMs: AN_HOUR_AGO(),
    })

    await expect(engine.loadBook(detail(), 1, { forceChapter: true })).resolves.toBe(true)

    expect(engine.getState()).toMatchObject({ status: 'playing', chapterIndex: 1, positionSeconds: 0 })
    expect(audio.src).toBe(CHAPTERS[1].streamUrl)
  })

  it('starts the FIRST Chapter from zero even though it is the card Play index', async () => {
    const { engine } = makeEngine({
      chapterIndex: 1,
      positionSeconds: 200,
      lastPausedAtEpochMs: AN_HOUR_AGO(),
    })

    await expect(engine.loadBook(detail(), 0, { forceChapter: true })).resolves.toBe(true)

    expect(engine.getState()).toMatchObject({ chapterIndex: 0, positionSeconds: 0 })
  })

  it('honours an explicit position (a bookmark) without Smart Rewind', async () => {
    const { engine } = makeEngine({
      chapterIndex: 0,
      positionSeconds: 200,
      lastPausedAtEpochMs: AN_HOUR_AGO(),
    })

    await engine.loadBook(detail(), 1, { forceChapter: true, startPositionSeconds: 42 })

    expect(engine.getState()).toMatchObject({ chapterIndex: 1, positionSeconds: 42 })
  })

  it('jumpTo starts the chosen Chapter from zero and refuses an out-of-range index', async () => {
    const { engine } = makeEngine({ chapterIndex: 0, positionSeconds: 200 })
    await engine.loadBook(detail(), 1, { forceChapter: true })

    expect(engine.jumpTo(2, 0)).toBe(true)
    expect(engine.getState()).toMatchObject({ chapterIndex: 2, positionSeconds: 0 })

    // Out of range: honest refusal, the current Chapter is left alone.
    expect(engine.jumpTo(9, 0)).toBe(false)
    expect(engine.jumpTo(-1, 0)).toBe(false)
    expect(engine.jumpTo(1.5, 0)).toBe(false)
    expect(engine.getState()).toMatchObject({ chapterIndex: 2, positionSeconds: 0 })
  })

  it('keeps a valid speed across an explicit Chapter transition and a seek', async () => {
    const { engine } = makeEngine({ chapterIndex: 0, positionSeconds: 90, lastPausedAtEpochMs: AN_HOUR_AGO() })

    engine.setSpeed(1.5)
    await engine.loadBook(detail(), 1, { forceChapter: true })
    expect(engine.getState()).toMatchObject({ chapterIndex: 1, positionSeconds: 0, speed: 1.5 })

    engine.seek(30)
    expect(engine.getState()).toMatchObject({ positionSeconds: 30, speed: 1.5 })
  })
})

describe('#611 — honest refusals never replace the active audio', () => {
  it('refuses an empty Edition and keeps the loaded Chapter playing', async () => {
    const { engine, audio } = makeEngine()
    await engine.loadBook(detail(), 1, { forceChapter: true })
    engine.pause()
    const srcBefore = audio.src

    await expect(engine.loadBook({ title: 'Порожня', chapters: [] }, 0)).resolves.toBe(false)

    expect(engine.getState()).toMatchObject({ status: 'paused', chapterIndex: 1, editionId: EDITION })
    expect(engine.chaptersOf()).toBe(CHAPTERS)
    expect(audio.src).toBe(srcBefore)
  })

  it('refuses an explicit Chapter outside the list and keeps the loaded Chapter', async () => {
    const { engine, audio } = makeEngine()
    await engine.loadBook(detail(), 1, { forceChapter: true })
    engine.pause()
    const srcBefore = audio.src

    await expect(engine.loadBook(detail(), 9, { forceChapter: true })).resolves.toBe(false)
    await expect(engine.loadBook(detail(), -1, { forceChapter: true })).resolves.toBe(false)

    expect(engine.getState()).toMatchObject({ status: 'paused', chapterIndex: 1 })
    expect(engine.chaptersOf()).toBe(CHAPTERS)
    expect(audio.src).toBe(srcBefore)
  })

  it('resolves loadBookAndAwaitPlaying(false) at once for an empty Edition', async () => {
    const { engine } = makeEngine()
    await engine.loadBook(detail(), 0, { forceChapter: true })
    engine.pause()

    let settled = false
    const result = engine
      .loadBookAndAwaitPlaying({ title: 'Порожня', chapters: [] }, 0, 10_000)
      .then((playing) => {
        settled = true
        return playing
      })
    await Promise.resolve()
    await Promise.resolve()

    expect(settled).toBe(true)
    await expect(result).resolves.toBe(false)
    expect(engine.getState().status).toBe('paused')
  })
})
