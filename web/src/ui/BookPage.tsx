import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { readWarm, warmKey, writeWarm } from '../api/warmCache'
import type { BookDetail } from '../worker/types'
import { canPlayBookFromDisplayedDetail, sourceNeedsBrowserSession } from './bookPlaybackAvailability'

/**
 * spec-43/T3+T5 — сторінка книги: метадані, розділи й «Інші начитки».
 * Розділи — кнопки ▶ для плеєра (T5).
 */
export function BookPage({
  url,
  source,
  onOpenBook,
  onPlay,
}: {
  url: string
  source: import('../worker/types').SourceId
  onOpenBook: (next: string, source: import('../worker/types').SourceId) => void
  onPlay?: (detail: BookDetail, chapterIndex: number) => Promise<boolean>
}) {
  const [detail, setDetail] = useState<BookDetail | null>(null)
  const [failed, setFailed] = useState(false)
  const [showingCachedBook, setShowingCachedBook] = useState(false)

  useEffect(() => {
    let alive = true
    setDetail(null)
    setFailed(false)
    setShowingCachedBook(false)
    api.book(source, url).then(async (result) => {
      if (!alive) return
      if (result === null) {
        const cached = await readWarm<BookDetail>(warmKey('book', source, url))
        if (!alive) return
        if (cached === null) setFailed(true)
        else {
          setDetail(cached)
          setShowingCachedBook(true)
        }
      } else {
        setDetail(result)
        setShowingCachedBook(false)
        // A browser-session Source may expose a temporary cookie-bound stream
        // URL. Keep its public metadata warm, but never persist that locator
        // as if another browser session could replay it.
        const cacheValue = sourceNeedsBrowserSession(source)
          ? { ...result, chapters: [] }
          : result
        void writeWarm(warmKey('book', source, url), cacheValue)
      }
    })
    return () => {
      alive = false
    }
  }, [url, source])

  if (failed) return <div className="placeholder">Книга недоступна — джерело не відповіло.</div>
  if (detail === null) return <div className="placeholder">Завантажуємо книгу…</div>

  const canPlay = canPlayBookFromDisplayedDetail(source, showingCachedBook)
  const requiresFreshSession = !canPlay && sourceNeedsBrowserSession(source)

  return (
    <article>
      <h1>{detail.title}</h1>
      {showingCachedBook && <p className="notice" role="status" aria-live="polite">Показуємо збережені дані книги. Оновлення каталогу тимчасово недоступне.</p>}
      {requiresFreshSession && (
        <p className="notice" role="status" aria-live="polite">
          Щоб перевірити доступ до аудіо, відкрийте джерело у браузері й пройдіть перевірку, якщо воно її попросить.{' '}
          <a href={url} target="_blank" rel="noreferrer">Відкрити сторінку джерела</a>
        </p>
      )}
      <p className="byline">
        {detail.author}
        {detail.narrator ? ` · читає ${detail.narrator}` : ''}
      </p>
      {detail.coverImageUrl && <img className="cover" src={detail.coverImageUrl} alt="" loading="lazy" />}
      {detail.genres.length > 0 && <p className="genres">{detail.genres.join(' · ')}</p>}
      {detail.descriptionHtml && (
        <div className="description">
          {detail.descriptionHtml.split('\n').map((paragraph, i) => (
            <p key={i}>{paragraph}</p>
          ))}
        </div>
      )}

      <h2>Розділи</h2>
      {detail.chapters.length === 0 ? (
        <p className="notice">Розділів не знайшли — джерело не віддає аудіо для цієї книги.</p>
      ) : (
        <ol className="chapters">
          {detail.chapters.map((chapter, idx) => (
            <li key={chapter.streamUrl} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span>{chapter.title}</span>
              {onPlay && canPlay && (
                <button
                  onClick={() => { void onPlay(detail, idx) }}
                  aria-label={`Слухати розділ ${idx + 1}`}
                  style={{ background: 'var(--accent)', color: 'var(--accent-contrast)', border: 'none', borderRadius: 999, padding: '4px 12px' }}
                >
                  ▶
                </button>
              )}
            </li>
          ))}
        </ol>
      )}

      {detail.otherNarrations.length > 0 && (
        <>
          <h2>Інші начитки</h2>
          <ul className="narrations">
            {detail.otherNarrations.map((card) => (
              <li key={card.url}>
                <button onClick={() => onOpenBook(card.url, source)}>{`${card.title} — ${card.author}`}</button>
              </li>
            ))}
          </ul>
        </>
      )}
    </article>
  )
}
