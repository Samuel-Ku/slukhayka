import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import { readWarm, warmKey, writeWarm } from '../api/warmCache'
import type { BookDetail, CatalogCard, SourceId, UnifiedWork } from '../worker/types'

const SOURCES: Array<{ id: 'all' | SourceId; label: string }> = [
  { id: 'all', label: 'Усі джерела' },
  { id: 'fourread', label: '4read' },
  { id: 'sound-books', label: 'Sound-Books' },
  { id: 'sluhayua', label: 'Sluhay UA' },
  { id: 'sluhay', label: 'Sluhay' },
  { id: 'audiobook-mp3', label: 'Audio-MP3' },
  { id: 'lihtar', label: 'Lihtar' },
]

/** spec-43/T3+T4 — огляд із перемикачем джерел і пошуком. */
export function Catalog({ onOpenBook, onPlay }: {
  onOpenBook: (url: string, source: SourceId) => void
  onPlay: (detail: BookDetail, chapterIndex: number) => void
}) {
  const [source, setSource] = useState<'all' | SourceId>('all')
  const [works, setWorks] = useState<UnifiedWork[] | null>(null)
  const [nextPageUrl, setNextPageUrl] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [failed, setFailed] = useState(false)
  const [query, setQuery] = useState('')
  const [searchGroups, setSearchGroups] = useState<import('../api/client').SearchGroup[] | null>(null)
  const [searching, setSearching] = useState(false)
  const loadMoreMarker = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    if (query.trim().length >= 2) return
    let alive = true
    setFailed(false)
    setWorks(null)
    setNextPageUrl(null)
    // The aggregate Work contract serves both the default feed and a
    // source-filtered feed. A filter changes candidates, not the book model.
    {
      api.workFeed(undefined, source === 'all' ? undefined : source).then(async (page) => {
        if (!alive) return
        if (page === null) {
          const cached = await readWarm<UnifiedWork[]>(warmKey('catalog', source))
          if (!alive) return
          if (cached === null) setFailed(true)
          else setWorks(cached)
          return
        }
        setWorks(page.works)
        setNextPageUrl(page.nextCursor ?? null)
        void writeWarm(warmKey('catalog', source), page.works)
      })
      return () => { alive = false }
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

  const loadMore = (): void => {
    if (!nextPageUrl || loadingMore || query.trim().length >= 2) return
    setLoadingMore(true)
    api.workFeed(nextPageUrl, source === 'all' ? undefined : source).then((page) => {
      if (page === null) return
      setWorks((current) => appendWorks(current ?? [], page.works))
      setNextPageUrl(page.nextCursor ?? null)
    }).finally(() => setLoadingMore(false))
  }

  useEffect(() => {
    const marker = loadMoreMarker.current
    if (!marker || !nextPageUrl || query.trim().length >= 2 || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) loadMore()
    }, { rootMargin: '320px' })
    observer.observe(marker)
    return () => observer.disconnect()
  }, [nextPageUrl, loadingMore, query, source])

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
              color: source === s.id ? 'var(--accent-contrast)' : 'var(--fg)',
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
                {group.cards.map((card) => <CatalogCardRow key={card.url} card={card} source={group.id as SourceId} onOpenBook={onOpenBook} onPlay={onPlay} />)}
              </ul>
            </section>
          ))
        )
      ) : failed ? (
        <div className="placeholder">Джерело не відповіло спробуйте пізніше.</div>
      ) : works === null ? (
        <div className="placeholder">Завантажуємо об’єднаний каталог…</div>
      ) : (
        <section>
          <h2>{source === 'all' ? 'Усі джерела' : SOURCES.find((item) => item.id === source)?.label}</h2>
          <ul className="card-list">
            {works!.map((work) => <UnifiedWorkRow key={work.id} work={work} onOpenBook={onOpenBook} onPlay={onPlay} />)}
          </ul>
        </section>
      )}
      {query.trim().length < 2 && nextPageUrl && (
        <div ref={loadMoreMarker} style={{ padding: '16px 0', textAlign: 'center' }}>
          <button onClick={loadMore} disabled={loadingMore}>
            {loadingMore ? 'Завантажуємо…' : 'Показати більше'}
          </button>
        </div>
      )}
    </div>
  )
}

function appendWorks(current: UnifiedWork[], incoming: UnifiedWork[]): UnifiedWork[] {
  const known = new Set(current.map((work) => work.id))
  return [...current, ...incoming.filter((work) => !known.has(work.id))]
}

/** One Work card with explicit Edition selection; changing it never mutates progress. */
function UnifiedWorkRow({ work, onOpenBook, onPlay }: {
  work: UnifiedWork
  onOpenBook: (url: string, source: SourceId) => void
  onPlay: (detail: BookDetail, chapterIndex: number) => void
}) {
  const [editionIndex, setEditionIndex] = useState(0)
  const edition = work.editions[editionIndex] ?? work.editions[0]
  const source = edition.sources[0]
  if (!source) return null
  const card: CatalogCard = {
    url: source.url,
    title: work.title,
    author: work.author,
    narrator: edition.narrator,
    coverImageUrl: work.coverImageUrl,
    durationSeconds: edition.durationSeconds,
  }
  return (
    <>
      <CatalogCardRow card={card} source={source.sourceId} onOpenBook={onOpenBook} onPlay={onPlay} />
      {work.editions.length > 1 && (
        <li style={{ padding: '0 8px 8px' }}>
          <label>
            Начитка{' '}
            <select value={editionIndex} onChange={(event) => setEditionIndex(Number(event.target.value))} aria-label={`Обрати начитку: ${work.title}`}>
              {work.editions.map((candidate, index) => <option key={candidate.id} value={index}>{candidate.narrator || 'Невідомий диктор'}</option>)}
            </select>
          </label>
        </li>
      )}
    </>
  )
}

/** Appends a source cursor page without moving cards the listener already saw. */
export type CardActionState = 'idle' | 'checking' | 'no-network' | 'temporary-failure' | 'audio-missing' | 'browser-required'

export function cardResultState(
  detail: BookDetail | null,
  sessionRequired: boolean,
  online: boolean,
): Exclude<CardActionState, 'idle' | 'checking'> | 'ready' {
  if (detail === null) return online ? 'temporary-failure' : 'no-network'
  if (detail.chapters.length > 0) return 'ready'
  return sessionRequired ? 'browser-required' : 'audio-missing'
}

function sourceHome(source: SourceId): string {
  switch (source) {
    case 'fourread': return 'https://4read.org/'
    case 'sound-books': return 'https://sound-books.net/'
    case 'sluhayua': return 'https://sluhay.com.ua/'
    case 'sluhay': return 'https://sluhay.com/'
    case 'audiobook-mp3': return 'https://audiobook-mp3.com/'
    case 'lihtar': return 'https://lihtar.com.ua/'
  }
}

/** A card's body opens details; its neighbouring action alone starts playback. */
function CatalogCardRow({ card, source, onOpenBook, onPlay }: {
  card: CatalogCard
  source: SourceId
  onOpenBook: (url: string, source: SourceId) => void
  onPlay: (detail: BookDetail, chapterIndex: number) => void
}) {
  const [state, setState] = useState<CardActionState>('idle')
  const generation = useRef(0)

  const cancel = (): void => {
    generation.current += 1
    setState('idle')
  }
  const play = (): void => {
    const request = ++generation.current
    setState('checking')
    api.book(source, card.url).then((detail) => {
      if (request !== generation.current) return
      const result = cardResultState(detail, source === 'fourread', typeof navigator === 'undefined' || navigator.onLine !== false)
      if (result !== 'ready') {
        setState(result)
      } else if (detail) {
        setState('idle')
        onPlay(detail, 0)
      }
    })
  }

  return (
    <li>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <button style={{ flex: 1, textAlign: 'left' }} onClick={() => onOpenBook(card.url, source)} aria-label={`Відкрити книгу: ${card.title}`}>
          {card.coverImageUrl && <img src={card.coverImageUrl} alt="" loading="lazy" />}
          <span className="card-title">{card.title}</span>
          <span className="card-author">{card.author}{card.narrator ? ` · ${card.narrator}` : ''}</span>
        </button>
        <button
          onClick={state === 'checking' ? cancel : play}
          aria-label={state === 'checking' ? `Скасувати перевірку: ${card.title}` : `Слухати: ${card.title}`}
          aria-live="polite"
          style={{ background: 'var(--accent)', color: 'var(--accent-contrast)', border: 'none', borderRadius: 999, padding: '8px 12px' }}
        >
          {state === 'checking' ? 'Скасувати' : '▶'}
        </button>
      </div>
      {state === 'checking' && <p className="notice" aria-live="polite">Перевіряємо доступність…</p>}
      {state === 'no-network' && <p className="notice" role="status">Немає мережі. Перевірте з’єднання та повторіть.</p>}
      {state === 'temporary-failure' && <p className="notice" role="status">Джерело тимчасово не відповідає. Спробуйте пізніше.</p>}
      {state === 'audio-missing' && <p className="notice" role="status">Джерело не віддає аудіо для цієї книги.</p>}
      {state === 'browser-required' && (
        <p className="notice" role="status">
          Це джерело потребує сесії на своєму сайті.{' '}
          <a href={sourceHome(source)} target="_blank" rel="noreferrer">Відкрити {SOURCES.find((item) => item.id === source)?.label}</a>
        </p>
      )}
    </li>
  )
}
