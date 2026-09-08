/**
 * #584 W1.2 — the pure Медіатека policy, ported from Android's
 * `LibraryModel.kt`: the Нові/Слухаю/Завершені trilogy over Listening
 * State, the honest progress hairline (a real cumulative fraction or
 * nothing — ADR-0014), and the sort orders. One decision, tested once —
 * the two-runtime rule of ADR-0034.
 *
 * Android's DOWNLOADED/LOCAL/ONLINE filters and FAVORITE sheet filter have
 * no honest web source yet (no downloads, no local sources, no favorite
 * flag in work_relationships) — they join when the data exists, never as
 * dead chips. DURATION sort joins with real totals the same way.
 */
import type { LibraryEntryEntity } from '../local/domain'
import type { EditionLink } from '../local/editionLinks'
import type { LocalListeningStateSnapshot } from '../player/localState'

/** Android's visible status trilogy + ALL (spec-28 #193). */
export type LibraryFilter = 'all' | 'new' | 'listening' | 'completed'

/** The honest subset of Android's LibrarySort for today's web data. */
export type LibrarySort = 'recently-listened' | 'recently-added' | 'title' | 'author'

export interface LibraryBookView {
  mergeKey: string
  title: string
  author: string
  createdAt: number
  /** 'new' | 'listening' | 'completed' — from a REAL Listening State row. */
  status: Exclude<LibraryFilter, 'all'>
  /** Cumulative 0..1 when both position and chapter durations are known; else absent (ADR-0014). */
  progress?: number
  /** The paused-at marker of the freshest snapshot; null = never paused here. */
  lastListenedAt: number | null
  /** Narration of the edition the progress belongs to (Edition-owned, CONTEXT.md). */
  narrator: string
  /** Real wall-clock totals when chapter durations are known; else absent (ADR-0014). */
  totalSeconds?: number
  cumulativeSeconds?: number
}

/**
 * The cumulative wall-clock position inside the book: full chapters before
 * the current one plus the in-chapter offset — Android's
 * `cumulativePositionSeconds` rule, so the fraction is honest.
 */
export function cumulativeFraction(
  snapshot: Pick<LocalListeningStateSnapshot, 'chapterIndex' | 'positionSeconds' | 'isCompleted'>,
  chapterDurations: number[] | null,
): number | undefined {
  if (snapshot.isCompleted) return 1
  if (chapterDurations === null || chapterDurations.length === 0) return undefined
  const total = chapterDurations.reduce((sum, seconds) => sum + (Number.isFinite(seconds) ? seconds : 0), 0)
  if (total <= 0) return undefined
  const before = chapterDurations
    .slice(0, Math.min(snapshot.chapterIndex, chapterDurations.length))
    .reduce((sum, seconds) => sum + (Number.isFinite(seconds) ? seconds : 0), 0)
  return Math.min(Math.max((before + snapshot.positionSeconds) / total, 0), 1)
}

/**
 * Joins Library Entries with edition links and Listening State into the
 * row model the screen renders. A Work with no local link is honestly
 * «never started here»: no status lie, no hairline.
 */
export function buildLibraryViews(
  entries: LibraryEntryEntity[],
  links: EditionLink[],
  snapshots: LocalListeningStateSnapshot[],
): LibraryBookView[] {
  const byEdition = new Map(snapshots.map((snapshot) => [snapshot.editionId, snapshot]))
  return entries.map((entry) => {
    const candidates = links.filter((link) => link.mergeKey === entry.mergeKey)
    // The edition this Work was last listened to here; otherwise the freshest link.
    const withSnapshots = candidates
      .filter((link) => byEdition.has(link.editionId))
      .sort((a, b) => (byEdition.get(b.editionId)!.lastPausedAtEpochMs ?? 0) - (byEdition.get(a.editionId)!.lastPausedAtEpochMs ?? 0))
    const link = withSnapshots[0] ?? candidates.sort((a, b) => b.updatedAt - a.updatedAt)[0]
    const snapshot = link === undefined ? null : byEdition.get(link.editionId) ?? null
    // The real wall-clock totals (ADR-0014): only when every chapter declared
    // a duration — the same honesty bar as the progress hairline.
    const chapterDurations = link?.chapterDurations ?? null
    const durationsKnown = chapterDurations !== null && chapterDurations.length > 0
    const totalSeconds = durationsKnown
      ? chapterDurations!.reduce((sum, seconds) => sum + seconds, 0)
      : link?.durationSeconds ?? undefined
    const cumulativeSeconds =
      snapshot === null || !durationsKnown
        ? undefined
        : chapterDurations!.slice(0, Math.min(snapshot.chapterIndex, chapterDurations!.length)).reduce((sum, seconds) => sum + seconds, 0) +
          snapshot.positionSeconds
    return {
      mergeKey: entry.mergeKey,
      title: entry.title,
      author: entry.author,
      createdAt: entry.createdAt,
      status: snapshot === null ? 'new' : snapshot.isCompleted ? 'completed' : 'listening',
      progress:
        snapshot === null || link === undefined
          ? undefined
          : cumulativeFraction(snapshot, link.chapterDurations),
      lastListenedAt: snapshot?.lastPausedAtEpochMs ?? null,
      narrator: link?.narrator ?? '',
      totalSeconds,
      cumulativeSeconds,
    }
  })
}

/** Android's trilogy: NEW = no Listening State row at all (spec-28 #193). */
export function matchesFilter(view: LibraryBookView, filter: LibraryFilter): boolean {
  return filter === 'all' || view.status === filter
}

export function filterLibrary(views: LibraryBookView[], filter: LibraryFilter): LibraryBookView[] {
  return views.filter((view) => matchesFilter(view, filter))
}

export function sortLibrary(views: LibraryBookView[], sort: LibrarySort): LibraryBookView[] {
  const sorted = [...views]
  switch (sort) {
    case 'recently-listened':
      // Never-paused rows go last, honestly unordered among themselves.
      sorted.sort((a, b) =>
        (b.lastListenedAt ?? -1) - (a.lastListenedAt ?? -1) || b.createdAt - a.createdAt)
      break
    case 'recently-added':
      sorted.sort((a, b) => b.createdAt - a.createdAt)
      break
    case 'title':
      sorted.sort((a, b) => a.title.localeCompare(b.title, 'uk'))
      break
    case 'author':
      sorted.sort((a, b) => a.author.localeCompare(b.author, 'uk') || a.title.localeCompare(b.title, 'uk'))
      break
  }
  return sorted
}
