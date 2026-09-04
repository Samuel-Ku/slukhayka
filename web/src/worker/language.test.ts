/**
 * spec-45 (#405) — web mirror of the Android LanguageCode normalizer.
 */
import { describe, expect, it } from 'vitest'
import { normalizeLanguage } from './language'

describe('normalizeLanguage', () => {
  it('keeps canonical primary tags verbatim', () => {
    expect(normalizeLanguage('uk')).toBe('uk')
    expect(normalizeLanguage('en')).toBe('en')
    expect(normalizeLanguage('de')).toBe('de')
  })

  it('maps full English names (the librivox api claim)', () => {
    expect(normalizeLanguage('English')).toBe('en')
    expect(normalizeLanguage('EngLish')).toBe('en')
    expect(normalizeLanguage('Ukrainian')).toBe('uk')
    expect(normalizeLanguage('German')).toBe('de')
  })

  it('maps ISO-639-3 codes (the archive.org claim)', () => {
    expect(normalizeLanguage('eng')).toBe('en')
    expect(normalizeLanguage('ukr')).toBe('uk')
    expect(normalizeLanguage('ger')).toBe('de')
  })

  it('lets the primary tag win in locale strings', () => {
    expect(normalizeLanguage('en-US')).toBe('en')
    expect(normalizeLanguage('uk_UA')).toBe('uk')
  })

  it('refuses unknown, blank and garbage claims', () => {
    expect(normalizeLanguage('')).toBeNull()
    expect(normalizeLanguage('   ')).toBeNull()
    expect(normalizeLanguage(null)).toBeNull()
    expect(normalizeLanguage(undefined)).toBeNull()
    expect(normalizeLanguage('xx')).toBeNull()
    expect(normalizeLanguage('klingon')).toBeNull()
  })
})