/**
 * spec-51 (#697, T9) — the web transport seam of the SAME shared
 * `curator_collections` documents Android reads and writes (#692/#693/#694),
 * shaped like the existing `ReviewsStore` (ADR-0051 house pattern): the
 * document policy lives in the pure `collectionModel.ts`, the transport only
 * moves documents, and every failure degrades to an honest outcome — never an
 * exception. Without a Firebase config the store is null and every collection
 * surface is honestly ABSENT, exactly as on Android (never a fake empty).
 */
import {
  collection,
  doc,
  getDoc,
  getDocs,
  limit as limitTo,
  orderBy,
  query,
  runTransaction,
  where,
  type Firestore,
} from 'firebase/firestore'
import {
  CollectionModeration,
  CollectionRanking,
  CollectionRating,
  collectionDocumentId,
  decodePublishedCollection,
  type CollectionReadResult,
  type PublishedCollection,
} from './collectionModel'

export interface CollectionsStore {
  /**
   * Every VISIBLE published collection that carries this book, with the three
   * outcomes kept apart so the caller can hold its last good list on failure.
   */
  readContaining(bookId: string): Promise<CollectionReadResult>
  /** The public rail: bounded candidates, then the shared ranking. */
  topPublic(limit: number): Promise<PublishedCollection[]>
  /** A curator's VISIBLE collections (hidden excluded). */
  visibleBy(authorId: string): Promise<PublishedCollection[]>
  /**
   * #694 — one vote per person, applied TRANSACTIONALLY with the collection's
   * `ratingSum`/`ratingCount`; a re-vote REPLACES the previous stars. The
   * caller passes `CollectionIdentity.voterKey(uid, collectionId)`. `true` is
   * the durable acceptance; `false` is an honest refusal (offline, no identity,
   * unknown collection) — never a queued or faked success.
   */
  vote(documentId: string, voterKey: string, stars: number): Promise<boolean>
  /** #694 — the listener's own stars, or null when they have not voted. */
  myVote(voterKey: string): Promise<number | null>
  /**
   * #696 — one complaint per person. Three UNIQUE complaints hide the
   * collection forever, transactionally with `reportCount`/`hidden`; a
   * duplicate is an idempotent acceptance that never counts again. `false` is
   * an honest refusal, exactly like a vote.
   */
  report(documentId: string, reporterKey: string): Promise<boolean>
}

export const PUBLIC_COLLECTIONS_COLLECTION = 'curator_collections'
/** #694 — anonymous votes: one document per (person, collection) key. */
export const PUBLIC_COLLECTION_VOTES_COLLECTION = 'curator_collection_votes'
/** #696 — anonymous complaints, the same key shape as a vote. */
export const PUBLIC_COLLECTION_REPORTS_COLLECTION = 'curator_collection_reports'

/** #693 — the bounded candidate page the rail ranks (Android's TOP_CANDIDATES). */
const TOP_CANDIDATES = 50

const FIELD_BOOK_IDS = 'bookIds'
const FIELD_RATING_COUNT = 'ratingCount'
const FIELD_RATING_SUM = 'ratingSum'
const FIELD_AUTHOR_ID = 'authorId'
const FIELD_DOCUMENT_ID = 'documentId'
const FIELD_STARS = 'stars'
const FIELD_CREATED_AT = 'createdAt'
const FIELD_HIDDEN = 'hidden'
const FIELD_REPORT_COUNT = 'reportCount'

/**
 * Firestore transport — the ONLY part that touches the SDK. Queries mirror
 * Kotlin's `FirestoreListenerCollectionsSharedStore`: an array-contains read
 * for the book block (never an N+1), a vote-count-ordered bounded page for the
 * rail (Firestore cannot order by a computed average), and an author query for
 * a curator. The aggregate is read as-is — a client never invents a number.
 */
export class FirestoreCollectionsStore implements CollectionsStore {
  constructor(private readonly firestore: Firestore) {}

  async readContaining(bookId: string): Promise<CollectionReadResult> {
    if (bookId.trim() === '') return { kind: 'empty' }
    let documents: Array<Record<string, unknown>>
    try {
      const snapshot = await getDocs(
        query(collection(this.firestore, PUBLIC_COLLECTIONS_COLLECTION), where(FIELD_BOOK_IDS, 'array-contains', bookId)),
      )
      documents = snapshot.docs.map((snap) => snap.data())
    } catch {
      return { kind: 'failure' }
    }
    const visible = documents
      .map((document) => decodePublishedCollection(document))
      .filter((decoded): decoded is PublishedCollection => decoded !== null && !decoded.hidden)
    return visible.length === 0 ? { kind: 'empty' } : { kind: 'data', collections: visible }
  }

  async topPublic(limit: number): Promise<PublishedCollection[]> {
    if (limit <= 0) return []
    let documents: Array<Record<string, unknown>>
    try {
      const snapshot = await getDocs(
        query(
          collection(this.firestore, PUBLIC_COLLECTIONS_COLLECTION),
          orderBy(FIELD_RATING_COUNT, 'desc'),
          limitTo(TOP_CANDIDATES),
        ),
      )
      documents = snapshot.docs.map((snap) => snap.data())
    } catch {
      return []
    }
    const visible = documents
      .map((document) => decodePublishedCollection(document))
      .filter((decoded): decoded is PublishedCollection => decoded !== null && !decoded.hidden)
    return CollectionRanking.top(visible, limit)
  }

  async visibleBy(authorId: string): Promise<PublishedCollection[]> {
    if (authorId.trim() === '') return []
    let documents: Array<Record<string, unknown>>
    try {
      const snapshot = await getDocs(
        query(collection(this.firestore, PUBLIC_COLLECTIONS_COLLECTION), where(FIELD_AUTHOR_ID, '==', authorId)),
      )
      documents = snapshot.docs.map((snap) => snap.data())
    } catch {
      return []
    }
    return documents
      .map((document) => decodePublishedCollection(document))
      .filter((decoded): decoded is PublishedCollection => decoded !== null && !decoded.hidden)
  }

  async vote(documentId: string, voterKey: string, stars: number): Promise<boolean> {
    if (documentId.trim() === '' || voterKey.trim() === '' || !CollectionRating.isValidStars(stars)) {
      return false
    }
    try {
      // The aggregate and the vote travel in ONE transaction, exactly as on
      // Android: a re-vote replaces the person's previous stars and never
      // doubles a count. The document shape is pinned by the Firestore rules
      // (`documentId`, `stars`, `createdAt` only).
      await runTransaction(this.firestore, async (transaction) => {
        const voteRef = doc(this.firestore, PUBLIC_COLLECTION_VOTES_COLLECTION, voterKey)
        const previous = starsOf((await transaction.get(voteRef)).data())
        const collectionRef = doc(this.firestore, PUBLIC_COLLECTIONS_COLLECTION, documentId)
        const snapshot = (await transaction.get(collectionRef)).data()
        const sum = nonNegativeIntOf(snapshot?.[FIELD_RATING_SUM])
        const count = nonNegativeIntOf(snapshot?.[FIELD_RATING_COUNT])
        const [nextSum, nextCount] = CollectionRating.applyVote(sum, count, previous, stars)
        transaction.set(voteRef, {
          [FIELD_DOCUMENT_ID]: documentId,
          [FIELD_STARS]: stars,
          [FIELD_CREATED_AT]: Date.now(),
        })
        transaction.update(collectionRef, { [FIELD_RATING_SUM]: nextSum, [FIELD_RATING_COUNT]: nextCount })
      })
      return true
    } catch {
      return false
    }
  }

  async myVote(voterKey: string): Promise<number | null> {
    if (voterKey.trim() === '') return null
    try {
      const snapshot = await getDoc(doc(this.firestore, PUBLIC_COLLECTION_VOTES_COLLECTION, voterKey))
      return starsOf(snapshot.data())
    } catch {
      return null
    }
  }

  async report(documentId: string, reporterKey: string): Promise<boolean> {
    if (documentId.trim() === '' || reporterKey.trim() === '') return false
    try {
      await runTransaction(this.firestore, async (transaction) => {
        const reportRef = doc(this.firestore, PUBLIC_COLLECTION_REPORTS_COLLECTION, reporterKey)
        // A duplicate complaint is an idempotent acceptance: it never counts
        // again, and `hidden` can only ever be turned ON.
        if (!(await transaction.get(reportRef)).exists()) {
          const collectionRef = doc(this.firestore, PUBLIC_COLLECTIONS_COLLECTION, documentId)
          const snapshot = (await transaction.get(collectionRef)).data()
          const count = nonNegativeIntOf(snapshot?.[FIELD_REPORT_COUNT])
          const hidden = snapshot?.[FIELD_HIDDEN] === true
          transaction.set(reportRef, {
            [FIELD_DOCUMENT_ID]: documentId,
            [FIELD_CREATED_AT]: Date.now(),
          })
          transaction.update(collectionRef, {
            [FIELD_REPORT_COUNT]: count + 1,
            [FIELD_HIDDEN]: CollectionModeration.nextHidden(hidden, count),
          })
        }
      })
      return true
    } catch {
      return false
    }
  }
}

/** A vote document's stars, or null when absent/malformed (a vote is 1..5). */
function starsOf(data: Record<string, unknown> | undefined): number | null {
  if (data === undefined) return null
  const raw = data[FIELD_STARS]
  if (typeof raw !== 'number' || !CollectionRating.isValidStars(raw)) return null
  return raw
}

function nonNegativeIntOf(raw: unknown): number {
  if (typeof raw !== 'number' || !Number.isFinite(raw)) return 0
  return Math.max(0, Math.trunc(raw))
}

/**
 * In-memory fake for unit tests — no Firebase. It mirrors the same read rules
 * (hidden never appears, the rail is ranked) so UI tests exercise behaviour,
 * not a stub that agrees with anything.
 */
export class InMemoryCollectionsStore implements CollectionsStore {
  private readonly collections = new Map<string, PublishedCollection>()
  /** #694 — one vote document per voter key; the map IS the "one per person". */
  readonly votes = new Map<string, { documentId: string; stars: number; createdAt: number }>()
  /** #696 — one complaint document per reporter key. */
  readonly reports = new Map<string, { documentId: string; createdAt: number }>()

  async readContaining(bookId: string): Promise<CollectionReadResult> {
    if (bookId.trim() === '') return { kind: 'empty' }
    const visible = this.visible().filter((candidate) => candidate.bookIds.includes(bookId))
    return visible.length === 0 ? { kind: 'empty' } : { kind: 'data', collections: visible }
  }

  async topPublic(limit: number): Promise<PublishedCollection[]> {
    return CollectionRanking.top(this.visible(), limit)
  }

  async visibleBy(authorId: string): Promise<PublishedCollection[]> {
    if (authorId.trim() === '') return []
    return this.visible().filter((candidate) => candidate.authorId === authorId)
  }

  async vote(documentId: string, voterKey: string, stars: number): Promise<boolean> {
    if (documentId.trim() === '' || voterKey.trim() === '' || !CollectionRating.isValidStars(stars)) {
      return false
    }
    const current = this.collections.get(documentId)
    // An unknown collection is a refusal, never a silent write: on Firestore
    // the transaction's `update` of a missing document fails the same way.
    if (current === undefined) return false
    const previous = this.votes.get(voterKey)?.stars ?? null
    const [nextSum, nextCount] = CollectionRating.applyVote(current.ratingSum, current.ratingCount, previous, stars)
    this.collections.set(documentId, { ...current, ratingSum: nextSum, ratingCount: nextCount })
    this.votes.set(voterKey, { documentId, stars, createdAt: Date.now() })
    return true
  }

  async myVote(voterKey: string): Promise<number | null> {
    if (voterKey.trim() === '') return null
    return this.votes.get(voterKey)?.stars ?? null
  }

  async report(documentId: string, reporterKey: string): Promise<boolean> {
    if (documentId.trim() === '' || reporterKey.trim() === '') return false
    // A duplicate is an idempotent acceptance and never counts again.
    if (this.reports.has(reporterKey)) return true
    const current = this.collections.get(documentId)
    if (current === undefined) return false
    this.reports.set(reporterKey, { documentId, createdAt: Date.now() })
    this.collections.set(documentId, {
      ...current,
      reportCount: current.reportCount + 1,
      hidden: CollectionModeration.nextHidden(current.hidden, current.reportCount),
    })
    return true
  }

  /** Test helper: plants one published document. */
  seed(collection: PublishedCollection): void {
    this.collections.set(collectionDocumentId(collection), collection)
  }

  private visible(): PublishedCollection[] {
    return [...this.collections.values()].filter((candidate) => !candidate.hidden)
  }
}
