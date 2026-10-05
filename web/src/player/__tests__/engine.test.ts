import { describe, expect, it } from 'vitest'
import { PlaybackEngine, type EngineState } from '../engine'
import type { Chapter } from '../../worker/types'

/**
 * The same 18 transport, completion, rewind, fallback and subscription
 * guarantees, with actual media observation replacing wall-time progression.
 * Source changes and natural end are pinned at AudioEngine's media boundary.
 */

class FakeClock {
  private now = 1_000_000
  nowMs(): number {
    return this.now
  }
  advanceBy(ms: number): void {
    this.now += ms
  }
}

const chapters: Chapter[] = [
  { title: 'Розділ 1', streamUrl: 'https://src.example/ch1.mp3', durationSeconds: 100 },
  { title: 'Розділ 2', streamUrl: 'https://src.example/ch2.mp3', durationSeconds: 80 },
  { title: 'Розділ 3', streamUrl: 'https://src.example/ch3.mp3', durationSeconds: 60 },
]

const relayOf = (url: string) => `https://relay.example/?url=${encodeURIComponent(url)}`

function makeEngine(clock: FakeClock): PlaybackEngine {
  return new PlaybackEngine({ clock, relayUrlOf: relayOf })
}

describe('PlaybackEngine — load and basic transport', () => {
  it('loads into loading at the requested chapter and position', () => {
    const engine = makeEngine(new FakeClock())
    engine.load(chapters, { startChapter: 2, startPositionSeconds: 30, editionId: 'ed-1' })
    expect(engine.getState()).toMatchObject({
      status: 'loading',
      editionId: 'ed-1',
      chapterIndex: 2,
      positionSeconds: 30,
      attemptKind: undefined,
      isCompleted: false,
    })
  })

  it('ignores an empty chapter list and stays idle', () => {
    const engine = makeEngine(new FakeClock())
    engine.load([], {})
    expect(engine.getState().status).toBe('idle')
    engine.play()
    expect(engine.getState().status).toBe('idle')
  })

  it('play begins a direct attempt; pause parks with a pause marker', () => {
    const clock = new FakeClock()
    const engine = makeEngine(clock)
    engine.load(chapters, { startPositionSeconds: 10 })
    engine.play()
    expect(engine.getState()).toMatchObject({ status: 'playing', attemptKind: 'direct' })
    engine.pause()
    expect(engine.getState()).toMatchObject({ status: 'paused', positionSeconds: 10 })
  })

  it('observes actual media position independently of speed and transport status', () => {
    const engine = makeEngine(new FakeClock())
    engine.load(chapters, { startPositionSeconds: 0 })
    engine.play()
    engine.observePosition(2)
    expect(engine.getState().positionSeconds).toBe(2)
    engine.setSpeed(2)
    engine.observePosition(4)
    expect(engine.getState().positionSeconds).toBe(4)
    engine.pause()
    engine.observePosition(4.5) // last media update may arrive after pause
    expect(engine.getState()).toMatchObject({ status: 'paused', positionSeconds: 4.5 })
    engine.observePosition(Number.NaN)
    engine.observePosition(Number.POSITIVE_INFINITY)
    engine.observePosition(-1)
    expect(engine.getState().positionSeconds).toBe(4.5)
  })

  it('setSpeed rejects non-positive and non-finite values', () => {
    const engine = makeEngine(new FakeClock())
    engine.load(chapters, {})
    engine.setSpeed(0)
    engine.setSpeed(-1)
    engine.setSpeed(Number.NaN)
    expect(engine.getState().speed).toBe(1)
    engine.setSpeed(1.5)
    expect(engine.getState().speed).toBe(1.5)
  })

  it('seek clamps to the chapter duration and clears any pending rewind', () => {
    const clock = new FakeClock()
    const engine = makeEngine(clock)
    engine.load(chapters, { startPositionSeconds: 90 })
    engine.play()
    engine.pause()
    clock.advanceBy(60 * 60 * 1000) // an hour away → 12 s rewind pending
    engine.seek(500)
    engine.play()
    expect(engine.getState().positionSeconds).toBe(100)
  })
})

describe('PlaybackEngine — media position and explicit completion', () => {
  it('never advances by observing a position beyond the catalog duration', () => {
    const states: EngineState[] = []
    const engine = makeEngine(new FakeClock())
    engine.subscribe((s) => states.push(s))
    engine.load(chapters, { startPositionSeconds: 99.5 })
    engine.play()
    engine.observePosition(100.5) // catalog end is not a natural media end
    expect(engine.getState()).toMatchObject({ status: 'playing', chapterIndex: 0, positionSeconds: 100.5, attemptKind: 'direct' })
    expect(states.length).toBeGreaterThan(0)
  })

  it('parks paused at the last chapter end with isCompleted=true in the payload', () => {
    const states: EngineState[] = []
    const engine = makeEngine(new FakeClock())
    engine.subscribe((s) => states.push(s))
    engine.load(chapters, { startChapter: 2, startPositionSeconds: 59.9 })
    engine.play()
    engine.observePosition(60)
    engine.markCompleted()
    expect(engine.getState()).toEqual({
      status: 'paused',
      editionId: undefined,
      chapterIndex: 2,
      positionSeconds: 60,
      speed: 1,
      attemptKind: undefined,
      isCompleted: true,
    })
    expect(states.at(-1)?.isCompleted).toBe(true)
  })

  it('a chapter without a known duration never fabricates an end', () => {
    const openEnded: Chapter[] = [{ title: 'Глава', streamUrl: 'u1' }]
    const engine = makeEngine(new FakeClock())
    engine.load(openEnded, {})
    engine.play()
    engine.observePosition(36_000)
    expect(engine.getState()).toMatchObject({ status: 'playing', chapterIndex: 0, positionSeconds: 36_000, isCompleted: false })
  })
})

describe('PlaybackEngine — in-session Smart Rewind (ADR-0003)', () => {
  it('resuming after an hour-long pause rewinds the 12 s medium tier', () => {
    const clock = new FakeClock()
    const engine = makeEngine(clock)
    engine.load(chapters, { startPositionSeconds: 90 })
    engine.play()
    engine.pause()
    clock.advanceBy(60 * 60 * 1000)
    engine.play()
    expect(engine.getState().positionSeconds).toBe(78)
  })

  it('a quick pause rewinds nothing', () => {
    const clock = new FakeClock()
    const engine = makeEngine(clock)
    engine.load(chapters, { startPositionSeconds: 90 })
    engine.play()
    engine.pause()
    clock.advanceBy(1500)
    engine.play()
    expect(engine.getState().positionSeconds).toBe(90)
  })

  it('an overnight pause rewinds the 25 s long tier, clamping at zero', () => {
    const clock = new FakeClock()
    const engine = makeEngine(clock)
    engine.load(chapters, { startPositionSeconds: 10 })
    engine.play()
    engine.pause()
    clock.advanceBy(26 * 60 * 60 * 1000)
    engine.play()
    expect(engine.getState().positionSeconds).toBe(0)
  })

  it('one pause never rewinds twice', () => {
    const clock = new FakeClock()
    const engine = makeEngine(clock)
    engine.load(chapters, { startPositionSeconds: 90 })
    engine.play()
    engine.pause()
    clock.advanceBy(60 * 60 * 1000)
    engine.play()
    engine.pause()
    engine.play()
    expect(engine.getState().positionSeconds).toBe(78)
  })
})

describe('PlaybackEngine — fallback attempts', () => {
  it('a direct error switches the exposed attemptKind to relay', () => {
    const engine = makeEngine(new FakeClock())
    engine.load(chapters, { editionId: 'ed-9' })
    engine.play()
    engine.attemptErrored()
    expect(engine.getState()).toMatchObject({ status: 'playing', attemptKind: 'relay' })
  })

  it('a second error surfaces the honest unavailable state', () => {
    const engine = makeEngine(new FakeClock())
    engine.load(chapters, {})
    engine.play()
    engine.attemptErrored()
    engine.attemptErrored()
    expect(engine.getState()).toMatchObject({ status: 'unavailable', attemptKind: undefined })
  })

  it('with no relay route configured the first error is already honest unavailability', () => {
    const engine = new PlaybackEngine({ clock: new FakeClock() })
    engine.load(chapters, {})
    engine.play()
    engine.attemptErrored()
    expect(engine.getState().status).toBe('unavailable')
  })

  it('pressing play again after unavailability begins a fresh prepare', () => {
    const engine = makeEngine(new FakeClock())
    engine.load(chapters, {})
    engine.play()
    engine.attemptErrored()
    engine.attemptErrored() // budget spent → unavailable
    engine.play()
    expect(engine.getState()).toMatchObject({ status: 'playing', attemptKind: 'direct' })
  })
})

describe('PlaybackEngine — subscription', () => {
  it('publishes snapshots and stops after unsubscribe', () => {
    const engine = makeEngine(new FakeClock())
    const seen: EngineState[] = []
    const unsubscribe = engine.subscribe((s) => seen.push(s))
    engine.load(chapters, { startPositionSeconds: 5 })
    engine.play()
    unsubscribe()
    engine.pause()
    expect(seen.map((s) => s.status)).toEqual(['loading', 'playing'])
    expect(engine.getState().status).toBe('paused')
  })
})
