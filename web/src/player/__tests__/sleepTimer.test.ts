/**
 * W5.1 — the pure sleep timer policy, pinned (the AC's «чисте ядро»):
 * Android's option set, the exact remainder, the +15 extension, the
 * end-of-chapter rearm, the 30 s fade, and the collapse at zero.
 */
import { describe, expect, it } from 'vitest'
import {
  extendSleepTimer,
  isSleepTimerActive,
  rearmEndOfChapterTimer,
  setSleepTimer,
  SLEEP_TIMER_EXTEND_SECONDS,
  SLEEP_TIMER_FADE_SECONDS,
  SLEEP_TIMER_OFF,
  sleepTimerCountdown,
  sleepTimerFadeVolume,
  tickSleepTimer,
} from '../sleepTimer'

describe('setSleepTimer', () => {
  it('0 turns the timer off', () => {
    expect(setSleepTimer(0, 100)).toEqual(SLEEP_TIMER_OFF)
    expect(isSleepTimerActive(setSleepTimer(0, 100))).toBe(false)
  })

  it('a positive value arms an ordinary countdown of exactly minutes*60', () => {
    const state = setSleepTimer(15, 100)
    expect(state).toEqual({ minutes: 15, remainingSeconds: 900, isEndOfChapter: false })
    expect(isSleepTimerActive(state)).toBe(true)
  })

  it('every Android option arms its exact countdown', () => {
    for (const minutes of [5, 15, 30, 45, 60, 90]) {
      const state = setSleepTimer(minutes, 0)
      expect(state.remainingSeconds).toBe(minutes * 60)
      expect(state.minutes).toBe(minutes)
    }
  })

  it('end-of-chapter arms with the caller-provided chapter remainder', () => {
    const state = setSleepTimer(-1, 42)
    expect(state).toEqual({ minutes: -1, remainingSeconds: 42, isEndOfChapter: true })
  })

  it('end-of-chapter without a known remainder degrades to 1 second, never 0', () => {
    expect(setSleepTimer(-1, null).remainingSeconds).toBe(1)
    expect(setSleepTimer(-1, 0).remainingSeconds).toBe(1)
  })
})

describe('tickSleepTimer', () => {
  it('decrements the exact remainder per second', () => {
    const state = setSleepTimer(5, 0)
    expect(tickSleepTimer(state).remainingSeconds).toBe(299)
    expect(tickSleepTimer(state, 10).remainingSeconds).toBe(290)
  })

  it('collapses to off when the countdown reaches zero', () => {
    const almost = setSleepTimer(5, 0)
    const state = { ...almost, remainingSeconds: 2 }
    const fired = tickSleepTimer(state, 2)
    expect(fired).toEqual(SLEEP_TIMER_OFF)
    expect(isSleepTimerActive(fired)).toBe(false)
  })

  it('an off timer never changes', () => {
    expect(tickSleepTimer(SLEEP_TIMER_OFF)).toEqual(SLEEP_TIMER_OFF)
  })
})

describe('extendSleepTimer', () => {
  it('adds exactly +15 minutes preserving the exact current remainder', () => {
    const state = setSleepTimer(5, 0)
    const extended = { ...state, remainingSeconds: 42 }
    const out = extendSleepTimer(extended)
    expect(out.remainingSeconds).toBe(42 + SLEEP_TIMER_EXTEND_SECONDS)
  })

  it('an end-of-chapter timer becomes an ordinary timed remainder', () => {
    const state = setSleepTimer(-1, 120)
    const out = extendSleepTimer(state)
    expect(out.isEndOfChapter).toBe(false)
    expect(out.remainingSeconds).toBe(120 + SLEEP_TIMER_EXTEND_SECONDS)
  })

  it('an off timer is untouched', () => {
    expect(extendSleepTimer(SLEEP_TIMER_OFF)).toEqual(SLEEP_TIMER_OFF)
  })
})

describe('rearmEndOfChapterTimer', () => {
  it('re-arms an end-of-chapter timer at the next chapter remainder', () => {
    const state = setSleepTimer(-1, 10)
    const rearmed = rearmEndOfChapterTimer(state, 600)
    expect(rearmed.remainingSeconds).toBe(600)
    expect(rearmed.isEndOfChapter).toBe(true)
  })

  it('an ordinary countdown is untouched by a chapter change', () => {
    const state = setSleepTimer(30, 0)
    expect(rearmEndOfChapterTimer(state, 600).remainingSeconds).toBe(1800)
  })
})

describe('sleepTimerFadeVolume', () => {
  it('is 1.0 outside the fade window and 0 at zero', () => {
    expect(sleepTimerFadeVolume(SLEEP_TIMER_FADE_SECONDS)).toBe(1)
    expect(sleepTimerFadeVolume(SLEEP_TIMER_FADE_SECONDS + 1)).toBe(1)
    expect(sleepTimerFadeVolume(0)).toBe(0)
  })

  it('ramps linearly inside the last 30 seconds', () => {
    expect(sleepTimerFadeVolume(30)).toBe(1)
    expect(sleepTimerFadeVolume(15)).toBeCloseTo(0.5)
    expect(sleepTimerFadeVolume(1)).toBeCloseTo(1 / 30)
  })
})

describe('sleepTimerCountdown', () => {
  it('formats m:ss like Android`s %d:%02d', () => {
    expect(sleepTimerCountdown(0)).toBe('0:00')
    expect(sleepTimerCountdown(5)).toBe('0:05')
    expect(sleepTimerCountdown(65)).toBe('1:05')
    expect(sleepTimerCountdown(905)).toBe('15:05')
  })
})
