/**
 * ADR-0023 (spec-43 T6) — what this browser already knows about the cloud's
 * listening_state documents, per Edition: the newest server timestamp it has
 * SEEN and the last push attempt's wall-clock mark.
 * Mirrors Android's SharedPreferencesProgressSyncLedger.
 */

export interface ProgressSyncLedger {
  lastSyncedServerMs(editionId: string): number | null
  recordSyncedServerMs(editionId: string, serverMs: number): void
  lastPushAttemptMs(editionId: string): number | null
  recordPushAttempt(editionId: string, atMs: number): void
}

export interface StorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

function syncKey(editionId: string): string {
  return `slukhayka.sync.synced_${editionId}`
}
function attemptKey(editionId: string): string {
  return `slukhayka.sync.attempt_${editionId}`
}

export class BrowserProgressSyncLedger implements ProgressSyncLedger {
  constructor(private readonly storage: StorageLike) {}

  lastSyncedServerMs(editionId: string): number | null {
    try {
      const raw = this.storage.getItem(syncKey(editionId))
      if (raw === null) return null
      const n = Number(raw)
      return Number.isFinite(n) && n >= 0 ? n : null
    } catch {
      return null
    }
  }

  recordSyncedServerMs(editionId: string, serverMs: number): void {
    try {
      this.storage.setItem(syncKey(editionId), String(serverMs))
    } catch {
      // degrade-never
    }
  }

  lastPushAttemptMs(editionId: string): number | null {
    try {
      const raw = this.storage.getItem(attemptKey(editionId))
      if (raw === null) return null
      const n = Number(raw)
      return Number.isFinite(n) && n >= 0 ? n : null
    } catch {
      return null
    }
  }

  recordPushAttempt(editionId: string, atMs: number): void {
    try {
      this.storage.setItem(attemptKey(editionId), String(atMs))
    } catch {
      // degrade-never
    }
  }
}

/** In-memory fake for unit tests. */
export class InMemoryLedger implements ProgressSyncLedger {
  private synced = new Map<string, number>()
  private attempts = new Map<string, number>()
  lastSyncedServerMs(editionId: string): number | null {
    return this.synced.get(editionId) ?? null
  }
  recordSyncedServerMs(editionId: string, serverMs: number): void {
    this.synced.set(editionId, serverMs)
  }
  lastPushAttemptMs(editionId: string): number | null {
    return this.attempts.get(editionId) ?? null
  }
  recordPushAttempt(editionId: string, atMs: number): void {
    this.attempts.set(editionId, atMs)
  }
}
