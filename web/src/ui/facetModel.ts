/**
 * W3.3 — the web port of Android's `WorkFacetFilter` contract
 * (`data/facets/WorkFacetFilter.kt`) + `EditionDurationPolicy` bucket
 * boundaries (`data/metadata/EditionDurationPolicy.kt`). Pure — one
 * decision, tested once (the ADR-0034 two-runtime rule).
 *
 * The contract, verbatim:
 *  - values WITHIN a dimension compose with OR;
 *  - dimensions compose with AND;
 *  - an EMPTY dimension is inactive — everything passes;
 *  - at most MAX_VALUES_PER_DIMENSION values per dimension;
 *  - the language dimension keeps Android's US17 neutrality: a Work is
 *    hidden only when it HAS known signals, none is selected, AND there is
 *    no unknown signal — unknown is never a reason to hide;
 *  - the duration dimension is strict: a Work with no REAL duration never
 *    matches a selected bucket (Android's `EXISTS edition_facets`).
 *
 * Honesty rule (ADR-0014): a bucket/dimension value is only ever derived
 * from real data — durations the source carried, languages the Editions
 * signal, genres the worker observed on a genre page.
 */

/** Android `FacetDurationBucket.wireName` values, in policy order. */
export type DurationBucket = 'under_5h' | '5h_to_10h' | '10h_to_20h' | '20h_plus'

export const DURATION_BUCKETS: readonly DurationBucket[] = [
  'under_5h',
  '5h_to_10h',
  '10h_to_20h',
  '20h_plus',
]

const FIVE_HOURS_SECONDS = 5 * 60 * 60
const TEN_HOURS_SECONDS = 10 * 60 * 60
const TWENTY_HOURS_SECONDS = 20 * 60 * 60

/** Android `EditionDurationPolicy.bucketFor`; implausible → null (never guessed). */
export function durationBucketFor(seconds: number | undefined): DurationBucket | null {
  if (seconds === undefined || !Number.isFinite(seconds) || seconds <= 0) return null
  if (seconds < FIVE_HOURS_SECONDS) return 'under_5h'
  if (seconds < TEN_HOURS_SECONDS) return '5h_to_10h'
  if (seconds < TWENTY_HOURS_SECONDS) return '10h_to_20h'
  return '20h_plus'
}

export const MAX_VALUES_PER_DIMENSION = 24

/** Frozen local query contract; dimensions compose with AND, values with OR. */
export interface WorkFacetFilter {
  /** Genre page URLs the listener selected (the worker's own genre pages). */
  readonly genreIds: ReadonlySet<string>
  /** Android `FacetDurationBucket.wireName` values. */
  readonly durationBucketIds: ReadonlySet<DurationBucket>
  /** BCP-47 content languages (US17: unknown signals never hide a Work). */
  readonly languages: ReadonlySet<string>
  /** Canonical author ids (web has no canonical author ledger yet — inert). */
  readonly authorIds: ReadonlySet<string>
}

export const EMPTY_FACET_FILTER: WorkFacetFilter = Object.freeze({
  genreIds: new Set<string>(),
  durationBucketIds: new Set<DurationBucket>(),
  languages: new Set<string>(),
  authorIds: new Set<string>(),
})

/** The `require` guard of Android's `init` block — a bug, not a runtime shrug. */
export function createFacetFilter(partial: {
  genreIds?: Iterable<string>
  durationBucketIds?: Iterable<DurationBucket>
  languages?: Iterable<string>
  authorIds?: Iterable<string>
} = {}): WorkFacetFilter {
  const dimensions: unknown[][] = [
    [...(partial.genreIds ?? [])],
    [...(partial.durationBucketIds ?? [])],
    [...(partial.languages ?? [])],
    [...(partial.authorIds ?? [])],
  ]
  for (const dimension of dimensions) {
    if (dimension.length > MAX_VALUES_PER_DIMENSION) {
      throw new Error(`WorkFacetFilter: a dimension exceeds ${MAX_VALUES_PER_DIMENSION} values`)
    }
  }
  return {
    genreIds: new Set(partial.genreIds ?? []),
    durationBucketIds: new Set(partial.durationBucketIds ?? []),
    languages: new Set(partial.languages ?? []),
    authorIds: new Set(partial.authorIds ?? []),
  }
}

/** The Work surface the matcher needs: real signals only (ADR-0014). */
export interface FacetWork {
  /** REAL durations the sources carried (unknown = absent). */
  readonly durations: readonly (number | undefined)[]
  /** Edition languages; '' / undefined = unknown signal (US17 neutral). */
  readonly languages: readonly (string | undefined)[]
  /** Genre page memberships the worker observed; absent = no claims. */
  readonly genres?: readonly string[]
  /** Canonical author id; absent = no claim (dimension inert). */
  readonly authorId?: string
}

/**
 * The one matcher (Android's SQL `WHERE` clause, ported): OR inside a
 * dimension, AND across, empty = inactive.
 */
export function workMatchesFacets(work: FacetWork, filter: WorkFacetFilter): boolean {
  if (filter.genreIds.size > 0) {
    const claimed = work.genres ?? []
    const matches = claimed.some((genre) => filter.genreIds.has(genre))
    if (!matches) return false
  }

  if (filter.durationBucketIds.size > 0) {
    // Strict (Android `EXISTS edition_facets … IN`): only REAL durations
    // count; a Work with no known duration never matches a selected bucket.
    const matches = work.durations.some(
      (seconds) => seconds !== undefined && durationBucketFor(seconds) !== null &&
        filter.durationBucketIds.has(durationBucketFor(seconds) as DurationBucket),
    )
    if (!matches) return false
  }

  if (filter.languages.size > 0) {
    // US17: hidden only when it HAS known signals, none selected, AND no
    // unknown signal either. An unknown (''/undefined/absent) signal is
    // neutral but never a reason to hide.
    const known = work.languages.filter((language) => language !== undefined && language !== '')
    const hasUnknown = known.length !== work.languages.length
    const matches = hasUnknown || known.some((language) => filter.languages.has(language as string))
    if (!matches) return false
  }

  if (filter.authorIds.size > 0) {
    const matches = work.authorId !== undefined && filter.authorIds.has(work.authorId)
    if (!matches) return false
  }

  return true
}