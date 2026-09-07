/**
 * #585 W2.1 — the shelf rules engine: each block's eligibility and reason,
 * the prefs' effective order and hiding, and the full cross-shelf dedup
 * (the hero claims first and never leaks).
 */
import { describe, expect, it } from 'vitest'
import type { LibraryBookView } from './libraryModel'
import {
  composeListenBlocks,
  deduplicateListenShelves,
  effectiveBlockOrder,
  formatRemainingTime,
} from './listenComposer'

const view = (mergeKey: string, overrides: Partial<LibraryBookView> = {}): LibraryBookView => ({
  mergeKey,
  title: `Книга ${mergeKey}`,
  author: 'Автор',
  createdAt: 0,
  status: 'new',
  lastListenedAt: null,
  narrator: '',
  ...overrides,
})

const NOW = 1_000_000_000_000
const DAY = 24 * 3600 * 1000

describe('effectiveBlockOrder', () => {
  it('defaults to the product priority', () => {
    expect(effectiveBlockOrder([])).toEqual([
      'hero', 'almost-done', 'return', 'next-in-series', 'travel', 'short', 'favorite-authors', 'recently-added',
    ])
  })

  it('the user reorder wins; unknown ids fall back to the default position', () => {
    expect(effectiveBlockOrder(['short' as never, 'hero' as never, 'bogus' as never]).slice(0, 2)).toEqual(['short', 'hero'])
    const order = effectiveBlockOrder(['short', 'hero'] as never[])
    expect(order).toHaveLength(8)
    expect(new Set(order).size).toBe(8)
  })
})

describe('composeListenBlocks', () => {
  it('a cold-start library composes nothing', () => {
    expect(composeListenBlocks([], { order: [], hidden: [], dismissed: [] }, { now: NOW })).toEqual([])
  })

  it('the hero is the most recently listened, unfinished book', () => {
    const blocks = composeListenBlocks(
      [
        view('a', { status: 'listening', lastListenedAt: NOW - 2 * DAY }),
        view('b', { status: 'listening', lastListenedAt: NOW - DAY }),
      ],
      { order: [], hidden: [], dismissed: [] },
      { now: NOW },
    )
    expect(blocks[0]!.id).toBe('hero')
    expect(blocks[0]!.books[0]!.mergeKey).toBe('b')
  })

  it('almost-done takes ≥ 80 % and states the honest remaining time', () => {
    const blocks = composeListenBlocks(
      [view('a', { status: 'listening', progress: 0.85, totalSeconds: 3600, cumulativeSeconds: 3600 * 0.85 })],
      { order: [], hidden: [], dismissed: [] },
      { now: NOW },
    )
    const almostDone = blocks.find((block) => block.id === 'almost-done')!
    expect(almostDone.reason).toEqual({ key: 'listenAlmostDoneReason', params: { time: '9 хв' } })
  })

  it('return catches a book dormant for 14+ days with the day count', () => {
    const blocks = composeListenBlocks(
      [view('a', { status: 'listening', lastListenedAt: NOW - 20 * DAY })],
      { order: [], hidden: [], dismissed: [] },
      { now: NOW },
    )
    const ret = blocks.find((block) => block.id === 'return')!
    expect(ret.reason).toEqual({ key: 'listenReturnReason', params: { days: '20 днів' } })
  })

  it('short takes ≤ 3 h totals only', () => {
    const blocks = composeListenBlocks(
      [
        view('shorty', { totalSeconds: 2 * 3600 }),
        view('long', { totalSeconds: 5 * 3600 }),
      ],
      { order: [], hidden: [], dismissed: [] },
      { now: NOW },
    )
    const short = blocks.find((block) => block.id === 'short')!
    expect(short.books.map((book) => book.mergeKey)).toEqual(['shorty'])
  })

  it('recently-added takes only the last week', () => {
    const blocks = composeListenBlocks(
      [
        view('fresh', { createdAt: NOW - 2 * DAY }),
        view('old', { createdAt: NOW - 30 * DAY }),
      ],
      { order: [], hidden: [], dismissed: [] },
      { now: NOW },
    )
    const recent = blocks.find((block) => block.id === 'recently-added')!
    expect(recent.books.map((book) => book.mergeKey)).toEqual(['fresh'])
  })

  it('dismissed works are filtered from every block', () => {
    const blocks = composeListenBlocks(
      [view('a', { status: 'listening', lastListenedAt: NOW - DAY })],
      { order: [], hidden: [], dismissed: ['a'] },
      { now: NOW },
    )
    expect(blocks).toEqual([])
  })

  it('a hidden block stays computed but unrendered', () => {
    const blocks = composeListenBlocks(
      [view('a', { status: 'listening', lastListenedAt: NOW - DAY })],
      { order: [], hidden: ['hero'], dismissed: [] },
      { now: NOW },
    )
    expect(blocks.find((block) => block.id === 'hero')).toBeUndefined()
  })

  it('the user reorder moves an eligible block up', () => {
    const library = [
      view('a', { status: 'listening', lastListenedAt: NOW - DAY }),
      view('b', { createdAt: NOW - DAY }),
    ]
    const blocks = composeListenBlocks(library, { order: ['recently-added', 'hero'], hidden: [], dismissed: [] }, { now: NOW })
    expect(blocks.map((block) => block.id)).toEqual(['recently-added', 'hero'])
  })

  it('the en locale renders the reason params honestly', () => {
    const blocks = composeListenBlocks(
      [view('a', { status: 'listening', lastListenedAt: NOW - 20 * DAY })],
      { order: [], hidden: [], dismissed: [] },
      { now: NOW, locale: 'en' },
    )
    expect(blocks.find((block) => block.id === 'return')!.reason!.params!.days).toBe('20 days')
  })
})

describe('deduplicateListenShelves', () => {
  it('the hero claims its book first; no shelf below repeats it', () => {
    const shared = view('shared', { status: 'listening', lastListenedAt: NOW - DAY, progress: 0.9 })
    const other = view('other', { createdAt: NOW - DAY })
    const blocks = deduplicateListenShelves([
      { id: 'hero', titleKey: 'listenHeroTitle', books: [shared] },
      { id: 'almost-done', titleKey: 'listenAlmostDoneTitle', books: [shared, other] },
    ])
    expect(blocks[0]!.books).toHaveLength(1)
    expect(blocks[1]!.books.map((book) => book.mergeKey)).toEqual(['other'])
  })

  it('reordering a shelf re-prioritises which shelf claims a book', () => {
    const book = view('x')
    const first = deduplicateListenShelves([
      { id: 'short', titleKey: 'listenShortTitle', books: [book] },
      { id: 'recently-added', titleKey: 'listenRecentlyAddedTitle', books: [book] },
    ])
    expect(first[0]!.books).toHaveLength(1)
    expect(first[1]!.books).toHaveLength(0)
    const second = deduplicateListenShelves([
      { id: 'recently-added', titleKey: 'listenRecentlyAddedTitle', books: [book] },
      { id: 'short', titleKey: 'listenShortTitle', books: [book] },
    ])
    expect(second[0]!.books).toHaveLength(1)
    expect(second[1]!.books).toHaveLength(0)
  })

  it('blocks without dupes pass through untouched', () => {
    const blocks = [{ id: 'short' as const, titleKey: 'listenShortTitle' as const, books: [view('a'), view('b')] }]
    expect(deduplicateListenShelves(blocks)).toEqual(blocks)
  })
})

describe('formatRemainingTime', () => {
  it('renders honest uk/en units', () => {
    expect(formatRemainingTime(0)).toBe('0 хв')
    expect(formatRemainingTime(600)).toBe('10 хв')
    expect(formatRemainingTime(3600)).toBe('1 год')
    expect(formatRemainingTime(4800)).toBe('1 год 20 хв')
    expect(formatRemainingTime(4800, 'en')).toBe('1 h 20 min')
  })
})
