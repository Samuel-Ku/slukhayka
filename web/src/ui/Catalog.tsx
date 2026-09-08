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
import { mergeWorkFeed, rankEditionsForPlayback } from '../worker/workFeed'
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
import { FiltersSheet, StickyFiltersToolbar } from './catalogFilters'
import { createFacetFilter, workMatchesFacets, type DurationBucket, type FacetWork } from './facetModel'
import { searchMemory } from './searchMemory'
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
  // W3.4 — the query survives the book round-trip (Android keeps the screen
  // on the backstack; the web remembers the two things the AC names).
  const [query, setQuery] = useState(() => searchMemory.query)
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
  // W3.3 — the homepage genre nav (the filter sheet's honest options; null =
  // the nav failed to load) and the live selections. Selections commit
  // immediately as an OR-set, no draft (Android's sheet contract).
  const [genres, setGenres] = useState<CatalogCard[] | null>(null)
  const [genreFilter, setGenreFilter] = useState<string[]>([])
  const [durationFilter, setDurationFilter] = useState<DurationBucket[]>([])
  const [showFilters, setShowFilters] = useState(false)
  // The genre-union feed: works from the selected genre pages (fourread),
  // MergeKey-deduped with their genre claims; null = not active/loading.
  const [genreWorks, setGenreWorks] = useState<UnifiedWork[] | null>(null)
  const filterTriggerRef = useRef<HTMLButtonElement | null>(null)
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
  // W3.4 — the round-trip ledger: every query change is remembered, and an
  // unmount (book open, tab switch) records the scroll place. Content settles
  // → the place is restored exactly once per mount.
  useEffect(() => {
    searchMemory.query = query
  }, [query])
  useEffect(() => () => { searchMemory.scrollY = window.scrollY ?? 0 }, [])
  const scrollRestored = useRef(false)
  useEffect(() => {
    if (scrollRestored.current) return
    if ((works !== null || searchWorks !== null) && query === searchMemory.query) {
      scrollRestored.current = true
      if (searchMemory.scrollY > 0) {
        window.scrollTo?.(0, searchMemory.scrollY)
        searchMemory.scrollY = 0
      }
    }
  }, [works, searchWorks, query])

  // W3.3 — the sheet's focus-return contract: closing the sheet returns
  // focus to the toolbar trigger that opened it (DeleteBookSheet's own rule:
  // the parent owns the return). Only when it was actually open.
  const filtersWereOpen = useRef(false)
  useEffect(() => {
    if (filtersWereOpen.current && !showFilters) filterTriggerRef.current?.focus()
    filtersWereOpen.current = showFilters
  }, [showFilters])
  const toggleGenre = (url: string | null): void => {
    setGenreFilter((current) => {
      if (url === null) return []
      return current.includes(url) ? current.filter((item) => item !== url) : [...current, url]
    })
  }
  const toggleDuration = (bucket: DurationBucket): void => {
    setDurationFilter((current) => current.includes(bucket) ? current.filter((item) => item !== bucket) : [...current, bucket])
  }
  // spec-45 T13 — the persisted content-language preference; empty = all.
  const [contentLanguages, setContentLanguages] = useState<string[]>(() => loadContentLanguagePrefs())
  const applyLanguages = (next: string[]): void => setContentLanguages(saveContentLanguagePrefs(next))
  const loadMoreMarker = useRef<HTMLDivElement | null>(null)
  // #584 W1.3 — the listener's deliberate hides: a tombstoned Work never
  // re-enters discovery, whatever the catalog refresh brings back.
  const [tombstoned, setTombstoned] = useState<ReadonlySet<string>>(new Set())

  // W3.3 — the facet matcher over the loaded works: durations AND languages
  // compose across dimensions; a real duration only ever comes from the
  // Edition's own data. The genre dimension is absent here by design — the
  // regular merged feed carries no genre claims (the genre feed below does).
  const facetOf = (work: UnifiedWork): FacetWork => ({
    durations: work.editions.map((edition) => edition.durationSeconds),
    languages: work.editions.map((edition) => edition.language),
    genres: work.genres,
  })
  const byDurations = (list: UnifiedWork[]): UnifiedWork[] => {
    if (durationFilter.length === 0) return list
    const filter = createFacetFilter({ durationBucketIds: durationFilter })
    return list.filter((work) => workMatchesFacets(facetOf(work), filter))
  }
  const visibleWorks = filterWorksByLanguage(byDurations((works ?? []).filter((work) => !tombstoned.has(work.mergeKey))), contentLanguages)
  const visibleGenreWorks = filterWorksByLanguage(byDurations((genreWorks ?? []).filter((work) => !tombstoned.has(work.mergeKey))), contentLanguages)
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

  // W3.1/W3.3 — the 4read homepage sections through the worker (spec-37
  // live list): «Цикли» feeds the shelf, «Жанри» feeds the filter sheet's
  // options. Homepage sections are catalog-grade (24 h); a failure leaves
  // both honestly absent and never breaks the refresh.
  useEffect(() => {
    if (source !== 'all' || query.trim().length >= 2) {
      setCycles(null)
      setGenres(null)
      return
    }
    let alive = true
    const key = warmKey('catalog', 'fourread', 'homepage')
    const sectionsOf = (value: ParsedCatalog | null): { cycles: CatalogCard[] | null; genres: CatalogCard[] | null } => {
      const cyclesSection = value?.sections.find((s) => s.id === 'series')
      const genresSection = value?.sections.find((s) => s.id === 'genres')
      return {
        cycles: cyclesSection && cyclesSection.cards.length > 0 ? cyclesSection.cards : null,
        genres: genresSection && genresSection.cards.length > 0 ? genresSection.cards : null,
      }
    }
    void (async () => {
      const cached = await readWarmEntry<ParsedCatalog>(key)
      if (!alive) return
      if (cached !== null && !needsNetwork(FEED_HOMEPAGE_SECTIONS, cached.savedAt, Date.now())) {
        const sections = sectionsOf(cached.value)
        setCycles(sections.cycles)
        setGenres(sections.genres)
        return
      }
      const parsed = await api.catalog('fourread')
      if (!alive) return
      if (parsed !== null) {
        void writeWarm(key, parsed)
        const sections = sectionsOf(parsed)
        setCycles(sections.cycles)
        setGenres(sections.genres)
      } else {
        // Offline: the last homepage snapshot still answers (honest stale).
        const sections = sectionsOf(cached !== null ? cached.value : null)
        setCycles(sections.cycles)
        setGenres(sections.genres)
      }
    })()
    return () => { alive = false }
  }, [source, query])

  // W3.3 — the genre-union feed: the selected genre pages through the
  // worker (catalog-grade 24 h snapshots, all pages), MergeKey-deduped — a
  // Work on two genre pages claims both genres. The regular merged feed
  // carries no genre claims (genre pages are the ONLY honest genre source),
  // so a genre selection replaces the feed with this union, exactly like a
  // source selection replaces it with that source's feed.
  useEffect(() => {
    if (query.trim().length >= 2 || genreFilter.length === 0) {
      setGenreWorks(null)
      return
    }
    let alive = true
    void (async () => {
      const fetchPage = async (url: string): Promise<ParsedCatalog | null> => {
        const key = warmKey('catalog', 'fourread', url)
        const cached = await readWarmEntry<ParsedCatalog>(key)
        if (cached !== null && !needsNetwork(FEED_CATALOG, cached.savedAt, Date.now())) return cached.value
        const parsed = await api.catalog('fourread', url)
        if (parsed !== null) {
          void writeWarm(key, parsed)
          return parsed
        }
        return cached !== null ? cached.value : null
      }
      const cards: CatalogCard[] = []
      for (const genreUrl of genreFilter) {
        let url: string | undefined = genreUrl
        // The genre page paginates like any poster grid; keep the honest
        // full list (bounded, so a runaway source can't loop forever).
        for (let page = 0; page < 8 && url !== undefined; page++) {
          const parsed = await fetchPage(url)
          if (!alive) return
          if (parsed === null) break
          for (const section of parsed.sections) {
            for (const card of section.cards) cards.push({ ...card, genres: [genreUrl] })
          }
          url = parsed.nextPageUrl
        }
      }
      if (!alive) return
      setGenreWorks(mergeWorkFeed([{ sourceId: 'fourread', cards }], 0, 200).works)
    })()
    return () => { alive = false }
  }, [query, genreFilter])

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

      {/* W3.3 — the sticky toolbar (spec-42 T1 #302): ONE compact filter
          entry above the endless feed; a selection lights the trigger. The
          sheet commits OR-sets immediately (no draft). */}
      {query.trim().length < 2 && (
        <>
          <StickyFiltersToolbar
            active={genreFilter.length > 0 || durationFilter.length > 0}
            onOpen={() => setShowFilters(true)}
            triggerRef={(node) => { filterTriggerRef.current = node }}
          />
          {showFilters && (
            <FiltersSheet
              genres={genres ?? []}
              selectedGenreUrls={new Set(genreFilter)}
              selectedDurations={new Set(durationFilter)}
              onGenreToggle={toggleGenre}
              onDurationToggle={toggleDuration}
              onReset={() => { setGenreFilter([]); setDurationFilter([]) }}
              onDismiss={() => setShowFilters(false)}
              onDone={() => setShowFilters(false)}
            />
          )}
        </>
      )}

      {query.trim().length >= 2 ? (
        searching ? (
          <EmptyStateRow message={t('searching')} />
        ) : searchWorks === null || visibleSearch.length === 0 ? (
          <EmptyStateRow message={t('nothingFound')} />
        ) : (
          <section>
            <SectionHeader level="group" title={t('allSources')} count={visibleSearch.length} />
            <ul className="card-list">
              {visibleSearch.map((work) => <UnifiedWorkRow key={work.id} work={work} onOpenBook={onOpenBook} onPlay={onPlay} onSaveWork={onSaveWork} />)}
            </ul>
          </section>
        )
      ) : genreFilter.length > 0 ? (
        genreWorks === null ? (
          <EmptyStateRow message={t('loadingCatalog')} />
        ) : visibleGenreWorks.length === 0 ? (
          <EmptyStateRow message={t('nothingFound')} />
        ) : (
          <section>
            <SectionHeader
              level="group"
              title={genreFilter.map((url) => genres?.find((genre) => genre.url === url)?.title ?? url).join(', ')}
              count={visibleGenreWorks.length}
            />
            <ul className="card-list">
              {visibleGenreWorks.map((work) => <UnifiedWorkRow key={work.id} work={work} onOpenBook={onOpenBook} onPlay={onPlay} onSaveWork={onSaveWork} />)}
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
