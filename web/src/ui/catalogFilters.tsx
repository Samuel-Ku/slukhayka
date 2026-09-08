import { useEffect, useRef } from 'react'
import { useTranslate } from '../i18n/locale'
import type { CatalogCard } from '../worker/types'
import { DURATION_BUCKETS, type DurationBucket } from './facetModel'

/**
 * W3.3 — the Огляд feed's sticky toolbar + «Фільтри» sheet (spec-42 T1 #302,
 * Android `WorkFeedFilters` + the work-feed filter sheet). The toolbar is
 * compact: ONE filter entry with a selected-state; the sheet holds the
 * dimensions (Жанри multi-select + Тривалість buckets). Values commit
 * immediately as an OR-set (no draft state) — Android's contract. The
 * language dimension lives in its own pills row (T13, already shipped).
 *
 * The genre options come from the worker's homepage genre nav (the site's
 * own «Аудіокниги жанру:» sidebar) — a failed nav leaves the Жанри section
 * honestly absent, never a dead list. The a11y contract rides inside: the
 * heading takes focus on open; the opener regains focus on close (the
 * parent owns that return).
 */

export function StickyFiltersToolbar({ active, onOpen, triggerRef }: {
  /** Any genre/duration selection is active — the button shows it. */
  active: boolean
  onOpen: () => void
  /** Focus-return target (the parent owns the return). */
  triggerRef?: (node: HTMLButtonElement | null) => void
}) {
  const t = useTranslate()
  return (
    <div className="feed-toolbar" role="toolbar" aria-label={t('feedFiltersAria')}>
      <button
        type="button"
        className={`feed-toolbar-filter${active ? ' feed-toolbar-filter-active' : ''}`}
        onClick={onOpen}
        ref={triggerRef}
        aria-pressed={active}
      >
        {t('feedFilters')}
      </button>
    </div>
  )
}

export function FiltersSheet({ genres, selectedGenreUrls, selectedDurations, onGenreToggle, onDurationToggle, onReset, onDismiss, onDone }: {
  /** The homepage genre nav (honest options); empty = the Жанри section is absent. */
  genres: CatalogCard[]
  selectedGenreUrls: ReadonlySet<string>
  selectedDurations: ReadonlySet<DurationBucket>
  /** null = «Усі» (clears the whole dimension). */
  onGenreToggle: (url: string | null) => void
  onDurationToggle: (bucket: DurationBucket) => void
  onReset: () => void
  onDismiss: () => void
  onDone: () => void
}) {
  const t = useTranslate()
  const headingRef = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    headingRef.current?.focus()
  }, [])

  const chip = (pressed: boolean): string => `filter-chip${pressed ? ' filter-chip-on' : ''}`

  return (
    <div className="lib-dialog-backdrop" onClick={onDone}>
      <div
        className="lib-sheet"
        role="dialog"
        aria-modal="true"
        aria-label={t('feedFilters')}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="lib-sheet-head">
          <h2 ref={headingRef} tabIndex={-1} className="lib-sheet-title">
            {t('feedFilters')}
          </h2>
          <button type="button" className="tabheader-btn" onClick={onDismiss} aria-label={t('feedFiltersCloseAria')}>✕</button>
        </div>

        {genres.length > 0 && (
          <>
            <p className="lib-sheet-hint">{t('feedGenres')}</p>
            <div className="filter-chip-row">
              <button type="button" className={chip(selectedGenreUrls.size === 0)} onClick={() => onGenreToggle(null)} aria-pressed={selectedGenreUrls.size === 0}>
                {t('all')}
              </button>
              {genres.map((genre) => (
                <button
                  key={genre.url}
                  type="button"
                  className={chip(selectedGenreUrls.has(genre.url))}
                  onClick={() => onGenreToggle(genre.url)}
                  aria-pressed={selectedGenreUrls.has(genre.url)}
                >
                  {genre.title}
                </button>
              ))}
            </div>
          </>
        )}

        <p className="lib-sheet-hint">{t('feedDuration')}</p>
        <div className="filter-chip-row">
          {DURATION_BUCKETS.map((bucket) => (
            <button
              key={bucket}
              type="button"
              className={chip(selectedDurations.has(bucket))}
              onClick={() => onDurationToggle(bucket)}
              aria-pressed={selectedDurations.has(bucket)}
            >
              {t(`durationBucket${bucket}`)}
            </button>
          ))}
        </div>

        <div className="lib-sheet-actions">
          <button type="button" className="lib-sheet-actions-reset" onClick={onReset}>{t('feedResetAll')}</button>
          <button type="button" className="lib-sheet-actions-done" onClick={onDone}>{t('feedDone')}</button>
        </div>
      </div>
    </div>
  )
}