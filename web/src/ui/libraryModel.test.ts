/**
 * #584 W1.2 — the pure Медіатека policy: honest classification, honest
 * progress fractions, Android's sort orders.
 */
import { describe, expect, it } from 'vitest'
import type { LibraryEntryEntity } from '../local/domain'
import type { EditionLink } from '../local/editionLinks'
import type { LocalListeningStateSnapshot } from '../player/localState'
import {
  buildLibraryViews,
  cumulativeFraction,
  filterLibrary,
  matchesFilter,
  sortLibrary,
} from './libraryModel'

const entry = (mergeKey: string, overrides: Partial<LibraryEntryEntity> = {}): LibraryEntryEntity => ({
  mergeKey,
  title: `Твір ${mergeKey}`,
  author: 'Автор',
  createdAt: 1000,
  ...overrides,
})

const link = (editionId: string, mergeKey: string, overrides: Partial<EditionLink> = {}): EditionLink => ({
  editionId,
  mergeKey,
  narrator: 'Диктор',
  language: 'uk',
  durationSeconds: null,
  chapterDurations: null,
  updatedAt: 10,
  ...overrides,
})

const snapshot = (editionId: string, overrides: Partial<LocalListeningStateSnapshot> = {}): LocalListeningStateSnapshot => ({
  editionId,
  chapterIndex: 0,
  positionSeconds: 0,
  isCompleted: false,
  preferredSpeed: null,
  lastPausedAtEpochMs: null,
  ...overrides,
})

describe('cumulativeFraction', () => {
  it('completed reads as the honest 1', () => {
    expect(cumulativeFraction({ chapterIndex: 3, positionSeconds: 10, isCompleted: true }, null)).toBe(1)
  })

  it('computes the cumulative wall-clock fraction from real chapter durations', () => {
    // Chapters of 100 s each; listener is 10 s into chapter 2 (index 1).
    const fraction = cumulativeFraction(
      { chapterIndex: 1, positionSeconds: 10, isCompleted: false },
      [100, 100, 100],
    )
    expect(fraction).toBeCloseTo(110 / 300, 10)
  })

  it('renders nothing when chapter durations are unknown — never a guess (ADR-0014)', () => {
    expect(cumulativeFraction({ chapterIndex: 1, positionSeconds: 10, isCompleted: false }, null)).toBeUndefined()
    expect(cumulativeFraction({ chapterIndex: 1, positionSeconds: 10, isCompleted: false }, [])).toBeUndefined()
  })

  it('clamps to the 0..1 band', () => {
    expect(cumulativeFraction({ chapterIndex: 9, positionSeconds: 999, isCompleted: false }, [100])).toBe(1)
  })
})

describe('buildLibraryViews', () => {
  it('a Work the browser never touched is honestly «нове» with no hairline', () => {
    const views = buildLibraryViews([entry('a|b')], [], [])
    expect(views).toHaveLength(1)
    expect(views[0]!.status).toBe('new')
    expect(views[0]!.progress).toBeUndefined()
    expect(views[0]!.lastListenedAt).toBeNull()
  })

  it('an in-progress edition classifies as «слухаю» with a real fraction', () => {
    const views = buildLibraryViews(
      [entry('a|b')],
      [link('ed1', 'a|b', { chapterDurations: [100, 100] })],
      [snapshot('ed1', { chapterIndex: 1, positionSeconds: 50, lastPausedAtEpochMs: 500 })],
    )
    expect(views[0]!.status).toBe('listening')
    expect(views[0]!.progress).toBeCloseTo(150 / 200, 10)
    expect(views[0]!.lastListenedAt).toBe(500)
  })

  it('a completed edition classifies as «завершений»', () => {
    const views = buildLibraryViews(
      [entry('a|b')],
      [link('ed1', 'a|b')],
      [snapshot('ed1', { isCompleted: true })],
    )
    expect(views[0]!.status).toBe('completed')
    expect(views[0]!.progress).toBe(1)
  })

  it('picks the edition the Work was last listened on, not an arbitrary one', () => {
    const views = buildLibraryViews(
      [entry('a|b')],
      [link('ed1', 'a|b'), link('ed2', 'a|b', { narrator: 'Інший' })],
      [
        snapshot('ed1', { lastPausedAtEpochMs: 100 }),
        snapshot('ed2', { lastPausedAtEpochMs: 900, chapterIndex: 2 }),
      ],
    )
    expect(views[0]!.narrator).toBe('Інший')
  })
})

describe('filters and sorts', () => {
  const views = buildLibraryViews(
    [entry('a', { createdAt: 1 }), entry('b', { createdAt: 2 }), entry('c', { createdAt: 3 })],
    [
      link('edA', 'a', { chapterDurations: [100] }),
      link('edB', 'b'),
      link('edC', 'c'),
    ],
    [
      snapshot('edA', { lastPausedAtEpochMs: 300 }),
      snapshot('edB', { isCompleted: true, lastPausedAtEpochMs: 100 }),
    ],
  )

  it('the trilogy filters over real Listening State only', () => {
    expect(filterLibrary(views, 'all').map((v) => v.mergeKey)).toEqual(['a', 'b', 'c'])
    expect(filterLibrary(views, 'new').map((v) => v.mergeKey)).toEqual(['c'])
    expect(filterLibrary(views, 'listening').map((v) => v.mergeKey)).toEqual(['a'])
    expect(filterLibrary(views, 'completed').map((v) => v.mergeKey)).toEqual(['b'])
  })

  it('sorts recently-listened by the pause marker with never-paused last', () => {
    const sorted = sortLibrary(views, 'recently-listened')
    expect(sorted.map((v) => v.mergeKey)).toEqual(['a', 'b', 'c'])
  })

  it('sorts recently-added, title and author deterministically', () => {
    expect(sortLibrary(views, 'recently-added').map((v) => v.mergeKey)).toEqual(['c', 'b', 'a'])
    const titled = sortLibrary(
      buildLibraryViews(
        [entry('x', { title: 'Яблуко' }), entry('y', { title: 'Абетка' })],
        [],
        [],
      ),
      'title',
    )
    expect(titled.map((v) => v.title)).toEqual(['Абетка', 'Яблуко'])
  })

  it('matchesFilter keeps «усі» inclusive', () => {
    expect(matchesFilter(views[0]!, 'all')).toBe(true)
  })
})
