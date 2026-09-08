/**
 * #581 W0.3 (R-W3, R-W13, ADR-0034) — the pure policy of the
 * `work_relationships` collection: a listener's attitude toward ONE Work is
 * one row with state `entry | tombstone | none`. Library Entry and Tombstone
 * stay the CONTEXT.md domain concepts; this is their sync-layer projection,
 * shared verbatim by web and Android (the Kotlin fixture tests pin the same
 * rules over the same document shape).
 *
 * LWW by SERVER time — the client never fabricates the clock — with the
 * tombstone winning every tie: a deliberate hide must never lose to a stale
 * favorite. Union-merge at linking: local pre-link rows (server stamp 0,
 * honest — no server ever vouched for them) join the account without
 * discarding either side.
 */
export type RelationshipState = 'entry' | 'tombstone'

export const RELATIONSHIP_STATE_ENTRY: RelationshipState = 'entry'
export const RELATIONSHIP_STATE_TOMBSTONE: RelationshipState = 'tombstone'

export interface RemoteWorkRelationship {
  mergeKey: string
  state: RelationshipState
  /** Display surface of the Work at write time (denormalized, honest cache). */
  title: string
  author: string
  /** Firestore SERVER timestamp — the ONLY LWW clock. */
  updatedAtServerMs: number
}

/** Mirrors listening_state's `{uid}_{editionId}` key shape. */
export const DOCUMENT_ID_MAX = 300

export function documentIdForRelationship(uid: string, mergeKey: string): string {
  return `${uid}_${mergeKey}`
}

/**
 * The ONE merge rule of the initiative (ADR-0034): newer server stamp wins;
 * a stamp tie goes to the tombstone; equal states on a tie keep the incoming
 * display data (the freshest claim). Pure, deterministic — both platforms
 * run exactly this shape.
 */
export function mergeRelationshipRows(
  local: RemoteWorkRelationship,
  incoming: RemoteWorkRelationship,
): RemoteWorkRelationship
export function mergeRelationshipRows(
  local: ReadonlyArray<RemoteWorkRelationship>,
  incoming: ReadonlyArray<RemoteWorkRelationship>,
): RemoteWorkRelationship[]
export function mergeRelationshipRows(
  local: RemoteWorkRelationship | ReadonlyArray<RemoteWorkRelationship>,
  incoming: RemoteWorkRelationship | ReadonlyArray<RemoteWorkRelationship>,
): RemoteWorkRelationship | RemoteWorkRelationship[] {
  if (Array.isArray(local) || Array.isArray(incoming)) {
    const localRows = Array.isArray(local) ? local : [local]
    const incomingRows = Array.isArray(incoming) ? incoming : [incoming]
    const byKey = new Map<string, RemoteWorkRelationship>()
    for (const row of [...localRows, ...incomingRows]) {
      const existing = byKey.get(row.mergeKey)
      byKey.set(row.mergeKey, existing === undefined ? row : mergeRelationshipRows(existing, row))
    }
    return [...byKey.values()]
  }
  const a = local as RemoteWorkRelationship
  const b = incoming as RemoteWorkRelationship
  if (b.updatedAtServerMs > a.updatedAtServerMs) return b
  if (b.updatedAtServerMs < a.updatedAtServerMs) return a
  // Tie: a deliberate hide never loses to a stale favorite.
  if (a.state === RELATIONSHIP_STATE_TOMBSTONE || b.state === RELATIONSHIP_STATE_TOMBSTONE) {
    return a.state === RELATIONSHIP_STATE_TOMBSTONE && b.state !== RELATIONSHIP_STATE_TOMBSTONE ? a : b
  }
  return b
}

/** The exact document shape the security rules gate (same bounds on both platforms). */
export const WorkRelationshipCodec = {
  FIELD_MERGE_KEY: 'mergeKey',
  FIELD_STATE: 'state',
  FIELD_TITLE: 'title',
  FIELD_AUTHOR: 'author',
  FIELD_UID: 'uid',
  FIELD_UPDATED_AT: 'updatedAt',

  ID_MAX: DOCUMENT_ID_MAX,
  TITLE_MAX: 500,
  AUTHOR_MAX: 300,

  toDocument(uid: string, row: RemoteWorkRelationship): Record<string, unknown> {
    return {
      [this.FIELD_MERGE_KEY]: row.mergeKey,
      [this.FIELD_STATE]: row.state,
      [this.FIELD_TITLE]: row.title,
      [this.FIELD_AUTHOR]: row.author,
      [this.FIELD_UID]: uid,
      // updatedAt is deliberately absent: the SERVER stamps it (FieldValue.serverTimestamp()).
    }
  },

  fromDocument(document: Record<string, unknown> | null | undefined): RemoteWorkRelationship | null {
    if (document === null || document === undefined || typeof document !== 'object') return null
    const keys = Object.keys(document)
    if (keys.length !== 6) return null // closed shape: uid + 4 fields + updatedAt
    const mergeKey = document[this.FIELD_MERGE_KEY]
    if (typeof mergeKey !== 'string' || mergeKey === '' || mergeKey.length > this.ID_MAX) return null
    const state = document[this.FIELD_STATE]
    if (state !== RELATIONSHIP_STATE_ENTRY && state !== RELATIONSHIP_STATE_TOMBSTONE) return null
    const title = document[this.FIELD_TITLE]
    if (typeof title !== 'string' || title === '' || title.length > this.TITLE_MAX) return null
    const author = document[this.FIELD_AUTHOR]
    if (typeof author !== 'string' || author === '' || author.length > this.AUTHOR_MAX) return null
    const uid = document[this.FIELD_UID]
    if (typeof uid !== 'string' || uid === '') return null
    const updatedAtRaw = document[this.FIELD_UPDATED_AT]
    let updatedAtServerMs: number
    if (typeof updatedAtRaw === 'number') {
      if (!Number.isFinite(updatedAtRaw) || updatedAtRaw <= 0) return null
      updatedAtServerMs = updatedAtRaw
    } else if (
      updatedAtRaw !== null && typeof updatedAtRaw === 'object' &&
      'toMillis' in (updatedAtRaw as Record<string, unknown>)
    ) {
      updatedAtServerMs = (updatedAtRaw as { toMillis: () => number }).toMillis()
    } else {
      return null
    }
    return { mergeKey, state, title, author, updatedAtServerMs }
  },
}
