/**
 * spec-45 (#405) T13 (#501) — the web Content Language Preference, mirroring
 * the Android `ContentLanguagePrefs` + the T4/T5 filter rule + the T7 badge
 * contract, in one client module.
 *
 * The preference is a persisted set of BCP-47 codes ("both on" default, i.e.
 * `["uk","en"]`); an EMPTY set means ALL languages ("empty = all" — the
 * toggle can never strand the listener with nothing selected). Unknown-
 * language rows stay visible under any selection, because a source that does
 * not declare a language must not make its books vanish (US17).
 *
 * Storage rides an injected Storage-like interface (localStorage in the
 * browser); corrupt payloads degrade to the default, never a crash.
 */

import type { UnifiedWork } from '../worker/types'
import { normalizeLanguage } from '../worker/language'

const KEY = 'slukhayka.content_languages'

/** The languages the toggle offers today (the LibriVox start: uk + en). */
export const CONTENT_LANGUAGES = ['uk', 'en'] as const

export const LANGUAGE_LABELS: Record<string, string> = {
  uk: 'Українська',
  en: 'English',
}

export interface StorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

function defaultStorage(): StorageLike {
  return {
    getItem: (key) => (typeof localStorage === 'undefined' ? null : localStorage.getItem(key)),
    setItem: (key, value) => {
      if (typeof localStorage !== 'undefined') localStorage.setItem(key, value)
    },
  }
}

/** The persisted selection; absent/corrupt storage falls back to both-on. */
export function loadContentLanguagePrefs(storage: StorageLike = defaultStorage()): string[] {
  try {
    const raw = storage.getItem(KEY)
    if (raw === null) return [...CONTENT_LANGUAGES]
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return [...CONTENT_LANGUAGES]
    return parsed.filter((code): code is string => typeof code === 'string' && code !== '')
  } catch {
    return [...CONTENT_LANGUAGES]
  }
}

export function saveContentLanguagePrefs(
  selection: readonly string[],
  storage: StorageLike = defaultStorage(),
): string[] {
  const saved = [...new Set(selection)]
  try {
    storage.setItem(KEY, JSON.stringify(saved))
  } catch {
    // degrade-never: the in-memory selection still applies this session
  }
  return saved
}

/** Empty selection = all languages (the ticket's "empty = all"). */
export function isAllLanguages(selection: readonly string[]): boolean {
  return selection.length === 0
}

export function toggleLanguage(selection: readonly string[], code: string): string[] {
  return selection.includes(code)
    ? selection.filter((candidate) => candidate !== code)
    : [...selection, code]
}

/**
 * The T4/T5 filter rule on the web's Work shape: a Work stays visible unless
 * EVERY edition carries a known language outside the selection. An edition
 * with no language (or an empty selection = all) always passes.
 */
export function filterWorksByLanguage(
  works: readonly UnifiedWork[],
  selection: readonly string[],
): UnifiedWork[] {
  if (isAllLanguages(selection)) return [...works]
  const selected = new Set(selection)
  return works.filter((work) =>
    work.editions.some((edition) => !edition.language || selected.has(edition.language)),
  )
}

/**
 * The languages the filters UI offers: those present in the loaded content
 * plus any still-selected one (a selection is never stranded invisibly).
 * `uk` first, `en` second, then the rest alphabetically.
 */
export function availableLanguagesOf(
  works: readonly UnifiedWork[],
  selection: readonly string[],
): string[] {
  const found = new Set<string>(selection.filter((code) => code !== ''))
  for (const work of works) {
    for (const edition of work.editions) {
      if (edition.language !== undefined) found.add(edition.language)
    }
  }
  const order = new Map([['uk', 0], ['en', 1]])
  return [...found].sort(
    (left, right) =>
      (order.get(left) ?? 9) - (order.get(right) ?? 9) || left.localeCompare(right),
  )
}

export interface LanguageBadge {
  /** The visible two-letter code — explicit map so `uk` never renders "UK". */
  label: string
  /** The full language name announced to assistive tech. */
  name: string
}

/**
 * The T7 badge contract: only a KNOWN (normalized) language yields a badge;
 * unknown renders nothing — the honest absence. EN/UA are the spec's codes;
 * other canonical tags show their own uppercase tag.
 */
export function badgeLabel(language: string | undefined): LanguageBadge | null {
  const code = normalizeLanguage(language)
  if (code === null) return null
  if (code === 'uk') return { label: 'UA', name: LANGUAGE_LABELS.uk }
  if (code === 'en') return { label: 'EN', name: LANGUAGE_LABELS.en }
  return { label: code.toUpperCase(), name: code }
}