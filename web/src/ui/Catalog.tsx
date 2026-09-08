import { useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { api } from '../api/client'
import { readWarmEntry, warmKey, writeWarm } from '../api/warmCache'
import type { BookDetail, CatalogCard, ParsedCatalog, SourceId, UnifiedEdition, UnifiedSource, UnifiedWork, UnifiedWorkPage } from '../worker/types'
import {
  availabilitySortRank,
  isAvailabilityFresh,
  preflightMediaRange,
  probeStreamPlaying,
  raceEditionSources,
  readAvailabilityAssertion,
  writeAvailabilityAssertion,
  type AvailabilityVerdict,
} from './catalogAvailability'
import { sourceNeedsBrowserSession } from './bookPlaybackAvailability'
import { SOURCE_METADATA, SOURCE_ORDER } from '../worker/sourceMetadata'
import { rankEditionsForPlayback } from '../worker/workFeed'
import { useTranslate, useUiLocale } from '../i18n/locale'
import type { DomainStore } from '../local/domain'
import {
  availableLanguagesOf,
  filterWorksByLanguage,
  LANGUAGE_LABELS,
  loadContentLanguagePrefs,
  saveContentLanguagePrefs,
  toggleLanguage,
} from './contentLanguagePrefs'
import { BookRow, CycleCard, EmptyStateRow, MetadataChip, PosterCard, SectionHeader, TabHeader } from './components'
import { CollectionsIndexPanel, PeopleIndexPanel, SeriesIndexPanel, Top100IndexPanel } from './catalogIndexes'
import { loadCollections } from './collectionAssets'
import { matchAllCollections } from './collectionModel'
import { FEED_CATALOG, FEED_HOMEPAGE_SECTIONS, FEED_NEW_ARRIVALS, needsNetwork } from './feedSnapshotPolicy'
import { formatRemainingTime } from './listenComposer'

const SOURCES: Array<{ id: 'all' | SourceId; label: string }> = [
  { id: 'all', label: 'all' },
  ...SOURCE_ORDER.map((id) => ({ id, label: SOURCE_METADATA[id].label })),
]

// W3.1 — the curated collections (static JSON assets) decoded once at module
// load; a malformed asset contributes nothing (best-effort, CollectionJson).
const SHIPPED_COLLECTIONS = loadCollections()

/** One horizontal shelf shows at most this many cards. */
const RAIL_LIMIT = 15

function pillStyle(active: boolean): CSSProperties {
  return {
    padding: '8px 14px',
    borderRadius: 999,
    border: '1px solid var(--line)',
    background: active ? 'var(--accent)' : 'var(--surface)',
    color: active ? 'var(--accent-contrast)' : 'var(--fg)',
  }
}

/** spec-43/T3+T4 — огляд із перемикачем джерел і пошуком. */
export function Catalog({ onOpenBook, onPlay, onSaveWork, domainStore }: {
  onOpenBook: (url: string, source: SourceId) => void
  onPlay: (detail: BookDetail, chapterIndex: number) => Promise<boolean>
  /** #584 W1.2 — «зберегти»: creates the Library Entry for this Work. */
  onSaveWork?: (work: UnifiedWork, edition: UnifiedEdition) => void
  /** #584 W1.3 — tombstones: a hidden Work never returns to Огляд. */
  domainStore?: DomainStore
}) {
  const t = useTranslate()
  const locale = useUiLocale()
  const [source, setSource] = useState<'all' | SourceId>('all')
  const [works, setWorks] = useState<UnifiedWork[] | null>(null)
  const [nextPageUrl, setNextPageUrl] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [appendError, setAppendError] = useState(false)
  const [showingCachedCatalog, setShowingCachedCatalog] = useState(false)
  const [cachedAt, setCachedAt] = useState<number | null>(null)
  const [failed, setFailed] = useState(false)
  const [query, setQuery] = useState('')
  const [searchWorks, setSearchWorks] = useState<UnifiedWork[] | null>(null)
  const [searching, setSearching] = useState(false)
  // W3.1 — explicit refresh: the «Оновити» pill sets the flag and bumps the
  // nonce; the load effect consumes the flag once (bypassing the snapshot
  // TTL) and rewrites the snapshot — the consumed-ref pattern avoids an echo
  // re-run that would briefly blank the feed.
  const forceRefreshRef = useRef(false)
  const [refreshNonce, setRefreshNonce] = useState(0)
  // W3.1 — «Цикли»: the 4read homepage series shelf (live list via the worker).
  const [cycles, setCycles] = useState<CatalogCard[] | null>(null)
  // W3.2 — the pushed index screens (spec-28 #198): one at a time over the
  // feed; null = the Огляд itself. The chip that opened the index keeps the
  // focus-return contract (back focuses it again).
  const [index, setIndex] = useState<'series' | 'collections' | 'top100' | 'performers' | 'authors' | null>(null)
  // The focus-return contract: closing an index returns focus to the chip
  // that opened it (the Settings/Android returnDestination → rowFocus form).
  // Per-id refs: on re-mount every chip's callback re-runs, and the effect
  // below fires after the commit, so the map holds the FRESH nodes.
  const chipRefs = useRef(new Map<string, HTMLButtonElement | null>())
  const openedIndex = useRef<string | null>(null)
  const openIndex = (id: NonNullable<typeof index>): void => {
    openedIndex.current = id
    setIndex(id)
  }
  useEffect(() => {
    if (index !== null) return
    const origin = openedIndex.current
    openedIndex.current = null
    if (origin !== null) chipRefs.current.get(origin)?.focus()
  }, [index])
  const closeIndex = (): void => setIndex(null)
  // spec-45 T13 — the persisted content-language preference; empty = all.
  const [contentLanguages, setContentLanguages] = useState<string[]>(() => loadContentLanguagePrefs())
  const applyLanguages = (next: string[]): void => setContentLanguages(saveContentLanguagePrefs(next))
  const loadMoreMarker = useRef<HTMLDivElement | null>(null)
  // #584 W1.3 — the listener's deliberate hides: a tombstoned Work never
  // re-enters discovery, whatever the catalog refresh brings back.
  const [tombstoned, setTombstoned] = useState<ReadonlySet<string>>(new Set())

  const visibleWorks = filterWorksByLanguage((works ?? []).filter((work) => !tombstoned.has(work.mergeKey)), contentLanguages)
  const visibleSearch = filterWorksByLanguage((searchWorks ?? []).filter((work) => !tombstoned.has(work.mergeKey)), contentLanguages)
  const languageOptions = availableLanguagesOf([...(works ?? []), ...(searchWorks ?? [])], contentLanguages)
  // W3.1 — «Колекції» match LOCALLY against the merged union (the same Works
  // the feed shows); a tombstoned or language-hidden Work never appears here.
  const collections = useMemo(() => matchAllCollections(SHIPPED_COLLECTIONS, visibleWorks), [visibleWorks])
  const railWorks = visibleWorks.slice(0, RAIL_LIMIT)

  // W3.1 — a shelf card opens the Work the same way a feed row does: the
  // top-ranked Edition's first real Source (never a fabricated URL).
  const openWork = (work: UnifiedWork): void => {
    const edition = rankEditionsForPlayback(work.editions)[0]
    const source = edition?.sources[0]
    if (source) onOpenBook(source.url, source.sourceId)
  }

  /** The distinct source badges of one Work (ADR-0014: only real sources). */
  const sourceBadges = (work: UnifiedWork): ReactNode => {
    const ids = new Set<SourceId>()
    for (const edition of work.editions) {
      for (const source of edition.sources) ids.add(source.sourceId)
    }
    return [...ids].map((id) => <MetadataChip key={id} kind="source">{SOURCE_METADATA[id].label}</MetadataChip>)
  }

  const posterDuration = (work: UnifiedWork): string | undefined => {
    const seconds = rankEditionsForPlayback(work.editions)[0]?.durationSeconds
    return seconds && seconds > 0 ? formatRemainingTime(seconds, locale) : undefined
  }

  useEffect(() => {
    // The tombstone read rides the same trigger as the feed, so a hide made
    // in Медіатека is honored the next time Огляд renders.
    let tombstonesAlive = true
    void domainStore?.tombstones().then((rows) => {
      if (tombstonesAlive) setTombstoned(new Set(rows.map((row) => row.mergeKey)))
    })
    return () => { tombstonesAlive = false }
  }, [domainStore])

  useEffect(() => {
    if (query.trim().length >= 2) return
    let alive = true
    setFailed(false)
    setAppendError(false)
    setShowingCachedCatalog(false)
    setCachedAt(null)
    setWorks(null)
    setNextPageUrl(null)
    // W3.1 — the FeedSnapshotPolicy port decides the network: a FRESH
    // snapshot answers without a call; stale/missing or the explicit refresh
    // fetches live; a failed fetch falls back to the last snapshot with an
    // honest note (offline start). One merged page serves the «Новинки» rail
    // and the catalog feed, so on the cross-source view the binding TTL is
    // the new-arrivals one (6 h); a per-source view is catalog-grade (24 h).
    const feedKey = source === 'all' ? FEED_NEW_ARRIVALS : FEED_CATALOG
    const forceRefresh = forceRefreshRef.current
    forceRefreshRef.current = false // consumed once, never echoed
    void (async () => {
      const cached = await readWarmEntry<UnifiedWorkPage | UnifiedWork[]>(warmKey('catalog', source))
      if (!alive) return
      if (cached !== null && !needsNetwork(feedKey, cached.savedAt, Date.now(), forceRefresh)) {
        const cachedPage = Array.isArray(cached.value)
          ? { works: cached.value }
          : cached.value
        setWorks(cachedPage.works)
        setNextPageUrl(cachedPage.nextCursor ?? null)
        return
      }
      const page = await api.workFeed(undefined, source === 'all' ? undefined : source)
      if (!alive) return
      if (page === null) {
        if (cached !== null) {
          const cachedPage = Array.isArray(cached.value)
            ? { works: cached.value }
            : cached.value
          setWorks(cachedPage.works)
          setNextPageUrl(cachedPage.nextCursor ?? null)
          setShowingCachedCatalog(true)
          setCachedAt(cached.savedAt)
        } else {
          setFailed(true)
        }
        return
      }
      setWorks(page.works)
      setNextPageUrl(page.nextCursor ?? null)
      setShowingCachedCatalog(false)
      setCachedAt(null)
      void writeWarm(warmKey('catalog', source), page)
    })()
    return () => { alive = false }
  }, [source, query, refreshNonce])

  // W3.1 — «Цикли»: the 4read homepage series section through the worker
  // (spec-37 live list). Homepage sections are catalog-grade (24 h); a
  // failure leaves the shelf honestly absent and never breaks the refresh.
  useEffect(() => {
    if (source !== 'all' || query.trim().length >= 2) {
      setCycles(null)
      return
    }
    let alive = true
    const key = warmKey('catalog', 'fourread', 'homepage')
    void (async () => {
      const cached = await readWarmEntry<ParsedCatalog>(key)
      if (!alive) return
      if (cached !== null && !needsNetwork(FEED_HOMEPAGE_SECTIONS, cached.savedAt, Date.now())) {
        const section = cached.value.sections.find((s) => s.id === 'series')
        setCycles(section && section.cards.length > 0 ? section.cards : null)
        return
      }
      const parsed = await api.catalog('fourread')
      if (!alive) return
      const sourceOf = (value: ParsedCatalog | null): CatalogCard[] | null => {
        const section = value?.sections.find((s) => s.id === 'series')
        return section && section.cards.length > 0 ? section.cards : null
      }
      if (parsed !== null) {
        void writeWarm(key, parsed)
        setCycles(sourceOf(parsed))
      } else {
        // Offline: the last homepage snapshot still answers (honest stale).
        setCycles(cached !== null ? sourceOf(cached.value) : null)
      }
    })()
    return () => { alive = false }
  }, [source, query])

  useEffect(() => {
    const trimmed = query.trim()
    if (trimmed.length < 2) {
      setSearchWorks(null)
      return
    }
    let alive = true
    setSearching(true)
    const timer = setTimeout(() => {
      api.workSearch(trimmed, source === 'all' ? undefined : source).then((page) => {
        if (!alive) return
        setSearching(false)
        setSearchWorks(page?.works ?? [])
      })
    }, 400)
    return () => {
      alive = false
      clearTimeout(timer)
    }
  }, [query, source])

  const loadMore = (): void => {
    if (!nextPageUrl || loadingMore || query.trim().length >= 2) return
    setLoadingMore(true)
    setAppendError(false)
    api.workFeed(nextPageUrl, source === 'all' ? undefined : source).then((page) => {
      if (page === null) {
        setAppendError(true)
        return
      }
      setWorks((current) => {
        const combined = appendWorks(current ?? [], page.works)
        void writeWarm(warmKey('catalog', source), { works: combined, nextCursor: page.nextCursor } satisfies UnifiedWorkPage)
        return combined
      })
      setNextPageUrl(page.nextCursor ?? null)
    }).finally(() => setLoadingMore(false))
  }

  useEffect(() => {
    const marker = loadMoreMarker.current
    if (!marker || !nextPageUrl || query.trim().length >= 2 || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) loadMore()
    }, { rootMargin: '320px' })
    observer.observe(marker)
    return () => observer.disconnect()
  }, [nextPageUrl, loadingMore, query, source])

  return (
    <div>
      <TabHeader
        title={t('tabCatalog')}
        search
        searchQuery={query}
        onSearchQueryChange={setQuery}
      />
      {index !== null ? (
        index === 'series' ? (
          <SeriesIndexPanel onBack={closeIndex} onOpenSeries={(card) => window.open(card.url, '_blank', 'noopener')} />
        ) : index === 'collections' ? (
          <CollectionsIndexPanel collections={collections} onBack={closeIndex} onOpenWork={(work) => openWork(work)} />
        ) : index === 'top100' ? (
          <Top100IndexPanel onBack={closeIndex} onOpenBook={onOpenBook} />
        ) : (
          <PeopleIndexPanel kind={index} onBack={closeIndex} onOpenPerson={(card) => window.open(card.url, '_blank', 'noopener')} />
        )
      ) : (
        <>
      <div style={{ display: 'flex', gap: 6, margin: '8px 0', flexWrap: 'wrap', alignItems: 'center' }}>
        {SOURCES.map((s) => (
          <button
            key={s.id}
            onClick={() => {
              setSource(s.id)
              setQuery('')
            }}
            style={pillStyle(source === s.id)}
          >
            {s.id === 'all' ? t('allSources') : s.label}
          </button>
        ))}
        {query.trim().length < 2 && (
          <button
            type="button"
            onClick={() => {
              // W3.1 — explicit refresh: bypasses the snapshot TTL once.
              forceRefreshRef.current = true
              setRefreshNonce((n) => n + 1)
            }}
            style={pillStyle(false)}
            aria-label={t('refreshCatalogAria')}
          >
            {t('refreshCatalog')}
          </button>
        )}
      </div>
      {languageOptions.length > 0 && (
        <div style={{ display: 'flex', gap: 6, margin: '8px 0', flexWrap: 'wrap', alignItems: 'center' }}>
          <span style={{ color: 'var(--fg-dim)', fontSize: 13 }}>{t('languageFilterLabel')}</span>
          <button
            onClick={() => applyLanguages([])}
            style={pillStyle(contentLanguages.length === 0)}
            aria-pressed={contentLanguages.length === 0}
          >
            {t('all')}
          </button>
          {languageOptions.map((code) => (
            <button
              key={code}
              onClick={() => applyLanguages(toggleLanguage(contentLanguages, code))}
              style={pillStyle(contentLanguages.includes(code))}
              aria-pressed={contentLanguages.includes(code)}
            >
              {LANGUAGE_LABELS[code] ?? code}
            </button>
          ))}
        </div>
      )}

      {/* W3.2 — «Швидкі переходи» (spec-28 #198): the five-chip navigation
          row, in Android's order — ТОП 100 / Виконавці / Автори / Серії /
          Колекції. Navigation chips (ADR-0018): filled, no outline. The
          row belongs to the default view only; an open index replaces the
          feed below (push-screen chassis). */}
      {source === 'all' && query.trim().length < 2 && (
        <section>
          <SectionHeader level="group" title={t('quickTransitions')} />
          <div style={{ display: 'flex', gap: 6, margin: '8px 0', flexWrap: 'wrap', alignItems: 'center' }}>
            <button type="button" className="nav-chip" ref={(node) => { chipRefs.current.set('top100', node) }} onClick={() => openIndex('top100')}>{t('navTop100')}</button>
            <button type="button" className="nav-chip" ref={(node) => { chipRefs.current.set('performers', node) }} onClick={() => openIndex('performers')}>{t('navPerformers')}</button>
            <button type="button" className="nav-chip" ref={(node) => { chipRefs.current.set('authors', node) }} onClick={() => openIndex('authors')}>{t('navAuthors')}</button>
            <button type="button" className="nav-chip" ref={(node) => { chipRefs.current.set('series', node) }} onClick={() => openIndex('series')}>{t('navSeries')}</button>
            <button type="button" className="nav-chip" ref={(node) => { chipRefs.current.set('collections', node) }} onClick={() => openIndex('collections')}>{t('navCollections')}</button>
          </div>
        </section>
      )}

      {/* W3.1 — Огляд shelves in the #302 pin order: search → quick
          transitions → «Для вас» → «Відкрити нове» → sticky toolbar → feed.
          The cross-source shelves belong to the default view only (a chosen
          source shows its own feed); the infinite feed stays the last
          element. An empty shelf renders nothing at all (ADR-0014). */}
      {source === 'all' && query.trim().length < 2 && works !== null && !failed &&
        (railWorks.length > 0 || (cycles?.length ?? 0) > 0 || collections.length > 0) && (
        <section>
          <SectionHeader level="group" title={t('shelfGroupNew')} />
          {railWorks.length > 0 && (
            <>
              <SectionHeader level="section" title={t('shelfNewArrivals')} />
              <ul className="shelf-rail">
                {railWorks.map((work) => (
                  <li key={work.id}>
                    <PosterCard
                      coverUrl={work.coverImageUrl}
                      title={work.title}
                      author={work.author}
                      duration={posterDuration(work)}
                      badges={sourceBadges(work)}
                      onClick={() => openWork(work)}
                      openAriaLabel={t('openBookPosterAria', { title: work.title })}
                    />
                  </li>
                ))}
              </ul>
            </>
          )}
          {cycles && cycles.length > 0 && (
            <>
              <SectionHeader level="section" title={t('shelfCycles')} />
              <ul className="shelf-rail">
                {cycles.map((cycle) => (
                  <li key={cycle.url}>
                    <CycleCard
                      coverUrl={cycle.coverImageUrl}
                      title={cycle.title}
                      onClick={() => window.open(cycle.url, '_blank', 'noopener')}
                      openAriaLabel={t('openCycleAria', { title: cycle.title })}
                    />
                  </li>
                ))}
              </ul>
            </>
          )}
          {collections.map((collection) => (
            <div key={collection.id}>
              <SectionHeader level="section" title={collection.name} />
              <ul className="shelf-rail">
                {collection.books.map((work) => (
                  <li key={work.id}>
                    <PosterCard
                      coverUrl={work.coverImageUrl}
                      title={work.title}
                      author={work.author}
                      duration={posterDuration(work)}
                      onClick={() => openWork(work)}
                      openAriaLabel={t('openBookPosterAria', { title: work.title })}
                    />
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </section>
      )}

      {query.trim().length >= 2 ? (
        searching ? (
          <EmptyStateRow message={t('searching')} />
        ) : searchWorks === null || visibleSearch.length === 0 ? (
          <EmptyStateRow message={t('nothingFound')} />
        ) : (
          <section>
            <SectionHeader level="group" title={t('allSources')} />
            <ul className="card-list">
              {visibleSearch.map((work) => <UnifiedWorkRow key={work.id} work={work} onOpenBook={onOpenBook} onPlay={onPlay} onSaveWork={onSaveWork} />)}
            </ul>
          </section>
        )
      ) : failed ? (
        <EmptyStateRow message={t('sourceFailed')} />
      ) : works === null ? (
        <EmptyStateRow message={t('loadingCatalog')} />
      ) : (
        <section>
          <SectionHeader
            level="group"
            title={source === 'all' ? t('allSources') : SOURCES.find((item) => item.id === source)?.label ?? ''}
            count={visibleWorks.length}
          />
          {showingCachedCatalog && (
            <EmptyStateRow message={t('cachedCatalogNotice', { date: cachedAt ? ` від ${new Date(cachedAt).toLocaleString('uk-UA')}` : '' })} />
          )}
          <ul className="card-list">
            {visibleWorks.map((work) => <UnifiedWorkRow key={work.id} work={work} onOpenBook={onOpenBook} onPlay={onPlay} onSaveWork={onSaveWork} />)}
          </ul>
        </section>
      )}
      {query.trim().length < 2 && nextPageUrl && (
        <div ref={loadMoreMarker} style={{ padding: '16px 0', textAlign: 'center' }}>
          {appendError && <EmptyStateRow message={t('appendFailed')} />}
          <button onClick={loadMore} disabled={loadingMore} style={pillStyle(false)}>
            {loadingMore ? t('loading') : appendError ? t('retry') : t('showMore')}
          </button>
        </div>
      )}
        </>
      )}
    </div>
  )
}

export function appendWorks(current: UnifiedWork[], incoming: UnifiedWork[]): UnifiedWork[] {
  const known = new Set(current.map((work) => work.id))
  return [...current, ...incoming.filter((work) => !known.has(work.id))]
}

/** One Work card with explicit Edition selection; changing it never mutates progress. */
function UnifiedWorkRow({ work, onOpenBook, onPlay, onSaveWork }: {
  work: UnifiedWork
  onOpenBook: (url: string, source: SourceId) => void
  onPlay: (detail: BookDetail, chapterIndex: number) => Promise<boolean>
  onSaveWork?: (work: UnifiedWork, edition: UnifiedEdition) => void
}) {
  const t = useTranslate()
  const [editionIndex, setEditionIndex] = useState(0)
  const [editions, setEditions] = useState(() => rankEditionsForPlayback(work.editions))
  useEffect(() => {
    let alive = true
    void Promise.all(work.editions.map(async (edition) => {
      const resolvedSources = await Promise.all(edition.sources.map(async (source) => {
        const assertion = await readAvailabilityAssertion(edition.id, source.sourceId)
        if (!assertion || !isAvailabilityFresh(assertion.verdict, assertion.observedAt, Date.now())) return source
        return {
          ...source,
          availability: assertion.verdict === 'playing' ? 'available' as const : 'unavailable' as const,
          verifiedAt: assertion.observedAt,
        }
      }))
      return {
        ...edition,
        sources: resolvedSources,
        verifiedAt: Math.max(edition.verifiedAt ?? 0, ...resolvedSources.map((source) => source.verifiedAt ?? 0)),
      }
    })).then((resolved) => {
      if (alive) setEditions(rankEditionsForPlayback(resolved))
    })
    return () => { alive = false }
  }, [work.id, work.editions])
  const edition = editions[editionIndex] ?? editions[0]
  const source = edition.sources[0]
  if (!source) return null
  const card: CatalogCard = {
    url: source.url,
    title: work.title,
    author: work.author,
    narrator: edition.narrator,
    coverImageUrl: work.coverImageUrl,
    durationSeconds: edition.durationSeconds,
    language: edition.language,
  }
  return (
    <>
      <CatalogCardRow
        card={card}
        editionId={edition.id}
        sources={edition.sources}
        onOpenBook={onOpenBook}
        onPlay={onPlay}
        onSave={onSaveWork === undefined ? undefined : (saved) => { if (!saved) onSaveWork(work, edition) }}
      />
      {editions.length > 1 && (
        <li style={{ padding: '0 8px 8px' }}>
          <label>
            {t('narrationSelect')}{' '}
            <select value={editionIndex} onChange={(event) => setEditionIndex(Number(event.target.value))} aria-label={t('chooseNarrationAria', { title: work.title })}>
              {editions.map((candidate, index) => <option key={candidate.id} value={index}>{candidate.narrator || t('unknownNarrator')}</option>)}
            </select>
          </label>
        </li>
      )}
    </>
  )
}

/** Appends a source cursor page without moving cards the listener already saw. */
export type CardActionState = 'idle' | 'checking' | 'no-network' | 'temporary-failure' | 'audio-missing' | 'browser-required'

export function cardResultState(
  detail: BookDetail | null,
  sessionRequired: boolean,
  online: boolean,
): Exclude<CardActionState, 'idle' | 'checking'> | 'ready' {
  if (detail === null) return online ? 'temporary-failure' : 'no-network'
  if (detail.chapters.length > 0) return 'ready'
  return sessionRequired ? 'browser-required' : 'audio-missing'
}

function sourceHome(source: SourceId): string {
  return SOURCE_METADATA[source].homeUrl
}

/** A card's body opens details; its neighbouring actions alone start playback or save. */
export function CatalogCardRow({ card, editionId, sources, onOpenBook, onPlay, onSave }: {
  card: CatalogCard
  editionId: string
  sources: UnifiedSource[]
  onOpenBook: (url: string, source: SourceId) => void
  onPlay: (detail: BookDetail, chapterIndex: number) => Promise<boolean>
  /** #584 W1.2 — the «зберегти» slot renders only when the action is real. */
  onSave?: (alreadySaved: boolean) => void
}) {
  const t = useTranslate()
  const [state, setState] = useState<CardActionState>('idle')
  const [saved, setSaved] = useState(false)
  const [rankedSources, setRankedSources] = useState(sources)
  const [sessionSource, setSessionSource] = useState<SourceId | null>(null)
  const generation = useRef(0)
  const activeAbort = useRef<AbortController | null>(null)
  const preflightAbort = useRef<AbortController | null>(null)
  const rowRef = useRef<HTMLLIElement | null>(null)
  const primarySource = rankedSources[0]
  const source = primarySource?.sourceId

  useEffect(() => {
    let alive = true
    void Promise.all(sources.map(async (candidate) => ({
      candidate,
      assertion: await readAvailabilityAssertion(editionId, candidate.sourceId),
    }))).then((resolved) => {
      if (!alive) return
      const now = Date.now()
      setRankedSources(
        resolved
          .map((entry, index) => ({ ...entry, index }))
          .sort((left, right) =>
            availabilitySortRank(left.assertion, now) - availabilitySortRank(right.assertion, now) ||
            left.index - right.index,
          )
          .map((entry) => entry.candidate),
      )
    })
    return () => { alive = false }
  }, [editionId, sources])

  useEffect(() => {
    const row = rowRef.current
    if (!row || !primarySource || typeof IntersectionObserver === 'undefined') return
    let checked = false
    const observer = new IntersectionObserver((entries) => {
      if (checked || !entries.some((entry) => entry.isIntersecting)) return
      checked = true
      observer.disconnect()
      const controller = new AbortController()
      preflightAbort.current = controller
      void (async () => {
        let verdict: Exclude<AvailabilityVerdict, 'playing' | 'verified-profile'> | null = null
        if (sourceNeedsBrowserSession(primarySource.sourceId)) {
          verdict = 'session-required'
        } else if (typeof navigator !== 'undefined' && navigator.onLine === false) {
          verdict = 'no-network'
        } else {
          const detail = await api.book(primarySource.sourceId, primarySource.url, controller.signal)
          if (controller.signal.aborted) return
          if (!detail) verdict = 'temporary-failure'
          else if (detail.chapters.length === 0) verdict = 'audio-missing'
          else if (!await preflightMediaRange(detail.chapters[0].streamUrl, controller.signal)) verdict = 'audio-missing'
        }
        if (!verdict || controller.signal.aborted) return
        void writeAvailabilityAssertion({
          editionId,
          sourceId: primarySource.sourceId,
          verdict,
          observedAt: Date.now(),
        })
        setState((current) => current === 'idle'
          ? (verdict === 'session-required' ? 'browser-required' : verdict)
          : current)
      })()
    }, { rootMargin: '320px' })
    observer.observe(row)
    return () => {
      observer.disconnect()
      preflightAbort.current?.abort()
      preflightAbort.current = null
    }
  }, [editionId, primarySource])

  if (!source || !primarySource) return null

  const cancel = (): void => {
    generation.current += 1
    preflightAbort.current?.abort()
    activeAbort.current?.abort()
    activeAbort.current = null
    setState('idle')
  }
  const play = (): void => {
    const request = ++generation.current
    preflightAbort.current?.abort()
    activeAbort.current?.abort()
    const actionAbort = new AbortController()
    activeAbort.current = actionAbort
    setState('checking')
    setSessionSource(null)
    const details = new Map<string, BookDetail>()
    const candidates = rankedSources.map((candidate) => ({ ...candidate, editionId }))
    void raceEditionSources(editionId, candidates, async (candidate, signal): Promise<Exclude<AvailabilityVerdict, 'verified-profile'>> => {
      if (actionAbort.signal.aborted || signal.aborted) return 'timeout'
      if (sourceNeedsBrowserSession(candidate.sourceId)) return 'session-required'
      if (typeof navigator !== 'undefined' && navigator.onLine === false) return 'no-network'
      const detail = await api.book(candidate.sourceId, candidate.url, signal)
      if (!detail) return typeof navigator !== 'undefined' && navigator.onLine === false
        ? 'no-network'
        : 'temporary-failure'
      if (detail.chapters.length === 0) return 'audio-missing'
      details.set(candidate.url, detail)
      return await probeStreamPlaying(detail.chapters[0].streamUrl, signal) ? 'playing' : 'audio-missing'
    }, undefined, actionAbort.signal).then(async (result) => {
      if (request !== generation.current || actionAbort.signal.aborted) return
      if (result.verdict !== 'playing') {
        if (result.candidate) {
          void writeAvailabilityAssertion({
            editionId,
            sourceId: result.candidate.sourceId,
            verdict: result.verdict,
            observedAt: Date.now(),
          })
        }
        if (result.verdict === 'session-required') setSessionSource(result.candidate?.sourceId ?? null)
        setState(
          result.verdict === 'timeout'
            ? 'temporary-failure'
            : result.verdict === 'session-required'
              ? 'browser-required'
              : result.verdict,
        )
        return
      }
      if (!result.candidate) {
        setState('temporary-failure')
        return
      }
      const detail = details.get(result.candidate.url)
      if (!detail) {
        setState('temporary-failure')
        return
      }
      const playing = await onPlay(detail, 0)
      if (request !== generation.current || actionAbort.signal.aborted) return
      const finalFailure: Exclude<CardActionState, 'idle' | 'checking' | 'browser-required'> = typeof navigator === 'undefined' || navigator.onLine !== false
        ? 'temporary-failure'
        : 'no-network'
      const finalVerdict: Exclude<AvailabilityVerdict, 'verified-profile'> = playing ? 'playing' : finalFailure
      void writeAvailabilityAssertion({
        editionId,
        sourceId: result.candidate.sourceId,
        verdict: finalVerdict,
        observedAt: Date.now(),
      })
      setState(playing ? 'idle' : finalFailure)
    }).finally(() => {
      if (request === generation.current) activeAbort.current = null
    })
  }

  return (
    <BookRow
      innerRef={(node) => { rowRef.current = node }}
      coverUrl={card.coverImageUrl}
      title={card.title}
      subtitle={card.author + (card.narrator ? ` · ${card.narrator}` : '')}
      badges={<MetadataChip kind="language" code={card.language} />}
      onOpen={() => onOpenBook(primarySource.url, source)}
      openAriaLabel={t('openBookAria', { title: card.title })}
      actions={
        <>
          {onSave !== undefined && (
            <button
              onClick={() => { onSave(saved); setSaved(true) }}
              aria-label={saved ? t('saveDone') : t('saveAria', { title: card.title })}
              aria-pressed={saved}
              style={{ background: 'var(--surface)', color: 'var(--fg)', border: '1px solid var(--line)', borderRadius: 999, padding: '8px 12px' }}
            >
              {saved ? '✓' : '🔖'}
            </button>
          )}
          <button
            onClick={state === 'checking' ? cancel : play}
            aria-label={state === 'checking' ? t('cancelCheckAria', { title: card.title }) : t('listenAria', { title: card.title })}
            aria-live="polite"
            style={{ background: 'var(--accent)', color: 'var(--accent-contrast)', border: 'none', borderRadius: 999, padding: '8px 12px' }}
          >
            {state === 'checking' ? t('cancel') : '▶'}
          </button>
        </>
      }
      trailing={
        <>
          {state === 'checking' && <EmptyStateRow message={t('checking')} />}
          {state === 'no-network' && <EmptyStateRow message={t('noNetwork')} />}
          {state === 'temporary-failure' && <EmptyStateRow message={t('temporaryFailure')} />}
          {state === 'audio-missing' && <EmptyStateRow message={t('audioMissing')} />}
          {state === 'browser-required' && (
            <EmptyStateRow message={t('sessionRequired')}>
              {' '}
              <a href={sourceHome(sessionSource ?? source)} target="_blank" rel="noreferrer">{t('openSourceLabel', { label: SOURCES.find((item) => item.id === (sessionSource ?? source))?.label ?? '' })}</a>
            </EmptyStateRow>
          )}
        </>
      }
    />
  )
}
