import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import { readWarmEntry, warmKey, writeWarm } from '../api/warmCache'
import type { BookDetail, CatalogCard, SourceId, UnifiedSource, UnifiedWork, UnifiedWorkPage } from '../worker/types'
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

const SOURCES: Array<{ id: 'all' | SourceId; label: string }> = [
  { id: 'all', label: 'Усі джерела' },
  ...SOURCE_ORDER.map((id) => ({ id, label: SOURCE_METADATA[id].label })),
]

/** spec-43/T3+T4 — огляд із перемикачем джерел і пошуком. */
export function Catalog({ onOpenBook, onPlay }: {
  onOpenBook: (url: string, source: SourceId) => void
  onPlay: (detail: BookDetail, chapterIndex: number) => Promise<boolean>
}) {
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
  const loadMoreMarker = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    if (query.trim().length >= 2) return
    let alive = true
    setFailed(false)
    setAppendError(false)
    setShowingCachedCatalog(false)
    setCachedAt(null)
    setWorks(null)
    setNextPageUrl(null)
    // The aggregate Work contract serves both the default feed and a
    // source-filtered feed. A filter changes candidates, not the book model.
    {
      api.workFeed(undefined, source === 'all' ? undefined : source).then(async (page) => {
        if (!alive) return
        if (page === null) {
          const cached = await readWarmEntry<UnifiedWorkPage | UnifiedWork[]>(warmKey('catalog', source))
          if (!alive) return
          if (cached === null) setFailed(true)
          else {
            const cachedPage = Array.isArray(cached.value)
              ? { works: cached.value }
              : cached.value
            setWorks(cachedPage.works)
            setNextPageUrl(cachedPage.nextCursor ?? null)
            setShowingCachedCatalog(true)
            setCachedAt(cached.savedAt)
          }
          return
        }
        setWorks(page.works)
        setNextPageUrl(page.nextCursor ?? null)
        setShowingCachedCatalog(false)
        setCachedAt(null)
        void writeWarm(warmKey('catalog', source), page)
      })
      return () => { alive = false }
    }
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
      <input
        placeholder="Пошук…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        style={{ width: '100%', padding: '8px', borderRadius: '8px', border: '1px solid var(--line)', background: 'var(--surface)', color: 'var(--fg)' }}
      />
      <div style={{ display: 'flex', gap: 6, margin: '8px 0', flexWrap: 'wrap' }}>
        {SOURCES.map((s) => (
          <button
            key={s.id}
            onClick={() => {
              setSource(s.id)
              setQuery('')
            }}
            style={{
              padding: '4px 10px',
              borderRadius: 999,
              border: '1px solid var(--line)',
              background: source === s.id ? 'var(--accent)' : 'var(--surface)',
              color: source === s.id ? 'var(--accent-contrast)' : 'var(--fg)',
            }}
          >
            {s.label}
          </button>
        ))}
      </div>

      {query.trim().length >= 2 ? (
        searching ? (
          <div className="placeholder">Шукаємо…</div>
        ) : searchWorks === null || searchWorks.length === 0 ? (
          <div className="placeholder">Нічого не знайшли.</div>
        ) : (
          <section>
            <h2>Результати пошуку</h2>
            <ul className="card-list">
              {searchWorks.map((work) => <UnifiedWorkRow key={work.id} work={work} onOpenBook={onOpenBook} onPlay={onPlay} />)}
            </ul>
          </section>
        )
      ) : failed ? (
        <div className="placeholder">Джерело не відповіло спробуйте пізніше.</div>
      ) : works === null ? (
        <div className="placeholder">Завантажуємо об’єднаний каталог…</div>
      ) : (
        <section>
          <h2>{source === 'all' ? 'Усі джерела' : SOURCES.find((item) => item.id === source)?.label}</h2>
          {showingCachedCatalog && <p className="notice" role="status" aria-live="polite">Показуємо останній збережений каталог{cachedAt ? ` від ${new Date(cachedAt).toLocaleString('uk-UA')}` : ''}. Оновлення тимчасово недоступне.</p>}
          <ul className="card-list">
            {works!.map((work) => <UnifiedWorkRow key={work.id} work={work} onOpenBook={onOpenBook} onPlay={onPlay} />)}
          </ul>
        </section>
      )}
      {query.trim().length < 2 && nextPageUrl && (
        <div ref={loadMoreMarker} style={{ padding: '16px 0', textAlign: 'center' }}>
          {appendError && <p className="notice" role="status">Не вдалося оновити каталог. Уже завантажені книги залишилися тут.</p>}
          <button onClick={loadMore} disabled={loadingMore}>
            {loadingMore ? 'Завантажуємо…' : appendError ? 'Спробувати ще раз' : 'Показати більше'}
          </button>
        </div>
      )}
    </div>
  )
}

export function appendWorks(current: UnifiedWork[], incoming: UnifiedWork[]): UnifiedWork[] {
  const known = new Set(current.map((work) => work.id))
  return [...current, ...incoming.filter((work) => !known.has(work.id))]
}

/** One Work card with explicit Edition selection; changing it never mutates progress. */
function UnifiedWorkRow({ work, onOpenBook, onPlay }: {
  work: UnifiedWork
  onOpenBook: (url: string, source: SourceId) => void
  onPlay: (detail: BookDetail, chapterIndex: number) => Promise<boolean>
}) {
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
  }
  return (
    <>
      <CatalogCardRow
        card={card}
        editionId={edition.id}
        sources={edition.sources}
        onOpenBook={onOpenBook}
        onPlay={onPlay}
      />
      {editions.length > 1 && (
        <li style={{ padding: '0 8px 8px' }}>
          <label>
            Начитка{' '}
            <select value={editionIndex} onChange={(event) => setEditionIndex(Number(event.target.value))} aria-label={`Обрати начитку: ${work.title}`}>
              {editions.map((candidate, index) => <option key={candidate.id} value={index}>{candidate.narrator || 'Невідомий диктор'}</option>)}
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

/** A card's body opens details; its neighbouring action alone starts playback. */
export function CatalogCardRow({ card, editionId, sources, onOpenBook, onPlay }: {
  card: CatalogCard
  editionId: string
  sources: UnifiedSource[]
  onOpenBook: (url: string, source: SourceId) => void
  onPlay: (detail: BookDetail, chapterIndex: number) => Promise<boolean>
}) {
  const [state, setState] = useState<CardActionState>('idle')
  const [rankedSources, setRankedSources] = useState(sources)
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
    <li ref={rowRef}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <button style={{ flex: 1, textAlign: 'left' }} onClick={() => onOpenBook(primarySource.url, source)} aria-label={`Відкрити книгу: ${card.title}`}>
          {card.coverImageUrl && <img src={card.coverImageUrl} alt="" loading="lazy" />}
          <span className="card-title">{card.title}</span>
          <span className="card-author">{card.author}{card.narrator ? ` · ${card.narrator}` : ''}</span>
        </button>
        <button
          onClick={state === 'checking' ? cancel : play}
          aria-label={state === 'checking' ? `Скасувати перевірку: ${card.title}` : `Слухати: ${card.title}`}
          aria-live="polite"
          style={{ background: 'var(--accent)', color: 'var(--accent-contrast)', border: 'none', borderRadius: 999, padding: '8px 12px' }}
        >
          {state === 'checking' ? 'Скасувати' : '▶'}
        </button>
      </div>
      {state === 'checking' && <p className="notice" aria-live="polite">Перевіряємо доступність…</p>}
      {state === 'no-network' && <p className="notice" role="status">Немає мережі. Перевірте з’єднання та повторіть.</p>}
      {state === 'temporary-failure' && <p className="notice" role="status">Джерело тимчасово не відповідає. Спробуйте пізніше.</p>}
      {state === 'audio-missing' && <p className="notice" role="status">Джерело не віддає аудіо для цієї книги.</p>}
      {state === 'browser-required' && (
        <p className="notice" role="status">
          Це джерело потребує сесії на своєму сайті.{' '}
          <a href={sourceHome(source)} target="_blank" rel="noreferrer">Відкрити {SOURCES.find((item) => item.id === source)?.label}</a>
        </p>
      )}
    </li>
  )
}
