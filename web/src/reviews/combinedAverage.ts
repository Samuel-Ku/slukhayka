/**
 * W4.1 — port of app/src/main/java/com/slukhayka/audiobooks/data/reviews/CombinedAverage.kt
 * (spec-40 #279, ADR-0014 appendix): the honest headline of a book's
 * «Відгуки» block. One flat arithmetic mean over two vote pools — every
 * SOURCE that carries a rating contributes one vote (a null source rating =
 * absent: excluded, never fabricated as 0), and every LISTENER rating of
 * 1..5 contributes one vote. Zero addends → null: nobody rated the book, so
 * no stars are shown. Pure and deterministic — the Kotlin fixture rules pin
 * the same numbers.
 */
export interface CombinedAverageResult {
  /** The raw (unrounded) combined average. */
  value: number
  /** The real number of addends behind it. */
  count: number
}

export const MIN_LISTENER_RATING = 1
export const MAX_LISTENER_RATING = 5

export function combinedAverage(
  sourceRatings: ReadonlyArray<number | null | undefined>,
  listenerRatings: ReadonlyArray<number>,
): CombinedAverageResult | null {
  const addends: number[] = []
  for (const rating of sourceRatings) {
    if (rating !== null && rating !== undefined && Number.isFinite(rating)) addends.push(rating)
  }
  for (const rating of listenerRatings) {
    if (Number.isInteger(rating) && rating >= MIN_LISTENER_RATING && rating <= MAX_LISTENER_RATING) {
      addends.push(rating)
    }
  }
  if (addends.length === 0) return null
  return { value: addends.reduce((sum, rating) => sum + rating, 0) / addends.length, count: addends.length }
}