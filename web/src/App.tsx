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

function Profile({ profile }: { profile: ListenerProfile | null }) {
  if (profile === null) return <Stub title="Профіль" what="Профіль з’явиться разом із першим запуском." />
  return (
    <div>
      <div className="profile-card">
        <span className="label">Нік</span>
        <span className="value">{profile.nickname}</span>
        <span className="label">Профіль</span>
        <span className="value">{profile.uid}</span>
      </div>
      <p className="notice">
        Цей профіль поки живе лише в браузері. Прив’язка до телефону через код
        відновлення прийде разом із синхронізацією прогресу.
      </p>
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
    engineRef.current = new AudioEngine()
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
