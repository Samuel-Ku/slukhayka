import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { CatalogCard, CatalogSection } from '../worker/types'

/** spec-43/T3 — огляд першого джерела (4read): секції з картками Work-ів. */
export function Catalog({ onOpenBook }: { onOpenBook: (url: string) => void }) {
  const [sections, setSections] = useState<CatalogSection[] | null>(null)

  useEffect(() => {
    let alive = true
    api.catalog('fourread').then((parsed) => {
      if (alive) setSections(parsed?.sections ?? [])
    })
    return () => {
      alive = false
    }
  }, [])

  if (sections === null) return <div className="placeholder">Завантажуємо каталог…</div>
  if (sections.length === 0)
    return <div className="placeholder">Джерело не відповіло спробуйте пізніше.</div>

  return (
    <div>
      {sections.map((section) => (
        <section key={section.id}>
          <h2>{section.title}</h2>
          <ul className="card-list">
            {section.cards.map((card: CatalogCard) => (
              <li key={card.url}>
                <button onClick={() => onOpenBook(card.url)}>
                  {card.coverImageUrl && <img src={card.coverImageUrl} alt="" loading="lazy" />}
                  <span className="card-title">{card.title}</span>
                  <span className="card-author">{card.author}</span>
                </button>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}
