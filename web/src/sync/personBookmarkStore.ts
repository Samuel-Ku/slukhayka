import {
  collection,
  deleteDoc,
  doc,
  getDocs,
  query,
  serverTimestamp,
  setDoc,
  where,
  type Firestore,
} from 'firebase/firestore'
import {
  personBookmarkDocumentId,
  remotePersonBookmarkFromDocument,
  toPersonBookmarkDocument,
  type RemotePersonBookmark,
} from './personBookmarkSync'
import type { PersonBookmarkEntity } from '../local/domain'

/**
 * #582 W0.4 — the store seam of the `person_bookmarks` collection, shaped
 * exactly like `FirestoreWorkRelationshipStore` (house pattern): the
 * policy lives in the pure module (`personBookmarkSync.ts`), the transport
 * only moves documents, and every failure degrades to null / an empty
 * list — never an exception. Documents live under Android's deterministic
 * key `${uid}_${kind}_${personId}`; `updatedAt` is written by the SERVER
 * only (`FieldValue.serverTimestamp()`), so the LWW clock is honest.
 */
export interface PersonBookmarkSyncStore {
  /** Every cloud bookmark of one listener; a failure is empty, never an error. */
  pullAll(uid: string): Promise<RemotePersonBookmark[]>
  /** Best-effort upload under the deterministic doc id; false on any failure. */
  push(uid: string, bookmark: PersonBookmarkEntity): Promise<boolean>
  /** Best-effort removal; false on any failure. */
  remove(uid: string, kind: string, personId: string): Promise<boolean>
}

/** In-memory fake for unit tests — no Firebase. */
export class InMemoryPersonBookmarkStore implements PersonBookmarkSyncStore {
  documents = new Map<string, Record<string, unknown>>()
  private nextServerMs = 1_000

  async pullAll(uid: string): Promise<RemotePersonBookmark[]> {
    const prefix = `${uid}_`
    const rows: RemotePersonBookmark[] = []
    for (const [id, doc] of this.documents) {
      if (!id.startsWith(prefix)) continue
      const row = remotePersonBookmarkFromDocument(doc)
      if (row !== null) rows.push(row)
    }
    return rows
  }

  async push(uid: string, bookmark: PersonBookmarkEntity): Promise<boolean> {
    const kind = bookmark.role === 'author' ? 'AUTHOR' : 'NARRATOR'
    const serverMs = ++this.nextServerMs
    this.documents.set(personBookmarkDocumentId(uid, kind, bookmark.personId), {
      ...toPersonBookmarkDocument(uid, bookmark),
      updatedAt: serverMs,
    })
    return true
  }

  async remove(uid: string, kind: string, personId: string): Promise<boolean> {
    return this.documents.delete(personBookmarkDocumentId(uid, kind, personId))
  }

  // Test helper: plants a server-vouched row directly.
  seed(uid: string, row: RemotePersonBookmark): void {
    this.documents.set(personBookmarkDocumentId(uid, row.kind, row.personId), {
      uid,
      kind: row.kind,
      personId: row.personId,
      displayName: row.displayName,
      notifyEnabled: row.notifyEnabled,
      updatedAt: row.updatedAtServerMs,
    })
    this.nextServerMs = Math.max(this.nextServerMs, row.updatedAtServerMs)
  }
}

/** The Firestore transport over `person_bookmarks` — degrade-never everywhere. */
export class FirestorePersonBookmarkStore implements PersonBookmarkSyncStore {
  constructor(private readonly firestore: Firestore) {}

  async pullAll(uid: string): Promise<RemotePersonBookmark[]> {
    try {
      const snapshot = await getDocs(
        query(collection(this.firestore, 'person_bookmarks'), where('uid', '==', uid)),
      )
      const rows: RemotePersonBookmark[] = []
      for (const snap of snapshot.docs) {
        const row = remotePersonBookmarkFromDocument(snap.data() as Record<string, unknown>)
        if (row !== null) rows.push(row)
      }
      return rows
    } catch {
      return []
    }
  }

  async push(uid: string, bookmark: PersonBookmarkEntity): Promise<boolean> {
    try {
      const kind = bookmark.role === 'author' ? 'AUTHOR' : 'NARRATOR'
      await setDoc(doc(this.firestore, 'person_bookmarks', personBookmarkDocumentId(uid, kind, bookmark.personId)), {
        ...toPersonBookmarkDocument(uid, bookmark),
        updatedAt: serverTimestamp(),
      })
      return true
    } catch {
      return false
    }
  }

  async remove(uid: string, kind: string, personId: string): Promise<boolean> {
    try {
      await deleteDoc(doc(this.firestore, 'person_bookmarks', personBookmarkDocumentId(uid, kind, personId)))
      return true
    } catch {
      return false
    }
  }
}