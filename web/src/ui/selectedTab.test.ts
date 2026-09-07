/**
 * #583 W1.1 (R-W7) — SelectedTab: Android's four-tab order verbatim and a
 * persisted selection that degrades to the default on anything unexpected.
 */
import { describe, expect, it } from 'vitest'
import {
  DEFAULT_SELECTED_TAB,
  loadSelectedTab,
  saveSelectedTab,
  SELECTED_TAB_ORDER,
  type SelectedTab,
} from './selectedTab'

function memoryStorage(initial: Record<string, string> = {}): Storage {
  const data = new Map<string, string>(Object.entries(initial))
  return {
    get length(): number {
      return data.size
    },
    clear(): void {
      data.clear()
    },
    getItem: (key: string) => data.get(key) ?? null,
    key: (index: number) => Array.from(data.keys())[index] ?? null,
    removeItem: (key: string) => void data.delete(key),
    setItem: (key: string, value: string) => void data.set(key, value),
  }
}

describe('SelectedTab', () => {
  it('mirrors Android’s enum order: Слухати · Огляд · Медіатека · Налаштування', () => {
    expect(SELECTED_TAB_ORDER).toEqual(['listen', 'explore', 'library', 'settings'])
  })

  it('round-trips the persisted selection', () => {
    const storage = memoryStorage()
    saveSelectedTab('settings', storage)
    expect(loadSelectedTab(storage)).toBe('settings')
    saveSelectedTab('listen', storage)
    expect(loadSelectedTab(storage)).toBe('listen')
  })

  it('falls back to the default when nothing is persisted', () => {
    expect(loadSelectedTab(memoryStorage())).toBe(DEFAULT_SELECTED_TAB)
    expect(DEFAULT_SELECTED_TAB).toBe('explore')
  })

  it('ignores stale or unknown ids from older releases', () => {
    // «Профіль» used to be a tab (#583 W1.1) — its old value must degrade.
    expect(loadSelectedTab(memoryStorage({ 'slukhayka.selected_tab': 'profile' }))).toBe(DEFAULT_SELECTED_TAB)
    expect(loadSelectedTab(memoryStorage({ 'slukhayka.selected_tab': '42' }))).toBe(DEFAULT_SELECTED_TAB)
  })

  it('degrades to the default when storage throws', () => {
    const broken: Pick<Storage, 'getItem'> = { getItem: () => { throw new Error('denied') } }
    expect(loadSelectedTab(broken)).toBe(DEFAULT_SELECTED_TAB)
    expect(() => saveSelectedTab('listen' satisfies SelectedTab, { setItem: () => { throw new Error('denied') } })).not.toThrow()
  })
})
