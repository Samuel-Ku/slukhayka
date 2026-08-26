import { useEffect, useState } from 'react'
import type { ListenerProfile } from './identity/listenerIdentity'
import { Catalog } from './ui/Catalog'
import { BookPage } from './ui/BookPage'

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
  const [bookUrl, setBookUrl] = useState<string | null>(null)
  useEffect(() => {
    document.title = 'Слухайка — аудіокниги українською'
  }, [])
  return (
    <>
      <header className="app-header">
        {bookUrl !== null ? (
          <button className="back" onClick={() => setBookUrl(null)}>
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
        {bookUrl !== null ? (
          <BookPage url={bookUrl} onOpenBook={setBookUrl} />
        ) : tab === 'listen' ? (
          <Stub title="Продовження слухання і плеєр" what="Спершу транспорт джерел — він уже тут, плеєр наступним кроком." />
        ) : tab === 'catalog' ? (
          <Catalog onOpenBook={setBookUrl} />
        ) : (
          <Profile profile={profile} />
        )}
      </main>
      {bookUrl === null && (
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
