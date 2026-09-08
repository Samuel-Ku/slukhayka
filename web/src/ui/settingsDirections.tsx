/**
 * #591 W5.2 — the Сховище and Приватність directions of Налаштування.
 *
 * Сховище (Android's StorageDestinationScreen, honestly adapted): the
 * browser's own usage numbers (estimate + the real audio cache + the real
 * snapshot rows) and TWO scoped clears in Android's separated danger zone
 * (ADR-0014: destructive never sits next to neutral data) — each with an
 * exact-scope confirmation and a destructive-styled confirm.
 *
 * Приватність (Android's NetworkPrivacyScreen, honestly adapted): the
 * REAL state of the web transport (ADR-0024) as text — no dead toggles:
 * there is no proxy/Tor route on the web, and the screen says so instead
 * of offering switches that cannot exist.
 */
import { useEffect, useRef, useState } from 'react'
import type { HybridListeningStateStorage } from '../local/hybridListeningState'
import type { ListenerDatabase } from '../local/listeningState'
import { TabHeader } from './components'
import { useTranslate } from '../i18n/locale'
import {
  audioCacheSize,
  clearAudioCache,
  estimateOriginUsage,
  formatBytes,
  listeningSnapshotCount,
  type ManagementCacheStorage,
  type OriginUsage,
} from '../local/storageManagement'

export type StorageClearTarget = 'audio' | 'snapshots'

export function StorageDirection({
  hybrid,
  idbStore,
  storage,
  cacheStorage,
  onBack,
}: {
  hybrid: HybridListeningStateStorage
  idbStore: ListenerDatabase
  storage: { getItem(key: string): string | null; removeItem(key: string): void; length?: number; key?(index: number): string | null }
  cacheStorage?: ManagementCacheStorage | null
  onBack: () => void
}) {
  const t = useTranslate()
  const [usage, setUsage] = useState<OriginUsage | null>(null)
  const [audioBytes, setAudioBytes] = useState(0)
  const [snapshots, setSnapshots] = useState(0)
  const [confirm, setConfirm] = useState<StorageClearTarget | null>(null)
  const [working, setWorking] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  // A monotone sequence: an in-flight measurement that started before a
  // clear must never overwrite the clear's fresher numbers (stale-race).
  const measureSeq = useRef(0)

  const measure = (): void => {
    const seq = ++measureSeq.current
    void estimateOriginUsage().then((value) => {
      if (seq === measureSeq.current) setUsage(value)
    })
    void audioCacheSize(cacheStorage).then((bytes) => {
      if (seq === measureSeq.current) setAudioBytes(bytes)
    })
    void listeningSnapshotCount({
      idbCount: () => idbStore.allSnapshots().then((rows) => rows.length),
      storage,
    }).then((count) => {
      if (seq === measureSeq.current) setSnapshots(count)
    })
  }
  useEffect(() => {
    measure()
    // Unmount: invalidate any in-flight measurements (React 18 no-ops the
    // setState anyway; this keeps the intent explicit).
    return () => {
      measureSeq.current += 1
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hybrid, idbStore, storage, cacheStorage])

  const clear = async (target: StorageClearTarget): Promise<void> => {
    setWorking(true)
    setError(null)
    if (target === 'audio') {
      const cleared = await clearAudioCache(cacheStorage)
      if (cleared) setNotice(t('storageClearAudioDone'))
      else setError(t('storageClearError'))
    } else {
      await hybrid.clearListeningSnapshots()
      setNotice(t('storageResetSnapshotsDone'))
    }
    setWorking(false)
    setConfirm(null)
    measure()
  }

  return (
    <div>
      <div className="dest-header">
        <button type="button" className="back" onClick={onBack}>{t('back')}</button>
      </div>
      <TabHeader title={t('storageTitle')} />
      <div className="profile-card">
        <span className="label">{t('storageUsageHeading')}</span>
        <span className="value">
          {usage === null ? t('storageEstimateUnavailable') : t('storageUsageTotal', { used: formatBytes(usage.used), quota: formatBytes(usage.quota) })}
        </span>
        <span className="label">{t('storageAudioCacheLabel')}</span>
        <span className="value">{audioBytes > 0 ? formatBytes(audioBytes) : t('storageAudioCacheEmpty')}</span>
        <span className="label">{t('storageSnapshotsLabel')}</span>
        <span className="value">{String(snapshots)}</span>
      </div>

      {/* ADR-0014: the destructive scope is separated from the neutral data. */}
      <hr className="settings-danger-divider" />
      <h2 className="settings-danger-heading">{t('storageDangerHeading')}</h2>
      <div className="settings-danger-actions">
        <button type="button" className="settings-danger-button" onClick={() => setConfirm('audio')}>
          {t('storageClearAudioTitle')}
        </button>
        <button type="button" className="settings-danger-button" onClick={() => setConfirm('snapshots')}>
          {t('storageResetSnapshotsTitle')}
        </button>
      </div>
      {notice && <p role="status" className="bm-notice">{notice}</p>}
      {error && <p role="alert" className="bm-error">{error}</p>}

      {confirm === 'audio' && (
        <ClearConfirmation
          title={t('storageClearAudioQuestion')}
          consequence={t('storageClearAudioConsequence')}
          confirmLabel={t('storageClearAudioConfirm')}
          working={working}
          onConfirm={() => void clear('audio')}
          onDismiss={() => !working && setConfirm(null)}
        />
      )}
      {confirm === 'snapshots' && (
        <ClearConfirmation
          title={t('storageResetSnapshotsQuestion')}
          consequence={t('storageResetSnapshotsConsequence')}
          confirmLabel={t('storageResetSnapshotsConfirm')}
          working={working}
          onConfirm={() => void clear('snapshots')}
          onDismiss={() => !working && setConfirm(null)}
        />
      )}
    </div>
  )
}

/** The exact-scope destructive confirmation (the house lib-sheet pattern). */
export function ClearConfirmation({ title, consequence, confirmLabel, working, onConfirm, onDismiss }: {
  title: string
  consequence: string
  confirmLabel: string
  working: boolean
  onConfirm: () => void
  onDismiss: () => void
}) {
  const t = useTranslate()
  return (
    <div className="lib-dialog-backdrop" onClick={onDismiss}>
      <div className="lib-sheet" role="alertdialog" aria-modal="true" aria-label={title} onClick={(event) => event.stopPropagation()}>
        <div className="lib-sheet-head">
          <h3>{title}</h3>
        </div>
        <p className="lib-sheet-text">{consequence}</p>
        <div className="lib-sheet-actions">
          <button type="button" className="feed-chip" onClick={onDismiss} disabled={working}>
            {t('cancel')}
          </button>
          <button type="button" className="feed-chip feed-chip-danger" onClick={onConfirm} disabled={working}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}

/** ADR-0024 п.4, as honest rows — no dead switches (there is no route to turn on). */
export function NetworkDirection({ onBack }: { onBack: () => void }) {
  const t = useTranslate()
  return (
    <div>
      <div className="dest-header">
        <button type="button" className="back" onClick={onBack}>{t('back')}</button>
      </div>
      <TabHeader title={t('networkTitle')} />
      <ul className="settings-list">
        <li>
          <div className="settings-row settings-row-static">
            <span className="settings-row-main">
              <span>{t('networkPagesTitle')}</span>
              <span className="settings-row-sub">{t('networkPagesDescription')}</span>
            </span>
          </div>
        </li>
        <li>
          <div className="settings-row settings-row-static">
            <span className="settings-row-main">
              <span>{t('networkAudioTitle')}</span>
              <span className="settings-row-sub">{t('networkAudioDescription')}</span>
            </span>
          </div>
        </li>
        <li>
          <div className="settings-row settings-row-static">
            <span className="settings-row-main">
              <span>{t('networkRouteTitle')}</span>
              <span className="settings-row-sub">{t('networkRouteDescription')}</span>
            </span>
          </div>
        </li>
      </ul>
    </div>
  )
}