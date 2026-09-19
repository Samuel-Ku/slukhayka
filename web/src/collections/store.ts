/**
 * spec-51 (#697, T9) — the web transport seam of the SAME shared
 * `curator_collections` documents Android reads and writes (#692/#693/#694),
 * shaped like the existing `ReviewsStore` (ADR-0051 house pattern): the
 * document policy lives in the pure `collectionModel.ts`, the transport only
 * moves documents, and every failure degrades to an honest outcome — never an
 * exception. Without a Firebase config the store is null and every collection
 * surface is honestly ABSENT, exactly as on Android (never a fake empty).
 */
import { collection, getDocs, limit as limitTo, orderBy, query, where, type Firestore } from 'firebase/firestore'
import {
  CollectionRanking,
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
}

export const PUBLIC_COLLECTIONS_COLLECTION = 'curator_collections'

/** #693 — the bounded candidate page the rail ranks (Android's TOP_CANDIDATES). */
const TOP_CANDIDATES = 50

const FIELD_BOOK_IDS = 'bookIds'
const FIELD_RATING_COUNT = 'ratingCount'
const FIELD_AUTHOR_ID = 'authorId'

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
}

/**
 * In-memory fake for unit tests — no Firebase. It mirrors the same read rules
 * (hidden never appears, the rail is ranked) so UI tests exercise behaviour,
 * not a stub that agrees with anything.
 */
export class InMemoryCollectionsStore implements CollectionsStore {
  private readonly collections = new Map<string, PublishedCollection>()

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

  /** Test helper: plants one published document. */
  seed(collection: PublishedCollection): void {
    this.collections.set(collectionDocumentId(collection), collection)
  }

  private visible(): PublishedCollection[] {
    return [...this.collections.values()].filter((candidate) => !candidate.hidden)
  }
}
