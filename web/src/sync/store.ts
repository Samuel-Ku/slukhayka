import {
  doc,
  getDoc,
  getDocFromServer,
  setDoc,
  serverTimestamp,
  type Firestore,
} from 'firebase/firestore'
import { ProgressSyncCodec, type RemoteListeningState } from './policy'

/**
 * ADR-0023 (spec-43 T6) — the Progress Sync store behind a pure seam.
 * Mirrors app/src/main/java/com/slukhayka/audiobooks/data/listening/ListenerProgressSyncStore.kt
 */

export interface ListenerProgressSyncStore {
  pull(uid: string, editionId: string): Promise<RemoteListeningState | null>
  push(uid: string, state: RemoteListeningState): Promise<number | null>
}

export class FirestoreProgressSyncStore implements ListenerProgressSyncStore {
  constructor(private readonly firestore: Firestore) {}

  async pull(uid: string, editionId: string): Promise<RemoteListeningState | null> {
    try {
      const id = `${uid}_${editionId}`
      const snap = await getDoc(doc(this.firestore, 'listening_state', id))
      if (!snap.exists()) return null
      const raw = { ...(snap.data() as Record<string, unknown>) }
      const ts = raw[ProgressSyncCodec.FIELD_UPDATED_AT]
      if (ts !== null && typeof ts === 'object' && 'toMillis' in (ts as Record<string, unknown>)) {
        raw[ProgressSyncCodec.FIELD_UPDATED_AT] = (ts as { toMillis: () => number }).toMillis()
      }
      return ProgressSyncCodec.fromDocument(raw)
    } catch {
      return null
    }
  }

  async push(uid: string, state: RemoteListeningState): Promise<number | null> {
    const id = `${uid}_${state.editionId}`
    const fields = ProgressSyncCodec.toDocument(uid, state) as Record<string, unknown>
    // Server stamps the LWW clock — client never fabricates updatedAt.
    const stamped = { ...fields, [ProgressSyncCodec.FIELD_UPDATED_AT]: serverTimestamp() }
    try {
      await setDoc(doc(this.firestore, 'listening_state', id), stamped as never, { merge: false })
    } catch {
      return null
    }
    // Read back the server-vouched timestamp (SERVER source only).
    try {
      const snap = await getDocFromServer(doc(this.firestore, 'listening_state', id))
      const data = snap.data() as Record<string, unknown> | undefined
      const ts = data?.[ProgressSyncCodec.FIELD_UPDATED_AT]
      // Firestore returns a Timestamp object for serverTimestamp fields.
      if (ts !== null && typeof ts === 'object' && 'toMillis' in (ts as Record<string, unknown>)) {
        return (ts as { toMillis: () => number }).toMillis()
      }
      if (typeof ts === 'number' && Number.isFinite(ts) && ts > 0) return ts
      return null
    } catch {
      return null
    }
  }
}

/** In-memory fake for unit tests — no Firebase. */
export class InMemoryProgressSyncStore implements ListenerProgressSyncStore {
  private docs = new Map<string, Record<string, unknown>>()
  private nextServerMs = 1_000

  async pull(uid: string, editionId: string): Promise<RemoteListeningState | null> {
    const id = `${uid}_${editionId}`
    const doc = this.docs.get(id)
    if (!doc) return null
    return ProgressSyncCodec.fromDocument(doc)
  }

  async push(uid: string, state: RemoteListeningState): Promise<number | null> {
    const id = `${uid}_${state.editionId}`
    const serverMs = ++this.nextServerMs
    const doc = {
      ...ProgressSyncCodec.toDocument(uid, state),
      [ProgressSyncCodec.FIELD_UPDATED_AT]: serverMs,
    }
    this.docs.set(id, doc)
    return serverMs
  }

  // Test helpers
  seed(uid: string, state: RemoteListeningState & { updatedAtServerMs: number }): void {
    const id = `${uid}_${state.editionId}`
    this.docs.set(id, {
      ...ProgressSyncCodec.toDocument(uid, state),
      [ProgressSyncCodec.FIELD_UPDATED_AT]: state.updatedAtServerMs,
    })
    this.nextServerMs = Math.max(this.nextServerMs, state.updatedAtServerMs)
  }

  clear(): void {
    this.docs.clear()
  }
}
