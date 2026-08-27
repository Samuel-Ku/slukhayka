/**
 * ADR-0023 (spec-43 T6) — the server-side mirror of one Edition's Listening
 * State. Only the fields Progress Sync carries: position, chapter,
 * completion, preferred speed — never Library Entries or other listeners' rows.
 */
export interface RemoteListeningState {
  editionId: string
  chapterIndex: number
  positionSeconds: number
  isCompleted: boolean
  preferredSpeed: number | null
  /** Firestore SERVER timestamp — the ONLY LWW clock. */
  updatedAtServerMs: number
}

export const MIN_PUSH_INTERVAL_MS = 60_000

export function shouldPull(
  remote: RemoteListeningState | null,
  syncedAtServerMs: number | null | undefined,
): boolean {
  if (remote === null) return false
  if (remote.updatedAtServerMs <= 0) return false
  if (syncedAtServerMs == null) return true
  return remote.updatedAtServerMs > syncedAtServerMs
}

export function shouldPush(
  nowMs: number,
  lastAttemptMs: number | null | undefined,
  immediate: boolean,
): boolean {
  if (immediate) return true
  if (lastAttemptMs == null) return true
  return nowMs - lastAttemptMs >= MIN_PUSH_INTERVAL_MS
}

export function documentId(uid: string, editionId: string): string {
  return `${uid}_${editionId}`
}

export const ProgressSyncCodec = {
  FIELD_EDITION_ID: 'editionId',
  FIELD_UID: 'uid',
  FIELD_CHAPTER_INDEX: 'chapterIndex',
  FIELD_POSITION_SECONDS: 'positionSeconds',
  FIELD_IS_COMPLETED: 'isCompleted',
  FIELD_PREFERRED_SPEED: 'preferredSpeed',
  FIELD_UPDATED_AT: 'updatedAt',

  ID_MAX: 300,
  POSITION_MAX_SECONDS: 86400 * 100,
  SPEED_MIN: 0.25,
  SPEED_MAX: 4.0,

  toDocument(uid: string, state: RemoteListeningState): Record<string, unknown> {
    const doc: Record<string, unknown> = {
      [this.FIELD_EDITION_ID]: state.editionId,
      [this.FIELD_UID]: uid,
      [this.FIELD_CHAPTER_INDEX]: state.chapterIndex,
      [this.FIELD_POSITION_SECONDS]: state.positionSeconds,
      [this.FIELD_IS_COMPLETED]: state.isCompleted,
    }
    if (state.preferredSpeed !== null && state.preferredSpeed !== undefined) {
      doc[this.FIELD_PREFERRED_SPEED] = state.preferredSpeed
    }
    return doc
  },

  fromDocument(document: Record<string, unknown> | null | undefined): RemoteListeningState | null {
    if (document == null) return null
    const editionId = document[this.FIELD_EDITION_ID]
    if (typeof editionId !== 'string' || editionId === '' || editionId.length > this.ID_MAX) return null

    const chapterIndexRaw = document[this.FIELD_CHAPTER_INDEX]
    if (typeof chapterIndexRaw !== 'number' || !Number.isInteger(chapterIndexRaw) || chapterIndexRaw < 0) return null

    const positionRaw = document[this.FIELD_POSITION_SECONDS]
    if (typeof positionRaw !== 'number' || !Number.isFinite(positionRaw) || positionRaw < 0 || positionRaw > this.POSITION_MAX_SECONDS) return null

    const isCompleted = document[this.FIELD_IS_COMPLETED]
    if (typeof isCompleted !== 'boolean') return null

    const speedRaw = document[this.FIELD_PREFERRED_SPEED]
    let preferredSpeed: number | null = null
    if (speedRaw !== undefined && speedRaw !== null) {
      if (typeof speedRaw !== 'number' || !Number.isFinite(speedRaw) || speedRaw < this.SPEED_MIN || speedRaw > this.SPEED_MAX) return null
      preferredSpeed = speedRaw
    }

    const updatedAtRaw = document[this.FIELD_UPDATED_AT]
    if (typeof updatedAtRaw !== 'number' || !Number.isFinite(updatedAtRaw) || updatedAtRaw <= 0) return null

    return {
      editionId,
      chapterIndex: chapterIndexRaw,
      positionSeconds: positionRaw,
      isCompleted,
      preferredSpeed,
      updatedAtServerMs: updatedAtRaw,
    }
  },
}
