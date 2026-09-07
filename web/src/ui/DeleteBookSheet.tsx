/**
 * #584 W1.3 — the web port of Android's `BookDeleteOptionsSheet`: the ONE
 * deletion entry that names the three tiers and each one's consequence
 * before anything happens («destructive actions never look neutral»):
 *
 *  1. Прибрати з медіатеки — the full cascade (tombstone + local state),
 *     device files stay;
 *  2. Видалити завантажену копію — only when a downloaded copy exists
 *     (web has none today — the platform ledger — so the slot is absent,
 *     never a dead option);
 *  3. Видалити «title» та файли з пристрою — the red tier; it alone opens
 *     the exact-scope confirmation.
 *
 * The a11y contract rides inside: the heading takes focus on open; the
 * opener row regains focus on close (the parent owns that return).
 */
import { useEffect, useRef } from 'react'
import { useTranslate } from '../i18n/locale'

/** A downloaded copy's honest scope; web reports null until downloads exist. */
export interface DownloadedCopy {
  fileCount: number
  bytes: number
}

export function DeleteBookSheet({ workTitle, downloads, onRemoveFromLibrary, onDeleteEverything, onDismiss }: {
  workTitle: string
  downloads: DownloadedCopy | null
  onRemoveFromLibrary: () => void
  onDeleteEverything: () => void
  onDismiss: () => void
}) {
  const t = useTranslate()
  const headingRef = useRef<HTMLHeadingElement | null>(null)
  // The dismiss prop changes identity every render; the listener reads the
  // newest one without re-subscribing (and without re-running the focus).
  const dismissRef = useRef(onDismiss)
  dismissRef.current = onDismiss

  useEffect(() => {
    headingRef.current?.focus()
    // Android dismisses the sheet with Back; Escape is the web's Back.
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') dismissRef.current()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  const removeConsequence = downloads === null
    ? t('deleteRemoveLibraryConsequence')
    : t('deleteRemoveLibraryConsequenceFiles')
  const everythingConsequence = downloads === null
    ? t('deleteDeleteEverythingConsequence')
    : t('deleteDeleteEverythingConsequenceFiles')

  return (
    <div className="lib-dialog-backdrop" onClick={onDismiss}>
      <div
        className="lib-sheet"
        role="dialog"
        aria-modal="true"
        aria-label={t('deleteOptionsTitle', { title: workTitle })}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="lib-sheet-head">
          <h2 ref={headingRef} tabIndex={-1} className="lib-sheet-title">
            {t('deleteOptionsTitle', { title: workTitle })}
          </h2>
          <button type="button" className="tabheader-btn" onClick={onDismiss} aria-label={t('deleteOptionsCloseAria')}>✕</button>
        </div>
        <p className="lib-sheet-hint">{t('deleteOptionsHint')}</p>

        <button type="button" className="lib-sheet-option lib-sheet-option-primary" onClick={onRemoveFromLibrary}>
          <span className="lib-sheet-icon" aria-hidden="true">⊖</span>
          <span className="lib-sheet-option-text">
            <span className="lib-sheet-option-title">{t('deleteRemoveLibrary', { title: workTitle })}</span>
            <span className="lib-sheet-option-consequence">{removeConsequence}</span>
          </span>
        </button>

        {downloads !== null && (
          <button type="button" className="lib-sheet-option" onClick={onDismiss}>
            <span className="lib-sheet-icon" aria-hidden="true">⤓</span>
            <span className="lib-sheet-option-text">
              <span className="lib-sheet-option-title">{t('deleteDeleteDownload', { title: workTitle })}</span>
              <span className="lib-sheet-option-consequence">{t('deleteDeleteDownloadConsequence')}</span>
            </span>
          </button>
        )}

        <button type="button" className="lib-sheet-option lib-sheet-option-danger" onClick={onDeleteEverything}>
          <span className="lib-sheet-icon" aria-hidden="true">🗑</span>
          <span className="lib-sheet-option-text">
            <span className="lib-sheet-option-title">{t('deleteDeleteEverything', { title: workTitle })}</span>
            <span className="lib-sheet-option-consequence">{everythingConsequence}</span>
          </span>
        </button>
      </div>
    </div>
  )
}
