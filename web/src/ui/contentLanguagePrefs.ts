/**
 * spec-45 (#405) T13 (#501), spec-51 (#742) — the web Content Language
 * Preference, mirroring the Android `ContentLanguagePrefs` + the T4/T5 filter
 * rule + the T7 badge contract, in one client module.
 *
 * The preference is a persisted set of BCP-47 codes. An EMPTY set means ALL
 * languages («Усі» — the shipped default, exactly like Android): the toggle
 * can never strand the listener with nothing selected. Unknown-language rows
 * stay visible under any selection, because a source that does not declare a
 * language must not make its books vanish (US17).
 *
 * Spec-51 widened the vocabulary from a hardcoded uk/en pair to the ONE
 * normalizer's set; the offered list is dynamic (`availableLanguagesOf`) and
 * an old default selection of exactly `["uk","en"]` widens to «Усі» once
 * (spec-51 migration), so newly admitted languages are not hidden behind a
 * choice nobody made. A deliberately narrowed selection (e.g. `["uk"]`) is
 * never touched.
 *
 * Storage rides an injected Storage-like interface (localStorage in the
 * browser); corrupt payloads degrade to «Усі», never a crash.
 */

import type { UnifiedWork } from '../worker/types'
import { normalizeLanguage } from '../worker/language'

const KEY = 'slukhayka.content_languages'
const MIGRATED_KEY = 'slukhayka.content_languages.migrated'

/** The pre-spec-51 default answer: the only value the migration widens. */
const LEGACY_DEFAULT = ['uk', 'en']

/**
 * Reader-facing names for the filter chips. The two flagship languages keep
 * their curated labels; wider languages show their own name, and an unknown
 * code falls back to itself (honest, never blank) — the same idea as
 * Android's `contentLanguageLabel`.
 */
export const LANGUAGE_LABELS: Record<string, string> = {
  uk: 'Українська',
  en: 'English',
  de: 'Deutsch',
  fr: 'Français',
  es: 'Español',
  it: 'Italiano',
  pt: 'Português',
  nl: 'Nederlands',
  pl: 'Polski',
  ru: 'Русский',
  sv: 'Svenska',
  da: 'Dansk',
  no: 'Norsk',
  fi: 'Suomi',
  cs: 'Čeština',
  sk: 'Slovenčina',
  hu: 'Magyar',
  ro: 'Română',
  bg: 'Български',
  el: 'Ελληνικά',
  hr: 'Hrvatski',
  sr: 'Српски',
  sl: 'Slovenščina',
  et: 'Eesti',
  lv: 'Latviešu',
  lt: 'Lietuvių',
  tr: 'Türkçe',
  ar: 'العربية',
  he: 'עברית',
  zh: '中文',
  ja: '日本語',
  af: 'Afrikaans',
  hy: 'Հայերեն',
  bn: 'বাংলা',
  ca: 'Català',
  eo: 'Esperanto',
  hi: 'हिन्दी',
  is: 'Íslenska',
  id: 'Bahasa Indonesia',
  ga: 'Gaeilge',
  jv: 'Basa Jawa',
  kn: 'ಕನ್ನಡ',
  ko: '한국어',
  la: 'Latina',
  ml: 'മലയാളം',
  mr: 'मराठी',
  fa: 'فارسی',
  su: 'Basa Sunda',
  tl: 'Tagalog',
  ta: 'தமிழ்',
  te: 'తెలుగు',
  ur: 'اردو',
  vi: 'Tiếng Việt',
  cy: 'Cymraeg',
  yi: 'ייִדיש',
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

/** The persisted selection; absent/corrupt storage falls back to «Усі». */
export function loadContentLanguagePrefs(storage: StorageLike = defaultStorage()): string[] {
  try {
    migrateLegacyDefault(storage)
    const raw = storage.getItem(KEY)
    if (raw === null) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((code): code is string => typeof code === 'string' && code !== '')
  } catch {
    return []
  }
}

/**
 * Spec-51 (#742) — the one-time migration. A stored selection of exactly
 * `["uk","en"]` is the OLD default: «both on», the only «Усі» the app had
 * when those were the only two languages. It widens to «Усі» once, so newly
 * admitted languages cannot hide behind a choice nobody made. The marker
 * makes this fire exactly once — a later, deliberate uk+en selection stays
 * exactly that. A narrowed selection (e.g. `["uk"]`) is never touched.
 */
function migrateLegacyDefault(storage: StorageLike): void {
  try {
    if (storage.getItem(MIGRATED_KEY) === 'true') return
    storage.setItem(MIGRATED_KEY, 'true')
    const raw = storage.getItem(KEY)
    if (raw === null) return
    const parsed: unknown = JSON.parse(raw)
    if (
      Array.isArray(parsed) &&
      parsed.length === LEGACY_DEFAULT.length &&
      parsed.every((code, index) => code === LEGACY_DEFAULT[index])
    ) {
      storage.setItem(KEY, JSON.stringify([]))
    }
  } catch {
    // degrade-never: a broken payload is simply left for the reader below
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
 * `uk` first, `en` second, then the rest alphabetically — the ONE ordering
 * rule shared with Android's `orderContentLanguages`.
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
  return { label: code.toUpperCase(), name: LANGUAGE_LABELS[code] ?? code }
}
