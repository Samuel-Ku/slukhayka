/**
 * W5.1 (ticket 12) — the player's three extra panes in Android's sheet
 * geometry: the chapter list with the current position, the sleep timer
 * (Android's exact option set and countdown), and the bookmarks list with
 * create/delete/open. All three are thin views over the pure cores
 * (sleepTimer.ts, bookmarks.ts) — the ADR-0034 two-runtime rule.
 */
import { useEffect, useState } from 'react'
import type { AudioEngine } from '../player/audioEngine'
import { SLEEP_TIMER_OPTIONS, sleepTimerCountdown, type SleepTimerState } from '../player/sleepTimer'
import type { PlayerBookmark, PlayerBookmarksStore } from '../player/bookmarks'
import { useTranslate } from '../i18n/locale'
import type { Chapter } from '../worker/types'

export function formatClock(seconds: number): string {
  const safe = Math.max(0, Math.floor(seconds))
  return `${Math.floor(safe / 60)}:${String(safe % 60).padStart(2, '0')}`
}

// ---- chapters ----------------------------------------------------------

export function ChapterList({
  chapters,
  currentIndex,
  currentPosition,
  onJump,
}: {
  chapters: Chapter[]
  currentIndex: number
  currentPosition: number
  onJump: (chapterIndex: number) => void
}) {
  const t = useTranslate()
  return (
    <ul className="chapters" aria-label={t('chaptersCount', { n: chapters.length })}>
      {chapters.map((chapter, index) => {
        const isCurrent = index === currentIndex
        return (
          <li key={`${index}-${chapter.title}`}>
            <button
              type="button"
              className={`chapter-row${isCurrent ? ' chapter-row-current' : ''}`}
              onClick={() => onJump(index)}
              aria-current={isCurrent ? 'true' : undefined}
              aria-label={`${chapter.title}${isCurrent ? ` · ${t('position', { time: formatClock(currentPosition) })}` : ''}`}
            >
              <span className="chapter-row-title">
                {chapter.title}
                {isCurrent && <span className="chapter-row-pos">{formatClock(currentPosition)}</span>}
              </span>
              <span className="chapter-row-dur">
                {typeof chapter.durationSeconds === 'number' && chapter.durationSeconds > 0
                  ? formatClock(chapter.durationSeconds)
                  : t('durationUnknown')}
              </span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}

// ---- sleep timer -------------------------------------------------------

export function SleepTimerPane({ engine }: { engine: AudioEngine }) {
  const t = useTranslate()
  const [timer, setTimer] = useState<SleepTimerState>(engine.getSleepTimerState())
  const [extendedNotice, setExtendedNotice] = useState(false)
  useEffect(() => engine.subscribeSleepTimer(setTimer), [engine])

  const active = timer.remainingSeconds > 0
  const selectedMinutes = active && timer.minutes !== -1 && SLEEP_TIMER_OPTIONS.includes(timer.minutes) ? timer.minutes : null
  const countdown = active
    ? timer.isEndOfChapter
      ? t('timerUntilChapterEndCountdown', { time: sleepTimerCountdown(timer.remainingSeconds) })
      : t('timerRemainingCountdown', { time: sleepTimerCountdown(timer.remainingSeconds) })
    : null

  return (
    <div className="timer-pane">
      <div className="timer-head">
        <span className="timer-title">{t('sleepTimerPane')}</span>
        {active && <span className="timer-countdown">{countdown}</span>}
      </div>
      {extendedNotice && active && <p className="notice">{t('timerExtended', { time: sleepTimerCountdown(timer.remainingSeconds) })}</p>}
      <div className="timer-options" role="radiogroup" aria-label={t('sleepTimerPane')}>
        {SLEEP_TIMER_OPTIONS.map((minutes) => {
          const isSelected = active && minutes === selectedMinutes
          return (
            <button
              key={minutes}
              type="button"
              role="radio"
              aria-checked={isSelected}
              className={`timer-option${isSelected ? ' timer-option-active' : ''}`}
              onClick={() => engine.setSleepTimer(minutes)}
            >
              {minutes === 0 ? t('timerOff') : minutes === -1 ? t('timerUntilChapterEnd') : t('timerMinutes', { n: minutes })}
            </button>
          )
        })}
      </div>
      {active && (
        <button
          type="button"
          className="feed-chip"
          onClick={() => {
            engine.extendSleepTimer()
            setExtendedNotice(true)
          }}
        >
          {t('timerExtend')}
        </button>
      )}
    </div>
  )
}

// ---- bookmarks ---------------------------------------------------------

export interface BookmarkContext {
  workId: string
  editionId: string
  bookTitle: string
  chapterIndex: number
  chapterTitle: string
  positionSeconds: number
}

export function BookmarksPane({
  store,
  context,
  onJump,
  onCountChange,
}: {
  store: PlayerBookmarksStore
  context: BookmarkContext | null
  onJump: (bookmark: PlayerBookmark) => void
  /** W5.1 — the honest count for the tab label, reported on every load. */
  onCountChange?: (count: number) => void
}) {
  const t = useTranslate()
  const [bookmarks, setBookmarks] = useState<PlayerBookmark[]>([])
  const [addOpen, setAddOpen] = useState(false)
  const [note, setNote] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<PlayerBookmark | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  const load = (): void => {
    if (context === null) {
      setBookmarks([])
      onCountChange?.(0)
      return
    }
    void store.forWork(context.workId).then((list) => {
      setBookmarks(list)
      onCountChange?.(list.length)
    })
  }
  useEffect(load, [store, context?.workId, onCountChange])

  if (context === null) {
    return <p className="empty-state-message">{t('noBookmarks')}</p>
  }

  const timeAt = (bookmark: PlayerBookmark): string => formatClock(bookmark.timestampSeconds)

  const save = (): void => {
    setSaving(true)
    setError(null)
    const trimmed = note.trim()
    void store
      .add({
        workId: context.workId,
        editionId: context.editionId,
        chapterIndex: context.chapterIndex,
        chapterTitle: context.chapterTitle,
        timestampSeconds: Math.floor(context.positionSeconds),
        note: trimmed !== '' ? trimmed : t('bookmarkDefaultNote', { time: formatClock(context.positionSeconds) }),
      })
      .then((created) => {
        setSaving(false)
        if (created === null) {
          setError(t('bookmarkSaveError'))
          return
        }
        setAddOpen(false)
        setNote('')
        setNotice(t('bookmarkSaved', { chapter: created.chapterTitle, time: timeAt(created) }))
        load()
      })
  }

  const confirmDelete = (): void => {
    if (deleteTarget === null) return
    setDeleting(true)
    void store.remove(deleteTarget.id).then(() => {
      setDeleting(false)
      setDeleteTarget(null)
      setNotice(t('bookmarkDeleted'))
      load()
    })
  }

  return (
    <div>
      <div className="bm-actions">
        <button type="button" className="feed-chip" onClick={() => setAddOpen(true)}>
          {t('addBookmark')}
        </button>
        {notice && <span className="bm-notice">{notice}</span>}
      </div>
      {bookmarks.length === 0 ? (
        <p className="empty-state-message">{t('noBookmarks')}</p>
      ) : (
        <ul className="bm-list">
          {bookmarks.map((bookmark) => (
            <li key={bookmark.id} className="bm-row">
              <div className="bm-row-main">
                <span className="bm-row-time">{timeAt(bookmark)}</span>
                <span className="bm-row-chapter">{bookmark.chapterTitle}</span>
                {bookmark.note !== '' && <span className="bm-row-note">{bookmark.note}</span>}
              </div>
              <div className="bm-row-actions">
                <button
                  type="button"
                  className="bm-row-btn"
                  aria-label={t('bookmarkJumpAria', { title: context.bookTitle, chapter: bookmark.chapterTitle, time: timeAt(bookmark) })}
                  onClick={() => onJump(bookmark)}
                >
                  ▶
                </button>
                <button
                  type="button"
                  className="bm-row-btn"
                  aria-label={t('bookmarkDeleteAria', { title: context.bookTitle, chapter: bookmark.chapterTitle, time: timeAt(bookmark) })}
                  onClick={() => setDeleteTarget(bookmark)}
                >
                  ✕
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {addOpen && (
        <div className="lib-dialog-backdrop" onClick={() => !saving && setAddOpen(false)}>
          <div className="lib-sheet" role="dialog" aria-modal="true" aria-label={t('bookmarkPane')} onClick={(event) => event.stopPropagation()}>
            <div className="lib-sheet-head">
              <h3>{t('bookmarkPane')}</h3>
              <button type="button" className="lib-sheet-close" onClick={() => !saving && setAddOpen(false)}>
                ✕
              </button>
            </div>
            <p className="bm-timestamp">{t('bookmarkTimestamp', { time: formatClock(context.positionSeconds) })}</p>
            <label className="bm-note-label" htmlFor="bm-note">
              {t('bookmarkNoteLabel')}
            </label>
            <textarea
              id="bm-note"
              className="bm-note-input"
              value={note}
              onChange={(event) => setNote(event.target.value)}
              placeholder={t('bookmarkNoteHint')}
              rows={3}
              autoFocus
            />
            {error && <p className="bm-error">{error}</p>}
            <div className="lib-sheet-actions">
              <button type="button" className="feed-chip" onClick={save} disabled={saving}>
                {t('bookmarkSave')}
              </button>
            </div>
          </div>
        </div>
      )}

      {deleteTarget && (
        <div className="lib-dialog-backdrop" onClick={() => !deleting && setDeleteTarget(null)}>
          <div className="lib-sheet" role="alertdialog" aria-modal="true" aria-label={t('bookmarkDeleteTitle')} onClick={(event) => event.stopPropagation()}>
            <div className="lib-sheet-head">
              <h3>{t('bookmarkDeleteTitle')}</h3>
            </div>
            <p className="lib-sheet-text">
              {t('bookmarkDeleteQuestion', {
                title: context.bookTitle,
                chapter: deleteTarget.chapterTitle,
                time: timeAt(deleteTarget),
              })}
            </p>
            <p className="lib-sheet-text lib-sheet-consequence">
              {deleteTarget.note !== ''
                ? t('bookmarkDeleteConsequence', { note: deleteTarget.note })
                : t('bookmarkDeleteConsequenceNoNote')}
            </p>
            <div className="lib-sheet-actions">
              <button type="button" className="feed-chip" onClick={() => setDeleteTarget(null)} disabled={deleting}>
                {t('close').replace('✕ ', '')}
              </button>
              <button type="button" className="feed-chip feed-chip-danger" onClick={confirmDelete} disabled={deleting}>
                {t('bookmarkDeleteConfirm')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}