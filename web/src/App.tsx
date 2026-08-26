import { useEffect, useRef, useState } from 'react'
import type { ListenerProfile } from './identity/listenerIdentity'
import { Catalog } from './ui/Catalog'
import { BookPage } from './ui/BookPage'
import { AudioEngine } from './player/audioEngine'
import { MiniPlayer } from './ui/MiniPlayer'
import { PlayerSheet } from './ui/PlayerSheet'
import type { BookDetail, SourceId } from './worker/types'

type Tab = 'listen' | 'catalog' | 'profile'

const TABS: Array<{ id: Tab; label: string }> = [
  { id: 'listen', label: 'Слухати' },
  { id: 'catalog', label: 'Огляд' },
  { id: 'profile', label: 'Профіль' },
]

function Stub({ title, what }: { title: string; what: string }) {
  return (
    <div className="placeholder">
      {title} ще в роботі.
      <br />
      {what}
    </div>
  )
}

function Profile({ profile: initialProfile }: { profile: ListenerProfile | null }) {
  const [profile, setProfile] = useState(initialProfile)
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [restoring, setRestoring] = useState(false)

  useEffect(() => setProfile(initialProfile), [initialProfile])

  if (profile === null) return <Stub title="Профіль" what="Профіль з’явиться разом із першим запуском." />

  const handleRestore = async (): Promise<void> => {
    setError(null)
    setRestoring(true)
    try {
      const { createAuthGateway } = await import('./firebase/bootstrap')
      const { BrowserCredentialStore } = await import('./identity/credentialStore')
      const { restoreFromCode } = await import('./identity/listenerIdentity')
      const gateway = await createAuthGateway(import.meta.env)
      if (!gateway) {
        setError('Firebase не налаштовано на цьому деплої')
        return
      }
      const store = new BrowserCredentialStore(window.localStorage)
      const restored = await restoreFromCode(gateway, code, (pair) => store.save(pair))
      if (!restored) setError('Не вдалося відновити — перевірте код і з’єднання')
      else setProfile(restored)
    } finally {
      setRestoring(false)
    }
  }

  return (
    <div>
      <div className="profile-card">
        <span className="label">Нік</span>
        <span className="value">{profile.nickname}</span>
        <span className="label">Профіль</span>
        <span className="value">{profile.uid}</span>
      </div>
      <div style={{ marginTop: 16, display: 'flex', flexDirection: 'column', gap: 8 }}>
        <label style={{ fontSize: 14, fontWeight: 600 }}>Код відновлення з телефону</label>
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
          {restoring ? 'Відновлюємо…' : 'Відновити профіль'}
        </button>
        {error && <span style={{ color: 'var(--bad)', fontSize: 13 }}>{error}</span>}
        {!error && profile.uid.startsWith('local-') && <span style={{ color: 'var(--fg-dim)', fontSize: 13 }}>Введіть код з ⚙️ Профіль на телефоні, щоб побачити свій нік і відгуки тут.</span>}
      </div>
    </div>
  )
}

export function App({ profile }: { profile: ListenerProfile | null }) {
  const [tab, setTab] = useState<Tab>('catalog')
  const [book, setBook] = useState<{ url: string; source: SourceId } | null>(null)
  const bookUrl = book?.url ?? null
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const engineRef = useRef<AudioEngine | null>(null)
  const [playerOpen, setPlayerOpen] = useState(false)
  const [, forceUpdate] = useState(0)

  if (!engineRef.current) {
    engineRef.current = new AudioEngine({ relayBase: '/api' })
  }
  const engine = engineRef.current

  useEffect(() => {
    document.title = 'Слухайка — аудіокниги українською'
    const unsub = engine.subscribe(() => forceUpdate((n) => n + 1))
    return unsub
  }, [engine])

  useEffect(() => {
    if (audioRef.current) engine.attachAudio(audioRef.current)
  }, [engine])

  const handlePlay = (detail: BookDetail, chapterIndex: number): void => {
    engine.loadBook({ title: detail.title, chapters: detail.chapters, editionId: detail.url }, chapterIndex)
    setPlayerOpen(true)
  }
  return (
    <>
      <audio ref={audioRef} preload="metadata" style={{ display: 'none' }} />
      <header className="app-header">
        {bookUrl !== null ? (
          <button className="back" onClick={() => setBook(null)}>
            ← Назад
          </button>
        ) : (
          <>
            <h1 className="app-title">Слухайка</h1>
            <p className="app-subtitle">аудіокниги українською · веб</p>
          </>
        )}
      </header>
      <main className="surface">
        {book !== null ? (
          <BookPage url={book.url} source={book.source} onOpenBook={(url, source) => setBook({ url, source })} onPlay={handlePlay} />
        ) : tab === 'listen' ? (
          <Stub title="Продовження слухання" what="Оберіть книгу в Огляді й натисніть ▶ на розділі." />
        ) : tab === 'catalog' ? (
          <Catalog onOpenBook={(url, source) => setBook({ url, source })} />
        ) : (
          <Profile profile={profile} />
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
