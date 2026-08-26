import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { BookDetail } from '../worker/types'

/**
 * spec-43/T3 — сторінка книги: метадані, розділи й «Інші начитки».
 * Плеєр прибуде в T5 — поки чесно без кнопки «грати».
 */
export function BookPage({ url, onOpenBook }: { url: string; onOpenBook: (next: string) => void }) {
  const [detail, setDetail] = useState<BookDetail | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let alive = true
    setDetail(null)
    setFailed(false)
    api.book('fourread', url).then((result) => {
      if (!alive) return
      if (result === null) setFailed(true)
      else setDetail(result)
    })
    return () => {
      alive = false
    }
  }, [url])

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
          {detail.chapters.map((chapter) => (
            <li key={chapter.streamUrl}>{chapter.title}</li>
          ))}
        </ol>
      )}

      {detail.otherNarrations.length > 0 && (
        <>
          <h2>Інші начитки</h2>
          <ul className="narrations">
            {detail.otherNarrations.map((card) => (
              <li key={card.url}>
                <button onClick={() => onOpenBook(card.url)}>{`${card.title} — ${card.author}`}</button>
              </li>
            ))}
          </ul>
        </>
      )}
    </article>
  )
}
