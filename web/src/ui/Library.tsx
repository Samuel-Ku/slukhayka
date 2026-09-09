/**
 * #584 W1.2 — the Медіатека tab answers «де мої книги?» exactly like
 * Android's LibraryScreen: one BookRow list with the progress hairline,
 * the Нові/Слухаю/Завершені status filters (spec-28 #193), the honest
 * sort orders, and live counters that show real numbers or nothing
 * (ADR-0014). Data comes from the Work-relationship projection (W0.3) —
 * favorites from a linked phone appear right after binding.
 *
 * Deletion is Android's three-tier flow (W1.3): the row's ⋮ action opens
 * the options sheet naming every tier and its consequence; tier 1
 * (Прибрати з медіатеки) executes the cascade immediately, tier 3
 * (Видалити та файли з пристрою) alone earns the exact-scope
 * confirmation. Confirming writes a tombstone (sync pushes it — a hidden
 * Work never resurrects), clears the local Listening State rows of the
 * Work's linked Editions, and removes the links.
 *
 * Web deltas (honest, per the platform ledger): a row body is not
 * openable yet — a synced Library Entry carries no Source URL; DOWNLOADED/
 * LOCAL/ONLINE filters and the DURATION sort have no honest web data yet
 * and stay absent instead of dead. Rows without local listening history
 * show no hairline — never a fabricated percent.
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import type { LibraryEntryEntity, PersonBookmarkEntity } from '../local/domain'
import type { DomainStore } from '../local/domain'
import type { EditionLink, EditionLinkStore } from '../local/editionLinks'
import type { ListenerDatabase } from '../local/listeningState'
import type { LocalListeningStateSnapshot } from '../player/localState'
import { useTranslate, useUiLocale } from '../i18n/locale'
import type { StringKey } from '../i18n/strings'
import { BookRow, EmptyState, EmptyStateRow, SectionHeader, TabHeader } from './components'
import { DeleteBookSheet, type DownloadedCopy } from './DeleteBookSheet'
import { deleteEverythingScopeText, type DeleteScope } from './deleteModel'
import type { PersonBookmarkSyncController } from '../sync/personBookmarkController'
import {
  buildLibraryViews,
  filterLibrary,
  sortLibrary,
  type LibraryBookView,
  type LibraryFilter,
  type LibrarySort,
} from './libraryModel'

const FILTERS: Array<{ id: LibraryFilter; labelKey: StringKey }> = [
  { id: 'all', labelKey: 'all' },
  { id: 'new', labelKey: 'libFilterNew' },
  { id: 'listening', labelKey: 'libFilterListening' },
  { id: 'completed', labelKey: 'libFilterCompleted' },
]

const SORTS: Array<{ id: LibrarySort; labelKey: StringKey }> = [
  { id: 'recently-listened', labelKey: 'libSortRecentlyListened' },
  { id: 'recently-added', labelKey: 'libSortRecentlyAdded' },
  { id: 'title', labelKey: 'libSortTitle' },
  { id: 'author', labelKey: 'libSortAuthor' },
]

function pillStyle(active: boolean): React.CSSProperties {
  return {
    padding: '8px 14px',
    borderRadius: 999,
    border: '1px solid var(--line)',
    background: active ? 'var(--accent)' : 'var(--surface)',
    color: active ? 'var(--accent-contrast)' : 'var(--fg)',
  }
}

export function Library({ domainStore, linkStore, listening, pushAfterChange, downloadsOf, personBookmarks }: {
  domainStore: DomainStore
  linkStore: EditionLinkStore
  listening: Pick<ListenerDatabase, 'allSnapshots' | 'clearSnapshot'>
  pushAfterChange: (mergeKey: string) => Promise<void>
  /** The Work's downloaded copy, when the (future) download manager owns one. */
  downloadsOf?: (mergeKey: string) => DownloadedCopy | null
  /** #582 W0.4 — the «Ваші виконавці/автори» block (absent without the seam). */
  personBookmarks?: PersonBookmarkSyncController
}) {
  const t = useTranslate()
  const locale = useUiLocale()
  const [entries, setEntries] = useState<LibraryEntryEntity[] | null>(null)
  const [links, setLinks] = useState<EditionLink[]>([])
  const [snapshots, setSnapshots] = useState<LocalListeningStateSnapshot[]>([])
  // #582 W0.4 — Android's «Люди» tab adapted as a block under the books:
  // the web Медіатека has no sub-tabs, so the block is the honest shape.
  const [people, setPeople] = useState<PersonBookmarkEntity[]>([])
  const [peopleReady, setPeopleReady] = useState(false)
  const [filter, setFilter] = useState<LibraryFilter>('all')
  const [sort, setSort] = useState<LibrarySort>('recently-listened')
  // The three-tier deletion state: the options sheet first, the exact-scope
  // confirmation only for the delete-everything tier (Android's flow).
  const [sheetFor, setSheetFor] = useState<LibraryBookView | null>(null)
  const [confirmFor, setConfirmFor] = useState<LibraryBookView | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  // The a11y contract: the dialog takes focus when it opens and hands it
  // back to the ⋮ row that opened it when everything closes.
  const dialogConfirmRef = useRef<HTMLButtonElement | null>(null)
  const menuButtonRefs = useRef(new Map<string, HTMLButtonElement | null>())
  const openedFrom = useRef<string | null>(null)

  useEffect(() => {
    if (confirmFor !== null) {
      dialogConfirmRef.current?.focus()
      return
    }
    if (sheetFor !== null) return
    const origin = openedFrom.current
    openedFrom.current = null
    const node = origin === null ? undefined : menuButtonRefs.current.get(origin)
    if (node?.isConnected === true) node.focus()
  }, [confirmFor, sheetFor])

  const load = async (): Promise<void> => {
    const [loadedEntries, loadedLinks, loadedSnapshots, loadedPeople] = await Promise.all([
      domainStore.libraryEntries(),
      linkStore.all(),
      listening.allSnapshots(),
      personBookmarks === undefined ? Promise.resolve([]) : domainStore.personBookmarks(),
    ])
    setEntries(loadedEntries)
    setLinks(loadedLinks)
    setSnapshots(loadedSnapshots)
    setPeople(loadedPeople)
    setPeopleReady(true)
  }

  useEffect(() => {
    // The screen remounts on every tab visit, so a visit is always a fresh
    // read — the live-counter analogue of Android's Room flow.
    void load()
  }, [])

  const views = useMemo(
    () => (entries === null ? null : buildLibraryViews(entries, links, snapshots)),
    [entries, links, snapshots],
  )
  const visible = useMemo(
    () => (views === null ? null : sortLibrary(filterLibrary(views, filter), sort)),
    [views, filter, sort],
  )

  /** The exact scope of THIS Work's delete-everything, in this platform's truth. */
  const scopeOf = (view: LibraryBookView): DeleteScope => ({
    title: view.title,
    chapters: false,
    bookmarks: false,
    progress: true,
    downloads: downloadsOf?.(view.mergeKey) ?? null,
  })

  const performDelete = async (view: LibraryBookView): Promise<void> => {
    // The tombstone anchors at the Work: one row blocks every Edition and
    // Source of it — nothing resurrects after a catalog refresh (CONTEXT.md).
    await domainStore.tombstoneWork(view.mergeKey)
    const linked = links.filter((link) => link.mergeKey === view.mergeKey)
    for (const link of linked) await listening.clearSnapshot(link.editionId)
    await linkStore.removeForMerge(view.mergeKey)
    await pushAfterChange(view.mergeKey)
    openedFrom.current = null
    setConfirmFor(null)
    setSheetFor(null)
    setNotice(t('libDeletedNotice', { title: view.title }))
    await load()
  }

  return (
    <div>
      <TabHeader title={t('tabLibrary')} />
      <div style={{ display: 'flex', gap: 6, margin: '8px 0', flexWrap: 'wrap', alignItems: 'center' }}>
        {FILTERS.map((item) => (
          <button
            key={item.id}
            onClick={() => setFilter(item.id)}
            style={pillStyle(filter === item.id)}
            aria-pressed={filter === item.id}
          >
            {t(item.labelKey)}
          </button>
        ))}
        <label style={{ marginLeft: 'auto', display: 'flex', gap: 6, alignItems: 'center', color: 'var(--fg-dim)', fontSize: 13 }}>
          {t('libSortLabel')}
          <select
            value={sort}
            onChange={(event) => setSort(event.target.value as LibrarySort)}
            aria-label={t('libSortLabel')}
          >
            {SORTS.map((item) => (
              <option key={item.id} value={item.id}>{t(item.labelKey)}</option>
            ))}
          </select>
        </label>
      </div>

      {notice && <EmptyStateRow message={notice} />}

      {/* #582 W0.4 — «Ваші виконавці/автори» on canonical BookRows (the AC's
          block), below the books; the honest count is the real bookmark
          total, and the row's ★ removes the bookmark (Android's toggle). */}
      {personBookmarks !== undefined && peopleReady && people.length > 0 && (
        <>
          <SectionHeader level="group" title={t('yourPeople')} count={people.length} />
          <ul className="card-list">
            {people.map((person) => (
              <BookRow
                key={person.personId}
                title={person.displayName}
                subtitle={t(person.role === 'author' ? 'personRoleAuthor' : 'personRolePerformer')}
                trailing={
                  <button
                    type="button"
                    onClick={() => {
                      void personBookmarks.toggle(person.role, person.displayName).then(load)
                    }}
                    aria-label={t('personBookmarkRemoveAria', { name: person.displayName })}
                    aria-pressed={true}
                    style={{ background: 'none', border: '1px solid var(--line)', borderRadius: 999, padding: '4px 10px', color: 'var(--accent)', cursor: 'pointer' }}
                  >
                    ★
                  </button>
                }
              />
            ))}
          </ul>
        </>
      )}

      {views === null || visible === null ? (
        <EmptyStateRow message={t('loading')} />
      ) : entries!.length === 0 ? (
        <EmptyState icon="📚" message={t('libEmptyTitle')} hint={t('libEmptyHint')} />
      ) : (
        <>
          <SectionHeader level="group" title={t('tabLibrary')} count={visible.length} />
          {visible.length === 0 ? (
            <EmptyStateRow message={t('libFilterEmpty')} />
          ) : (
            <ul className="card-list">
              {visible.map((view) => (
                <BookRow
                  key={view.mergeKey}
                  title={view.title}
                  subtitle={view.author + (view.narrator !== '' ? ` · ${view.narrator}` : '')}
                  progress={view.progress}
                  actions={
                    <button
                      type="button"
                      ref={(node) => { menuButtonRefs.current.set(view.mergeKey, node) }}
                      onClick={() => { openedFrom.current = view.mergeKey; setSheetFor(view) }}
                      aria-label={t('libActionsAria', { title: view.title })}
                      aria-haspopup="dialog"
                      style={{ background: 'none', border: '1px solid var(--line)', borderRadius: 999, color: 'var(--fg)' }}
                    >
                      ⋮
                    </button>
                  }
                />
              ))}
            </ul>
          )}
        </>
      )}

      {sheetFor !== null && (
        <DeleteBookSheet
          workTitle={sheetFor.title}
          downloads={downloadsOf?.(sheetFor.mergeKey) ?? null}
          onRemoveFromLibrary={() => {
            // Tier 1 — the full cascade, immediately (Android: no second
            // confirmation; the sheet's consequence line is the warning).
            const view = sheetFor
            openedFrom.current = null
            setSheetFor(null)
            void performDelete(view)
          }}
          onDeleteEverything={() => {
            // Tier 3 — the red tier alone earns the exact-scope confirmation.
            const view = sheetFor
            setSheetFor(null)
            setConfirmFor(view)
          }}
          onDismiss={() => setSheetFor(null)}
        />
      )}

      {confirmFor !== null && (
        <div className="lib-dialog-backdrop">
          <div className="lib-dialog" role="alertdialog" aria-modal="true" aria-label={t('deleteConfirmTitle', { title: confirmFor.title })}>
            <h2 className="lib-dialog-title">{t('deleteConfirmTitle', { title: confirmFor.title })}</h2>
            <p className="lib-dialog-scope">
              {deleteEverythingScopeText(scopeOf(confirmFor), t, locale)}
            </p>
            <div className="lib-dialog-actions">
              <button
                type="button"
                onClick={() => { openedFrom.current = confirmFor.mergeKey; setConfirmFor(null) }}
              >
                {t('cancel')}
              </button>
              <button
                type="button"
                ref={dialogConfirmRef}
                className="lib-dialog-danger"
                onClick={() => void performDelete(confirmFor)}
              >
                {t('deleteConfirmAction')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
