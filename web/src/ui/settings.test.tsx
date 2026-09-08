// @vitest-environment jsdom
/**
 * #583 W1.1 — the Налаштування tab: «Профіль» is a direction, not a tab.
 * Covers the direction list, the profile sub-screen's binding status /
 * recovery-code entry / sync switch, and the focus-return contract
 * (Android's returnDestination → rowFocus).
 */
import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { Settings } from './Settings'
import { DomainStore } from '../local/domain'
import { RecommendationPrefsStore } from '../local/recommendationPrefs'
import { RecommendationParticipation } from '../recommend/participation'
import { IdbListeningStateStore } from '../local/listeningState'
import { HybridListeningStateStorage } from '../local/hybridListeningState'
import { setUiLocale } from '../i18n/locale'
import type { ListenerProfile } from '../identity/listenerIdentity'

const localProfile: ListenerProfile = { uid: 'local-abc123', nickname: 'Слухач-1' }
const boundProfile: ListenerProfile = { uid: 'uid-xyz789', nickname: 'Слухач-1' }

const recPrefs = (): RecommendationPrefsStore => new RecommendationPrefsStore()
const domain = (): DomainStore => new DomainStore()
/** #592 W6.1 — the participation consent, fresh per render. */
const participation = (): RecommendationParticipation => new RecommendationParticipation(window.localStorage)

/** #591 W5.2 — the storage direction's seams, shared by every render. */
const hybrid = (): HybridListeningStateStorage =>
  new HybridListeningStateStorage(new IdbListeningStateStore(), window.localStorage)
const idbStore = (): IdbListeningStateStore => new IdbListeningStateStore()

const renderSettings = (profile: ListenerProfile | null = localProfile): void => {
  render(
    <Settings
      profile={profile}
      recommendationPrefs={recPrefs()}
      domainStore={domain()}
      participation={participation()}
      hybrid={hybrid()}
      idbStore={idbStore()}
      storage={window.localStorage}
    />,
  )
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  setUiLocale('uk')
  window.localStorage.clear()
})

afterEach(() => {
  cleanup()
})

describe('Settings', () => {
  it('renders the tab header and the direction rows in Android order (Профіль, Сховище, Приватність, Рекомендації)', () => {
    renderSettings()
    expect(screen.getByRole('heading', { level: 1, name: 'Налаштування' })).toBeTruthy()
    expect(screen.getByRole('button', { name: /Профіль/ })).toBeTruthy()
    expect(screen.getByRole('button', { name: /Сховище/ })).toBeTruthy()
    expect(screen.getByRole('button', { name: /Приватність/ })).toBeTruthy()
    expect(screen.getByRole('button', { name: /Персональні рекомендації/ })).toBeTruthy()
    // Android's order: Профіль, Сховище, Приватність, Рекомендації.
    const rows = screen
      .getAllByRole('button')
      .filter((row) => row.className.includes('settings-row'))
      .map((row) => row.textContent)
    expect(rows).toEqual([
      expect.stringContaining('Профіль'),
      expect.stringContaining('Сховище'),
      expect.stringContaining('Приватність'),
      expect.stringContaining('Персональні рекомендації'),
    ])
  })

  it('opens the profile direction: recovery code, binding status and the sync switch', async () => {
    const user = userEvent.setup()
    renderSettings(boundProfile)
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
    renderSettings()
    await user.click(screen.getByRole('button', { name: /Профіль/ }))

    expect(screen.getByText(/Введіть код з ⚙️ Профіль на телефоні/)).toBeTruthy()
    expect(screen.getByText(/Поки профіль не прив’язано/)).toBeTruthy()
    expect(screen.queryByRole('checkbox', { name: 'Синхронізація прогресу' })).toBeNull()
  })

  it('a null profile renders the honest stub, not the code entry', async () => {
    const user = userEvent.setup()
    renderSettings(null)
    await user.click(screen.getByRole('button', { name: /Профіль/ }))

    expect(screen.getByText('Профіль ще в роботі.')).toBeTruthy()
    expect(screen.queryByLabelText('Код відновлення з телефону')).toBeNull()
  })

  it('back returns to the directions list and focus returns to the opening row', async () => {
    const user = userEvent.setup()
    renderSettings()
    await user.click(screen.getByRole('button', { name: /Профіль/ }))
    expect(screen.getByRole('heading', { level: 1, name: 'Профіль' })).toBeTruthy()

    await user.click(screen.getByRole('button', { name: '← Назад' }))
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1, name: 'Налаштування' })).toBeTruthy()
      expect(document.activeElement?.textContent).toContain('Профіль')
    })
  })

  it('«Рекомендації» lists the hidden works and restores them; back returns focus to the row', async () => {
    const user = userEvent.setup()
    const prefs = recPrefs()
    const store = domain()
    await store.addLibraryEntry({ title: 'Чужинець', author: 'Камю' })
    await prefs.add('HIDE_WORK', 'чужинець|камю', 'чужинець|камю')
    await prefs.add('HIDE_WORK', 'пустеля|камю', 'пустеля|камю')

    render(
      <Settings
        profile={localProfile}
        recommendationPrefs={prefs}
        domainStore={store}
        participation={participation()}
        hybrid={hybrid()}
        idbStore={idbStore()}
        storage={window.localStorage}
      />,
    )
    await user.click(screen.getByRole('button', { name: /Персональні рекомендації/ }))

    expect(screen.getByRole('heading', { level: 1, name: 'Персональні рекомендації' })).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'Приховане вами' })).toBeTruthy()
    // The kind label appears once per hidden work (both are HIDE_WORK).
    expect(screen.getAllByText('Не рекомендувати цю книгу')).toHaveLength(2)
    // The real title resolves from the local domain, not the raw mergeKey.
    expect(screen.getByText('Чужинець')).toBeTruthy()

    // Restore one: the row leaves the list, the other stays (its name is
    // the honest fallback — no domain row for that mergeKey).
    await user.click(screen.getByRole('button', { name: 'Повернути: Чужинець' }))
    await waitFor(() => expect(screen.queryByText('Чужинець')).toBeNull())
    expect(screen.getByText('пустеля|камю')).toBeTruthy()
    expect(await prefs.all()).toHaveLength(1)

    // Back returns to the list with focus on the Рекомендації row.
    await user.click(screen.getByRole('button', { name: '← Назад' }))
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1, name: 'Налаштування' })).toBeTruthy()
      expect(document.activeElement?.textContent).toContain('Персональні рекомендації')
    })
  })

  it('«Рекомендації» shows the honest empty state when nothing is hidden', async () => {
    const user = userEvent.setup()
    renderSettings()
    await user.click(screen.getByRole('button', { name: /Персональні рекомендації/ }))

    expect(await screen.findByText('Нічого не приховано')).toBeTruthy()
  })

  it('«Рекомендації» carries the participation switch, OFF by default, persisted and revocable', async () => {
    const user = userEvent.setup()
    const consent = participation()
    expect(consent.isEnabled()).toBe(false)
    render(
      <Settings
        profile={localProfile}
        recommendationPrefs={recPrefs()}
        domainStore={domain()}
        participation={consent}
        hybrid={hybrid()}
        idbStore={idbStore()}
        storage={window.localStorage}
      />,
    )
    await user.click(screen.getByRole('button', { name: /Персональні рекомендації/ }))

    const toggle = screen.getByRole('checkbox', { name: /Допомагати покращувати спільні рекомендації/ }) as HTMLInputElement
    expect(toggle).toBeTruthy()
    expect(toggle.checked).toBe(false)
    expect(screen.getByText('Не беру участі')).toBeTruthy()
    expect(screen.getByText(/згода стосується майбутнього серверного профілю/)).toBeTruthy()

    // ON: the consent persists and the state description flips.
    await user.click(toggle)
    expect(toggle.checked).toBe(true)
    expect(consent.isEnabled()).toBe(true)
    expect(screen.getByText('Згоду збережено локально')).toBeTruthy()

    // Revocation stops contributions — the switch flips back, local recs stay.
    await user.click(toggle)
    expect(toggle.checked).toBe(false)
    expect(consent.isEnabled()).toBe(false)
    expect(screen.getByText('Не беру участі')).toBeTruthy()
  })
})
