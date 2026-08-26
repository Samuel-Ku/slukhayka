/**
 * ADR-0023 (spec-43 T6) — the visible Progress Sync switch (⚙️ Профіль on
 * web): on by default, because the sync carries nothing until the listener's
 * own devices share one profile — and the switch turns it off for good,
 * keeping the local Listening State untouched.
 */

const KEY = 'slukhayka.progress_sync_enabled'

export interface StorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

export class ProgressSyncSettings {
  constructor(private readonly storage: StorageLike) {}

  isEnabled(): boolean {
    try {
      const raw = this.storage.getItem(KEY)
      if (raw === null) return true // on by default
      return raw !== '0' && raw !== 'false'
    } catch {
      return true
    }
  }

  setEnabled(value: boolean): void {
    try {
      this.storage.setItem(KEY, value ? '1' : '0')
    } catch {
      // degrade-never
    }
  }
}
