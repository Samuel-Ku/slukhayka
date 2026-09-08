import { useEffect, useMemo, useState } from 'react'
import type { AudioEngine } from '../player/audioEngine'
import type { EngineState } from '../player/engine'
import { useTranslate } from '../i18n/locale'
import type { ListenerProfile } from '../identity/listenerIdentity'
import type { ReviewsStore } from '../reviews/store'
import { editionIdFor, mergeKeyFor } from '../sync/edition'
import { reviewWorkIdFor, ListenerReviewFormSheet } from './bookReviews'
import type { ListenerReview } from '../reviews/reviewModel'

export function PlayerSheet({
  engine,
  onClose,
  lastPlayed,
  profile,
  reviewsStore,
}: {
  engine: AudioEngine
  onClose: () => void
  /** W4.1 — the Work the player last loaded, for the finish prompt's review form. */
  lastPlayed: { title: string; author: string; narrator?: string; language?: string; url: string } | null
  profile: ListenerProfile | null
  reviewsStore: ReviewsStore | null
}) {
  const t = useTranslate()
  const [state, setState] = useState<EngineState>(engine.getState())
  const [finishFormOpen, setFinishFormOpen] = useState(false)
  useEffect(() => engine.subscribe(setState), [engine])

  const isPlaying = state.status === 'playing'
  const finished = state.isCompleted && lastPlayed !== null && reviewsStore !== null

  // W4.1 — the finish prompt's form target: the SAME ListenerReviewFormSheet
  // component the book page's block uses (one entry, one form — AC).
  const finishTarget = useMemo(() => {
    if (lastPlayed === null) return null
    const mergeKey = mergeKeyFor(lastPlayed.title, lastPlayed.author)
    const editionId = editionIdFor(mergeKey, lastPlayed.url, lastPlayed.narrator ?? '', lastPlayed.language ?? '')
    return {
      workId: reviewWorkIdFor(mergeKey, editionId),
      bookTitle: lastPlayed.title,
      defaultEditionTag: lastPlayed.narrator ?? '',
      editionOptions: [lastPlayed.narrator ?? ''],
      narrationEditionId: editionId,
    }
  }, [lastPlayed])

  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [editingReview, setEditingReview] = useState<ListenerReview | null>(null)

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'var(--bg)', zIndex: 50, display: 'flex', flexDirection: 'column', padding: '16px' }}>
      <button onClick={onClose} style={{ alignSelf: 'flex-start', background: 'none', border: 'none', color: 'var(--accent)', fontSize: 16 }}>{t('close')}</button>
      <h2 style={{ marginTop: 16 }}>{state.isCompleted ? t('completed') : t('chapter', { n: state.chapterIndex + 1 })}</h2>
      <p style={{ color: 'var(--fg-dim)', fontSize: 14 }}>{t('position', { time: `${Math.floor(state.positionSeconds / 60)}:${String(Math.floor(state.positionSeconds % 60)).padStart(2, '0')}` })}</p>
      <input
        type="range"
        min={0}
        max={100}
        value={state.positionSeconds}
        onChange={(e) => engine.seek(Number(e.target.value))}
        style={{ width: '100%', margin: '16px 0' }}
      />
      <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
        <button onClick={() => engine.skip(-15)}>⏪ 15s</button>
        <button onClick={() => (isPlaying ? engine.pause() : engine.play())} style={{ fontSize: 24, padding: '8px 24px', borderRadius: 999, background: 'var(--accent)', color: '#000', border: 'none' }}>
          {isPlaying ? '⏸' : '▶'}
        </button>
        <button onClick={() => engine.skip(15)}>15s ⏩</button>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button onClick={() => engine.prevChapter()}>{t('prev')}</button>
        <button onClick={() => engine.nextChapter()}>{t('next')}</button>
      </div>
      <label style={{ marginTop: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
        {t('speed')}
        <select value={state.speed} onChange={(e) => engine.setSpeed(Number(e.target.value))}>
          {[0.75, 1, 1.25, 1.5, 1.75, 2].map((s) => (
            <option key={s} value={s}>
              {s}×
            </option>
          ))}
        </select>
      </label>
      {state.status === 'unavailable' && <p style={{ color: 'var(--bad)', marginTop: 12 }}>{t('bookUnavailable')}</p>}

      {/* W4.1 — the finish prompt: «Книгу прослухано» opens the SAME review
          form the book page's block uses. No Firebase config → no prompt. */}
      {finished && finishTarget !== null && (
        <div style={{ marginTop: 24, borderTop: '1px solid var(--line)', paddingTop: 16 }}>
          <p style={{ fontWeight: 600 }}>{t('completed')}</p>
          <button
            type="button"
            className="feed-chip"
            onClick={() => {
              setEditingReview(null)
              setSaveError(null)
              setFinishFormOpen(true)
            }}
          >
            {t('writeReview')}
          </button>
        </div>
      )}

      {finishFormOpen && finishTarget !== null && reviewsStore !== null && (
        <ListenerReviewFormSheet
          bookTitle={finishTarget.bookTitle}
          editing={editingReview}
          editionOptions={finishTarget.editionOptions}
          defaultEditionTag={finishTarget.defaultEditionTag}
          isSaving={saving}
          errorMessage={saveError}
          onSave={(rating, body, editionTag) => {
            if (profile === null) return
            setSaving(true)
            setSaveError(null)
            void reviewsStore
              .putReview({
                workId: finishTarget.workId,
                uid: profile.uid,
                authorName: profile.nickname,
                rating,
                ...(body ? { body } : {}),
                ...(editionTag ? { editionTag } : {}),
                createdAt: editingReview?.createdAt ?? Date.now(),
                ...(editingReview ? { editedAt: Date.now() } : {}),
              })
              .then((accepted) => {
                setSaving(false)
                if (accepted) setFinishFormOpen(false)
                else setSaveError(t('reviewSaveError'))
              })
          }}
          onDismiss={() => {
            if (!saving) {
              setFinishFormOpen(false)
              setEditingReview(null)
              setSaveError(null)
            }
          }}
        />
      )}
    </div>
  )
}