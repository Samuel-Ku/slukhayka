/**
 * W4.1 — port of app/src/main/java/com/slukhayka/audiobooks/data/reviews/NarrationRating.kt
 * (ADR-0023, #348): one listener's stars-only verdict (1..5) on ONE Edition
 * («Оцінка начитки»). The document lives in Firestore's `edition_ratings`
 * collection under the deterministic key `${workId}_${uid}_${editionId}` —
 * one rating per listener per Edition, idempotently replaced on edit. Pure
 * and fail-closed, like `reviewModel.ts` — the Kotlin fixture tests pin the
 * same document shape, so web ratings merge with Android's pool.
 */
export interface NarrationRating {
  workId: string
  uid: string
  editionId: string
  rating: number
  createdAt: number
  editedAt?: number
}

export const NarrationRatingLimits = {
  MIN_RATING: 1,
  MAX_RATING: 5,
  MAX_WORK_ID_LEN: 300,
  MAX_EDITION_ID_LEN: 200,
} as const

export function isValidNarrationRating(rating: number): boolean {
  return (
    Number.isInteger(rating) &&
    rating >= NarrationRatingLimits.MIN_RATING &&
    rating <= NarrationRatingLimits.MAX_RATING
  )
}

/** The write gate: real identity fields and an in-range rating. */
export function isWritableNarrationRating(rating: NarrationRating): boolean {
  return (
    rating.workId.trim() !== '' &&
    rating.workId.length <= NarrationRatingLimits.MAX_WORK_ID_LEN &&
    rating.uid.trim() !== '' &&
    rating.editionId.trim() !== '' &&
    rating.editionId.length <= NarrationRatingLimits.MAX_EDITION_ID_LEN &&
    isValidNarrationRating(rating.rating)
  )
}

/** The deterministic document key: one rating per (Work × listener × Edition). */
export function narrationRatingDocumentId(workId: string, uid: string, editionId: string): string {
  return `${workId}_${uid}_${editionId}`
}

export const NarrationRatingCodec = {
  FIELD_WORK_ID: 'workId',
  FIELD_UID: 'uid',
  FIELD_EDITION_ID: 'editionId',
  FIELD_RATING: 'rating',
  FIELD_CREATED_AT: 'createdAt',
  FIELD_EDITED_AT: 'editedAt',

  documentId(rating: { workId: string; uid: string; editionId: string }): string {
    return narrationRatingDocumentId(rating.workId, rating.uid, rating.editionId)
  },

  toMap(rating: NarrationRating): Record<string, unknown> {
    const out: Record<string, unknown> = {
      [this.FIELD_WORK_ID]: rating.workId.slice(0, NarrationRatingLimits.MAX_WORK_ID_LEN),
      [this.FIELD_UID]: rating.uid,
      [this.FIELD_EDITION_ID]: rating.editionId.slice(0, NarrationRatingLimits.MAX_EDITION_ID_LEN),
      [this.FIELD_RATING]: rating.rating,
      [this.FIELD_CREATED_AT]: rating.createdAt,
    }
    if (rating.editedAt !== undefined) out[this.FIELD_EDITED_AT] = rating.editedAt
    return out
  },

  /** Fail-closed decode: a corrupt document is a miss, never a crash. */
  fromMap(map: Record<string, unknown>): NarrationRating | null {
    try {
      const workId = str(map[this.FIELD_WORK_ID])
      if (workId === null || workId.length > NarrationRatingLimits.MAX_WORK_ID_LEN) return null
      const uid = str(map[this.FIELD_UID])
      if (uid === null) return null
      const editionId = str(map[this.FIELD_EDITION_ID])
      if (editionId === null || editionId.length > NarrationRatingLimits.MAX_EDITION_ID_LEN) return null
      const rating = wholeInt(map[this.FIELD_RATING])
      if (rating === null || !isValidNarrationRating(rating)) return null
      const createdAt = wholeNumber(map[this.FIELD_CREATED_AT])
      if (createdAt === null) return null
      let editedAt: number | undefined
      if (map[this.FIELD_EDITED_AT] !== undefined) {
        const edited = wholeNumber(map[this.FIELD_EDITED_AT])
        if (edited === null) return null
        editedAt = edited
      }
      return { workId, uid, editionId, rating, createdAt, ...(editedAt !== undefined ? { editedAt } : {}) }
    } catch {
      return null
    }
  },
}

function str(raw: unknown): string | null {
  if (typeof raw !== 'string') return null
  const trimmed = raw.trim()
  return trimmed === '' ? null : trimmed
}

function wholeInt(raw: unknown): number | null {
  const value = wholeNumber(raw)
  if (value === null) return null
  return Number.isInteger(value) ? value : null
}

function wholeNumber(raw: unknown): number | null {
  if (typeof raw === 'number' && Number.isFinite(raw)) return raw
  if (typeof raw === 'bigint') return Number(raw)
  return null
}