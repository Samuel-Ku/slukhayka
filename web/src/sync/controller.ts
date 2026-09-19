import { shouldPull, shouldPush, type RemoteListeningState } from './policy'
import type { ProgressSyncLedger } from './ledger'
import type { ListenerProgressSyncStore } from './store'

export const LOCAL_UID_PREFIX = 'local-'

/**
 * #619 — how long a resume may wait for the remote Listening State before it
 * falls back to the local one. The listener pressed Play; Progress Sync is an
 * enhancement of that moment, never its gate.
 */
export const RESUME_PULL_TIMEOUT_MS = 2_000

/**
 * #619 — identifies the playback attempt a pull belongs to. The controller
 * re-checks it AFTER the await, so a remote answer can only reach the local
 * mirror while the attempt that asked for it is still the current one.
 */
export interface ResumePullAttempt {
  isCurrent(): boolean
}

export interface SyncIdentity {
  getUid(): string | null
}

export interface SyncMirror {
  editionIdForSync(bookId: string): string | null
  progressByEdition(editionId: string): RemoteListeningState | null
  applyRemoteProgress(bookId: string, state: RemoteListeningState): void
}

/**
 * ADR-0051 (spec-43 T6) — orchestrator of Progress Sync for web.
 * Mirrors app/src/main/java/com/slukhayka/audiobooks/data/listening/ProgressSyncController.kt
 */
export class ProgressSyncController {
  constructor(
    private readonly identity: SyncIdentity,
    private readonly mirror: SyncMirror,
    private readonly store: ListenerProgressSyncStore | null,
    private readonly ledger: ProgressSyncLedger,
    private readonly isEnabled: () => boolean,
    private readonly nowMs: () => number = () => Date.now(),
    private readonly pullTimeoutMs: number = RESUME_PULL_TIMEOUT_MS,
  ) {}

  private uid(): string | null {
    const uid = this.identity.getUid()
    if (!uid || uid.startsWith(LOCAL_UID_PREFIX)) return null
    return uid
  }

  async pullBeforeResume(bookId: string, attempt?: ResumePullAttempt): Promise<void> {
    if (!this.isEnabled()) return
    if (!this.store) return
    const uid = this.uid()
    if (!uid) return
    const editionId = this.mirror.editionIdForSync(bookId)
    if (!editionId) return

    const remote = await this.pullWithinBudget(uid, editionId)
    // #619 — every gate is re-read AFTER the await. The world may have moved
    // while the cloud was silent: a newer playback intent, a profile switch or
    // the sync switch must all make this answer worthless BEFORE it can be
    // written into the local mirror. The answer must also be the state of the
    // Edition we asked for, not of whatever document the store handed back.
    if (attempt && !attempt.isCurrent()) return
    if (!this.isEnabled()) return
    if (this.uid() !== uid) return
    if (!remote || remote.editionId !== editionId) return
    if (!shouldPull(remote, this.ledger.lastSyncedServerMs(editionId))) return

    this.mirror.applyRemoteProgress(bookId, remote)
    this.ledger.recordSyncedServerMs(editionId, remote.updatedAtServerMs)
  }

  /**
   * #619 — the pull is BOUNDED: a silent Progress Sync must never hold the
   * resume. The budget expiring is not a failure — the listener continues
   * from the local Listening State — so it resolves `null`, and an answer
   * that lands afterwards finds the promise already settled and is dropped
   * instead of being written into the mirror.
   */
  private pullWithinBudget(uid: string, editionId: string): Promise<RemoteListeningState | null> {
    const store = this.store
    if (!store) return Promise.resolve(null)
    return new Promise<RemoteListeningState | null>((resolve) => {
      let settled = false
      const timer = setTimeout(() => {
        settled = true
        resolve(null)
      }, this.pullTimeoutMs)
      const finish = (remote: RemoteListeningState | null): void => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        resolve(remote)
      }
      store.pull(uid, editionId).then(finish, () => finish(null))
    })
  }

  async pushAfterSave(bookId: string, immediate: boolean): Promise<void> {
    if (!this.isEnabled()) return
    if (!this.store) return
    const uid = this.uid()
    if (!uid) return
    const editionId = this.mirror.editionIdForSync(bookId)
    if (!editionId) return
    const local = this.mirror.progressByEdition(editionId)
    if (!local) return

    if (!shouldPush(this.nowMs(), this.ledger.lastPushAttemptMs(editionId), immediate)) return
    this.ledger.recordPushAttempt(editionId, this.nowMs())

    if (!this.isEnabled()) return
    const serverStamp = await this.store.push(uid, local).catch(() => null)
    if (serverStamp != null && serverStamp > 0) {
      const prev = this.ledger.lastSyncedServerMs(editionId)
      if (prev == null || serverStamp > prev) {
        this.ledger.recordSyncedServerMs(editionId, serverStamp)
      }
    }
  }
}
