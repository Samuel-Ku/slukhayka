/**
 * spec-45 T13 (#501) — content-language preference: persistence round-trip,
 * the T4/T5 filter rule on the Work shape, and the T7 badge contract.
 */
import { describe, expect, it } from 'vitest'
import type { UnifiedWork } from '../worker/types'
import {
  availableLanguagesOf,
  badgeLabel,
  filterWorksByLanguage,
  loadContentLanguagePrefs,
  saveContentLanguagePrefs,
  toggleLanguage,
  type StorageLike,
} from './contentLanguagePrefs'

class FakeStorage implements StorageLike {
  private readonly map = new Map<string, string>()

  getItem(key: string): string | null {
    return this.map.get(key) ?? null
  }

  setItem(key: string, value: string): void {
    this.map.set(key, value)
  }
}

function work(id: string, languages: Array<string | undefined>): UnifiedWork {
  return {
    id,
    mergeKey: id,
    title: id,
    author: 'Автор',
    editions: languages.map((language, index) => ({
      id: `${id}-${index}`,
      narrator: `Читець ${index}`,
      language,
      sources: [{ sourceId: 'sound-books', url: `https://sound-books.net/${id}-${index}` }],
    })),
  }
}

describe('content-language preference', () => {
  it('defaults to both languages on when storage is absent', () => {
    expect(loadContentLanguagePrefs(new FakeStorage())).toEqual(['uk', 'en'])
  })

  it('round-trips a selection and an empty selection (= all) across instances', () => {
    const storage = new FakeStorage()
    saveContentLanguagePrefs(['en'], storage)
    expect(loadContentLanguagePrefs(storage)).toEqual(['en'])
    saveContentLanguagePrefs([], storage)
    expect(loadContentLanguagePrefs(storage)).toEqual([])
  })

  it('degrades corrupt payloads to the default', () => {
    const storage = new FakeStorage()
    storage.setItem('slukhayka.content_languages', '{not-json')
    expect(loadContentLanguagePrefs(storage)).toEqual(['uk', 'en'])
    storage.setItem('slukhayka.content_languages', JSON.stringify({ uk: true }))
    expect(loadContentLanguagePrefs(storage)).toEqual(['uk', 'en'])
  })

  it('toggles codes and never keeps duplicates', () => {
    expect(toggleLanguage(['uk'], 'en')).toEqual(['uk', 'en'])
    expect(toggleLanguage(['uk', 'en'], 'en')).toEqual(['uk'])
    expect(toggleLanguage(['uk'], 'uk')).toEqual([]) // empty = all
    expect(saveContentLanguagePrefs(['uk', 'uk', 'en'])).toEqual(['uk', 'en'])
  })
})

describe('filterWorksByLanguage', () => {
  const catalog = [
    work('only-uk', ['uk']),
    work('only-en', ['en']),
    work('both', ['uk', 'en']),
    work('unknown', [undefined]),
    work('only-de', ['de']),
  ]

  it('hides a book only when every edition language is deselected', () => {
    const visible = filterWorksByLanguage(catalog, ['uk'])
    expect(visible.map((item) => item.id)).toEqual(['only-uk', 'both', 'unknown'])
  })

  it('keeps unknown-language rows visible under any selection', () => {
    expect(filterWorksByLanguage(catalog, ['en']).map((item) => item.id)).toEqual(['only-en', 'both', 'unknown'])
  })

  it('an empty selection is all: nothing is hidden', () => {
    expect(filterWorksByLanguage(catalog, [])).toHaveLength(catalog.length)
  })
})

describe('availableLanguagesOf and badgeLabel', () => {
  it('offers languages present in content plus still-selected ones, uk first', () => {
    expect(availableLanguagesOf([work('a', ['en', 'de']), work('b', ['uk'])], ['uk', 'en'])).toEqual(['uk', 'en', 'de'])
    // A selected language stays offered even without current content.
    expect(availableLanguagesOf([], ['en'])).toEqual(['en'])
  })

  it('renders EN/UA explicitly and nothing for unknown', () => {
    expect(badgeLabel('en')).toEqual({ label: 'EN', name: 'English' })
    expect(badgeLabel('uk')).toEqual({ label: 'UA', name: 'Українська' })
    expect(badgeLabel('en-US')).toEqual({ label: 'EN', name: 'English' })
    expect(badgeLabel('de')).toEqual({ label: 'DE', name: 'de' })
    expect(badgeLabel(undefined)).toBeNull()
    expect(badgeLabel('')).toBeNull()
    expect(badgeLabel('garbage')).toBeNull()
  })
})