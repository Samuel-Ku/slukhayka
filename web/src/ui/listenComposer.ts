/**
 * #585 W2.1 — the web port of Android's `ListenComposer` +
 * `ListenShelfDedup` (ADR-0015): a transparent rules engine, NOT an opaque
 * recommendation model. A pure function `(library, prefs, now) → ordered
 * block list` where every block carries its one-line **reason** — the block
 * answers «чому це тут?» by itself. Nothing is fabricated and nothing is
 * network-dependent: a cold-start library renders nothing, and blocks
 * appear as their eligibility becomes true.
 *
 * Platform deltas (honest, per the ledger): NEXT_IN_SERIES has no series
 * data source on web yet, TRAVEL has no downloads, FAVORITE_AUTHORS has no
 * favorite flag in work_relationships — their rules exist and run, they
 * just find nothing today, exactly like Android's cold start.
 */
import type { ListenBlockId, ListenPrefsRow } from '../local/listenPrefs'
import type { LibraryBookView } from './libraryModel'
import type { StringKey } from '../i18n/strings'

/** The default (product) priority — first eligible block renders first. */
export const DEFAULT_BLOCK_ORDER: readonly ListenBlockId[] = [
  'hero',
  'almost-done',
  'return',
  'next-in-series',
  'travel',
  'short',
  'favorite-authors',
  'recently-added',
]

/** «Ви слухали N днів тому» — a book not touched for this long is dormant. */
export const DORMANT_DAYS = 14
/** «~2 год прослуховування» — a short book fits a commute session. */
export const SHORT_BOOK_MAX_HOURS = 3
/** «Додано цього тижня» — recently-added window. */
export const RECENT_ADDED_DAYS = 7

export type ListenReason = { key: StringKey; params?: Record<string, string | number> }

export type ComposerLocale = 'uk' | 'en'

export interface ListenBlock {
  id: ListenBlockId
  titleKey: StringKey
  reason?: ListenReason
  books: LibraryBookView[]
}

/** The user's reorder wins; unknown ids fall back to the default position. */
export function effectiveBlockOrder(order: ListenBlockId[]): ListenBlockId[] {
  if (order.length === 0) return [...DEFAULT_BLOCK_ORDER]
  const known = order.filter((id) => DEFAULT_BLOCK_ORDER.includes(id))
  const rest = DEFAULT_BLOCK_ORDER.filter((id) => !known.includes(id))
  return [...known, ...rest]
}

/** The most recently listened, not-yet-completed book. */
export function heroBlock(library: LibraryBookView[]): ListenBlock | null {
  const book = library
    .filter((view) => view.status === 'listening')
    .sort((a, b) => (b.lastListenedAt ?? 0) - (a.lastListenedAt ?? 0))[0]
  return book === undefined ? null : { id: 'hero', titleKey: 'listenHeroTitle', books: [book] }
}

/** «Майже дочитали»: ≥ 80 % through, not completed. */
export function almostDoneBlock(library: LibraryBookView[], locale: ComposerLocale = 'uk'): ListenBlock | null {
  const books = library
    .filter((view) => view.status === 'listening' && (view.progress ?? 0) >= 0.8)
    .sort((a, b) => (b.progress ?? 0) - (a.progress ?? 0))
  if (books.length === 0) return null
  const first = books[0]!
  const remaining = first.totalSeconds !== undefined && first.cumulativeSeconds !== undefined
    ? Math.max(first.totalSeconds - first.cumulativeSeconds, 0)
    : null
  const reason: ListenReason | undefined = remaining === null
    ? undefined
    : { key: 'listenAlmostDoneReason', params: { time: formatRemainingTime(remaining, locale) } }
  return { id: 'almost-done', titleKey: 'listenAlmostDoneTitle', reason, books }
}

/** «Поверніться»: touched progress, but dormant for 14+ days. */
export function returnBlock(library: LibraryBookView[], now: number, locale: ComposerLocale = 'uk'): ListenBlock | null {
  const books = library
    .filter((view) => view.status === 'listening' && view.lastListenedAt !== null && now - view.lastListenedAt > DORMANT_DAYS * 24 * 3600 * 1000)
    .sort((a, b) => (b.lastListenedAt ?? 0) - (a.lastListenedAt ?? 0))
  if (books.length === 0) return null
  const days = Math.floor((now - books[0]!.lastListenedAt!) / (24 * 3600 * 1000))
  return {
    id: 'return',
    titleKey: 'listenReturnTitle',
    reason: { key: 'listenReturnReason', params: { days: `${days} ${pluralDays(days, locale)}` } },
    books,
  }
}

/** «Готово до поїздки»: downloaded books play offline — web has none yet. */
export function travelBlock(library: LibraryBookView[], isDownloaded: (mergeKey: string) => boolean): ListenBlock | null {
  const books = library.filter((view) => isDownloaded(view.mergeKey))
  if (books.length === 0) return null
  return { id: 'travel', titleKey: 'listenTravelTitle', reason: { key: 'listenTravelReason' }, books }
}

/** «Щось коротке»: total duration ≤ 3 h. */
export function shortBooksBlock(library: LibraryBookView[]): ListenBlock | null {
  const maxSeconds = SHORT_BOOK_MAX_HOURS * 3600
  const books = library
    .filter((view) => (view.totalSeconds ?? 0) > 0 && view.totalSeconds! <= maxSeconds)
    .sort((a, b) => a.totalSeconds! - b.totalSeconds!)
  if (books.length === 0) return null
  const hours = books[0]!.totalSeconds! / 3600
  return {
    id: 'short',
    titleKey: 'listenShortTitle',
    reason: hours < 1
      ? { key: 'listenShortReasonUnderHour' }
      : { key: 'listenShortReason', params: { hours: Math.round(hours) } },
    books,
  }
}

/** «Улюблені автори»: the listener's favorite works, most-listened first. */
export function favoriteAuthorsBlock(library: LibraryBookView[], isFavorite: (mergeKey: string) => boolean): ListenBlock | null {
  const books = library.filter((view) => isFavorite(view.mergeKey))
  if (books.length === 0) return null
  return {
    id: 'favorite-authors',
    titleKey: 'listenFavoriteAuthorsTitle',
    reason: { key: 'listenFavoriteAuthorsReason' },
    books: [...books].sort((a, b) => (b.lastListenedAt ?? 0) - (a.lastListenedAt ?? 0)),
  }
}

/** «Нещодавно додані»: created within the last week. */
export function recentlyAddedBlock(library: LibraryBookView[], now: number): ListenBlock | null {
  const books = library
    .filter((view) => view.createdAt > 0 && now - view.createdAt <= RECENT_ADDED_DAYS * 24 * 3600 * 1000)
    .sort((a, b) => b.createdAt - a.createdAt)
  if (books.length === 0) return null
  return { id: 'recently-added', titleKey: 'listenRecentlyAddedTitle', reason: { key: 'listenRecentlyAddedReason' }, books }
}

/**
 * Composes the eligible, ordered, prefs-filtered block list. Web deltas:
 * NEXT_IN_SERIES needs a series data source (joins with W3.2) and is
 * composed only when the caller supplies one; TRAVEL / FAVORITE_AUTHORS
 * take their eligibility predicates until downloads/favorites exist.
 */
export function composeListenBlocks(
  library: LibraryBookView[],
  prefs: Pick<ListenPrefsRow, 'order' | 'hidden' | 'dismissed'>,
  deps: {
    now: number
    locale?: ComposerLocale
    isDownloaded?: (mergeKey: string) => boolean
    isFavorite?: (mergeKey: string) => boolean
    nextInSeries?: LibraryBookView | null
    nextInSeriesReason?: ListenReason
  },
): ListenBlock[] {
  const locale = deps.locale ?? 'uk'
  const dismissed = new Set(prefs.dismissed)
  const visible = library.filter((view) => !dismissed.has(view.mergeKey))

  const byId = new Map<ListenBlockId, ListenBlock>()
  const hero = heroBlock(visible)
  if (hero !== null) byId.set('hero', hero)
  const almostDone = almostDoneBlock(visible, locale)
  if (almostDone !== null) byId.set('almost-done', almostDone)
  const ret = returnBlock(visible, deps.now, locale)
  if (ret !== null) byId.set('return', ret)
  if (deps.nextInSeries) {
    byId.set('next-in-series', {
      id: 'next-in-series',
      titleKey: 'listenNextInSeriesTitle',
      reason: deps.nextInSeriesReason,
      books: [deps.nextInSeries],
    })
  }
  const travel = travelBlock(visible, deps.isDownloaded ?? (() => false))
  if (travel !== null) byId.set('travel', travel)
  const short = shortBooksBlock(visible)
  if (short !== null) byId.set('short', short)
  const favorites = favoriteAuthorsBlock(visible, deps.isFavorite ?? (() => false))
  if (favorites !== null) byId.set('favorite-authors', favorites)
  const recent = recentlyAddedBlock(visible, deps.now)
  if (recent !== null) byId.set('recently-added', recent)

  return effectiveBlockOrder(prefs.order)
    .filter((id) => !prefs.hidden.includes(id))
    .map((id) => byId.get(id))
    .filter((block): block is ListenBlock => block !== undefined)
}

/**
 * spec-28 (#191) — the full cross-shelf dedup port: a book renders in at
 * most ONE shelf — the highest one on screen. The hero claims its book
 * FIRST and is exempt; every later shelf drops already-claimed works, so
 * reordering a shelf re-prioritises which shelf claims a book.
 */
export function deduplicateListenShelves(blocks: ListenBlock[]): ListenBlock[] {
  const claimed = new Set<string>()
  blocks
    .filter((block) => block.id === 'hero')
    .forEach((block) => block.books.forEach((book) => claimed.add(book.mergeKey)))
  return blocks.map((block) => {
    if (block.id === 'hero') return block
    const kept = block.books.filter((book) => !claimed.has(book.mergeKey) && claimed.add(book.mergeKey))
    return kept.length === block.books.length ? block : { ...block, books: kept }
  })
}

/** «До кінця 1 год 20 хв» — the honest remaining time from real totals. */
export function formatRemainingTime(seconds: number, locale: ComposerLocale = 'uk'): string {
  const units = locale === 'en' ? { h: 'h', min: 'min' } : { h: 'год', min: 'хв' }
  if (seconds <= 0) return `0 ${units.min}`
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.round((seconds % 3600) / 60)
  if (hours === 0) return `${minutes} ${units.min}`
  if (minutes === 0) return `${hours} ${units.h}`
  return `${hours} ${units.h} ${minutes} ${units.min}`
}

function pluralDays(days: number, locale: ComposerLocale): string {
  if (locale === 'en') return days === 1 ? 'day' : 'days'
  if (days % 10 === 1 && days % 100 !== 11) return 'день'
  if (days % 10 >= 2 && days % 10 <= 4 && (days % 100 < 12 || days % 100 > 14)) return 'дні'
  return 'днів'
}
