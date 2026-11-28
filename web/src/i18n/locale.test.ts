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

describe('degrade-never (#1787)', () => {
  it('a storage throw still applies the locale and the document language', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => null,
      setItem: () => {
        throw new Error('quota')
      },
    })
    const listener = vi.fn()
    const unsubscribe = subscribeUiLocale(listener)
    document.documentElement.lang = 'uk'

    expect(() => setUiLocale('en')).not.toThrow()
    expect(getUiLocale()).toBe('en')
    expect(document.documentElement.lang).toBe('en')
    expect(listener).toHaveBeenCalledOnce()
    unsubscribe()
  })
})

describe('document language (#1787)', () => {
  it('an in-app switch sets <html lang> to the chosen locale', () => {
    setUiLocale('en')
    expect(document.documentElement.lang).toBe('en')
    setUiLocale('uk')
    expect(document.documentElement.lang).toBe('uk')
  })

  it('the first frame carries the resolved locale, before any React effect', async () => {
    // A fresh module instance simulates the page load: web/index.html declares
    // <html lang="uk">, the device is English and no override is persisted.
    vi.resetModules()
    localStorage.clear()
    vi.stubGlobal('navigator', { language: 'en-US' })
    document.documentElement.lang = 'uk'

    const fresh = await import('./locale')
    // Resolution happens in the store (during the first render), not in an
    // effect, so English is announced from the very first frame.
    expect(fresh.getUiLocale()).toBe('en')
    expect(document.documentElement.lang).toBe('en')
  })
})