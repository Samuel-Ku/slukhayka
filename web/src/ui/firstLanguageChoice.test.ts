/**
 * spec-51 (#742) — the web First Language Choice fires-once rule.
 */
import { describe, expect, it } from 'vitest'
import {
  isFirstLanguageChoiceAnswered,
  markFirstLanguageChoiceAnswered,
  shouldAskFirstLanguageChoice,
} from './firstLanguageChoice'
import type { StorageLike } from './contentLanguagePrefs'

class FakeStorage implements StorageLike {
  private readonly map = new Map<string, string>()

  getItem(key: string): string | null {
    return this.map.get(key) ?? null
  }

  setItem(key: string, value: string): void {
    this.map.set(key, value)
  }
}

describe('shouldAskFirstLanguageChoice', () => {
  it('asks after content exists, when unanswered and still «Усі»', () => {
    expect(shouldAskFirstLanguageChoice({ hasContent: true, answered: false, selection: [] })).toBe(true)
  })

  it('never asks before any content exists', () => {
    expect(shouldAskFirstLanguageChoice({ hasContent: false, answered: false, selection: [] })).toBe(false)
  })

  it('never asks again once answered', () => {
    expect(shouldAskFirstLanguageChoice({ hasContent: true, answered: true, selection: [] })).toBe(false)
  })

  it('treats an already-narrowed selection as the answer (US16)', () => {
    expect(shouldAskFirstLanguageChoice({ hasContent: true, answered: false, selection: ['uk'] })).toBe(false)
  })
})

describe('the answered marker', () => {
  it('persists across instances', () => {
    const storage = new FakeStorage()
    expect(isFirstLanguageChoiceAnswered(storage)).toBe(false)
    markFirstLanguageChoiceAnswered(storage)
    expect(isFirstLanguageChoiceAnswered(storage)).toBe(true)
  })
})
