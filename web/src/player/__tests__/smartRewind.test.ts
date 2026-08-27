import { describe, expect, it } from 'vitest'
import {
  computeRewindSeconds,
  rewoundPositionMs,
  NO_REWIND_BELOW_MS,
  SHORT_PAUSE_MS,
  DAY_MS,
} from '../smartRewind'

/**
 * spec-43/T5 — the boundary matrix for the ONE ADR-0003 Smart Rewind rule,
 * in the decision-matrix style of Android's ResumeStartTest. Every tier
 * boundary and clamp from SmartRewind.kt is pinned here at its exact edge:
 * <2 s → nothing · <10 min → 3 s · <24 h → 12 s · else 25 s; positions
 * smaller than the rewind clamp to zero; a future-dated pause (clock skew)
 * rewinds nothing.
 */
describe('rewoundPositionMs — tier boundary matrix', () => {
  const cases: Array<{ name: string; positionSeconds: number; pausedForMs: number; expected: number }> = [
    // --- sub-second toggles rewind nothing -------------------------------
    { name: 'a quick play/pause double-tap (1.9 s) rewinds nothing', positionSeconds: 90, pausedForMs: 1_900, expected: 90 },
    { name: 'zero pause rewinds nothing', positionSeconds: 90, pausedForMs: 0, expected: 90 },
    // --- exact floor of the short tier -----------------------------------
    { name: `exactly ${NO_REWIND_BELOW_MS / 1000} s pause enters the short tier`, positionSeconds: 90, pausedForMs: NO_REWIND_BELOW_MS, expected: 87 },
    { name: 'one ms below the floor still rewinds nothing', positionSeconds: 90, pausedForMs: NO_REWIND_BELOW_MS - 1, expected: 90 },
    { name: 'a five-minute pause rewinds the short tier', positionSeconds: 600, pausedForMs: 5 * 60 * 1000, expected: 597 },
    { name: 'one ms below the 10-minute edge stays in the short tier', positionSeconds: 600, pausedForMs: SHORT_PAUSE_MS - 1, expected: 597 },
    // --- exact 10-minute edge → medium tier ------------------------------
    { name: `exactly ${SHORT_PAUSE_MS / 60_000} minutes enters the medium tier`, positionSeconds: 600, pausedForMs: SHORT_PAUSE_MS, expected: 588 },
    { name: 'an hour-long break rewinds the medium tier', positionSeconds: 100, pausedForMs: 60 * 60 * 1000, expected: 88 },
    { name: 'one ms below the day edge stays in the medium tier', positionSeconds: 100, pausedForMs: DAY_MS - 1, expected: 88 },
    // --- exact 24-hour edge → long tier ----------------------------------
    { name: `exactly ${DAY_MS / 3_600_000} h enters the long tier`, positionSeconds: 100, pausedForMs: DAY_MS, expected: 75 },
    { name: 'a month away rewinds the long tier', positionSeconds: 100, pausedForMs: 30 * DAY_MS, expected: 75 },
    // --- clamp-at-zero (ADR-0003 unified boundary) ------------------------
    { name: 'position smaller than the rewind clamps at zero', positionSeconds: 2, pausedForMs: 5 * 60 * 1000, expected: 0 },
    { name: 'position exactly equal to the rewind lands on zero', positionSeconds: 25, pausedForMs: 30 * DAY_MS, expected: 0 },
    { name: 'zero position stays zero under any pause', positionSeconds: 0, pausedForMs: 60 * 60 * 1000, expected: 0 },
    // --- future-dated pause marker (clock skew) rewinds nothing ----------
    { name: 'future-dated pause rewinds nothing', positionSeconds: 50, pausedForMs: -5_000, expected: 50 },
  ]

  it('pins every tier edge and the clamp semantics', () => {
    for (const c of cases) {
      expect(rewoundPositionMs(c.positionSeconds, c.pausedForMs), c.name).toBe(c.expected)
    }
  })

  it('never returns a negative position', () => {
    expect(rewoundPositionMs(0.5, 30 * DAY_MS)).toBe(0)
    expect(rewoundPositionMs(-4, 60 * 60 * 1000)).toBeGreaterThanOrEqual(0)
  })
})

describe('computeRewindSeconds — tier table', () => {
  it('mirrors the Kotlin tiers exactly', () => {
    expect(computeRewindSeconds(NO_REWIND_BELOW_MS - 1)).toBe(0)
    expect(computeRewindSeconds(NO_REWIND_BELOW_MS)).toBe(3)
    expect(computeRewindSeconds(SHORT_PAUSE_MS - 1)).toBe(3)
    expect(computeRewindSeconds(SHORT_PAUSE_MS)).toBe(12)
    expect(computeRewindSeconds(DAY_MS - 1)).toBe(12)
    expect(computeRewindSeconds(DAY_MS)).toBe(25)
    expect(computeRewindSeconds(Number.MAX_SAFE_INTEGER)).toBe(25)
  })
})
