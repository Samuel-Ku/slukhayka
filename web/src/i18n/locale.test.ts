// @vitest-environment jsdom
/**
 * spec-45 T14 (#502) — locale resolution: device language default, persisted
 * override round-trip, and live subscription.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getUiLocale, resolveUiLocale, setUiLocale, subscribeUiLocale } from './locale'

const KEY = 'slukhayka.ui_language'

beforeEach(() => {
  localStorage.clear()
  vi.stubGlobal('navigator', { language: 'en-US' })
})

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('resolveUiLocale', () => {
  it('defaults to English on an English-speaking device (US15)', () => {
    expect(resolveUiLocale()).toBe('en')
  })

  it('defaults to Ukrainian everywhere else', () => {
    vi.stubGlobal('navigator', { language: 'uk-UA' })
    expect(resolveUiLocale()).toBe('uk')
  })

  it('a persisted override wins over the device language and round-trips', () => {
    localStorage.setItem(KEY, 'uk')
    expect(resolveUiLocale()).toBe('uk')
    localStorage.setItem(KEY, 'en')
    expect(resolveUiLocale()).toBe('en')
  })

  it('ignores garbage override values', () => {
    localStorage.setItem(KEY, 'fr')
    expect(resolveUiLocale()).toBe('en')
  })
})

describe('ui locale store', () => {
  it('setUiLocale persists and notifies subscribers', () => {
    const listener = vi.fn()
    const unsubscribe = subscribeUiLocale(listener)
    setUiLocale('uk')
    expect(listener).toHaveBeenCalledOnce()
    expect(getUiLocale()).toBe('uk')
    expect(localStorage.getItem(KEY)).toBe('uk')
    unsubscribe()
    setUiLocale('en')
    expect(listener).toHaveBeenCalledOnce()
  })
})