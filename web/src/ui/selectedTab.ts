/**
 * #583 W1.1 (R-W7) — the web's bottom-bar structure is Android's
 * `SelectedTab` verbatim: Слухати / Огляд / Медіатека / Налаштування, in
 * Android's enum order (listening first, spec-9 IA). «Профіль» is not a
 * tab — the recovery code and the sync switch live as a Налаштування
 * direction (ui/Settings.tsx). The selected tab persists, so a returning
 * listener lands where they left off.
 */

/** Mirrors Android's `SelectedTab` enum — the order defines the bottom bar. */
export type SelectedTab = 'listen' | 'explore' | 'library' | 'settings'

export const SELECTED_TAB_ORDER: readonly SelectedTab[] = ['listen', 'explore', 'library', 'settings']

const STORAGE_KEY = 'slukhayka.selected_tab'

/** The interim default until W2.1 fills Слухати: Огляд is the tab with content. */
export const DEFAULT_SELECTED_TAB: SelectedTab = 'explore'

function isSelectedTab(value: unknown): value is SelectedTab {
  return typeof value === 'string' && (SELECTED_TAB_ORDER as readonly string[]).includes(value)
}

/**
 * Restores the persisted tab; anything unexpected (missing key, stale id
 * from an older release, storage off) falls back to the default — never a
 * broken bar.
 */
export function loadSelectedTab(storage: Pick<Storage, 'getItem'> = window.localStorage): SelectedTab {
  try {
    const raw = storage.getItem(STORAGE_KEY)
    if (isSelectedTab(raw)) return raw
  } catch {
    // degrade-never: the default applies this session
  }
  return DEFAULT_SELECTED_TAB
}

/** Persists the listener's tab choice; a failed write never breaks navigation. */
export function saveSelectedTab(tab: SelectedTab, storage: Pick<Storage, 'setItem'> = window.localStorage): void {
  try {
    storage.setItem(STORAGE_KEY, tab)
  } catch {
    // degrade-never
  }
}
