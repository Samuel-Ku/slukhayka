import { describe, expect, it } from 'vitest'
import {
  createFacetFilter,
  durationBucketFor,
  DURATION_BUCKETS,
  EMPTY_FACET_FILTER,
  MAX_VALUES_PER_DIMENSION,
  workMatchesFacets,
  type FacetWork,
} from './facetModel'

const work: FacetWork = {
  durations: [4 * 3600 + 59 * 60, 8 * 3600, 21 * 3600],
  languages: ['uk', 'en'],
  genres: ['https://4read.org/kazka/', 'https://4read.org/fentezi/'],
}

describe('duration buckets (EditionDurationPolicy port)', () => {
  it('places real durations at the exact Android boundaries', () => {
    expect(durationBucketFor(59 * 60)).toBe('under_5h')
    expect(durationBucketFor(5 * 3600 - 1)).toBe('under_5h')
    expect(durationBucketFor(5 * 3600)).toBe('5h_to_10h')
    expect(durationBucketFor(10 * 3600 - 1)).toBe('5h_to_10h')
    expect(durationBucketFor(10 * 3600)).toBe('10h_to_20h')
    expect(durationBucketFor(20 * 3600 - 1)).toBe('10h_to_20h')
    expect(durationBucketFor(20 * 3600)).toBe('20h_plus')
    expect(durationBucketFor(100 * 3600)).toBe('20h_plus')
  })

  it('never buckets an unknown or implausible duration', () => {
    expect(durationBucketFor(undefined)).toBeNull()
    expect(durationBucketFor(0)).toBeNull()
    expect(durationBucketFor(-5)).toBeNull()
    expect(durationBucketFor(Number.NaN)).toBeNull()
    expect(durationBucketFor(Number.POSITIVE_INFINITY)).toBeNull()
  })

  it('keeps Android bucket order and wire names', () => {
    expect(DURATION_BUCKETS).toEqual(['under_5h', '5h_to_10h', '10h_to_20h', '20h_plus'])
  })
})

describe('the OR/AND/empty contract (WorkFacetFilter port)', () => {
  it('an empty filter passes everything', () => {
    expect(workMatchesFacets(work, EMPTY_FACET_FILTER)).toBe(true)
    expect(workMatchesFacets({ durations: [], languages: [] }, EMPTY_FACET_FILTER)).toBe(true)
  })

  it('values inside one dimension compose with OR', () => {
    const filter = createFacetFilter({ genreIds: ['https://4read.org/dytlit/', 'https://4read.org/kazka/'] })
    expect(workMatchesFacets(work, filter)).toBe(true)

    const durations = createFacetFilter({ durationBucketIds: ['under_5h', '20h_plus'] })
    expect(workMatchesFacets(work, durations)).toBe(true)
  })

  it('dimensions compose with AND', () => {
    const both = createFacetFilter({
      genreIds: ['https://4read.org/kazka/'],
      durationBucketIds: ['5h_to_10h'],
    })
    expect(workMatchesFacets(work, both)).toBe(true)
    // The fixture work's real durations cover under_5h / 5h_to_10h /
    // 20h_plus — 10h_to_20h is the bucket it genuinely lacks.
    const none = createFacetFilter({
      genreIds: ['https://4read.org/kazka/'],
      durationBucketIds: ['10h_to_20h'],
    })
    expect(workMatchesFacets(work, none)).toBe(false)
  })

  it('a Work with no genre claims never matches a genre selection', () => {
    const filter = createFacetFilter({ genreIds: ['https://4read.org/kazka/'] })
    expect(workMatchesFacets({ durations: [], languages: [] }, filter)).toBe(false)
  })

  it('a Work with no real duration never matches a duration selection', () => {
    const filter = createFacetFilter({ durationBucketIds: ['under_5h'] })
    expect(workMatchesFacets({ durations: [undefined], languages: [] }, filter)).toBe(false)
    expect(workMatchesFacets({ durations: [0], languages: [] }, filter)).toBe(false)
  })

  it('the author dimension follows the same contract and stays inert when empty', () => {
    const filter = createFacetFilter({ authorIds: ['unknown-author'] })
    expect(workMatchesFacets(work, filter)).toBe(false)
    expect(workMatchesFacets({ durations: [], languages: [], authorId: 'unknown-author' }, filter)).toBe(true)
    expect(workMatchesFacets(work, EMPTY_FACET_FILTER)).toBe(true)
  })

  it('guards the 24-values-per-dimension limit (Android require)', () => {
    const tooMany = Array.from({ length: MAX_VALUES_PER_DIMENSION + 1 }, (_, i) => `g${i}`)
    expect(() => createFacetFilter({ genreIds: tooMany })).toThrow(/24/)
    // Exactly the limit is fine.
    const atLimit = Array.from({ length: MAX_VALUES_PER_DIMENSION }, (_, i) => `g${i}`)
    expect(() => createFacetFilter({ genreIds: atLimit })).not.toThrow()
  })
})

describe('language dimension (US17 neutrality)', () => {
  it('hides only when known signals exist, none selected, no unknown signal', () => {
    const ukOnly = createFacetFilter({ languages: ['uk'] })
    expect(workMatchesFacets({ durations: [], languages: ['uk', 'en'] }, ukOnly)).toBe(true)
    expect(workMatchesFacets({ durations: [], languages: ['en'] }, ukOnly)).toBe(false)
    // Unknown signals ('', undefined) are neutral — never a reason to hide
    // (the web's filterWorksByLanguage rule and Android's SQL agree).
    expect(workMatchesFacets({ durations: [], languages: ['en', ''] }, ukOnly)).toBe(true)
    expect(workMatchesFacets({ durations: [], languages: [undefined] }, ukOnly)).toBe(true)
    // A Work with NO language signal at all is hidden under a selection
    // (Android's `EXISTS` semantics — an unclaimed Work matches nothing).
    expect(workMatchesFacets({ durations: [], languages: [] }, ukOnly)).toBe(false)
  })
})