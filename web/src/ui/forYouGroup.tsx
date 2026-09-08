/**
 * #592 W6.1 — the «Для вас» group on Огляд (Android's HomeFeedContent
 * R-W4): the «Рекомендовано для вас» shelf with Android's reason chips
 * under every personal pick, the honest «У медіатеці» marker for saved
 * books, and the «Не цікаво» ✕ that feeds the SAME local preference the
 * Listen shelves read (one dictionary, one undo surface in Рекомендації).
 *
 * The participation split, honestly: participation ON → the server
 * Recommendation Profile is read through the seam; absent (no server
 * layer anywhere yet, ADR-0030) → the shelf falls back to the local
 * adaptation — ADR-0031's «absent graph never blocks Огляд». OFF → local
 * adaptation, period. Empty states are Android's wording verbatim.
 */
import { useEffect, useMemo, useState } from 'react'
import type { UnifiedWork } from '../worker/types'
import { rankEditionsForPlayback } from '../worker/workFeed'
import type { DomainStore } from '../local/domain'
import type { EditionLinkStore } from '../local/editionLinks'
import type { ListenerDatabase } from '../local/listeningState'
import { RecommendationPrefsStore } from '../local/recommendationPrefs'
import type { RecommendationProfileSource, ProfilePick } from '../recommend/profile'
import { buildLibraryViews, type LibraryBookView } from './libraryModel'
import { useTranslate } from '../i18n/locale'
import type { StringKey } from '../i18n/strings'
import { EmptyStateRow, PosterCard, SectionHeader } from './components'
import { formatRemainingTime } from './listenComposer'
import { useUiLocale } from '../i18n/locale'
import {
  rankPersonalShelf,
  signalsOf,
  type PersonalCandidate,
  type PersonalPrefs,
  type PersonalPick,
} from '../recommend/personalization'

/** The candidate pool: the merged catalog's openable Works (never invented). */
export function candidatesOf(works: UnifiedWork[], libraryMergeKeys: Set<string>): PersonalCandidate[] {
  return works.map((work) => ({
    mergeKey: work.mergeKey,
    title: work.title,
    author: work.author,
    genres: work.genres ?? [],
    coverImageUrl: work.coverImageUrl,
    durationSeconds: rankDuration(work),
    inLibrary: libraryMergeKeys.has(work.mergeKey),
  }))
}

/** The playback-ranked Edition's real duration, or nothing (ADR-0014). */
function rankDuration(work: UnifiedWork): number | undefined {
  const seconds = rankEditionsForPlayback(work.editions)[0]?.durationSeconds
  return seconds !== undefined && seconds > 0 ? seconds : undefined
}

/** Android's reason chip text — the dictionary, verbatim («Схоже на X»). */
export function reasonTextOf(pick: PersonalPick, t: (key: StringKey, params?: Readonly<Record<string, string | number>>) => string): string | null {
  if (pick.reason === null) return null
  return pick.reason.kind === 'similar'
    ? t('recommendReasonSimilar', { title: pick.reason.title })
    : pick.reason.kind === 'genre'
      ? t('recommendReasonGenre', { genre: pick.reason.genre })
      : t('recommendReasonCollective', { title: pick.reason.title })
}

/** The group's props: data seams + the participation split + navigation. */
export function ForYouGroup({ works, domainStore, linkStore, listening, recommendationPrefs, participation, profileSource, onOpenWork, onDismiss }: {
  /** The loaded merged catalog (null = the feed is still loading). */
  works: UnifiedWork[] | null
  domainStore: DomainStore
  linkStore: EditionLinkStore
  listening: Pick<ListenerDatabase, 'allSnapshots'>
  /** #586 W2.2 — the SAME local preference store Listen shelves write. */
  recommendationPrefs: RecommendationPrefsStore
  /** #592 W6.1 — the participation consent (Settings → Рекомендації). */
  participation: boolean
  profileSource: RecommendationProfileSource
  onOpenWork: (work: UnifiedWork) => void
  /** «Не цікаво» — HIDE_WORK, the same row action as the Listen shelves. */
  onDismiss: (mergeKey: string) => void
}) {
  const t = useTranslate()
  const locale = useUiLocale()
  const [views, setViews] = useState<LibraryBookView[] | null>(null)
  const [prefs, setPrefs] = useState<PersonalPrefs>({ hiddenWorks: new Set(), hiddenAuthors: new Set(), reduceSimilar: new Set() })
  const [profilePicks, setProfilePicks] = useState<ProfilePick[] | null>(null)

  useEffect(() => {
    let alive = true
    void Promise.all([domainStore.libraryEntries(), linkStore.all(), listening.allSnapshots(), recommendationPrefs.all()]).then(
      ([entries, links, snapshots, rows]) => {
        if (!alive) return
        setViews(buildLibraryViews(entries, links, snapshots))
        setPrefs({
          hiddenWorks: new Set(rows.filter((row) => row.kind === 'HIDE_WORK').map((row) => row.targetKey)),
          hiddenAuthors: new Set(rows.filter((row) => row.kind === 'HIDE_AUTHOR').map((row) => row.targetKey)),
          reduceSimilar: new Set(rows.filter((row) => row.kind === 'REDUCE_SIMILAR').map((row) => row.targetKey)),
        })
      },
    )
    return () => { alive = false }
  }, [domainStore, linkStore, listening, recommendationPrefs])

  // The participation split: ON reads the server profile; absent (no
  // server layer yet) → null → the local adaptation serves the shelf.
  useEffect(() => {
    if (!participation) {
      setProfilePicks(null)
      return
    }
    let alive = true
    void profileSource.load().then((picks) => {
      if (alive) setProfilePicks(picks)
    })
    return () => { alive = false }
  }, [participation, profileSource])

  const viewsByKey = useMemo(() => {
    const map = new Map<string, LibraryBookView>()
    for (const view of views ?? []) map.set(view.mergeKey, view)
    return map
  }, [views])

  const libraryMergeKeys = useMemo(() => new Set((views ?? []).map((view) => view.mergeKey)), [views])

  /** «Не цікаво» — persists through the caller AND re-ranks immediately: the
   * hidden mergeKey joins the local prefs so the shelf drops the pick now. */
  const handleDismiss = (mergeKey: string): void => {
    onDismiss(mergeKey)
    setPrefs((current) => ({ ...current, hiddenWorks: new Set([...current.hiddenWorks, mergeKey]) }))
  }

  const picks: PersonalPick[] = useMemo(() => {
    if (works === null || views === null) return []
    if (profilePicks !== null) {
      // The server profile's picks, mapped to the openable candidates. A
      // profile pick with no openable candidate is honestly skipped.
      const mapped: (PersonalPick | null)[] = profilePicks.map((pick) => {
        const candidate = candidatesOf(works, libraryMergeKeys).find((item) => item.mergeKey === pick.mergeKey)
        if (candidate === undefined) return null
        return { candidate, score: 1, reason: pick.reason ?? null, exploration: false }
      })
      return mapped.filter((pick): pick is PersonalPick => pick !== null)
    }
    return rankPersonalShelf(candidatesOf(works, libraryMergeKeys), signalsOf(views), prefs, viewsByKey, Date.now())
  }, [works, views, prefs, viewsByKey, libraryMergeKeys, profilePicks])

  const loading = views === null
  const empty = !loading && picks.length === 0

  return (
    <section aria-label={t('feedForYou')}>
      <SectionHeader level="group" title={t('feedForYou')} />
      {loading ? (
        <EmptyStateRow message={t('homePersonalPicksLoading')} />
      ) : empty ? (
        <EmptyStateRow message={t('homeNoPersonalPicks')} />
      ) : (
        <>
          <SectionHeader level="section" title={t('feedRecommendedForYou')} />
          <ul className="shelf-rail">
            {picks.map((pick) => (
              <li key={pick.candidate.mergeKey} className="for-you-item">
                <PosterCard
                  coverUrl={pick.candidate.coverImageUrl}
                  title={pick.candidate.title}
                  author={pick.candidate.author}
                  duration={pick.candidate.durationSeconds !== undefined ? formatRemainingTime(pick.candidate.durationSeconds, locale) : undefined}
                  caption={reasonTextOf(pick, t) ?? undefined}
                  badges={pick.candidate.inLibrary ? <span className="meta-chip">{t('inLibrary')}</span> : undefined}
                  onClick={() => onOpenWorkByKey(works, pick.candidate.mergeKey, onOpenWork)}
                  openAriaLabel={t('openBookPosterAria', { title: pick.candidate.title })}
                />
                <button
                  type="button"
                  className="bookrow-dismiss for-you-dismiss"
                  onClick={() => handleDismiss(pick.candidate.mergeKey)}
                  aria-label={t('notInterestedAria', { title: pick.candidate.title })}
                >
                  ✕
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}

/** Opens the Work the same way any Огляд shelf does: real source, never a guess. */
function onOpenWorkByKey(works: UnifiedWork[] | null, mergeKey: string, onOpen: (work: UnifiedWork) => void): void {
  const work = works?.find((item) => item.mergeKey === mergeKey)
  if (work !== undefined) onOpen(work)
}

