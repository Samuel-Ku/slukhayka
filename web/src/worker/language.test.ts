/**
 * spec-45 (#405), spec-51 (#742) — web mirror of the Android LanguageCode
 * normalizer.
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

  it('maps the wider librivox vocabulary through the same normalizer', () => {
    // Spec-51 (#742): a source that really serves ~49 languages must not
    // have half of them fall out of the filter as "unknown".
    expect(normalizeLanguage('Afrikaans')).toBe('af')
    expect(normalizeLanguage('Bengali')).toBe('bn')
    expect(normalizeLanguage('Esperanto')).toBe('eo')
    expect(normalizeLanguage('Latin')).toBe('la')
    expect(normalizeLanguage('Persian')).toBe('fa')
    expect(normalizeLanguage('Tagalog')).toBe('tl')
    expect(normalizeLanguage('Welsh')).toBe('cy')
    expect(normalizeLanguage('Yiddish')).toBe('yi')
    // The archive mirror's ISO-639-2/B codes of the same vocabulary.
    expect(normalizeLanguage('ice')).toBe('is')
    expect(normalizeLanguage('per')).toBe('fa')
    expect(normalizeLanguage('wel')).toBe('cy')
    expect(normalizeLanguage('lat')).toBe('la')
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
    // LibriVox's "Multilingual" is a collection label, not a language.
    expect(normalizeLanguage('Multilingual')).toBeNull()
  })
})
