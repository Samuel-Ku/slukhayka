/**
 * spec-45 T14 (#502) — strings module: uk/en key parity and interpolation.
 */
import { describe, expect, it } from 'vitest'
import { STRINGS, translate } from './strings'

describe('strings', () => {
  it('keeps uk and en key sets in full parity', () => {
    const ukKeys = Object.keys(STRINGS.uk).sort()
    const enKeys = Object.keys(STRINGS.en).sort()
    expect(ukKeys).toEqual(enKeys)
    expect(ukKeys.length).toBeGreaterThan(50)
  })

  it('interpolates tokens and leaves unknown params untouched', () => {
    expect(translate('en', 'openBookAria', { title: 'Moby Dick' })).toBe('Open book: Moby Dick')
    expect(translate('uk', 'openBookAria', { title: 'Книга' })).toBe('Відкрити книгу: Книга')
    expect(translate('en', 'listenChapterAria', { n: 3 })).toBe('Listen to chapter 3')
    expect(translate('en', 'openBookAria', {})).toBe('Open book: {title}')
  })

  it('renders no Ukrainian literal under the en locale', () => {
    const cyrillic = /[А-Яа-яІіЇїЄєҐґ]/
    // The app name «Слухайка» is a brand proper noun, kept in every locale.
    const brandKeys = new Set(['docTitle'])
    for (const [key, value] of Object.entries(STRINGS.en)) {
      if (brandKeys.has(key)) continue
      expect(value, `en.${key}`).not.toMatch(cyrillic)
    }
  })
})