// @vitest-environment jsdom
/**
 * #583 W1.1 — the App bottom bar is Android's `SelectedTab` verbatim:
 * four tabs, Android's names and order, the selection persisted, and
 * «Профіль» gone from the bar (it lives in Налаштування now).
 */
import 'fake-indexeddb/auto'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { App } from '../App'
import { setUiLocale } from '../i18n/locale'
import type { ListenerProfile } from '../identity/listenerIdentity'

const profile: ListenerProfile = { uid: 'local-abc123', nickname: 'Слухач-1' }

afterEach(() => {
  cleanup()
  window.localStorage.clear()
  setUiLocale('uk')
})

describe('App tab bar', () => {
  it('renders the four Android tabs in order and nothing else', async () => {
    setUiLocale('uk')
    render(<App profile={profile} />)
    await waitFor(() => expect(screen.getAllByRole('tab')).toHaveLength(4))
    const tabs = screen.getAllByRole('tab')
    expect(tabs.map((tab) => tab.textContent)).toEqual(['Слухати', 'Огляд', 'Медіатека', 'Налаштування'])
    // The persisted default lands on Огляд until W2.1 fills Слухати.
    expect(tabs[1]!.getAttribute('aria-selected')).toBe('true')
    // A bound session's profile sub-screen belongs to Налаштування now —
    // the bar carries no fourth «Профіль» destination.
    expect(tabs.filter((tab) => tab.textContent === 'Профіль')).toHaveLength(0)
  })

  it('switches tab content and persists the choice', async () => {
    setUiLocale('uk')
    const user = userEvent.setup()
    render(<App profile={profile} />)
    await waitFor(() => expect(screen.getAllByRole('tab')).toHaveLength(4))

    await user.click(screen.getByRole('tab', { name: 'Налаштування' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Налаштування' })).toBeTruthy()
    expect(window.localStorage.getItem('slukhayka.selected_tab')).toBe('settings')

    await user.click(screen.getByRole('tab', { name: 'Слухати' }))
    expect(await screen.findByText('Продовження слухання')).toBeTruthy()
    expect(window.localStorage.getItem('slukhayka.selected_tab')).toBe('listen')
  })

  it('restores the persisted selection on remount', async () => {
    setUiLocale('uk')
    window.localStorage.setItem('slukhayka.selected_tab', 'library')
    render(<App profile={profile} />)
    await waitFor(() => expect(screen.getAllByRole('tab')).toHaveLength(4))
    const tabs = screen.getAllByRole('tab')
    expect(tabs[2]!.getAttribute('aria-selected')).toBe('true')
    // Медіатека renders the canonical empty state until W1.2 fills it.
    expect(screen.getByText('Медіатека ще в роботі.')).toBeTruthy()
  })

  it('ignores a stale tab id from an older release', async () => {
    setUiLocale('uk')
    window.localStorage.setItem('slukhayka.selected_tab', 'profile')
    render(<App profile={profile} />)
    await waitFor(() => expect(screen.getAllByRole('tab')).toHaveLength(4))
    const tabs = screen.getAllByRole('tab')
    expect(tabs[1]!.getAttribute('aria-selected')).toBe('true')
  })

  it('keeps the i18n labels aligned with Android across locales', async () => {
    setUiLocale('en')
    render(<App profile={profile} />)
    await waitFor(() => expect(screen.getAllByRole('tab')).toHaveLength(4))
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(
      ['Listen', 'Explore', 'Library', 'Settings'],
    )
  })
})
