import { shouldPull, shouldPush, type RemoteListeningState } from './policy'
import type { ProgressSyncLedger } from './ledger'
import type { ListenerProgressSyncStore } from './store'

export const LOCAL_UID_PREFIX = 'local-'

export interface SyncIdentity {
  getUid(): string | null
}

export interface SyncMirror {
  editionIdForSync(bookId: string): string | null
  progressByEdition(editionId: string): RemoteListeningState | null
  applyRemoteProgress(bookId: string, state: RemoteListeningState): void
}

/**
 * ADR-0023 (spec-43 T6) — orchestrator of Progress Sync for web.
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
  ) {}

  private uid(): string | null {
    const uid = this.identity.getUid()
    if (!uid || uid.startsWith(LOCAL_UID_PREFIX)) return null
    return uid
  }

  async pullBeforeResume(bookId: string): Promise<void> {
    if (!this.isEnabled()) return
    if (!this.store) return
    const uid = this.uid()
    if (!uid) return
    const editionId = this.mirror.editionIdForSync(bookId)
    if (!editionId) return

    const remote = await this.store.pull(uid, editionId).catch(() => null)
    if (!this.isEnabled()) return
    if (!remote || !shouldPull(remote, this.ledger.lastSyncedServerMs(editionId))) return

    this.mirror.applyRemoteProgress(bookId, remote)
    this.ledger.recordSyncedServerMs(editionId, remote.updatedAtServerMs)
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
