/**
 * W4.1 — the store seam of the `book_reviews` and `edition_ratings`
 * collections, shaped exactly like the Kotlin stores
 * (`FirestoreListenerReviewsStore` / `FirestoreNarrationRatingsStore`,
 * ADR-0023 house pattern): the policy lives in the pure modules
 * (`reviewModel.ts`, `narrationRatingModel.ts`), the transport only moves
 * documents, and every failure degrades to null / an empty list — never an
 * exception. Documents live under the deterministic keys
 * `${workId}_${uid}` and `${workId}_${uid}_${editionId}` — the document
 * identity itself makes a second vote impossible (one doc per listener per
 * Work/Edition, idempotently replaced on edit), exactly as on Android.
 *
 * App Check: the web installs App Check when a site key is configured
 * (`src/firebase/appCheck.ts`) and Firestore rules refuse token-less
 * writes, so the write path is App Check-gated server-side — the same
 * contract as Android. Without a Firebase config the store is null and the
 * book page renders NO reviews block (honest absence, never a fake state).
 */
import {
  collection,
  deleteDoc,
  doc,
  getDocs,
  orderBy,
  query,
  setDoc,
  where,
  type Firestore,
} from 'firebase/firestore'
import { ListenerReviewCodec, type ListenerReview } from './reviewModel'
import { NarrationRatingCodec, type NarrationRating } from './narrationRatingModel'

export interface ReviewsStore {
  /** Every review of one Work, newest first; a failure is empty, never an error. */
  getForWork(workId: string): Promise<ListenerReview[]>
  /** Idempotent write under `${workId}_${uid}`; true when durably accepted. */
  putReview(review: ListenerReview): Promise<boolean>
  /** Best-effort removal of one listener's review; false on any failure. */
  deleteReview(workId: string, uid: string): Promise<boolean>
}

export interface NarrationRatingsStore {
  /** Every narration rating of one Work (UI filters by editionId), newest first. */
  getForWork(workId: string): Promise<NarrationRating[]>
  /** Idempotent write under `${workId}_${uid}_${editionId}`; true when accepted. */
  putRating(rating: NarrationRating): Promise<boolean>
  /** Best-effort removal of one listener's rating; false on any failure. */
  deleteRating(workId: string, uid: string, editionId: string): Promise<boolean>
}

const REVIEWS_COLLECTION = 'book_reviews'
const RATINGS_COLLECTION = 'edition_ratings'

/**
 * Firestore transport — the ONLY part that touches the SDK. Queries mirror
 * the Kotlin store: `workId ==` ordered by `createdAt` desc (with the plain
 * equality fallback when the compound query lacks a composite index, since
 * the seam sorts client-side anyway).
 */
export class FirestoreReviewsStore implements ReviewsStore {
  constructor(private readonly firestore: Firestore) {}

  async getForWork(workId: string): Promise<ListenerReview[]> {
    try {
      const ordered = await getDocs(
        query(
          collection(this.firestore, REVIEWS_COLLECTION),
          where(ListenerReviewCodec.FIELD_WORK_ID, '==', workId),
          orderBy(ListenerReviewCodec.FIELD_CREATED_AT, 'desc'),
        ),
      )
      return decodeReviews(ordered.docs.map((snap) => snap.data()))
    } catch {
      try {
        const plain = await getDocs(
          query(collection(this.firestore, REVIEWS_COLLECTION), where(ListenerReviewCodec.FIELD_WORK_ID, '==', workId)),
        )
        return decodeReviews(plain.docs.map((snap) => snap.data()))
      } catch {
        return []
      }
    }
  }

  async putReview(review: ListenerReview): Promise<boolean> {
    try {
      await setDoc(
        doc(this.firestore, REVIEWS_COLLECTION, ListenerReviewCodec.documentId(review)),
        ListenerReviewCodec.toMap(review),
      )
      return true
    } catch {
      return false
    }
  }

  async deleteReview(workId: string, uid: string): Promise<boolean> {
    try {
      await deleteDoc(doc(this.firestore, REVIEWS_COLLECTION, `${workId}_${uid}`))
      return true
    } catch {
      return false
    }
  }
}

export class FirestoreNarrationRatingsStore implements NarrationRatingsStore {
  constructor(private readonly firestore: Firestore) {}

  async getForWork(workId: string): Promise<NarrationRating[]> {
    try {
      const snapshots = await getDocs(
        query(
          collection(this.firestore, RATINGS_COLLECTION),
          where(NarrationRatingCodec.FIELD_WORK_ID, '==', workId),
          orderBy(NarrationRatingCodec.FIELD_CREATED_AT, 'desc'),
        ),
      )
      return decodeRatings(snapshots.docs.map((snap) => snap.data()))
    } catch {
      try {
        const plain = await getDocs(
          query(collection(this.firestore, RATINGS_COLLECTION), where(NarrationRatingCodec.FIELD_WORK_ID, '==', workId)),
        )
        return decodeRatings(plain.docs.map((snap) => snap.data()))
      } catch {
        return []
      }
    }
  }

  async putRating(rating: NarrationRating): Promise<boolean> {
    try {
      await setDoc(
        doc(this.firestore, RATINGS_COLLECTION, NarrationRatingCodec.documentId(rating)),
        NarrationRatingCodec.toMap(rating),
      )
      return true
    } catch {
      return false
    }
  }

  async deleteRating(workId: string, uid: string, editionId: string): Promise<boolean> {
    try {
      await deleteDoc(doc(this.firestore, RATINGS_COLLECTION, `${workId}_${uid}_${editionId}`))
      return true
    } catch {
      return false
    }
  }
}

function decodeReviews(documents: Array<Record<string, unknown>>): ListenerReview[] {
  return documents
    .map((document) => ListenerReviewCodec.fromMap(document))
    .filter((review): review is ListenerReview => review !== null)
    .sort((a, b) => b.createdAt - a.createdAt)
}

function decodeRatings(documents: Array<Record<string, unknown>>): NarrationRating[] {
  return documents
    .map((document) => NarrationRatingCodec.fromMap(document))
    .filter((rating): rating is NarrationRating => rating !== null)
    .sort((a, b) => b.createdAt - a.createdAt)
}

/** In-memory fakes for unit tests — no Firebase. */
export class InMemoryReviewsStore implements ReviewsStore {
  documents = new Map<string, Record<string, unknown>>()
  private nextServerMs = 1_000

  async getForWork(workId: string): Promise<ListenerReview[]> {
    return decodeReviews(
      [...this.documents.values()].filter((document) => document[ListenerReviewCodec.FIELD_WORK_ID] === workId),
    )
  }

  async putReview(review: ListenerReview): Promise<boolean> {
    this.documents.set(ListenerReviewCodec.documentId(review), ListenerReviewCodec.toMap(review))
    return true
  }

  async deleteReview(workId: string, uid: string): Promise<boolean> {
    this.documents.delete(`${workId}_${uid}`)
    return true
  }

  /** Test helper: plants a server-vouched review document directly. */
  seed(review: ListenerReview): void {
    this.documents.set(ListenerReviewCodec.documentId(review), {
      ...ListenerReviewCodec.toMap(review),
      createdAt: review.createdAt,
    })
    this.nextServerMs = Math.max(this.nextServerMs, review.createdAt)
  }
}

export class InMemoryNarrationRatingsStore implements NarrationRatingsStore {
  documents = new Map<string, Record<string, unknown>>()
  private nextServerMs = 1_000

  async getForWork(workId: string): Promise<NarrationRating[]> {
    return decodeRatings(
      [...this.documents.values()].filter((document) => document[NarrationRatingCodec.FIELD_WORK_ID] === workId),
    )
  }

  async putRating(rating: NarrationRating): Promise<boolean> {
    this.documents.set(NarrationRatingCodec.documentId(rating), NarrationRatingCodec.toMap(rating))
    return true
  }

  async deleteRating(workId: string, uid: string, editionId: string): Promise<boolean> {
    this.documents.delete(`${workId}_${uid}_${editionId}`)
    return true
  }

  seed(rating: NarrationRating): void {
    this.documents.set(NarrationRatingCodec.documentId(rating), NarrationRatingCodec.toMap(rating))
    this.nextServerMs = Math.max(this.nextServerMs, rating.createdAt)
  }
}