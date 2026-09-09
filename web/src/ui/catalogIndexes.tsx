import { useEffect, useState, type ReactNode } from 'react'
import { api } from '../api/client'
import { readWarmEntry, warmKey, writeWarm } from '../api/warmCache'
import { useTranslate, useUiLocale } from '../i18n/locale'
import type { CatalogCard, ParsedCatalog, SourceId, UnifiedWork } from '../worker/types'
import { SOURCE_METADATA } from '../worker/sourceMetadata'
import { ukPlural } from './deleteModel'
import { FEED_CATALOG, needsNetwork } from './feedSnapshotPolicy'
import { formatRemainingTime } from './listenComposer'
import { BookRow, CycleCard, EmptyStateRow, PosterCard, SectionHeader } from './components'
import type { MatchedCollection } from './collectionModel'
import type { DomainStore, PersonRole } from '../local/domain'
import { personIdentityOf } from '../local/personIdentity'
import type { PersonBookmarkSyncController } from '../sync/personBookmarkController'

/**
 * W3.2 — the four catalogue indexes (Серії, Колекції, ТОП 100, Виконавці/
 * Автори) on the shared push-screen chassis (spec-28 #189/#190/#198,
 * ADR-0033). The chassis carries the canonical title, the honest count and
 * the back arrow; only the content differs per screen — exactly the
 * Android `IndexScreenScaffold` contract.
 *
 * Data is what the catalogue actually has: series come from the 4read
 * homepage's own «Цикли» section, collections are the shipped assets matched
 * LOCALLY, ТОП 100 and people come from the site's own index pages through
 * the worker. A fetch failure falls back to the last snapshot with the
 * honest loading note; no data → the canonical empty state, never a crash.
 */

/** undefined = loading, null = absent/failed, array = the real cards. */
export function useFourreadSection(
  url: string | undefined,
  cacheKey: string,
  sectionId: string,
): CatalogCard[] | null | undefined {
  const [cards, setCards] = useState<CatalogCard[] | null | undefined>(undefined)
  useEffect(() => {
    let alive = true
    setCards(undefined)
    void (async () => {
      const cached = await readWarmEntry<ParsedCatalog>(cacheKey)
      if (!alive) return
      const sectionOf = (value: ParsedCatalog | null): CatalogCard[] | null => {
        const section = value?.sections.find((s) => s.id === sectionId)
        return section && section.cards.length > 0 ? section.cards : null
      }
      // Catalog-grade TTL (24 h): a fresh snapshot answers without a call.
      if (cached !== null && !needsNetwork(FEED_CATALOG, cached.savedAt, Date.now())) {
        setCards(sectionOf(cached.value))
        return
      }
      const parsed = url === undefined ? await api.catalog('fourread') : await api.catalog('fourread', url)
      if (!alive) return
      if (parsed !== null) {
        void writeWarm(cacheKey, parsed)
        setCards(sectionOf(parsed))
      } else {
        // Offline: the last snapshot still answers (honest stale).
        setCards(cached !== null ? sectionOf(cached.value) : null)
      }
    })()
    return () => { alive = false }
  }, [url, cacheKey, sectionId])
  return cards
}

/** The shared index chassis: back arrow, canonical title, honest count. */
export function IndexChrome({ title, count, onBack, children }: {
  title: string
  /** Real counts only; `undefined` renders nothing — never a fabricated 0 (ADR-0014). */
  count?: number
  onBack: () => void
  children: ReactNode
}) {
  const t = useTranslate()
  return (
    <div>
      <button type="button" className="back" onClick={onBack}>{t('back')}</button>
      <SectionHeader level="group" title={title} count={count} />
      {children}
    </div>
  )
}

/** «Серії»: every series of the catalogue's «Цикли» section as CycleCards. */
export function SeriesIndexPanel({ onBack, onOpenSeries }: {
  onBack: () => void
  onOpenSeries: (card: CatalogCard) => void
}) {
  const t = useTranslate()
  const cards = useFourreadSection(undefined, warmKey('catalog', 'fourread', 'homepage'), 'series')
  return (
    <IndexChrome title={t('navSeries')} count={cards && cards.length > 0 ? cards.length : undefined} onBack={onBack}>
      {cards === undefined ? (
        <EmptyStateRow message={t('loadingCatalog')} />
      ) : cards === null || cards.length === 0 ? (
        <EmptyStateRow message={t('seriesIndexEmpty')} />
      ) : (
        <ul className="index-grid">
          {cards.map((card) => (
            <li key={card.url}>
              <CycleCard
                coverUrl={card.coverImageUrl}
                title={card.title}
                onClick={() => onOpenSeries(card)}
                openAriaLabel={t('openCycleAria', { title: card.title })}
              />
            </li>
          ))}
        </ul>
      )}
    </IndexChrome>
  )
}

/** «Колекції»: one header + horizontal cover row per matched collection. */
export function CollectionsIndexPanel({ collections, onBack, onOpenWork }: {
  collections: MatchedCollection<UnifiedWork>[]
  onBack: () => void
  onOpenWork: (work: UnifiedWork) => void
}) {
  const t = useTranslate()
  return (
    // Android's CollectionsIndexScreen carries no count subtitle (matched
    // books overlap collections, so no single honest total exists) — the
    // canonical chrome omits it too.
    <IndexChrome title={t('navCollections')} onBack={onBack}>
      {collections.length === 0 ? (
        <EmptyStateRow message={t('collectionsIndexEmpty')} />
      ) : (
        collections.map((collection) => (
          <div key={collection.id}>
            <SectionHeader level="section" title={collection.name} />
            <ul className="shelf-rail">
              {collection.books.map((work) => (
                <li key={work.id}>
                  <PosterCard
                    coverUrl={work.coverImageUrl}
                    title={work.title}
                    author={work.author}
                    onClick={() => onOpenWork(work)}
                    openAriaLabel={t('openBookPosterAria', { title: work.title })}
                  />
                </li>
              ))}
            </ul>
          </div>
        ))
      )}
    </IndexChrome>
  )
}

/** «ТОП 100»: the site's ranked list; rank is the list order (1-based). */
export function Top100IndexPanel({ onBack, onOpenBook }: {
  onBack: () => void
  onOpenBook: (url: string, source: SourceId) => void
}) {
  const t = useTranslate()
  const locale = useUiLocale()
  const home = SOURCE_METADATA.fourread.homeUrl
  const cards = useFourreadSection(`${home}/top-100.html`, warmKey('catalog', 'fourread', 'top100'), 'top100')
  return (
    <IndexChrome title={t('navTop100')} count={cards && cards.length > 0 ? cards.length : undefined} onBack={onBack}>
      {cards === undefined ? (
        <EmptyStateRow message={t('loadingCatalog')} />
      ) : cards === null || cards.length === 0 ? (
        <EmptyStateRow message={t('top100IndexEmpty')} />
      ) : (
        <ul className="card-list">
          {cards.map((card, rank) => (
            <BookRow
              key={card.url}
              rank={rank + 1}
              coverUrl={card.coverImageUrl}
              title={card.title}
              subtitle={card.author || undefined}
              trailing={card.durationSeconds && card.durationSeconds > 0
                ? <span className="bookrow-duration">{formatRemainingTime(card.durationSeconds, locale)}</span>
                : undefined}
              onOpen={() => onOpenBook(card.url, 'fourread')}
              openAriaLabel={t('openRankedAria', { title: card.title })}
            />
          ))}
        </ul>
      )}
    </IndexChrome>
  )
}

/**
 * #582 W0.4 — one people index (Виконавці or Автори) from its own site
 * page, with Android's person-bookmark toggle on every row: the web's
 * in-app person surface is this list (person pages themselves are external
 * site pages), so the bookmark button lives here — the honest counterpart
 * of Android's PersonBooksScreen control. The buttons render only when the
 * controller is present (no Firebase config → no buttons, never dead UI).
 */
export function PeopleIndexPanel({ kind, onBack, onOpenPerson, domainStore, personBookmarks }: {
  kind: 'performers' | 'authors'
  onBack: () => void
  onOpenPerson: (card: CatalogCard) => void
  /** #582 W0.4 — read the bookmark state (buttons render only with both). */
  domainStore?: Pick<DomainStore, 'personBookmarks'>
  personBookmarks?: PersonBookmarkSyncController
}) {
  const t = useTranslate()
  const home = SOURCE_METADATA.fourread.homeUrl
  const url = kind === 'performers' ? `${home}/readers.html` : `${home}/avtors.html`
  const cards = useFourreadSection(url, warmKey('catalog', 'fourread', kind), 'people')
  const role: PersonRole = kind === 'performers' ? 'narrator' : 'author'
  const [booked, setBooked] = useState<Set<string>>(new Set())
  const [ready, setReady] = useState(false)
  useEffect(() => {
    let alive = true
    setReady(false)
    if (domainStore === undefined || personBookmarks === undefined) return
    void domainStore.personBookmarks().then((rows) => {
      if (!alive) return
      setBooked(new Set(rows.map((row) => row.personId)))
      setReady(true)
    })
    return () => { alive = false }
  }, [domainStore, personBookmarks])
  const toggle = async (name: string): Promise<void> => {
    if (personBookmarks === undefined || domainStore === undefined) return
    await personBookmarks.toggle(role, name)
    const rows = await domainStore.personBookmarks()
    setBooked(new Set(rows.map((row) => row.personId)))
  }
  // en's few === many ('books'), so the one plural helper covers both locales.
  const countLabel = (n: number): string =>
    `${n} ${ukPlural(n, t('peopleBookOne'), t('peopleBookFew'), t('peopleBookMany'))}`
  return (
    <IndexChrome
      title={kind === 'performers' ? t('navPerformers') : t('navAuthors')}
      count={cards && cards.length > 0 ? cards.length : undefined}
      onBack={onBack}
    >
      {cards === undefined ? (
        <EmptyStateRow message={t('loadingCatalog')} />
      ) : cards === null || cards.length === 0 ? (
        <EmptyStateRow message={t('peopleIndexEmpty')} />
      ) : (
        <ul className="card-list">
          {cards.map((card) => {
            const isBookmarked = ready && booked.has(personIdOfName(role, card.title))
            return (
              <BookRow
                key={card.url}
                title={card.title}
                subtitle={card.count !== undefined ? countLabel(card.count) : undefined}
                onOpen={() => onOpenPerson(card)}
                openAriaLabel={t('openPersonAria', { name: card.title })}
                trailing={domainStore !== undefined && personBookmarks !== undefined ? (
                  <button
                    type="button"
                    onClick={() => void toggle(card.title)}
                    aria-label={isBookmarked
                      ? t('personBookmarkRemoveAria', { name: card.title })
                      : t('personBookmarkAddAria', { name: card.title })}
                    aria-pressed={isBookmarked}
                    style={{
                      background: 'none',
                      border: '1px solid var(--line)',
                      borderRadius: 999,
                      padding: '4px 10px',
                      color: isBookmarked ? 'var(--accent)' : 'var(--fg)',
                      cursor: 'pointer',
                    }}
                  >
                    {isBookmarked ? '★' : '☆'}
                  </button>
                ) : undefined}
              />
            )
          })}
        </ul>
      )}
    </IndexChrome>
  )
}

/** The deterministic bookmark id of a displayed person name (Android's boundedId). */
function personIdOfName(role: PersonRole, name: string): string {
  return personIdentityOf(role, name).id
}