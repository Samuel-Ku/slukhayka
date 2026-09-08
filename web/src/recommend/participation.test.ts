/**
 * #592 W6.1 — the participation consent: off by default (Android's
 * default), persisted, revocable; revoking leaves the local adaptation
 * running (the seam returns null and the shelf falls back to local).
 */
import { describe, expect, it } from 'vitest'
import { NoServerProfileSource } from './profile'
import { RecommendationParticipation } from './participation'

function memoryStorage(): { storage: Record<string, string> } & { getItem(key: string): string | null; setItem(key: string, value: string): void } {
  const storage: Record<string, string> = {}
  return {
    storage,
    getItem: (key) => storage[key] ?? null,
    setItem: (key, value) => { storage[key] = value },
  }
}

describe('RecommendationParticipation', () => {
  it('is OFF by default — Android\'s sharedLearningConsent default', () => {
    const store = new RecommendationParticipation(memoryStorage())
    expect(store.isEnabled()).toBe(false)
  })

  it('persists the consent and reads it back', () => {
    const memory = memoryStorage()
    const store = new RecommendationParticipation(memory)
    store.setEnabled(true)
    expect(new RecommendationParticipation(memory).isEnabled()).toBe(true)
  })

  it('revocation stops contributions (persisted OFF)', () => {
    const memory = memoryStorage()
    const store = new RecommendationParticipation(memory)
    store.setEnabled(true)
    store.setEnabled(false)
    expect(new RecommendationParticipation(memory).isEnabled()).toBe(false)
  })

  it('a corrupt value degrades to OFF, never a crash', () => {
    const memory = memoryStorage()
    memory.storage['slukhayka.recommendation_participation'] = 'garbage'
    expect(new RecommendationParticipation(memory).isEnabled()).toBe(false)
  })
})

describe('NoServerProfileSource — the honest seam', () => {
  it('returns null: no server layer exists yet (ADR-0030 implementation pending)', async () => {
    const source = new NoServerProfileSource()
    expect(await source.load()).toBeNull()
  })
})