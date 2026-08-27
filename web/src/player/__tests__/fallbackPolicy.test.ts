import { describe, expect, it } from 'vitest'
import { decideNext, type Attempt } from '../fallbackPolicy'

/**
 * spec-43/T5 — the attempt matrix for the ADR-0019-spirit fallback policy:
 * direct first, ONE relay retry per user-initiated prepare, honest give-up
 * when the budget is spent, `playing` resets nothing. Includes a bounded
 * brute-force property over every 6-event sequence proving the machine can
 * never loop.
 */

const DIRECT_URL = 'https://source.example/book/ch1.mp3'
const RELAY_URL = 'https://relay.example/stream?url=ch1.mp3'
const relayUrlOf = (url: string) => (url === DIRECT_URL ? RELAY_URL : '')
const NO_ROUTE = () => ''

function expectDirect(result: ReturnType<typeof decideNext>, url: string): Attempt {
  expect('giveUp' in result).toBe(false)
  if ('giveUp' in result || result.next === null) throw new Error('expected an attempt')
  expect(result.next.kind).toBe('direct')
  expect(result.next.url).toBe(url)
  return result.next
}

function expectRelay(result: ReturnType<typeof decideNext>, url: string): Attempt {
  if ('giveUp' in result || result.next === null) throw new Error('expected an attempt')
  expect(result.next.kind).toBe('relay')
  expect(result.next.url).toBe(url)
  return result.next
}

describe('fallbackPolicy — decision matrix', () => {
  it('a user-initiated prepare starts DIRECT at the fresh URL', () => {
    const result = decideNext(null, { type: 'started' }, DIRECT_URL, relayUrlOf)
    expectDirect(result, DIRECT_URL)
  })

  it('a prepare resets any stale chain to a fresh DIRECT attempt', () => {
    const staleRelay: Attempt = { kind: 'relay', url: 'https://stale.example/old' }
    const result = decideNext(staleRelay, { type: 'started' }, DIRECT_URL, relayUrlOf)
    expectDirect(result, DIRECT_URL)
  })

  it('a direct error switches to the relay of the FRESH direct URL — once', () => {
    const direct = expectDirect(decideNext(null, { type: 'started' }, DIRECT_URL, relayUrlOf), DIRECT_URL)
    expectRelay(decideNext(direct, { type: 'error' }, DIRECT_URL, relayUrlOf), RELAY_URL)
  })

  it('a second error — on the relay attempt — gives up honestly', () => {
    const direct = { kind: 'direct', url: DIRECT_URL } as Attempt
    const relay = expectRelay(decideNext(direct, { type: 'error' }, DIRECT_URL, relayUrlOf), RELAY_URL)
    const result = decideNext(relay, { type: 'error' }, DIRECT_URL, relayUrlOf)
    expect(result).toEqual({ giveUp: true })
  })

  it('an error with no current attempt gives up (nothing running to heal)', () => {
    expect(decideNext(null, { type: 'error' }, DIRECT_URL, relayUrlOf)).toEqual({ giveUp: true })
  })

  it('no relay route for the source → the direct failure gives up immediately', () => {
    const direct = { kind: 'direct', url: DIRECT_URL } as Attempt
    expect(decideNext(direct, { type: 'error' }, DIRECT_URL, NO_ROUTE)).toEqual({ giveUp: true })
  })

  it('playing resets nothing — a playing direct still spends its budget on error', () => {
    const started = expectDirect(decideNext(null, { type: 'started' }, DIRECT_URL, relayUrlOf), DIRECT_URL)
    const afterPlaying = decideNext(started, { type: 'playing' }, DIRECT_URL, relayUrlOf)
    const stillDirect = expectDirect(afterPlaying, DIRECT_URL)
    expectRelay(decideNext(stillDirect, { type: 'error' }, DIRECT_URL, relayUrlOf), RELAY_URL)
  })

  it('playing passes a relay attempt through unchanged', () => {
    const relay: Attempt = { kind: 'relay', url: RELAY_URL }
    const result = decideNext(relay, { type: 'playing' }, DIRECT_URL, relayUrlOf)
    expectRelay(result, RELAY_URL)
  })

  it('playing with no current attempt stays null', () => {
    const result = decideNext(null, { type: 'playing' }, DIRECT_URL, relayUrlOf)
    expect('giveUp' in result).toBe(false)
    if (!('giveUp' in result)) expect(result.next).toBeNull()
  })
})

describe('fallbackPolicy — no-infinite-loop property', () => {
  const events = [
    { type: 'started' },
    { type: 'error' },
    { type: 'playing' },
  ] as const

  it('every event sequence stays inside the ADR-0019 budget and always terminates honestly', () => {
    let sequencesChecked = 0
    let sequencesWithGiveUp = 0
    // brute force over ALL sequences of length 6 from {started, error, playing}
    for (let mask = 0; mask < 3 ** 6; mask++) {
      let code = mask
      let current: Attempt | null = null
      let startedCount = 0
      let relayCount = 0
      let errorsSinceStart = 0
      let gaveUp = false
      for (let step = 0; step < 6; step++) {
        const event = events[code % 3]
        code = Math.floor(code / 3)
        const before: Attempt | null = current
        const result = decideNext(current, event, DIRECT_URL, relayUrlOf)
        if (event.type === 'started') {
          startedCount++
          errorsSinceStart = 0
        }
        if (event.type === 'error') errorsSinceStart++
        if ('giveUp' in result) {
          gaveUp = true
          break
        }
        current = result.next
        // a 'playing' pass-through hands back the SAME attempt — never counted
        if (current !== null && current !== before && current.kind === 'relay') relayCount++
      }
      sequencesChecked++
      if (gaveUp) sequencesWithGiveUp++
      expect(
        relayCount,
        `sequence #${mask} retried the relay more than once per prepare`
      ).toBeLessThanOrEqual(startedCount)
      expect(
        errorsSinceStart,
        `sequence #${mask} survived more than two errors without giving up`
      ).toBeLessThanOrEqual(2)
    }
    expect(sequencesChecked).toBe(729)
    expect(sequencesWithGiveUp).toBeGreaterThan(0)
  })
})
