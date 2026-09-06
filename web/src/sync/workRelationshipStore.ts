import {
  collection,
  doc,
  getDoc,
  getDocFromServer,
  getDocs,
  query,
  serverTimestamp,
  setDoc,
  where,
  type Firestore,
} from 'firebase/firestore'
import {
  WorkRelationshipCodec,
  documentIdForRelationship,
  type RemoteWorkRelationship,
} from './workRelationships'

/**
 * #581 W0.3 — the store seam of the `work_relationships` collection, shaped
 * exactly like `ListenerProgressSyncStore` (ADR-0023 house pattern): the
 * policy lives in pure modules (`workRelationships.ts`), the transport only
 * moves documents, and every failure degrades to null / an empty list —
 * never an exception. Documents live under the deterministic key
 * `{uid}_{mergeKey}`; the FIELD uid is the ownership anchor the security
 * rules check; `updatedAt` is written by the SERVER only.
 */
export interface WorkRelationshipSyncStore {
  /** The cloud row for one Work of one listener, or null when absent/failed/corrupt. */
  pull(uid: string, mergeKey: string): Promise<RemoteWorkRelationship | null>
  /** Every cloud row of this listener (the linking merge's read side). */
  pullAll(uid: string): Promise<RemoteWorkRelationship[]>
  /**
   * Best-effort upload. The transport stamps the write with
   * `FieldValue.serverTimestamp()`; the returned server stamp (when it can
   * be read back) becomes the caller's sync point. Null when not accepted.
   */
  push(uid: string, row: RemoteWorkRelationship): Promise<number | null>
}

/** In-memory fake for unit tests — no Firebase. */
export class InMemoryWorkRelationshipStore implements WorkRelationshipSyncStore {
  documents = new Map<string, Record<string, unknown>>()
  private nextServerMs = 1_000

  async pull(uid: string, mergeKey: string): Promise<RemoteWorkRelationship | null> {
    const doc = this.documents.get(documentIdForRelationship(uid, mergeKey))
    if (doc === undefined) return null
    return WorkRelationshipCodec.fromDocument(doc)
  }

  async pullAll(uid: string): Promise<RemoteWorkRelationship[]> {
    const prefix = `${uid}_`
    const rows: RemoteWorkRelationship[] = []
    for (const [id, doc] of this.documents) {
      if (!id.startsWith(prefix)) continue
      const row = WorkRelationshipCodec.fromDocument(doc)
      if (row !== null) rows.push(row)
    }
    return rows
  }

  async push(uid: string, row: RemoteWorkRelationship): Promise<number | null> {
    const serverMs = ++this.nextServerMs
    this.documents.set(documentIdForRelationship(uid, row.mergeKey), {
      ...WorkRelationshipCodec.toDocument(uid, row),
      [WorkRelationshipCodec.FIELD_UPDATED_AT]: serverMs,
    })
    return serverMs
  }

  // Test helper: plants a server-vouched row directly.
  seed(uid: string, row: RemoteWorkRelationship): void {
    this.documents.set(documentIdForRelationship(uid, row.mergeKey), {
      ...WorkRelationshipCodec.toDocument(uid, row),
      [WorkRelationshipCodec.FIELD_UPDATED_AT]: row.updatedAtServerMs,
    })
    this.nextServerMs = Math.max(this.nextServerMs, row.updatedAtServerMs)
  }
}

/**
 * The Firestore transport over `work_relationships` — the exact glue shape
 * of `FirestoreProgressSyncStore` (spec-43 T6): server-stamped writes,
 * SERVER-source read-back for the ordering stamp, degrade-never everywhere.
 */
export class FirestoreWorkRelationshipStore implements WorkRelationshipSyncStore {
  constructor(private readonly firestore: Firestore) {}

  async pull(uid: string, mergeKey: string): Promise<RemoteWorkRelationship | null> {
    try {
      const snap = await getDoc(this.doc(uid, mergeKey))
      if (!snap.exists()) return null
      return WorkRelationshipCodec.fromDocument(snap.data() as Record<string, unknown>)
    } catch {
      return null
    }
  }

  async pullAll(uid: string): Promise<RemoteWorkRelationship[]> {
    try {
      const snap = await getDocs(this.ownRows(uid))
      const rows: RemoteWorkRelationship[] = []
      snap.forEach((d) => {
        const row = WorkRelationshipCodec.fromDocument(d.data() as Record<string, unknown>)
        if (row !== null) rows.push(row)
      })
      return rows
    } catch {
      return []
    }
  }

  async push(uid: string, row: RemoteWorkRelationship): Promise<number | null> {
    const ref = this.doc(uid, row.mergeKey)
    // Server stamps the LWW clock — the client never fabricates updatedAt.
    const stamped = {
      ...WorkRelationshipCodec.toDocument(uid, row),
      [WorkRelationshipCodec.FIELD_UPDATED_AT]: serverTimestamp(),
    }
    try {
      await setDoc(ref, stamped as never, { merge: false })
    } catch {
      return null
    }
    // Read back the server-vouched timestamp (SERVER source only).
    try {
      const snap = await getDocFromServer(ref)
      const data = snap.data() as Record<string, unknown> | undefined
      const ts = data?.[WorkRelationshipCodec.FIELD_UPDATED_AT]
      if (ts !== null && typeof ts === 'object' && 'toMillis' in (ts as Record<string, unknown>)) {
        return (ts as { toMillis: () => number }).toMillis()
      }
      if (typeof ts === 'number' && Number.isFinite(ts) && ts > 0) return ts
      return null
    } catch {
      return null
    }
  }

  private doc(uid: string, mergeKey: string) {
    return doc(this.firestore, 'work_relationships', documentIdForRelationship(uid, mergeKey))
  }

  private ownRows(uid: string) {
    return query(
      collection(this.firestore, 'work_relationships'),
      where(WorkRelationshipCodec.FIELD_UID, '==', uid),
    )
  }
}
