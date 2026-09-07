/**
 * #583 W1.1 (R-W7) — the Налаштування tab: one home for the settings
 * directions, mirroring Android's `SettingsScreen` (one headline model —
 * the canonical TabHeader — plus a row list; preferences stay in their
 * modules). «Профіль» stops being a tab: the recovery code, the Progress
 * Sync switch and the binding status live in the profile direction's
 * sub-screen, like Android's ProfileScreen under the ⚙️ Налаштування.
 *
 * More directions join with their tickets (W5.2: Сховище, Мережа,
 * Рекомендації; Мови контенту already exists as chips on Огляд).
 *
 * Focus-return contract (Android `returnDestination`): closing a
 * direction returns focus to the row that opened it.
 */
import { useEffect, useRef, useState } from 'react'
import type { ListenerProfile } from '../identity/listenerIdentity'
import { EmptyState, TabHeader } from './components'
import { useTranslate } from '../i18n/locale'
import type { StringKey } from '../i18n/strings'

export type SettingsDestination = 'profile'

/** Android's order (Profile first); the missing destinations join with W5.2. */
export const SETTINGS_DESTINATIONS: readonly SettingsDestination[] = ['profile']

/** One label per destination — never a lookup keyed to the only existing row. */
const SETTINGS_DESTINATION_LABELS: Record<SettingsDestination, StringKey> = {
  profile: 'profileTitle',
}

/** The profile sub-screen: binding status, the recovery-code entry and the sync switch. */
export function ProfileDirection({ profile: initialProfile, onProfileChange, evicted = false, onLinked, onBack }: {
  profile: ListenerProfile | null
  onProfileChange?: (p: ListenerProfile) => void
  evicted?: boolean
  /** #581 W0.3 — fired after a Recovery-Code restore: the linking moment. */
  onLinked?: (uid: string) => void
  onBack: () => void
}) {
  const t = useTranslate()
  const [profile, setProfile] = useState(initialProfile)
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [restoring, setRestoring] = useState(false)
  const [syncEnabled, setSyncEnabled] = useState(() => {
    try {
      const raw = window.localStorage.getItem('slukhayka.progress_sync_enabled')
      if (raw === null) return true
      return raw !== '0' && raw !== 'false'
    } catch {
      return true
    }
  })

  useEffect(() => setProfile(initialProfile), [initialProfile])
  // Keep parent in sync when local profile changes (after restore)
  useEffect(() => {
    if (profile && initialProfile && profile.uid !== initialProfile.uid) {
      onProfileChange?.(profile)
    }
  }, [profile, initialProfile, onProfileChange])

  const isBound = profile !== null && !profile.uid.startsWith('local-')

  const handleRestore = async (): Promise<void> => {
    setError(null)
    setRestoring(true)
    try {
      const { createAuthGateway } = await import('../firebase/bootstrap')
      const { BrowserCredentialStore } = await import('../identity/credentialStore')
      const { restoreFromCode } = await import('../identity/listenerIdentity')
      const gateway = await createAuthGateway(import.meta.env)
      if (!gateway) {
        setError(t('firebaseNotConfigured'))
        return
      }
      const store = new BrowserCredentialStore(window.localStorage)
      const restored = await restoreFromCode(gateway, code, (pair) => store.save(pair))
      if (!restored) setError(t('restoreFailed'))
      else {
        setProfile(restored)
        // Also persist as current parent profile
        onProfileChange?.(restored)
        // #581 W0.3 — the linking moment: pre-link local rows union-merge
        // with the account's rows (favorites upload beside them, a phone's
        // deliberate hide wins every tie). Best-effort — a failure leaves
        // the binding done; the next pull catches up.
        onLinked?.(restored.uid)
      }
    } finally {
      setRestoring(false)
    }
  }

  const handleSyncToggle = (next: boolean): void => {
    try {
      window.localStorage.setItem('slukhayka.progress_sync_enabled', next ? '1' : '0')
    } catch {
      // degrade-never
    }
    setSyncEnabled(next)
    // Dispatch storage event for controller's isEnabled check if needed
  }

  const evictionNotice = evicted ? (
    <div role="alert" className="profile-card" style={{ borderColor: 'var(--bad)' }}>
      <span className="label">{t('storageEvictedTitle')}</span>
      <span className="value">{t('storageEvictedHint')}</span>
    </div>
  ) : null

  if (profile === null)
    return (
      <div>
        <div className="dest-header">
          <button type="button" className="back" onClick={onBack}>{t('back')}</button>
        </div>
        <TabHeader title={t('profileTitle')} />
        {evictionNotice}
        <EmptyState icon="👤" message={t('stubInProgress', { title: t('profileTitle') })} hint={t('profileStubWhat')} />
      </div>
    )

  return (
    <div>
      <div className="dest-header">
        <button type="button" className="back" onClick={onBack}>{t('back')}</button>
      </div>
      <TabHeader title={t('profileTitle')} />
      <div className="profile-card">
        <span className="label">{t('nickLabel')}</span>
        <span className="value">{profile.nickname}</span>
        <span className="label">{t('profileLabel')}</span>
        <span className="value">{profile.uid}</span>
      </div>
      <div style={{ marginTop: 16, display: 'flex', flexDirection: 'column', gap: 8 }}>
        <label htmlFor="profile-recovery-code" style={{ fontSize: 14, fontWeight: 600 }}>{t('restoreCodeLabel')}</label>
        <input
          id="profile-recovery-code"
          value={code}
          onChange={(e) => setCode(e.target.value)}
          placeholder="SLK1.…"
          style={{ padding: '8px', borderRadius: 8, border: '1px solid var(--line)', background: 'var(--surface)', color: 'var(--fg)' }}
        />
        <button
          onClick={() => void handleRestore()}
          disabled={code.trim().length < 10 || restoring}
          style={{ padding: '8px 12px', borderRadius: 8, border: 'none', background: 'var(--accent)', color: '#000', opacity: code.trim().length < 10 ? 0.5 : 1 }}
        >
          {restoring ? t('restoring') : t('restoreProfile')}
        </button>
        {error && <span style={{ color: 'var(--bad)', fontSize: 13 }}>{error}</span>}
        {!error && profile.uid.startsWith('local-') && <span style={{ color: 'var(--fg-dim)', fontSize: 13 }}>{t('enterCodeHint')}</span>}
        {!error && isBound && <span style={{ color: 'var(--fg-dim)', fontSize: 13 }}>{t('boundHint')}</span>}
      </div>
      {isBound && (
        <div style={{ marginTop: 20, padding: 12, border: '1px solid var(--line)', borderRadius: 8, background: 'var(--surface)' }}>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={syncEnabled}
              onChange={(e) => handleSyncToggle(e.target.checked)}
            />
            <span style={{ fontSize: 14, fontWeight: 600 }}>{t('syncTitle')}</span>
          </label>
          <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--fg-dim)' }}>
            {t('syncDescription')}
          </p>
        </div>
      )}
      {!isBound && (
        <p style={{ marginTop: 16, fontSize: 13, color: 'var(--fg-dim)' }}>
          {t('unboundHint')}
        </p>
      )}
    </div>
  )
}

/** The Налаштування tab: the one home for settings directions. */
export function Settings({ profile, onProfileChange, evicted = false, onLinked }: {
  profile: ListenerProfile | null
  onProfileChange?: (p: ListenerProfile) => void
  evicted?: boolean
  onLinked?: (uid: string) => void
}) {
  const t = useTranslate()
  const [destination, setDestination] = useState<SettingsDestination | null>(null)
  const rowRefs = useRef(new Map<SettingsDestination, HTMLButtonElement | null>())
  // The direction to hand focus back to when the list re-appears.
  const openedFrom = useRef<SettingsDestination | null>(null)

  // The focus-return contract: closing a direction returns focus to the
  // row that opened it (Android's returnDestination → rowFocus).
  useEffect(() => {
    if (destination !== null) {
      openedFrom.current = destination
      return
    }
    const origin = openedFrom.current
    openedFrom.current = null
    if (origin !== null) rowRefs.current.get(origin)?.focus()
  }, [destination])

  if (destination === 'profile') {
    return (
      <ProfileDirection
        profile={profile}
        onProfileChange={onProfileChange}
        evicted={evicted}
        onLinked={onLinked}
        onBack={() => setDestination(null)}
      />
    )
  }

  return (
    <div>
      <TabHeader title={t('tabSettings')} />
      <ul className="settings-list">
        {SETTINGS_DESTINATIONS.map((dest) => (
          <li key={dest}>
            <button
              type="button"
              className="settings-row"
              ref={(node) => { rowRefs.current.set(dest, node) }}
              onClick={() => setDestination(dest)}
            >
              <span>{t(SETTINGS_DESTINATION_LABELS[dest])}</span>
              <span className="settings-row-chevron" aria-hidden="true">›</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
