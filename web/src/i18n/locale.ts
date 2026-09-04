/**
 * spec-45 (#405) T14 (#502) — the web UI locale (mirror of the Android
 * `AppLocale`): English is the default on English-speaking devices (US15),
 * Ukrainian everywhere else; a manual override is persisted locally and wins
 * over the device language. UI language never filters content (US12) — it
 * only picks which [STRINGS] the interface renders.
 *
 * A tiny module store + `useSyncExternalStore` keeps every component
 * subscribed with zero prop drilling; the header toggle re-renders the whole
 * tree on switch.
 */
import { useSyncExternalStore } from 'react'
import { STRINGS, translate, type StringKey, type Strings, type UiLocale } from './strings'

const KEY = 'slukhayka.ui_language'

/**
 * The device resolution: the persisted override wins; otherwise English
 * devices get English, everything else Ukrainian (the Android AppLocale
 * default). Exported for tests; callers use [getUiLocale].
 */
export function resolveUiLocale(): UiLocale {
  try {
    const raw = localStorage.getItem(KEY)
    if (raw === 'uk' || raw === 'en') return raw
  } catch {
    // fall through to device language
  }
  try {
    const device = navigator.language?.toLowerCase() ?? ''
    return device.startsWith('en') ? 'en' : 'uk'
  } catch {
    return 'uk'
  }
}

// Lazy init: the first read resolves from the current device/storage state,
// so a test (or an embedder) can pin the locale before anything renders.
let current: UiLocale | null = null
const listeners = new Set<() => void>()

export function getUiLocale(): UiLocale {
  if (current === null) current = resolveUiLocale()
  return current
}

export function setUiLocale(locale: UiLocale): void {
  current = locale
  try {
    localStorage.setItem(KEY, locale)
  } catch {
    // degrade-never: the in-memory locale still applies this session
  }
  for (const listener of listeners) listener()
}

export function subscribeUiLocale(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** The current locale, live: switching re-renders every subscriber. */
export function useUiLocale(): UiLocale {
  return useSyncExternalStore(subscribeUiLocale, getUiLocale, getUiLocale)
}

/** The current locale's string table. */
export function useStrings(): Strings {
  return STRINGS[useUiLocale()]
}

/** Interpolated string for the current locale (components' one call site). */
export function useTranslate(): (key: StringKey, params?: Readonly<Record<string, string | number>>) => string {
  const locale = useUiLocale()
  return (key, params) => translate(locale, key, params)
}