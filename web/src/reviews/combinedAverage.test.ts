/**
 * W4.1 — the CombinedAverage port, pinned to the Kotlin fixture rules
 * (spec-40 #279, ADR-0014): one flat mean over source + listener votes,
 * null when nobody rated, hostile ratings never poison the number.
 */
import { describe, expect, it } from 'vitest'
import { combinedAverage } from './combinedAverage'

describe('combinedAverage', () => {
  it('averages every non-null source rating and every valid listener rating', () => {
    expect(combinedAverage([4.5, null, 3.0], [5, 4])).toEqual({ value: (4.5 + 3 + 5 + 4) / 4, count: 4 })
    expect(combinedAverage([null], [5])).toEqual({ value: 5, count: 1 })
    expect(combinedAverage([4.0], [])).toEqual({ value: 4, count: 1 })
  })

  it('returns null when nobody rated — a fabricated zero is a lie', () => {
    expect(combinedAverage([], [])).toBeNull()
    expect(combinedAverage([null, undefined], [])).toBeNull()
  })

  it('ignores listener ratings outside 1..5 defensively', () => {
    expect(combinedAverage([], [0, 6, 3.5, 2])).toEqual({ value: 2, count: 1 })
    expect(combinedAverage([], [-1, 7])).toBeNull()
  })

  it('ignores non-finite source ratings', () => {
    expect(combinedAverage([Number.NaN, 4], [])).toEqual({ value: 4, count: 1 })
  })
})