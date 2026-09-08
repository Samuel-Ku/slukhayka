/**
 * #582 W0.4 — the pure `person_bookmarks` sync contract, ported verbatim
 * from Android's `PersonBookmarksSyncStore` / `PersonBookmarksSyncCodec`
 * (#404): document id `${uid}_${kind}_${personId}`, fields
 * uid/kind/personId/displayName/notifyEnabled + server-stamped updatedAt,
 * and the LWW policy (remote wins only when strictly newer — pull never
 * infers deletion). Local rows push when their clock is ahead.
 *
 * Pending deletes: an offline local removal persists its marker BEFORE
 * the network call, so a later pull can never resurrect the row (Android's
 * `PendingPersonBookmarkDeletes`). Refusing sync never touches local rows.
 */
import type { PersonBookmarkEntity } from '../local/domain'

/** The wire row — kind is Android's storage value (AUTHOR/NARRATOR). */
export interface RemotePersonBookmark {
  kind: string
  personId: string
  displayName: string
  notifyEnabled: boolean
  updatedAtServerMs: number
}

/** Android's storage values; the web domain roles map 1:1. */
export function wireKindOf(role: 'author' | 'narrator'): string {
  return role === 'author' ? 'AUTHOR' : 'NARRATOR'
}

export function roleOfWireKind(kind: string): 'author' | 'narrator' | null {
  return kind === 'AUTHOR' ? 'author' : kind === 'NARRATOR' ? 'narrator' : null
}

/** Android's documentId(uid, kind, personId). */
export function personBookmarkDocumentId(uid: string, kind: string, personId: string): string {
  return `${uid}_${kind}_${personId}`
}

/** Android's allowed-field strictness: unknown fields are misses, not ignores. */
const ALLOWED_FIELDS = new Set(['uid', 'kind', 'personId', 'displayName', 'notifyEnabled', 'updatedAt'])

/** Android's PersonBookmarksSyncCodec.toDocument (updatedAt is server-stamped). */
export function toPersonBookmarkDocument(uid: string, bookmark: PersonBookmarkEntity): Record<string, unknown> {
  return {
    uid,
    kind: wireKindOf(bookmark.role),
    personId: bookmark.personId,
    displayName: bookmark.displayName,
    notifyEnabled: bookmark.notifyEnabled,
  }
}

/** Android's PersonBookmarksSyncCodec.fromDocument — same bounds, same misses. */
export function remotePersonBookmarkFromDocument(document: Record<string, unknown>): RemotePersonBookmark | null {
  if (Object.keys(document).some((key) => !ALLOWED_FIELDS.has(key))) return null
  const kind = document['kind']
  if (typeof kind !== 'string' || roleOfWireKind(kind) === null) return null
  const personId = document['personId']
  if (typeof personId !== 'string') return null
  const displayName = document['displayName']
  if (typeof displayName !== 'string') return null
  const notifyEnabled = document['notifyEnabled']
  if (typeof notifyEnabled !== 'boolean') return null
  const updatedAt = document['updatedAt']
  if (typeof updatedAt !== 'number' || !Number.isFinite(updatedAt)) return null
  if (personId === '' || personId.length > 300 || displayName === '' || displayName.length > 200 || updatedAt <= 0) return null
  return { kind, personId, displayName, notifyEnabled, updatedAtServerMs: updatedAt }
}

/** Maps a remote row onto the domain entity (updatedAt = the server clock). */
export function toDomainBookmark(remote: RemotePersonBookmark): PersonBookmarkEntity {
  const role = roleOfWireKind(remote.kind)
  if (role === null) throw new Error('unreachable: validated kind')
  return {
    role,
    personId: remote.personId,
    displayName: remote.displayName,
    createdAt: remote.updatedAtServerMs,
    updatedAt: remote.updatedAtServerMs,
    notifyEnabled: remote.notifyEnabled,
  }
}

/** The remote wins only when strictly newer (Android's `>` in sync). */
export function remoteWins(local: PersonBookmarkEntity | null, remote: RemotePersonBookmark): boolean {
  return local === null || remote.updatedAtServerMs > local.updatedAt
}

/** Android's push decision: a local row newer than the cloud row uploads. */
export function shouldPush(local: PersonBookmarkEntity, remote: RemotePersonBookmark | null): boolean {
  return remote === null || local.updatedAt > remote.updatedAtServerMs
}

/**
 * The pending-delete marker list (Android's `PendingPersonBookmarkDeletes`):
 * `${kind}:${personId}` keys persisted BEFORE the network call, removed only
 * after the remote delete succeeds. A pull filters them out, so an offline
 * removal is never resurrected.
 */
export function pendingDeleteKey(kind: string, personId: string): string {
  return `${kind}:${personId}`
}