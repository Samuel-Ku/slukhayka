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
 * exist (ADR-0014). Voting and reporting (#694/#696) live on the collection
 * screen through the SAME seam, online-only and with an honest refusal.
 */
import { useEffect, useRef, useState } from 'react'
import { CollectionRating, collectionDocumentId, type PublishedCollection, type PublishedCollectionItem } from '../collections/collectionModel'
import { CollectionIdentity, CuratorIdentity } from '../collections/collectionIdentity'
import type { CollectionsStore } from '../collections/store'
import { useTranslate } from '../i18n/locale'
import type { ListenerProfile } from '../identity/listenerIdentity'
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
 * their reasons), the real average or its absence, the viewer's 1–5 stars with
 * re-voting, and the complaint — all through the SAME `CollectionsStore` seam
 * the reading surfaces use (#694/#696). The author never sees the vote
 * control (an author does not rate themselves), and without an identity the
 * sheet stays honestly read-only instead of offering a dead control.
 *
 * A write is online-only with an honest refusal: `false` shows the error and
 * changes nothing — no queued vote, no fabricated new average. On success the
 * caller re-reads the surfaces (the server aggregate is the truth), and an
 * accepted complaint drops the collection from the reporter's surfaces at once.
 */
export function CollectionDetailSheet({
  collection,
  profile,
  collectionsStore,
  onClose,
  onCollectionChanged,
  onReported,
}: {
  collection: PublishedCollection
  profile?: ListenerProfile | null
  /** spec-51 (#694/#696) — the write seam; absent => read-only sheet. */
  collectionsStore?: CollectionsStore | null
  onClose: () => void
  /** A stored vote: the caller re-reads its surfaces instead of faking an average. */
  onCollectionChanged?: () => void
  /** A stored complaint: the caller hides the collection from its own surfaces at once. */
  onReported?: (documentId: string) => void
}) {
  const t = useTranslate()
  const headingRef = useRef<HTMLHeadingElement | null>(null)
  const [myStars, setMyStars] = useState<number | null>(null)
  const [voteBusy, setVoteBusy] = useState(false)
  const [voteError, setVoteError] = useState<string | null>(null)
  const [reportBusy, setReportBusy] = useState(false)
  const [reportError, setReportError] = useState<string | null>(null)

  const documentId = collectionDocumentId(collection)
  const uid = profile?.uid ?? null
  // The vote and the complaint share ONE anonymous key: sha256(uid + collectionId).
  const voterKey = uid === null ? '' : CollectionIdentity.voterKey(uid, collection.collectionId)
  // An author never rates or reports their own collection.
  const isOwn = uid !== null && collection.authorId === CuratorIdentity.authorId(uid)
  const canAct = collectionsStore !== undefined && collectionsStore !== null && voterKey !== '' && !isOwn

  useEffect(() => {
    headingRef.current?.focus()
    // Android dismisses with Back; Escape is the web's Back.
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  // Android's `loadMyCollectionVote`: read THIS listener's stars when the
  // screen opens, so a re-vote starts from the real previous value.
  useEffect(() => {
    if (collectionsStore === undefined || collectionsStore === null || voterKey === '') {
      setMyStars(null)
      return
    }
    let alive = true
    void collectionsStore.myVote(voterKey).then((stars) => {
      if (alive) setMyStars(stars)
    })
    return () => {
      alive = false
    }
  }, [collectionsStore, voterKey])

  const vote = (stars: number): void => {
    if (collectionsStore === undefined || collectionsStore === null || voterKey === '') return
    setVoteBusy(true)
    setVoteError(null)
    void collectionsStore.vote(documentId, voterKey, stars).then((accepted) => {
      setVoteBusy(false)
      if (!accepted) {
        // An honest refusal: nothing changed, and nothing claims it did.
        setVoteError(t('collectionVoteError'))
        return
      }
      setMyStars(stars)
      onCollectionChanged?.()
    })
  }

  const report = (): void => {
    if (collectionsStore === undefined || collectionsStore === null || voterKey === '') return
    setReportBusy(true)
    setReportError(null)
    void collectionsStore.report(documentId, voterKey).then((accepted) => {
      setReportBusy(false)
      if (!accepted) {
        setReportError(t('collectionReportError'))
        return
      }
      onReported?.(documentId)
      onClose()
    })
  }

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

        {/* #694/#696 — the viewer's stars and the complaint. Hidden on the
            author's own collection and until an identity exists. */}
        {canAct && (
          <div className="collection-actions">
            <p className="collection-your-rating">{t('collectionYourRating')}</p>
            <span
              className="review-stars review-stars-interactive"
              role="radiogroup"
              aria-label={t('collectionYourRating')}
            >
              {Array.from({ length: CollectionRating.MAX_STARS }, (_, index) => index + 1).map((position) => (
                <button
                  key={position}
                  type="button"
                  role="radio"
                  aria-checked={position === myStars}
                  aria-label={t('reviewRatingSummary', { rating: position })}
                  className={`review-star${position <= (myStars ?? 0) ? ' review-star-on' : ''}`}
                  onClick={() => vote(position)}
                  disabled={voteBusy}
                >
                  ★
                </button>
              ))}
            </span>
            {voteError !== null && <p className="review-error" role="status">{voteError}</p>}
            <button
              type="button"
              className="collection-report"
              onClick={report}
              disabled={reportBusy}
            >
              {t('collectionReport')}
            </button>
            {reportError !== null && <p className="review-error" role="status">{reportError}</p>}
          </div>
        )}
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
