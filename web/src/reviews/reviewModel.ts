/**
 * W4.1 — port of app/src/main/java/com/slukhayka/audiobooks/data/reviews/ListenerReview.kt
 * (spec-40 #277): one listener review of a Work — stars (1..5), an optional
 * text body and the optional edition tag («Начитка: …»). The document lives
 * in Firestore's `book_reviews` collection under the deterministic key
 * `${workId}_${uid}` — one review per listener per Work, idempotently
 * replaced on edit. Pure, no DOM, no Firebase: the same document shape the
 * Kotlin fixture tests pin, so a review written on web is readable on
 * Android and vice versa (the AC's cross-platform visibility).
 *
 * `authorName` is the listener's nickname at write time (a snapshot — later
 * nickname changes do not rewrite old reviews); `createdAt`/`editedAt` are
 * epoch millis.
 */
export interface ListenerReview {
  workId: string
  uid: string
  authorName: string
  rating: number
  body?: string
  editionTag?: string
  createdAt: number
  editedAt?: number
}

/** Spec-40 #277 — the sanity limits, enforced on write (truncate) and decode (miss). */
export const ListenerReviewLimits = {
  MIN_RATING: 1,
  MAX_RATING: 5,
  MAX_BODY_LEN: 2_000,
  MAX_AUTHOR_LEN: 100,
  MAX_EDITION_TAG_LEN: 200,
  MAX_WORK_ID_LEN: 300,
} as const

export function isValidRating(rating: number): boolean {
  return Number.isInteger(rating) && rating >= ListenerReviewLimits.MIN_RATING && rating <= ListenerReviewLimits.MAX_RATING
}

/** The whole-document validity gate for a WRITE: real identity + in-range rating. */
export function isWritableReview(review: ListenerReview): boolean {
  return (
    review.workId.trim() !== '' &&
    review.workId.length <= ListenerReviewLimits.MAX_WORK_ID_LEN &&
    review.uid.trim() !== '' &&
    review.authorName.trim() !== '' &&
    isValidRating(review.rating)
  )
}

/** The deterministic document key of one listener's review of a Work. */
export function reviewDocumentId(workId: string, uid: string): string {
  return `${workId}_${uid}`
}

/**
 * The Firestore document codec — bounded on write (optional blanks dropped,
 * lengths truncated), fail-closed on read (a corrupt document is a miss,
 * never a crash). Mirrors `ListenerReviewCodec` field-for-field:
 * workId / uid / authorName / rating / body? / editionTag? / createdAt / editedAt?
 */
export const ListenerReviewCodec = {
  FIELD_WORK_ID: 'workId',
  FIELD_UID: 'uid',
  FIELD_AUTHOR_NAME: 'authorName',
  FIELD_RATING: 'rating',
  FIELD_BODY: 'body',
  FIELD_EDITION_TAG: 'editionTag',
  FIELD_CREATED_AT: 'createdAt',
  FIELD_EDITED_AT: 'editedAt',

  documentId(review: { workId: string; uid: string }): string {
    return reviewDocumentId(review.workId, review.uid)
  },

  toMap(review: ListenerReview): Record<string, unknown> {
    const out: Record<string, unknown> = {
      [this.FIELD_WORK_ID]: review.workId.slice(0, ListenerReviewLimits.MAX_WORK_ID_LEN),
      [this.FIELD_UID]: review.uid,
      [this.FIELD_AUTHOR_NAME]: review.authorName.slice(0, ListenerReviewLimits.MAX_AUTHOR_LEN),
      [this.FIELD_RATING]: review.rating,
      [this.FIELD_CREATED_AT]: review.createdAt,
    }
    const body = review.body?.trim()
    if (body) out[this.FIELD_BODY] = body.slice(0, ListenerReviewLimits.MAX_BODY_LEN)
    const tag = review.editionTag?.trim()
    if (tag) out[this.FIELD_EDITION_TAG] = tag.slice(0, ListenerReviewLimits.MAX_EDITION_TAG_LEN)
    if (review.editedAt !== undefined) out[this.FIELD_EDITED_AT] = review.editedAt
    return out
  },

  /** Fail-closed decode: any problem inside collapses to null. */
  fromMap(map: Record<string, unknown>): ListenerReview | null {
    try {
      const workId = stringField(map, this.FIELD_WORK_ID)
      if (workId === null || workId.length > ListenerReviewLimits.MAX_WORK_ID_LEN) return null
      const uid = stringField(map, this.FIELD_UID)
      if (uid === null) return null
      const authorName = stringField(map, this.FIELD_AUTHOR_NAME)
      if (authorName === null || authorName.length > ListenerReviewLimits.MAX_AUTHOR_LEN) return null
      const rating = wholeInt(map[this.FIELD_RATING])
      if (rating === null || !isValidRating(rating)) return null
      const createdAt = wholeNumber(map[this.FIELD_CREATED_AT])
      if (createdAt === null) return null
      const body = boundedOptional(map[this.FIELD_BODY], ListenerReviewLimits.MAX_BODY_LEN)
      if (body === null) return null
      const editionTag = boundedOptional(map[this.FIELD_EDITION_TAG], ListenerReviewLimits.MAX_EDITION_TAG_LEN)
      if (editionTag === null) return null
      let editedAt: number | undefined
      if (map[this.FIELD_EDITED_AT] !== undefined) {
        const edited = wholeNumber(map[this.FIELD_EDITED_AT])
        if (edited === null) return null
        editedAt = edited
      }
      return { workId, uid, authorName, rating, ...(body ? { body } : {}), ...(editionTag ? { editionTag } : {}), createdAt, ...(editedAt !== undefined ? { editedAt } : {}) }
    } catch {
      return null
    }
  },
}

function stringField(map: Record<string, unknown>, key: string): string | null {
  const raw = map[key]
  if (typeof raw !== 'string') return null
  const trimmed = raw.trim()
  return trimmed === '' ? null : trimmed
}

/** Integral value only: a mistyped string or a fractional 3.5 is corrupt. */
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

/** Optional string: absent → undefined; blank → undefined; over-limit or mistyped → null (fail-closed). */
function boundedOptional(raw: unknown, maxLen: number): string | undefined | null {
  if (raw === undefined || raw === null) return undefined
  if (typeof raw !== 'string') return null
  const trimmed = raw.trim()
  if (trimmed === '') return undefined
  if (trimmed.length > maxLen) return null
  return trimmed
}