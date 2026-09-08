/**
 * W4.1 — the «Відгуки» vocabulary of the web book page, ported from Android's
 * BookDetailReviews.kt (spec-40 #277/#278/#279/#280/#281): the star row, the
 * Google-style review card (stars ABOVE the text, then nickname, then date),
 * the honest pending-free read/write through the store seam, the write/edit
 * form (one form for both modes; edit re-sets under the same document key),
 * and the exact-scope delete confirmation. Also the narration-rating row
 * (ADR-0023 #348): the crowd average ONLY when votes exist, below it THIS
 * listener's interactive stars under the explicit invitation «Оцінити
 * начитку». Pure components over the store seam — the Kotlin fixture rules
 * pin the same document identity, so a review written here is the same
 * document Android reads (the AC's cross-platform visibility).
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslate } from '../i18n/locale'
import type { ListenerProfile } from '../identity/listenerIdentity'
import { combinedAverage, type CombinedAverageResult } from '../reviews/combinedAverage'
import type { NarrationRating } from '../reviews/narrationRatingModel'
import { NarrationRatingLimits } from '../reviews/narrationRatingModel'
import { ListenerReviewLimits, type ListenerReview } from '../reviews/reviewModel'
import type { NarrationRatingsStore, ReviewsStore } from '../reviews/store'
import { SectionHeader } from './components'

/** Android's reviewWorkIdFor(editionId, workId) — the Work's mergeKey when mergeable, else the Edition id. */
export function reviewWorkIdFor(mergeKey: string, editionId: string): string {
  return mergeKey.trim() !== '' ? mergeKey : editionId
}

/** «12 серп. 2026 р.» style label for a review's createdAt stamp (Android MEDIUM, uk). */
export function reviewDateLabel(createdAtMillis: number, locale = 'uk'): string {
  try {
    return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(new Date(createdAtMillis))
  } catch {
    return new Date(createdAtMillis).toLocaleDateString()
  }
}

function starRow(rating: number, interactive: boolean, onRatingChange: (rating: number) => void, starLabel: (position: number) => string) {
  return (
    <span className={`review-stars${interactive ? ' review-stars-interactive' : ''}`} role={interactive ? 'radiogroup' : undefined} aria-label={interactive ? undefined : starLabel(rating)}>
      {Array.from({ length: 5 }, (_, index) => index + 1).map((position) =>
        interactive ? (
          <button
            key={position}
            type="button"
            role="radio"
            aria-checked={position === rating}
            aria-label={starLabel(position)}
            className={`review-star${position <= rating ? ' review-star-on' : ''}`}
            onClick={() => onRatingChange(position)}
          >
            ★
          </button>
        ) : (
          <span key={position} className={`review-star${position <= rating ? ' review-star-on' : ''}`} aria-hidden="true">
            ★
          </span>
        ),
      )}
    </span>
  )
}

/**
 * One listener review, Google-style: stars above everything, optional text,
 * then the nickname and the date; the edition tag renders as a muted
 * «Начитка: …» chip ONLY when the reviewer named one (#278). An own review
 * offers edit/delete (delete sits behind its confirmation upstream); a
 * stranger's review offers the ONLY moderation v1 provides: hide this
 * author's reviews locally, reversibly (#281).
 */
export function ReviewCard({
  review,
  workTitle,
  isOwn,
  onEdit,
  onDelete,
}: {
  review: ListenerReview
  workTitle: string
  isOwn: boolean
  onEdit: () => void
  onDelete: () => void
}) {
  const t = useTranslate()
  return (
    <li className="review-card">
      {starRow(review.rating, false, () => undefined, (position) => t('reviewRatingSummary', { rating: position }))}
      {review.body && <p className="review-body">{review.body}</p>}
      {review.editionTag && <span className="review-edition-chip">Начитка: {review.editionTag}</span>}
      <span className="review-meta">
        <span className="review-author">{review.authorName}</span>
        <span className="review-date">{reviewDateLabel(review.createdAt)}</span>
      </span>
      <span className="review-actions">
        {isOwn && (
          <>
            <button
              type="button"
              onClick={onEdit}
              aria-label={t('reviewEditAria', { title: workTitle, rating: review.rating })}
            >
              Змінити
            </button>
            <button
              type="button"
              className="review-delete"
              onClick={onDelete}
              aria-label={t('reviewDeleteAria', { title: workTitle, rating: review.rating })}
            >
              🗑
            </button>
          </>
        )}
      </span>
    </li>
  )
}

/**
 * #277/#278 — the write/edit form as a bottom sheet (ADR-0018: transient
 * input; dialogs stay for irreversible confirmations). Stars are REQUIRED
 * (Save stays disabled until at least one is set), the text body is
 * optional with a live counter against the 2000-char limit, and the edition
 * tag is a dropdown of the narrations this session knows («Не вказувати» is
 * the explicit no-tag choice). Save re-sets under the SAME deterministic
 * document key — an edit replaces, never duplicates (document identity).
 */
export function ListenerReviewFormSheet({
  bookTitle,
  editing,
  editionOptions,
  defaultEditionTag,
  onSave,
  onDismiss,
  isSaving,
  errorMessage,
}: {
  bookTitle: string
  editing: ListenerReview | null
  editionOptions: string[]
  defaultEditionTag: string
  onSave: (rating: number, body: string | null, editionTag: string | null) => void
  onDismiss: () => void
  isSaving: boolean
  errorMessage: string | null
}) {
  const t = useTranslate()
  const headingRef = useRef<HTMLHeadingElement>(null)
  const [selectedRating, setSelectedRating] = useState(editing?.rating ?? 0)
  const [bodyText, setBodyText] = useState(editing?.body ?? '')
  const [selectedTag, setSelectedTag] = useState<string | null>(editing?.editionTag ?? (defaultEditionTag || null))
  const [tagMenuOpen, setTagMenuOpen] = useState(false)

  useEffect(() => {
    headingRef.current?.focus()
  }, [])

  const editionChoices = useMemo(
    () => [...new Set([...(editionOptions.filter((option) => option.trim() !== ''))])],
    [editionOptions],
  )

  return (
    <div className="lib-dialog-backdrop" onClick={() => { if (!isSaving) onDismiss() }}>
      <div
        className="lib-sheet"
        role="dialog"
        aria-modal="true"
        aria-label={t('reviewFormPane', { title: bookTitle })}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="lib-sheet-head">
          <h2 ref={headingRef} tabIndex={-1} className="lib-sheet-title">
            {t(editing === null ? 'reviewNewTitle' : 'reviewEditTitle')}
          </h2>
          <button type="button" className="tabheader-btn" onClick={onDismiss} disabled={isSaving}>✕</button>
        </div>
        <p className="lib-sheet-hint">«{bookTitle}»</p>

        <p className="review-form-label">{t('reviewRatingLabel')}</p>
        {starRow(selectedRating, !isSaving, setSelectedRating, (position) => t('reviewRatingSummary', { rating: position }))}

        <p className="review-form-label">{t('reviewEditionLabel')}</p>
        <div className="review-tag-select">
          <button
            type="button"
            className="filter-chip"
            onClick={() => setTagMenuOpen((open) => !open)}
            aria-expanded={tagMenuOpen}
          >
            {selectedTag ?? t('editionTagNone')}
          </button>
          {tagMenuOpen && (
            <ul className="review-tag-menu">
              <li>
                <button type="button" onClick={() => { setSelectedTag(null); setTagMenuOpen(false) }}>
                  {t('editionTagNone')}
                </button>
              </li>
              {editionChoices.map((option) => (
                <li key={option}>
                  <button type="button" onClick={() => { setSelectedTag(option); setTagMenuOpen(false) }}>
                    {option}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <label className="review-form-label" htmlFor="review-body">
          {t('reviewBodyLabel')}
        </label>
        <textarea
          id="review-body"
          className="review-body-input"
          value={bodyText}
          maxLength={ListenerReviewLimits.MAX_BODY_LEN}
          onChange={(event) => setBodyText(event.target.value)}
          disabled={isSaving}
          rows={4}
        />
        <p className="review-counter">
          {bodyText.length} / {ListenerReviewLimits.MAX_BODY_LEN}
        </p>

        {errorMessage && <p className="review-error" role="status">{errorMessage}</p>}

        <div className="lib-dialog-actions">
          <button type="button" onClick={onDismiss} disabled={isSaving}>
            {t('cancel')}
          </button>
          <button
            type="button"
            onClick={() => onSave(selectedRating, bodyText.trim() === '' ? null : bodyText.trim(), selectedTag)}
            disabled={!selectedRating || isSaving}
          >
            {isSaving ? t('reviewSaving') : t(editing === null ? 'reviewPublish' : 'reviewSave')}
          </button>
        </div>
      </div>
    </div>
  )
}

/** Exact, destructive confirmation for the listener's own review (spec-27). */
export function ReviewDeleteConfirmation({
  workTitle,
  review,
  onConfirm,
  onDismiss,
}: {
  workTitle: string
  review: ListenerReview
  onConfirm: () => void
  onDismiss: () => void
}) {
  const t = useTranslate()
  const headingRef = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    headingRef.current?.focus()
  }, [])
  return (
    <div className="lib-dialog-backdrop" onClick={onDismiss}>
      <div
        className="lib-sheet"
        role="alertdialog"
        aria-modal="true"
        aria-label={t('reviewDeletePane')}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="lib-sheet-head">
          <h2 ref={headingRef} tabIndex={-1} className="lib-sheet-title">{t('reviewDeleteTitle')}</h2>
        </div>
        <p className="lib-sheet-hint">
          {t(
            review.body ? 'reviewDeleteConsequenceWithText' : 'reviewDeleteConsequenceWithoutText',
            { title: workTitle, rating: review.rating },
          )}
        </p>
        <div className="lib-dialog-actions">
          <button type="button" onClick={onDismiss}>
            {t('cancel')}
          </button>
          <button type="button" className="review-delete-confirm" onClick={onConfirm}>
            {t('reviewDeleteConfirm')}
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * ADR-0023 (#348) — the narration-rating row beside the narrator's name:
 * the crowd average ONLY when votes exist (#383 — zero votes never draw even
 * the bare label), below it THIS listener's interactive stars under the
 * explicit invitation «Оцінити начитку». Renders nothing when there is
 * nothing to show and nobody to ask.
 */
export function NarrationRatingRow({
  average,
  voteCount,
  ownRating,
  canRate,
  onRate,
  onDeleteOwn,
}: {
  average: number | null
  voteCount: number
  ownRating: number | null
  canRate: boolean
  onRate: (rating: number) => void
  onDeleteOwn?: () => void
}) {
  const t = useTranslate()
  if (average === null && !canRate) return null
  return (
    <div className="narration-rating-row">
      {average !== null && (
        <p className="narration-rating-average">
          <span className="narration-rating-label">{t('narrationRatingLabel')}</span>
          {starRow(Math.round(average), false, () => undefined, (position) => t('reviewRatingSummary', { rating: position }))}
          <span className="narration-rating-count">
            {t('narrationRatingAverage', { value: average.toFixed(1), count: voteCount })}
          </span>
        </p>
      )}
      {canRate && (
        <p className="narration-rating-ask">
          <span>{t('narrationRatingAsk')}</span>
          {starRow(ownRating ?? 0, true, onRate, (position) => t('reviewRatingSummary', { rating: position }))}
          {ownRating !== null && onDeleteOwn && (
            <button
              type="button"
              className="review-delete"
              onClick={onDeleteOwn}
              aria-label={t('narrationRatingDeleteAria')}
            >
              🗑
            </button>
          )}
        </p>
      )}
    </div>
  )
}

/**
 * Spec-40 #277/#279 — the honest headline of the reviews block: one flat
 * mean over every SOURCE rating that exists and every listener review.
 * No addends → no row at all (ADR-0014: zeros are never drawn).
 */
export function CombinedAverageRow({ average }: { average: CombinedAverageResult | null }) {
  const t = useTranslate()
  if (average === null) return null
  return (
    <p className="reviews-average" aria-label={t('reviewsCombinedAria', { value: average.value.toFixed(1), count: average.count })}>
      <span className="review-star review-star-on" aria-hidden="true">★</span>
      <span className="reviews-average-value">{average.value.toFixed(1)}</span>
      <span className="reviews-average-count">
        {t('reviewsCombinedSummary', { value: average.value.toFixed(1), count: average.count })}
      </span>
    </p>
  )
}

/**
 * The whole «Відгуки» block of the book page: header with count + write
 * action, the honest combined average, the review cards, the canonical
 * empty state, and the write/edit form + delete confirmation. Writing needs
 * a listener identity — until the profile seam answers, the block stays
 * honestly read-only (the Android contract).
 */
export function ReviewsBlock({
  workId,
  bookTitle,
  defaultEditionTag,
  editionOptions,
  sourceRating,
  profile,
  store,
  narrationRatingsStore,
  narrationEditionId,
}: {
  workId: string
  bookTitle: string
  defaultEditionTag: string
  editionOptions: string[]
  /** The source's own rating of THIS book (the honest source vote), when the page declared one. */
  sourceRating?: number
  profile: ListenerProfile | null
  store: ReviewsStore
  narrationRatingsStore: NarrationRatingsStore
  narrationEditionId: string
}) {
  const t = useTranslate()
  const [reviews, setReviews] = useState<ListenerReview[]>([])
  const [ratings, setRatings] = useState<NarrationRating[]>([])
  const [showForm, setShowForm] = useState(false)
  const [editingReview, setEditingReview] = useState<ListenerReview | null>(null)
  const [reviewToDelete, setReviewToDelete] = useState<ListenerReview | null>(null)
  const [isSaving, setIsSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [loaded, setLoaded] = useState(false)

  const uid = profile?.uid ?? null

  useEffect(() => {
    let alive = true
    setLoaded(false)
    void Promise.all([store.getForWork(workId), narrationRatingsStore.getForWork(workId)]).then(([workReviews, workRatings]) => {
      if (!alive) return
      setReviews(workReviews)
      setRatings(workRatings)
      setLoaded(true)
    })
    return () => {
      alive = false
    }
  }, [store, narrationRatingsStore, workId])

  const average = useMemo(
    () =>
      combinedAverage(
        // The source's own rating of THIS book is the honest source vote;
        // a page without one contributes nothing (ADR-0014: never a 0).
        [sourceRating],
        reviews.map((review) => review.rating),
      ),
    [sourceRating, reviews],
  )
  const ownRatings = ratings.filter((rating) => rating.editionId === narrationEditionId)
  const ownNarrationRating = ownRatings.find((rating) => rating.uid === uid)?.rating ?? null
  const narrationAverage = (() => {
    const own = ownRatings.filter((rating) => rating.rating >= NarrationRatingLimits.MIN_RATING && rating.rating <= NarrationRatingLimits.MAX_RATING)
    if (own.length === 0) return null
    return own.reduce((sum, rating) => sum + rating.rating, 0) / own.length
  })()

  const canWrite = uid !== null

  const saveReview = (rating: number, body: string | null, editionTag: string | null): void => {
    if (uid === null) return
    setIsSaving(true)
    setSaveError(null)
    const now = Date.now()
    void store
      .putReview({
        workId,
        uid,
        authorName: profile?.nickname ?? 'Слухач',
        rating,
        ...(body ? { body } : {}),
        ...(editionTag ? { editionTag } : {}),
        createdAt: editingReview?.createdAt ?? now,
        ...(editingReview ? { editedAt: now } : {}),
      })
      .then((accepted) => {
        setIsSaving(false)
        if (!accepted) {
          setSaveError(t('reviewSaveError'))
          return
        }
        setShowForm(false)
        setEditingReview(null)
        // Re-read the whole work list (the write may replace an own review).
        void Promise.all([store.getForWork(workId), narrationRatingsStore.getForWork(workId)]).then(([workReviews, workRatings]) => {
          setReviews(workReviews)
          setRatings(workRatings)
        })
      })
  }

  const deleteReview = (review: ListenerReview): void => {
    void store.deleteReview(review.workId, review.uid).then(() => {
      setReviewToDelete(null)
      void store.getForWork(workId).then(setReviews)
    })
  }

  const saveNarrationRating = (rating: number): void => {
    if (uid === null) return
    void narrationRatingsStore
      .putRating({
        workId,
        uid,
        editionId: narrationEditionId,
        rating,
        createdAt: ownRatings.find((entry) => entry.uid === uid)?.createdAt ?? Date.now(),
        ...(ownRatings.find((entry) => entry.uid === uid) ? { editedAt: Date.now() } : {}),
      })
      .then(() => narrationRatingsStore.getForWork(workId).then(setRatings))
  }

  const deleteNarrationRating = (): void => {
    if (uid === null) return
    void narrationRatingsStore.deleteRating(workId, uid, narrationEditionId).then(() =>
      narrationRatingsStore.getForWork(workId).then(setRatings),
    )
  }

  return (
    <section className="reviews-block" aria-label={t('reviewsTitle')}>
      <SectionHeader
        level="section"
        title={t('reviewsTitle')}
        count={loaded ? reviews.length : undefined}
        action={
          canWrite ? (
            <button
              type="button"
              className="feed-chip"
              onClick={() => {
                setEditingReview(null)
                setSaveError(null)
                setShowForm(true)
              }}
            >
              {t('writeReview')}
            </button>
          ) : undefined
        }
      />
      {loaded && <CombinedAverageRow average={average} />}
      {loaded && reviews.length === 0 && <p className="reviews-empty">{t('reviewsEmpty')}</p>}
      {reviews.length > 0 && (
        <ul className="review-list">
          {reviews.map((review) => (
            <ReviewCard
              key={`${review.workId}_${review.uid}`}
              review={review}
              workTitle={bookTitle}
              isOwn={uid === review.uid}
              onEdit={() => {
                setEditingReview(review)
                setSaveError(null)
                setShowForm(true)
              }}
              onDelete={() => setReviewToDelete(review)}
            />
          ))}
        </ul>
      )}

      <NarrationRatingRow
        average={narrationAverage}
        voteCount={ownRatings.length}
        ownRating={ownNarrationRating}
        canRate={canWrite}
        onRate={saveNarrationRating}
        onDeleteOwn={ownNarrationRating !== null ? deleteNarrationRating : undefined}
      />

      {showForm && (
        <ListenerReviewFormSheet
          bookTitle={bookTitle}
          editing={editingReview}
          editionOptions={editionOptions}
          defaultEditionTag={defaultEditionTag}
          isSaving={isSaving}
          errorMessage={saveError}
          onSave={saveReview}
          onDismiss={() => {
            if (!isSaving) {
              setShowForm(false)
              setEditingReview(null)
              setSaveError(null)
            }
          }}
        />
      )}
      {reviewToDelete && (
        <ReviewDeleteConfirmation
          workTitle={bookTitle}
          review={reviewToDelete}
          onConfirm={() => deleteReview(reviewToDelete)}
          onDismiss={() => setReviewToDelete(null)}
        />
      )}
    </section>
  )
}