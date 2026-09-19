/**
 * spec-51 (#697, T9) — the web READER of the same `curator_collections`
 * documents Android publishes (#692/#693/#694). The wire shape is a contract,
 * not a private detail: a collection written on Android decodes here into the
 * same fields, the same text hygiene and the same order, so the browser sees
 * one community, not a second one.
 *
 * Pure module: no DOM, no Firebase, no React. The decoder is FAIL-CLOSED — a
 * hostile or corrupt document is a miss (null), never a half-valid object —
 * mirroring Kotlin's `PublishedCollectionCodec`/`ListenerCollectionLimits`,
 * and `CollectionRanking`/`CollectionRating` mirror the shared ordering and
 * the honest average ("no votes → no number", ADR-0014).
 */

/** One published position: the curator's frozen display snapshot of a book. */
export interface PublishedCollectionItem {
  bookId: string
  title: string
  author: string
  coverUrl?: string
  reason: string
}

/** A listener collection as it exists OUTSIDE the device. */
export interface PublishedCollection {
  /** `sha256(uid)` on Android — the raw uid never travels. */
  authorId: string
  collectionId: string
  pseudonym: string
  title: string
  description: string
  bookIds: string[]
  /** Positionally parallel to [bookIds] (spec-51 #695). */
  reasons: string[]
  /** The transactional rating aggregate (#694); both zero when nobody voted. */
  ratingSum: number
  ratingCount: number
  /** Moderation state (#696): a hidden collection is never a public surface. */
  hidden: boolean
  reportCount: number
  /** The composition with display snapshots; legacy documents carry none. */
  items: PublishedCollectionItem[]
  publishedAt: number
}

/** The public document id: the author and the collection, still hashed. */
export function collectionDocumentId(collection: { authorId: string; collectionId: string }): string {
  return `${collection.authorId}-${collection.collectionId}`
}

/** Bounds shared with Kotlin's `PublishedCollectionCodec`/`ListenerCollectionLimits`. */
export const CollectionLimits = {
  MAX_TITLE_LEN: 80,
  MAX_DESCRIPTION_LEN: 600,
  MAX_REASON_LEN: 200,
  MAX_PSEUDONYM_LEN: 40,
  MAX_BOOKS: 500,
  MAX_AUTHOR_LEN: 200,
} as const

const URL_PATTERN = /(https?:\/\/|www\.)\S+/gi

/**
 * The canonical form of a free-text field: links removed, whitespace
 * collapsed, then truncated — exactly Kotlin's `ListenerCollectionLimits.clean`.
 * An empty string is the honest "nothing was written".
 */
export function cleanCollectionText(text: string | null | undefined, maxLen: number): string {
  if (text === null || text === undefined || text.trim() === '') return ''
  const withoutLinks = text.replace(URL_PATTERN, ' ')
  const collapsed = withoutLinks.replace(/\s+/g, ' ').trim()
  return collapsed.length <= maxLen ? collapsed : collapsed.slice(0, maxLen).trimEnd()
}

export function cleanCollectionTitle(title: string | null | undefined): string {
  return cleanCollectionText(title, CollectionLimits.MAX_TITLE_LEN)
}

export function cleanCollectionDescription(description: string | null | undefined): string {
  return cleanCollectionText(description, CollectionLimits.MAX_DESCRIPTION_LEN)
}

export function cleanCollectionReason(reason: string | null | undefined): string {
  return cleanCollectionText(reason, CollectionLimits.MAX_REASON_LEN)
}

/**
 * The document decoder — field-for-field with `PublishedCollectionCodec.decode`.
 * A document missing an author, a collection id or a real title is a miss; every
 * numeric field falls back to its honest zero (a negative aggregate is never a
 * fabricated rating).
 */
export function decodePublishedCollection(document: Record<string, unknown> | null | undefined): PublishedCollection | null {
  if (document === null || document === undefined) return null
  const authorId = nonBlankString(document['authorId'])
  const collectionId = nonBlankString(document['collectionId'])
  const rawTitle = document['title']
  if (authorId === null || collectionId === null) return null
  if (typeof rawTitle !== 'string') return null
  const title = cleanCollectionTitle(rawTitle)
  if (title === '') return null

  const bookIds = stringList(document['bookIds']).slice(0, CollectionLimits.MAX_BOOKS)
  // Parallel and positionally aligned: a short list is padded, a long one
  // truncated — a reason can never attach to the wrong book.
  const cleanedReasons = stringList(document['reasons'])
    .map((reason) => cleanCollectionReason(reason))
    .slice(0, CollectionLimits.MAX_BOOKS)
  const reasons = bookIds.map((_, index) => cleanedReasons[index] ?? '')

  return {
    authorId,
    collectionId,
    pseudonym: typeof document['pseudonym'] === 'string' ? document['pseudonym'].slice(0, CollectionLimits.MAX_PSEUDONYM_LEN) : '',
    title,
    description: cleanCollectionDescription(typeof document['description'] === 'string' ? document['description'] : null),
    bookIds,
    reasons,
    ratingSum: nonNegativeInt(document['ratingSum']),
    ratingCount: nonNegativeInt(document['ratingCount']),
    hidden: document['hidden'] === true,
    reportCount: nonNegativeInt(document['reportCount']),
    items: Array.isArray(document['items'])
      ? document['items'].map((entry) => decodePublishedCollectionItem(entry)).filter((item): item is PublishedCollectionItem => item !== null).slice(0, CollectionLimits.MAX_BOOKS)
      : [],
    publishedAt: wholeNumber(document['publishedAt']) ?? 0,
  }
}

/** A display snapshot; a malformed entry is dropped, never half-shown. */
function decodePublishedCollectionItem(entry: unknown): PublishedCollectionItem | null {
  if (typeof entry !== 'object' || entry === null) return null
  const map = entry as Record<string, unknown>
  const bookId = nonBlankString(map['bookId'])
  if (bookId === null) return null
  return {
    bookId,
    title: cleanCollectionTitle(typeof map['title'] === 'string' ? map['title'] : null),
    author: cleanCollectionText(typeof map['author'] === 'string' ? map['author'] : null, CollectionLimits.MAX_AUTHOR_LEN),
    coverUrl: nonBlankString(map['coverUrl']) ?? undefined,
    reason: cleanCollectionReason(typeof map['reason'] === 'string' ? map['reason'] : null),
  }
}

function nonBlankString(raw: unknown): string | null {
  if (typeof raw !== 'string') return null
  const trimmed = raw.trim()
  return trimmed === '' ? null : trimmed
}

function stringList(raw: unknown): string[] {
  if (!Array.isArray(raw)) return []
  return raw.filter((entry): entry is string => typeof entry === 'string' && entry.trim() !== '')
}

function wholeNumber(raw: unknown): number | null {
  if (typeof raw === 'number' && Number.isFinite(raw)) return Math.trunc(raw)
  if (typeof raw === 'bigint') return Number(raw)
  return null
}

function nonNegativeInt(raw: unknown): number {
  const value = wholeNumber(raw)
  return value === null ? 0 : Math.max(0, value)
}

/**
 * The honest arithmetic of collection stars (#694): one vote per person, a
 * re-vote replaces, and a collection nobody rated has NO average at all.
 */
export const CollectionRating = {
  MIN_STARS: 1,
  MAX_STARS: 5,

  isValidStars(stars: number): boolean {
    return Number.isInteger(stars) && stars >= CollectionRating.MIN_STARS && stars <= CollectionRating.MAX_STARS
  },

  /** The real average, or null when nobody voted (or the aggregate is impossible). */
  average(sum: number, count: number): number | null {
    if (count <= 0) return null
    if (sum <= 0) return null
    return sum / count
  },

  /**
   * The aggregate after one listener's vote — exactly Kotlin's
   * `CollectionRating.applyVote`. [previousStars] is that listener's earlier
   * vote (null on a first vote), so a re-vote REPLACES it instead of adding a
   * second one. Returns the new `[sum, count]`.
   */
  applyVote(sum: number, count: number, previousStars: number | null, newStars: number): [number, number] {
    if (!CollectionRating.isValidStars(newStars)) {
      throw new Error(`stars must be ${CollectionRating.MIN_STARS}..${CollectionRating.MAX_STARS}`)
    }
    const hadPrevious = previousStars !== null && CollectionRating.isValidStars(previousStars)
    const baseSum = Math.max(0, sum - (hadPrevious ? previousStars : 0))
    const baseCount = Math.max(0, count - (hadPrevious ? 1 : 0))
    return [baseSum + newStars, baseCount + 1]
  },
} as const

/**
 * The moderation threshold (#696), exactly Kotlin's `CollectionModeration`.
 * Three UNIQUE complaints hide a collection FOREVER: [nextHidden] only ever
 * turns the flag on, mirroring the Firestore one-way `false → true` rule, so a
 * client can never un-hide what the community removed.
 */
export const CollectionModeration = {
  HIDE_THRESHOLD: 3,

  /** The moderation state after one NEW unique complaint. */
  nextHidden(currentHidden: boolean, currentCount: number): boolean {
    return currentHidden || currentCount + 1 >= CollectionModeration.HIDE_THRESHOLD
  },
} as const

/**
 * The ONE ordering behind every public collection surface (#692/#693): real
 * average descending (no votes sort below any vote), then votes, then newest,
 * then the document id. Deterministic, so the book block and the rail can
 * never disagree about order.
 */
/** A rail/block is a shelf, not an archive. */
const DEFAULT_RAIL_LIMIT = 10

export const CollectionRanking = {
  DEFAULT_LIMIT: DEFAULT_RAIL_LIMIT,

  top(collections: readonly PublishedCollection[], limit: number = DEFAULT_RAIL_LIMIT): PublishedCollection[] {
    if (limit <= 0) return []
    return [...collections].sort(compareCollections).slice(0, limit)
  },
} as const

function compareCollections(a: PublishedCollection, b: PublishedCollection): number {
  const averageA = CollectionRating.average(a.ratingSum, a.ratingCount) ?? -1
  const averageB = CollectionRating.average(b.ratingSum, b.ratingCount) ?? -1
  if (averageA !== averageB) return averageB - averageA
  if (a.ratingCount !== b.ratingCount) return b.ratingCount - a.ratingCount
  if (a.publishedAt !== b.publishedAt) return b.publishedAt - a.publishedAt
  const idA = collectionDocumentId(a)
  const idB = collectionDocumentId(b)
  return idA < idB ? -1 : idA > idB ? 1 : 0
}

/**
 * The honest outcome of a public READ (#692): real data, an honest empty, or an
 * unreachable shared layer. The caller keeps its last good list on `failure`
 * instead of blanking a surface on a transient error.
 */
export type CollectionReadResult =
  | { readonly kind: 'data'; readonly collections: PublishedCollection[] }
  | { readonly kind: 'empty' }
  | { readonly kind: 'failure' }
