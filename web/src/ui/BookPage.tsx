import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { BookDetail } from '../worker/types'

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
  onPlay?: (detail: BookDetail, chapterIndex: number) => void
}) {
  const [detail, setDetail] = useState<BookDetail | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let alive = true
    setDetail(null)
    setFailed(false)
    api.book(source, url).then((result) => {
      if (!alive) return
      if (result === null) setFailed(true)
      else setDetail(result)
    })
    return () => {
      alive = false
    }
  }, [url, source])

  if (failed) return <div className="placeholder">Книга недоступна — джерело не відповіло.</div>
  if (detail === null) return <div className="placeholder">Завантажуємо книгу…</div>

  return (
    <article>
      <h1>{detail.title}</h1>
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
              {onPlay && (
                <button
                  onClick={() => onPlay(detail, idx)}
                  aria-label={`Слухати розділ ${idx + 1}`}
                  style={{ background: 'var(--accent)', color: '#000', border: 'none', borderRadius: 999, padding: '4px 12px' }}
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
