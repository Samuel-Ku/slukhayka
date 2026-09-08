/**
 * #585 W2.1 — the Слухати tab answers «що я слухаю?» exactly like
 * Android's ListenScreen (ADR-0015): the eight rule-based shelves, each
 * with its one-line reason, the hero deduplicated out of every other
 * block, and ONE «Керувати полицями» sheet owning reorder / hide /
 * restore. The order lives in IndexedDB (ListenPrefsStore, R-W8) —
 * local-only, never synced, fully reversible.
 *
 * Platform deltas (honest): shelf rows are not openable yet — a synced or
 * saved Work carries no Source URL on web (joining with the Огляд/книга
 * sources is its own step); NEXT_IN_SERIES / TRAVEL / FAVORITE_AUTHORS
 * have no web data source yet and stay absent until their data exists —
 * Android's own cold-start rule, never a fabricated shelf.
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import type { DomainStore } from '../local/domain'
import type { EditionLinkStore } from '../local/editionLinks'
import type { ListenerDatabase } from '../local/listeningState'
import { LISTEN_BLOCK_IDS, ListenPrefsStore, type ListenBlockId, type ListenPrefsRow } from '../local/listenPrefs'
import { RecommendationPrefsStore } from '../local/recommendationPrefs'
import { useTranslate } from '../i18n/locale'
import type { StringKey } from '../i18n/strings'
import { BookRow, EmptyState, EmptyStateRow, SectionHeader, TabHeader } from './components'
import { buildLibraryViews, type LibraryBookView } from './libraryModel'
import { composeListenBlocks, deduplicateListenShelves, type ListenBlock } from './listenComposer'

/** One label per block id — the manage sheet's vocabulary. */
const BLOCK_TITLE_KEYS: Record<ListenBlockId, StringKey> = {
  hero: 'listenHeroTitle',
  'almost-done': 'listenAlmostDoneTitle',
  return: 'listenReturnTitle',
  'next-in-series': 'listenNextInSeriesTitle',
  travel: 'listenTravelTitle',
  short: 'listenShortTitle',
  'favorite-authors': 'listenFavoriteAuthorsTitle',
  'recently-added': 'listenRecentlyAddedTitle',
}

export function blockTitleKey(id: ListenBlockId): StringKey {
  return BLOCK_TITLE_KEYS[id]
}

export function Listen({ domainStore, linkStore, listening, prefsStore, recommendationPrefs }: {
  domainStore: DomainStore
  linkStore: EditionLinkStore
  listening: Pick<ListenerDatabase, 'allSnapshots'>
  prefsStore: ListenPrefsStore
  /** #586 W2.2 — the local Recommendation Preference store («Не цікаво»). */
  recommendationPrefs: RecommendationPrefsStore
}) {
  const t = useTranslate()
  const [views, setViews] = useState<LibraryBookView[] | null>(null)
  const [prefs, setPrefs] = useState<ListenPrefsRow>({ id: 'listen', order: [], hidden: [] })
  // #586 W2.2 — «Не цікаво» mergeKeys; the HIDE_WORK preference targets.
  const [dismissed, setDismissed] = useState<string[]>([])
  const [manageOpen, setManageOpen] = useState(false)
  const manageButtonRef = useRef<HTMLButtonElement | null>(null)
  const sheetWasOpen = useRef(false)

  useEffect(() => {
    let alive = true
    void Promise.all([domainStore.libraryEntries(), linkStore.all(), listening.allSnapshots(), prefsStore.load(), recommendationPrefs.all()]).then(
      ([entries, links, snapshots, loadedPrefs, loadedPreferences]) => {
        if (!alive) return
        setViews(buildLibraryViews(entries, links, snapshots))
        setPrefs(loadedPrefs)
        setDismissed(loadedPreferences.filter((p) => p.kind === 'HIDE_WORK').map((p) => p.targetKey))
      },
    )
    return () => {
      alive = false
    }
  }, [domainStore, linkStore, listening, prefsStore, recommendationPrefs])

  const blocks: ListenBlock[] = useMemo(() => {
    if (views === null) return []
    // An emptied shelf (its only book claimed by a higher one) renders
    // nothing — empty shelves are not a thing; the empty STATE is for the
    // whole screen.
    return deduplicateListenShelves(
      composeListenBlocks(views, { order: prefs.order, hidden: prefs.hidden }, dismissed, { now: Date.now() }),
    ).filter((block) => block.books.length > 0)
  }, [views, prefs, dismissed])

  // The focus-return contract: the sheet takes focus when it opens; the
  // manage button regains it when the sheet closes.
  useEffect(() => {
    if (manageOpen) {
      sheetWasOpen.current = true
      return
    }
    if (sheetWasOpen.current) {
      sheetWasOpen.current = false
      manageButtonRef.current?.focus()
    }
  }, [manageOpen])

  const savePrefs = async (next: Omit<ListenPrefsRow, 'id'>): Promise<void> => {
    await prefsStore.save(next)
    setPrefs({ id: 'listen', ...next })
  }

  /** #586 W2.2 — «Не цікаво»: a local HIDE_WORK preference, reversible from Рекомендації. */
  const dismissBook = (mergeKey: string): void => {
    void recommendationPrefs.add('HIDE_WORK', mergeKey, mergeKey).then(() => {
      setDismissed((current) => (current.includes(mergeKey) ? current : [...current, mergeKey]))
    })
  }

  return (
    <div>
      <TabHeader
        title={t('tabListen')}
        action={
          <button
            type="button"
            ref={manageButtonRef}
            onClick={() => setManageOpen(true)}
            aria-label={t('listenManageAria')}
            aria-haspopup="dialog"
            style={{ background: 'none', border: '1px solid var(--line)', borderRadius: 999, padding: '4px 12px', color: 'var(--fg)' }}
          >
            ☰
          </button>
        }
      />

      {views === null ? (
        <EmptyStateRow message={t('loading')} />
      ) : blocks.length === 0 ? (
        <EmptyState icon="🎧" message={t('listenEmptyTitle')} hint={t('listenEmptyHint')} />
      ) : (
        blocks.map((block) => (
          <section key={block.id}>
            <SectionHeader
              level="group"
              title={t(block.titleKey)}
              hint={block.reason === undefined ? undefined : t(block.reason.key, block.reason.params)}
            />
            <ul className="card-list">
              {block.books.map((book) => (
                <BookRow
                  key={`${block.id}-${book.mergeKey}`}
                  title={book.title}
                  subtitle={book.author}
                  // The hero is the resume CTA — no dismiss on it (Android's
                  // ListenHeroCard); the «Не цікаво» ✕ lives on shelf rows.
                  actions={block.id === 'hero' ? undefined : (
                    <button
                      type="button"
                      className="bookrow-dismiss"
                      onClick={() => dismissBook(book.mergeKey)}
                      aria-label={t('notInterestedAria', { title: book.title })}
                    >
                      ✕
                    </button>
                  )}
                />
              ))}
            </ul>
          </section>
        ))
      )}

      {manageOpen && (
        <ManageShelvesSheet
          prefs={prefs}
          onClose={() => setManageOpen(false)}
          onSave={(next) => void savePrefs(next)}
        />
      )}
    </div>
  )
}

/** ADR-0015's one chrome entry: reorder, hide and restore for the eight blocks. */
export function ManageShelvesSheet({ prefs, onClose, onSave }: {
  prefs: ListenPrefsRow
  onClose: () => void
  onSave: (next: Omit<ListenPrefsRow, 'id'>) => void
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

  // The full working order: the user's permutation over all eight ids.
  const [order, setOrder] = useState<ListenBlockId[]>(() => {
    const known = prefs.order.filter((id) => LISTEN_BLOCK_IDS.includes(id))
    return [...known, ...LISTEN_BLOCK_IDS.filter((id) => !known.includes(id))]
  })
  const hidden = new Set(prefs.hidden)
  const visibleOrder = order.filter((id) => !hidden.has(id))
  const hiddenOrder = order.filter((id) => hidden.has(id))

  const commit = (nextOrder: ListenBlockId[], nextHidden: ListenBlockId[]): void => {
    setOrder(nextOrder)
    onSave({ order: nextOrder, hidden: nextHidden })
  }

  const move = (id: ListenBlockId, delta: -1 | 1): void => {
    const list = [...order]
    const index = list.indexOf(id)
    const target = index + delta
    if (index < 0 || target < 0 || target >= list.length) return
    ;[list[index], list[target]] = [list[target], list[index]]
    commit(list, [...hidden])
  }

  const setHidden = (id: ListenBlockId, isHidden: boolean): void => {
    const nextHidden = isHidden
      ? [...hidden, id]
      : [...hidden].filter((candidate) => candidate !== id)
    commit([...order], nextHidden)
  }

  const renderRow = (id: ListenBlockId, isHiddenRow: boolean) => (
    <li key={id} className="manage-row">
      <span className="manage-row-title">{t(blockTitleKey(id))}</span>
      {!isHiddenRow && (
        <>
          <button type="button" onClick={() => move(id, -1)} aria-label={t('manageMoveUpAria', { title: t(blockTitleKey(id)) })}>↑</button>
          <button type="button" onClick={() => move(id, 1)} aria-label={t('manageMoveDownAria', { title: t(blockTitleKey(id)) })}>↓</button>
        </>
      )}
      <button
        type="button"
        className={isHiddenRow ? undefined : 'manage-danger'}
        onClick={() => setHidden(id, !isHiddenRow)}
        aria-label={isHiddenRow ? t('manageRestoreAria', { title: t(blockTitleKey(id)) }) : t('manageHideAria', { title: t(blockTitleKey(id)) })}
      >
        {isHiddenRow ? '↺' : '✕'}
      </button>
    </li>
  )

  return (
    <div className="lib-dialog-backdrop" onClick={onClose}>
      <div
        className="lib-sheet"
        role="dialog"
        aria-modal="true"
        aria-label={t('manageTitle')}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="lib-sheet-head">
          <h2 ref={headingRef} tabIndex={-1} className="lib-sheet-title">{t('manageTitle')}</h2>
          <button type="button" className="tabheader-btn" onClick={onClose}>✕</button>
        </div>
        <p className="lib-sheet-hint">{t('manageHint')}</p>
        <ul className="manage-list">{visibleOrder.map((id) => renderRow(id, false))}</ul>
        {hiddenOrder.length > 0 && (
          <>
            <SectionHeader level="section" title={t('manageHiddenHeading')} />
            <ul className="manage-list">{hiddenOrder.map((id) => renderRow(id, true))}</ul>
          </>
        )}
        <div className="lib-dialog-actions">
          <button type="button" onClick={onClose}>{t('manageDone')}</button>
        </div>
      </div>
    </div>
  )
}
