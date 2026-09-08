/**
 * #592 W6.1 — the honest web port of Android's local adaptation
 * (`RecommendationPersonalization` + `RecommendationEngine`, R-W4):
 * signals from the browser's own data (Listening State + the «Не цікаво»
 * Recommendation Preferences), candidates ranked by transparent
 * word/genre/author overlap — the baseline Android itself falls back to
 * when the embedding model is unavailable — and every pick carries
 * Android's reason chip («Схоже на X» / «За твоїм інтересом до …»).
 *
 * No embeddings, no network, no fabricated numbers (ADR-0014): the score
 * is the agreed 2026-09-05 design's composition with the weight of any
 * absent feature redistributed proportionally among the available
 * positive components. The collective component C stays zero until a real
 * server graph exists (ADR-0031: absent graph never blocks Огляд).
 */
import type { LibraryBookView } from '../ui/libraryModel'

/** One candidate for the shelf — the merged catalog's openable Works. */
export interface PersonalCandidate {
  mergeKey: string
  title: string
  author: string
  /** Genre-page memberships the worker actually observed; absent = none. */
  genres?: string[]
  coverImageUrl?: string
  durationSeconds?: number
  /** True when the listener saved it («У медіатеці» marker, design §3). */
  inLibrary: boolean
}

/** One listener signal, Android's `RecommendationEngine.Signal` shape. */
export interface PersonalSignal {
  mergeKey: string
  title: string
  author: string
  genres: string[]
  weight: number
}

/** The «Не цікаво» input: exclusion sets + the reduce-similar direction. */
export interface PersonalPrefs {
  hiddenWorks: Set<string>
  hiddenAuthors: Set<string>
  /** mergeKeys with REDUCE_SIMILAR — the −1.0 negative direction. */
  reduceSimilar: Set<string>
}

/** The strongest signal behind a pick — the reason chip («Схоже на X»). */
export type PickReason =
  | { kind: 'similar'; title: string }
  | { kind: 'genre'; genre: string }
  /** Only a REAL server graph edge (ADR-0031) — never fabricated locally. */
  | { kind: 'collective'; title: string }

export interface PersonalPick {
  candidate: PersonalCandidate
  score: number
  /** Null for exploration picks — Android's isExploration has no chip. */
  reason: PickReason | null
  exploration: boolean
}

/**
 * One signal per library Work, from REAL Listening State only (design §5:
 * completion +0.6; progress ≥70% +0.35 / ≥30% +0.2, ONE threshold;
 * progress and completion never stack). The web has no favorite flag, no
 * own-rating feed and no genre claims on Library rows yet — they join
 * when the data exists, never as invented lift (ADR-0014; the genre
 * reason chip stays dormant until then). REDUCE_SIMILAR applies at
 * scoring time.
 */
export function signalsOf(views: LibraryBookView[]): PersonalSignal[] {
  const byKey = new Map<string, LibraryBookView>()
  for (const view of views) {
    const current = byKey.get(view.mergeKey)
    if (current === undefined || (view.status === 'completed' && current.status !== 'completed')) {
      byKey.set(view.mergeKey, view)
    }
  }
  const signals: PersonalSignal[] = []
  for (const view of byKey.values()) {
    let weight = 0
    if (view.status === 'completed') weight = 0.6
    else if ((view.progress ?? 0) >= 0.7) weight = 0.35
    else if ((view.progress ?? 0) >= 0.3) weight = 0.2
    else continue
    signals.push({
      mergeKey: view.mergeKey,
      title: view.title,
      author: view.author,
      genres: [],
      weight,
    })
  }
  return signals
}

/** The token set of one text — the honest word baseline (Android's fallback). */
function tokens(value: string): Set<string> {
  return new Set(
    value
      .toLowerCase()
      .split(/[^a-zа-яіїєґ'0-9]+/i)
      .filter((token) => token.length > 1),
  )
}

/** Word-overlap similarity in [0,1]; no vector, no model. */
export function wordOverlap(a: string, b: string): number {
  const left = tokens(a)
  if (left.size === 0) return 0
  const right = tokens(b)
  if (right.size === 0) return 0
  let shared = 0
  for (const token of left) if (right.has(token)) shared++
  return shared / left.size
}

function authorTokens(author: string): Set<string> {
  return tokens(author)
}

function genreSet(genres: string[]): Set<string> {
  const set = new Set<string>()
  for (const genre of genres) for (const token of tokens(genre)) set.add(token)
  return set
}

/**
 * The score of one candidate against the positive profile: the max
 * weighted similarity to the strongest signals (design §6) with the
 * negative component subtracted and the reduce-similar direction applied.
 * Missing features never fabricate — their weight redistributes
 * proportionally among the available positive components.
 */
export function scoreCandidate(
  candidate: PersonalCandidate,
  signals: PersonalSignal[],
  prefs: PersonalPrefs,
): { score: number; reason: PickReason | null } {
  if (signals.length === 0) return { score: 0, reason: null }

  let semantic = 0
  let genreAffinity = 0
  let authorAffinity = 0
  let strongestTitle = ''
  let strongestGenre = ''
  for (const signal of signals) {
    const similarity = wordOverlap(candidate.title, signal.title)
    if (similarity > semantic) {
      semantic = similarity
      strongestTitle = signal.title
    }
    const signalGenres = genreSet(signal.genres)
    if (signalGenres.size > 0) {
      const shared = [...genreSet(candidate.genres ?? [])].filter((token) => signalGenres.has(token)).length
      const affinity = shared / Math.max(signalGenres.size, 1)
      if (affinity > genreAffinity) {
        genreAffinity = affinity
        strongestGenre = signal.genres[0] ?? ''
      }
    }
    if (signal.author !== '' && candidate.author !== '') {
      const shared = [...authorTokens(candidate.author)].filter((token) => authorTokens(signal.author).has(token)).length
      const affinity = shared / Math.max(authorTokens(signal.author).size, 1)
      if (affinity > authorAffinity) authorAffinity = affinity
    }
  }

  // The design's weights: 0.60 S + 0.20 G + 0.10 A + 0.05 R + 0.05 F − 0.70 N.
  // The web has no publishedAt (freshness) and no series facet — their
  // weights redistribute proportionally among the available components.
  const weights = [0.6, 0.2, 0.1]
  const values = [semantic, genreAffinity, authorAffinity]
  const present = weights.reduce((sum, weight, index) => sum + (values[index] > 0 ? weight : 0), 0)
  let positive = 0
  if (present > 0) {
    for (let index = 0; index < weights.length; index++) {
      if (values[index] > 0) positive += (weights[index] / present) * values[index]
    }
  }

  let score = positive
  if (prefs.reduceSimilar.has(candidate.mergeKey)) score -= 0.7

  const reason: PickReason | null =
    genreAffinity > semantic && strongestGenre !== ''
      ? { kind: 'genre', genre: strongestGenre }
      : semantic > 0
        ? { kind: 'similar', title: strongestTitle }
        : null

  return { score: Math.max(score, 0), reason }
}

/**
 * Hard exclusions, design §3: hidden works/authors, completed and already
 * started works. Not-yet-started library books ARE candidates
 * («У медіатеці» marker); favorites explain picks but are not picks.
 */
export function isExcluded(candidate: PersonalCandidate, prefs: PersonalPrefs, viewsByKey: Map<string, LibraryBookView>): boolean {
  if (prefs.hiddenWorks.has(candidate.mergeKey)) return true
  if (prefs.hiddenAuthors.size > 0 && candidate.author !== '') {
    const candidateAuthor = authorTokens(candidate.author)
    for (const author of prefs.hiddenAuthors) {
      const hidden = authorTokens(author)
      if (hidden.size > 0 && [...candidateAuthor].some((token) => hidden.has(token))) return true
    }
  }
  const view = viewsByKey.get(candidate.mergeKey)
  if (view !== undefined && view.status !== 'new') return true
  return false
}

/** The shelf's diversity caps (design §3): max two works per author. */
const MAX_PER_AUTHOR = 2

/**
 * The «Для вас» shelf: top personal picks by score with Android's
 * diversity caps, then the exploration slots (stable day-seeded shuffle,
 * no reason chip). A short pool renders a short shelf — never padding.
 */
export function rankPersonalShelf(
  candidates: PersonalCandidate[],
  signals: PersonalSignal[],
  prefs: PersonalPrefs,
  viewsByKey: Map<string, LibraryBookView>,
  nowEpochMs: number,
  topN = 10,
): PersonalPick[] {
  const eligible = candidates.filter((candidate) => !isExcluded(candidate, prefs, viewsByKey))
  if (eligible.length === 0) return []

  const scored = eligible
    .map((candidate) => {
      const { score, reason } = scoreCandidate(candidate, signals, prefs)
      return { candidate, score, reason }
    })
    .filter((pick) => pick.score > 0 || pick.reason !== null)
    .sort((a, b) => b.score - a.score || a.candidate.mergeKey.localeCompare(b.candidate.mergeKey))

  const explorationCount = Math.min(2, Math.floor(0.2 * topN))
  const personalSlots = topN - explorationCount
  const selected: PersonalPick[] = []
  const authorCounts = new Map<string, number>()

  const addIfDiverse = (pick: { candidate: PersonalCandidate; score: number; reason: PickReason | null }, exploration: boolean): boolean => {
    const authorKey = [...authorTokens(pick.candidate.author)].join(' ')
    if (authorKey !== '' && (authorCounts.get(authorKey) ?? 0) >= MAX_PER_AUTHOR) return false
    selected.push({ candidate: pick.candidate, score: pick.score, reason: pick.reason, exploration })
    if (authorKey !== '') authorCounts.set(authorKey, (authorCounts.get(authorKey) ?? 0) + 1)
    return true
  }

  for (const pick of scored) {
    if (selected.length >= personalSlots) break
    addIfDiverse(pick, false)
  }

  if (selected.length < topN) {
    const selectedKeys = new Set(selected.map((pick) => pick.candidate.mergeKey))
    // The stable day-seeded exploration pool: eligible candidates with a
    // real interest basis, shuffled deterministically per day (design §6).
    const day = Math.floor(nowEpochMs / 86_400_000)
    const pool = scored
      .filter((pick) => !selectedKeys.has(pick.candidate.mergeKey))
      .sort((a, b) => stableKey(a.candidate.mergeKey, day) - stableKey(b.candidate.mergeKey, day))
    for (const pick of pool) {
      if (selected.length >= topN) break
      addIfDiverse({ ...pick, reason: null }, true)
    }
  }

  return selected
}

/** A stable, day-scoped shuffle key — the same candidate keeps its slot all day. */
function stableKey(id: string, day: number): number {
  let hash = 0
  for (let index = 0; index < id.length; index++) {
    hash = (hash * 31 + id.charCodeAt(index)) | 0
  }
  return Math.abs(hash ^ (day * 0x9e3779b1)) | 0
}