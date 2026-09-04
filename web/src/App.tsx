import { useEffect, useMemo, useRef, useState } from 'react'
import type { ListenerProfile } from './identity/listenerIdentity'
import { Catalog } from './ui/Catalog'
import { BookPage } from './ui/BookPage'
import { AudioEngine } from './player/audioEngine'
import { MiniPlayer } from './ui/MiniPlayer'
import { PlayerSheet } from './ui/PlayerSheet'
import type { BookDetail, SourceId } from './worker/types'
import { LocalListeningStateStore } from './player/localState'
import { BrowserProgressSyncLedger } from './sync/ledger'
import { ProgressSyncSettings } from './sync/settings'
import { FirestoreProgressSyncStore } from './sync/store'
import { ProgressSyncController } from './sync/controller'
import { getFirestoreForEnv } from './firebase/firestore'
import { editionIdFor, mergeKeyFor } from './sync/edition'
import { setUiLocale, useTranslate, useUiLocale } from './i18n/locale'
import { translate } from './i18n/strings'

type Tab = 'listen' | 'catalog' | 'profile'

function Stub({ title, what }: { title: string; what: string }) {
  const t = useTranslate()
  return (
    <div className="placeholder">
      {t('stubInProgress', { title })}
      <br />
      {what}
    </div>
  )
}

function Profile({
  profile: initialProfile,
  onProfileChange,
}: {
  profile: ListenerProfile | null
  onProfileChange?: (p: ListenerProfile) => void
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
      const { createAuthGateway } = await import('./firebase/bootstrap')
      const { BrowserCredentialStore } = await import('./identity/credentialStore')
      const { restoreFromCode } = await import('./identity/listenerIdentity')
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

  if (profile === null) return <Stub title={t('tabProfile')} what={t('profileStubWhat')} />

  return (
    <div>
      <div className="profile-card">
        <span className="label">{t('nickLabel')}</span>
        <span className="value">{profile.nickname}</span>
        <span className="label">{t('profileLabel')}</span>
        <span className="value">{profile.uid}</span>
      </div>
      <div style={{ marginTop: 16, display: 'flex', flexDirection: 'column', gap: 8 }}>
        <label style={{ fontSize: 14, fontWeight: 600 }}>{t('restoreCodeLabel')}</label>
        <input
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

export function App({ profile: initialProfile }: { profile: ListenerProfile | null }) {
  const t = useTranslate()
  const locale = useUiLocale()
  const [profile, setProfile] = useState(initialProfile)
  useEffect(() => setProfile(initialProfile), [initialProfile])

  const TABS: Array<{ id: Tab; label: string }> = [
    { id: 'listen', label: t('tabListen') },
    { id: 'catalog', label: t('tabCatalog') },
    { id: 'profile', label: t('tabProfile') },
  ]

  const [tab, setTab] = useState<Tab>('catalog')
  const [book, setBook] = useState<{ url: string; source: SourceId } | null>(null)
  const bookUrl = book?.url ?? null
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const engineRef = useRef<AudioEngine | null>(null)
  const [playerOpen, setPlayerOpen] = useState(false)
  const [, forceUpdate] = useState(0)

  // Shared local store for engine + sync mirror (same localStorage backing).
  const localStore = useMemo(() => {
    const storageLike = {
      getItem: (k: string) => window.localStorage.getItem(k),
      setItem: (k: string, v: string) => window.localStorage.setItem(k, v),
      removeItem: (k: string) => window.localStorage.removeItem(k),
    }
    return new LocalListeningStateStore(storageLike as never)
  }, [])

  const ledger = useMemo(() => new BrowserProgressSyncLedger(window.localStorage), [])
  const settings = useMemo(() => new ProgressSyncSettings(window.localStorage), [])
  const firestore = useMemo(() => getFirestoreForEnv(import.meta.env), [])
  const syncStore = useMemo(() => (firestore ? new FirestoreProgressSyncStore(firestore) : null), [firestore])

  // Identity reads current profile (updated after restore) — no writes before binding.
  const profileRef = useRef(profile)
  useEffect(() => {
    profileRef.current = profile
  }, [profile])

  const syncController = useMemo(() => {
    const mirror = {
      editionIdForSync: (bookId: string) => bookId,
      progressByEdition: (editionId: string) => {
        const snap = localStore.load(editionId)
        if (!snap) return null
        return {
          editionId,
          chapterIndex: snap.chapterIndex,
          positionSeconds: snap.positionSeconds,
          isCompleted: snap.isCompleted,
          preferredSpeed: snap.preferredSpeed,
          updatedAtServerMs: 0,
        }
      },
      applyRemoteProgress: (_bookId: string, remote: import('./sync/policy').RemoteListeningState) => {
        localStore.save({
          editionId: remote.editionId,
          chapterIndex: remote.chapterIndex,
          positionSeconds: remote.positionSeconds,
          isCompleted: remote.isCompleted,
          preferredSpeed: remote.preferredSpeed,
          lastPausedAtEpochMs: null,
        })
      },
    }
    return new ProgressSyncController(
      { getUid: () => profileRef.current?.uid ?? null },
      mirror,
      syncStore,
      ledger,
      () => settings.isEnabled(),
    )
  }, [localStore, ledger, settings, syncStore])

  if (!engineRef.current) {
    engineRef.current = new AudioEngine({ relayBase: '/api', store: localStore })
  }
  const engine = engineRef.current
  // Keep engine's sync controller in sync with current profile/settings.
  // Set synchronously so the first loadBook after restore sees the bound controller.
  engine.setSyncController(syncController)

  useEffect(() => {
    document.title = translate(locale, 'docTitle')
    const unsub = engine.subscribe(() => forceUpdate((n) => n + 1))
    return unsub
  }, [engine, locale])

  useEffect(() => {
    if (audioRef.current) engine.attachAudio(audioRef.current)
  }, [engine])

  const handlePlay = async (detail: BookDetail, chapterIndex: number): Promise<boolean> => {
    const mergeKey = mergeKeyFor(detail.title, detail.author)
    const editionId = editionIdFor(mergeKey, detail.url, detail.narrator ?? '')
    const playing = await engine.loadBookAndAwaitPlaying(
      { title: detail.title, chapters: detail.chapters, editionId },
      chapterIndex,
    )
    if (playing) setPlayerOpen(true)
    return playing
  }
  return (
    <>
      <audio ref={audioRef} preload="metadata" style={{ display: 'none' }} />
      <header className="app-header">
        {bookUrl !== null ? (
          <button className="back" onClick={() => setBook(null)}>
            {t('back')}
          </button>
        ) : (
          <>
            <h1 className="app-title">Слухайка</h1>
            <p className="app-subtitle">{t('appSubtitle')}</p>
          </>
        )}
        <button
          onClick={() => setUiLocale(locale === 'uk' ? 'en' : 'uk')}
          aria-label={t('langSwitchAria')}
          style={{ marginLeft: 'auto', background: 'none', border: '1px solid var(--line)', borderRadius: 999, padding: '4px 10px', color: 'var(--fg)' }}
        >
          {locale === 'uk' ? 'EN' : 'UA'}
        </button>
      </header>
      <main className="surface">
        {book !== null ? (
          <BookPage url={book.url} source={book.source} onOpenBook={(url, source) => setBook({ url, source })} onPlay={handlePlay} />
        ) : tab === 'listen' ? (
          <Stub title={t('listenStubTitle')} what={t('listenStubWhat')} />
        ) : tab === 'catalog' ? (
          <Catalog onOpenBook={(url, source) => setBook({ url, source })} onPlay={handlePlay} />
        ) : (
          <Profile profile={profile} onProfileChange={setProfile} />
        )}
      </main>
      <MiniPlayer engine={engine} onExpand={() => setPlayerOpen(true)} />
      {playerOpen && <PlayerSheet engine={engine} onClose={() => setPlayerOpen(false)} />}
      {book === null && (
        <nav className="tab-bar" role="tablist">
          {TABS.map((t) => (
            <button key={t.id} role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}>
              {t.label}
            </button>
          ))}
        </nav>
      )}
    </>
  )
}
