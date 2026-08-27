import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { CatalogCard, CatalogSection } from '../worker/types'

const SOURCES: Array<{ id: import('../worker/types').SourceId; label: string }> = [
  { id: 'fourread', label: '4read' },
  { id: 'sound-books', label: 'Sound-Books' },
  { id: 'sluhayua', label: 'Sluhay UA' },
  { id: 'sluhay', label: 'Sluhay' },
  { id: 'audiobook-mp3', label: 'Audio-MP3' },
  { id: 'lihtar', label: 'Lihtar' },
]

/** spec-43/T3+T4 — огляд із перемикачем джерел і пошуком. */
export function Catalog({ onOpenBook }: { onOpenBook: (url: string, source: import('../worker/types').SourceId) => void }) {
  const [source, setSource] = useState<import('../worker/types').SourceId>('fourread')
  const [sections, setSections] = useState<CatalogSection[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [query, setQuery] = useState('')
  const [searchGroups, setSearchGroups] = useState<import('../api/client').SearchGroup[] | null>(null)
  const [searching, setSearching] = useState(false)

  useEffect(() => {
    if (query.trim().length >= 2) return
    let alive = true
    setFailed(false)
    setSections(null)
    api.catalog(source).then((parsed) => {
      if (!alive) return
      if (parsed === null) setFailed(true)
      else setSections(parsed.sections)
    })
    return () => {
      alive = false
    }
  }, [source, query])

  useEffect(() => {
    const trimmed = query.trim()
    if (trimmed.length < 2) {
      setSearchGroups(null)
      return
    }
    let alive = true
    setSearching(true)
    const timer = setTimeout(() => {
      import('../api/client').then(({ api, dedupeWorks }) => {
        api.searchAll(trimmed).then((groups) => {
          if (!alive) return
          setSearching(false)
          if (groups === null) setSearchGroups([])
          else setSearchGroups(dedupeWorks(groups))
        })
      })
    }, 400)
    return () => {
      alive = false
      clearTimeout(timer)
    }
  }, [query])

  return (
    <div>
      <input
        placeholder="Пошук…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        style={{ width: '100%', padding: '8px', borderRadius: '8px', border: '1px solid var(--line)', background: 'var(--surface)', color: 'var(--fg)' }}
      />
      <div style={{ display: 'flex', gap: 6, margin: '8px 0', flexWrap: 'wrap' }}>
        {SOURCES.map((s) => (
          <button
            key={s.id}
            onClick={() => {
              setSource(s.id)
              setQuery('')
            }}
            style={{
              padding: '4px 10px',
              borderRadius: 999,
              border: '1px solid var(--line)',
              background: source === s.id ? 'var(--accent)' : 'var(--surface)',
              color: source === s.id ? '#000' : 'var(--fg)',
            }}
          >
            {s.label}
          </button>
        ))}
      </div>

      {query.trim().length >= 2 ? (
        searching ? (
          <div className="placeholder">Шукаємо…</div>
        ) : searchGroups === null || searchGroups.length === 0 ? (
          <div className="placeholder">Нічого не знайшли.</div>
        ) : (
          searchGroups.map((group) => (
            <section key={group.id}>
              <h2>
                {group.displayName} · {group.cards.length}
              </h2>
              <ul className="card-list">
                {group.cards.map((card) => (
                  <li key={card.url}>
                    <button onClick={() => onOpenBook(card.url, group.id as import('../worker/types').SourceId)}>
                      {card.coverImageUrl && <img src={card.coverImageUrl} alt="" loading="lazy" />}
                      <span className="card-title">{card.title}</span>
                      <span className="card-author">
                        {card.author}
                        {card.narrator ? ` · ${card.narrator}` : ''}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ))
        )
      ) : failed ? (
        <div className="placeholder">Джерело не відповіло спробуйте пізніше.</div>
      ) : sections === null ? (
        <div className="placeholder">Завантажуємо каталог…</div>
      ) : sections.length === 0 ? (
        <div className="placeholder">Каталог поки порожній.</div>
      ) : (
        sections.map((section) => (
          <section key={section.id}>
            <h2>{section.title}</h2>
            <ul className="card-list">
              {section.cards.map((card: CatalogCard) => (
                <li key={card.url}>
                  <button onClick={() => onOpenBook(card.url, source)}>
                    {card.coverImageUrl && <img src={card.coverImageUrl} alt="" loading="lazy" />}
                    <span className="card-title">{card.title}</span>
                    <span className="card-author">{card.author}</span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        ))
      )}
    </div>
  )
}
