/**
 * #592 W6.1 — the participation switch (Android's
 * `recommendations_shared_switch`, `sharedLearningConsent`): a local
 * consent flag, off by default — exactly Android's default and its honest
 * wording: nothing leaves the browser until the server layer passes the
 * privacy/security/legal gates (ADR-0030: implementation pending;
 * ADR-0031: absent graph never blocks Огляд). Revoking stops future
 * contributions and leaves the local adaptation running.
 */
const KEY = 'slukhayka.recommendation_participation'

export interface StorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

/** The one seam for the participation consent; degrade-never everywhere. */
export class RecommendationParticipation {
  constructor(private readonly storage: StorageLike) {}

  isEnabled(): boolean {
    try {
      const raw = this.storage.getItem(KEY)
      if (raw === null) return false // off by default (Android's default)
      return raw === '1'
    } catch {
      return false
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