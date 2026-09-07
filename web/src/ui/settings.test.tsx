// @vitest-environment jsdom
/**
 * #583 W1.1 — the Налаштування tab: «Профіль» is a direction, not a tab.
 * Covers the direction list, the profile sub-screen's binding status /
 * recovery-code entry / sync switch, and the focus-return contract
 * (Android's returnDestination → rowFocus).
 */
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { Settings } from './Settings'
import { setUiLocale } from '../i18n/locale'
import type { ListenerProfile } from '../identity/listenerIdentity'

const localProfile: ListenerProfile = { uid: 'local-abc123', nickname: 'Слухач-1' }
const boundProfile: ListenerProfile = { uid: 'uid-xyz789', nickname: 'Слухач-1' }

beforeEach(() => {
  setUiLocale('uk')
  window.localStorage.clear()
})

afterEach(() => {
  cleanup()
})

describe('Settings', () => {
  it('renders the tab header and the «Профіль» direction row', () => {
    render(<Settings profile={localProfile} />)
    expect(screen.getByRole('heading', { level: 1, name: 'Налаштування' })).toBeTruthy()
    expect(screen.getByRole('button', { name: /Профіль/ })).toBeTruthy()
  })

  it('opens the profile direction: recovery code, binding status and the sync switch', async () => {
    const user = userEvent.setup()
    render(<Settings profile={boundProfile} />)
    await user.click(screen.getByRole('button', { name: /Профіль/ }))

    expect(screen.getByRole('heading', { level: 1, name: 'Профіль' })).toBeTruthy()
    expect(screen.getByLabelText('Код відновлення з телефону')).toBeTruthy()
    // Binding status: a bound profile shows the bound hint and the sync switch.
    expect(screen.getByText('Профіль прив’язано — ваш нік і відгуки тепер тут.')).toBeTruthy()
    const syncToggle = screen.getByRole('checkbox', { name: 'Синхронізація прогресу' }) as HTMLInputElement
    expect(syncToggle.checked).toBe(true)
    // The sync switch persists its own honest preference (ADR-0023).
    await user.click(syncToggle)
    expect(window.localStorage.getItem('slukhayka.progress_sync_enabled')).toBe('0')
  })

  it('an unbound (local) profile offers the code entry and hides the sync switch', async () => {
    const user = userEvent.setup()
    render(<Settings profile={localProfile} />)
    await user.click(screen.getByRole('button', { name: /Профіль/ }))

    expect(screen.getByText(/Введіть код з ⚙️ Профіль на телефоні/)).toBeTruthy()
    expect(screen.getByText(/Поки профіль не прив’язано/)).toBeTruthy()
    expect(screen.queryByRole('checkbox', { name: 'Синхронізація прогресу' })).toBeNull()
  })

  it('a null profile renders the honest stub, not the code entry', async () => {
    const user = userEvent.setup()
    render(<Settings profile={null} />)
    await user.click(screen.getByRole('button', { name: /Профіль/ }))

    expect(screen.getByText('Профіль ще в роботі.')).toBeTruthy()
    expect(screen.queryByLabelText('Код відновлення з телефону')).toBeNull()
  })

  it('back returns to the directions list and focus returns to the opening row', async () => {
    const user = userEvent.setup()
    render(<Settings profile={localProfile} />)
    await user.click(screen.getByRole('button', { name: /Профіль/ }))
    expect(screen.getByRole('heading', { level: 1, name: 'Профіль' })).toBeTruthy()

    await user.click(screen.getByRole('button', { name: '← Назад' }))
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1, name: 'Налаштування' })).toBeTruthy()
      expect(document.activeElement?.textContent).toContain('Профіль')
    })
  })
})
