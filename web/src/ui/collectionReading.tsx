/**
 * spec-51 (#697, T9) — the web READING surfaces of listener collections: the
 * «Добірки з цією книгою» block on the book page, the «Добірки слухачів» rail
 * on Слухати, and the collection screen itself. Same documents, same shared
 * ranking and the same honest average as Android (#692/#693/#694) — the ONE
 * community, read from a browser.
 *
 * Honest absence is the contract: with no store the caller renders nothing at
 * all (no empty-but-present surface), an empty read renders nothing, and a
 * failed read keeps the caller's last good list. Star only when real votes
 * exist (ADR-0014). Voting and reporting (#694/#696) are the next slice; this
 * one is deliberately read-only, exactly as the ticket orders it.
 */
import { useEffect, useRef } from 'react'
import { CollectionRating, collectionDocumentId, type PublishedCollection, type PublishedCollectionItem } from '../collections/collectionModel'
import { useTranslate } from '../i18n/locale'
import { SectionHeader } from './components'

/** The real average line, or the honest absence — one rule for every surface. */
export function CollectionRatingLine({ collection }: { collection: PublishedCollection }) {
  const t = useTranslate()
  const average = CollectionRating.average(collection.ratingSum, collection.ratingCount)
  if (average === null) {
    return <span className="collection-no-ratings">{t('collectionNoRatings')}</span>
  }
  return (
    <span className="collection-average">
      ★ {average.toFixed(1)} · {t('collectionVotes', { count: collection.ratingCount })}
    </span>
  )
}

/**
 * #692 — «Добірки з цією книгою»: the ranked visible collections that contain
 * the open book. With no rows the block renders NOTHING (a book nobody curated
 * shows no empty headline), mirroring Android's `CollectionsWithBookBlock`.
 */
export function CollectionsWithBookBlock({
  collections,
  onOpen,
}: {
  collections: PublishedCollection[]
  onOpen: (collection: PublishedCollection) => void
}) {
  const t = useTranslate()
  if (collections.length === 0) return null
  return (
    <section className="collections-with-book" aria-label={t('collectionsWithBookTitle')}>
      <SectionHeader level="section" title={t('collectionsWithBookTitle')} />
      <ul className="collection-list">
        {collections.map((collection) => (
          <li key={collectionDocumentId(collection)}>
            <button
              type="button"
              className="collection-row"
              onClick={() => onOpen(collection)}
              aria-label={t('collectionOpenAria', { title: collection.title, pseudonym: collection.pseudonym })}
            >
              <span className="collection-row-title">{collection.title}</span>
              <span className="collection-row-curator">{t('collectionByPseudonym', { pseudonym: collection.pseudonym })}</span>
              <CollectionRatingLine collection={collection} />
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}

/**
 * #693 — «Добірки слухачів» on Слухати: the top public collections by the SAME
 * shared ranking the book block uses. No rows means no rail at all.
 */
export function CollectionsRail({
  collections,
  onOpen,
}: {
  collections: PublishedCollection[]
  onOpen: (collection: PublishedCollection) => void
}) {
  const t = useTranslate()
  if (collections.length === 0) return null
  return (
    <section className="collections-rail" aria-label={t('collectionsRailTitle')}>
      <SectionHeader level="section" title={t('collectionsRailTitle')} />
      <ul className="collection-rail-row">
        {collections.map((collection) => (
          <li key={collectionDocumentId(collection)}>
            <button
              type="button"
              className="collection-rail-card"
              onClick={() => onOpen(collection)}
              aria-label={t('collectionOpenAria', { title: collection.title, pseudonym: collection.pseudonym })}
            >
              <span className="collection-rail-title">{collection.title}</span>
              <span className="collection-row-curator">{t('collectionByPseudonym', { pseudonym: collection.pseudonym })}</span>
              <CollectionRatingLine collection={collection} />
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}

/**
 * The collection screen: the curator's own composition (frozen display
 * snapshots when the document carries them, else the honest book ids with
 * their reasons), the real average or its absence, and a close action. Read
 * only — editing is the owner's job and stays on Android (ticket's out of
 * scope).
 */
export function CollectionDetailSheet({
  collection,
  onClose,
}: {
  collection: PublishedCollection
  onClose: () => void
}) {
  const t = useTranslate()
  const headingRef = useRef<HTMLHeadingElement | null>(null)
  useEffect(() => {
    headingRef.current?.focus()
    // Android dismisses with Back; Escape is the web's Back.
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  const composition = collectionComposition(collection)
  return (
    <div className="lib-dialog-backdrop" onClick={onClose}>
      <div
        className="lib-sheet"
        role="dialog"
        aria-modal="true"
        aria-label={t('collectionPane', { title: collection.title })}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="lib-sheet-head">
          <h2 ref={headingRef} tabIndex={-1} className="lib-sheet-title">{collection.title}</h2>
          <button type="button" className="tabheader-btn" onClick={onClose}>{t('close')}</button>
        </div>
        <p className="lib-sheet-hint">{t('collectionByPseudonym', { pseudonym: collection.pseudonym })}</p>
        {collection.description !== '' && <p className="collection-description">{collection.description}</p>}
        <p className="collection-count">{t('collectionBooksCount', { count: collection.bookIds.length })}</p>
        <ul className="collection-composition">
          {composition.map((item, index) => (
            <li key={`${item.bookId}-${index}`} className="collection-composition-item">
              {item.coverUrl !== undefined && <img className="collection-item-cover" src={item.coverUrl} alt="" loading="lazy" />}
              <span className="collection-item-body">
                <span className="collection-item-title">
                  {item.title !== '' ? item.title : t('collectionItemUnknown')}
                </span>
                {item.author !== '' && <span className="collection-item-author">{item.author}</span>}
                {item.reason !== '' && <span className="collection-item-reason">{item.reason}</span>}
              </span>
            </li>
          ))}
        </ul>
        <p className="collection-detail-rating">
          <CollectionRatingLine collection={collection} />
        </p>
      </div>
    </div>
  )
}

/**
 * The composition in the curator's own order: the frozen snapshot when present,
 * else `bookIds` with their positional reasons (legacy documents carry no
 * titles — the honest id is shown, never an invented one).
 */
export function collectionComposition(collection: PublishedCollection): PublishedCollectionItem[] {
  if (collection.items.length > 0) return collection.items
  return collection.bookIds.map((bookId, index) => ({
    bookId,
    title: '',
    author: '',
    reason: collection.reasons[index] ?? '',
  }))
}
