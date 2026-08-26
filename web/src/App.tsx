import { useEffect, useState } from 'react'
import type { ListenerProfile } from './identity/listenerIdentity'

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
  const [tab, setTab] = useState<Tab>('listen')
  useEffect(() => {
    document.title = 'Слухайка — аудіокниги українською'
  }, [])
  return (
    <>
      <header className="app-header">
        <h1 className="app-title">Слухайка</h1>
        <p className="app-subtitle">аудіокниги українською · веб</p>
      </header>
      <main className="surface">
        {tab === 'listen' && (
          <Stub
            title="Продовження слухання і плеєр"
            what="Каталог уже працює над приходом книжок: спершу транспорт джерел, потім відтворення."
          />
        )}
        {tab === 'catalog' && (
          <Stub
            title="Огляд каталогу і сторінки книги"
            what="Шість джерел українських аудіокниг в одному місці — скоро."
          />
        )}
        {tab === 'profile' && <Profile profile={profile} />}
      </main>
      <nav className="tab-bar" role="tablist">
        {TABS.map((t) => (
          <button key={t.id} role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}>
            {t.label}
          </button>
        ))}
      </nav>
    </>
  )
}
