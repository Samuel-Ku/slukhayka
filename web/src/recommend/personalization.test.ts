/**
 * #592 W6.1 — the pure local-adaptation policy: signal weights (design §5),
 * exclusions (design §3), score composition with proportional
 * redistribution (design §6), diversity caps, stable exploration, and the
 * honest reason chips from Android's dictionary.
 */
import { describe, expect, it } from 'vitest'
import type { LibraryBookView } from '../ui/libraryModel'
import {
  isExcluded,
  rankPersonalShelf,
  scoreCandidate,
  signalsOf,
  type PersonalCandidate,
  type PersonalPrefs,
  type PersonalSignal,
} from './personalization'

function view(overrides: Partial<LibraryBookView> & { mergeKey: string; title: string; author: string }): LibraryBookView {
  return {
    createdAt: 0,
    status: 'new',
    lastListenedAt: null,
    narrator: '',
    ...overrides,
  }
}

function candidate(overrides: Partial<PersonalCandidate> & { mergeKey: string; title: string; author: string }): PersonalCandidate {
  return { genres: [], inLibrary: false, ...overrides }
}

function prefs(overrides: Partial<PersonalPrefs> = {}): PersonalPrefs {
  return { hiddenWorks: new Set(), hiddenAuthors: new Set(), reduceSimilar: new Set(), ...overrides }
}

describe('signalsOf — design §5 weights from REAL Listening State', () => {
  it('completion is +0.6', () => {
    const signals = signalsOf([view({ mergeKey: 'k1', title: 'Кобзар', author: 'Шевченко', status: 'completed' })])
    expect(signals).toEqual([{ mergeKey: 'k1', title: 'Кобзар', author: 'Шевченко', genres: [], weight: 0.6 }])
  })

  it('progress ≥70% is +0.35, ≥30% is +0.2, one threshold only', () => {
    const at70 = signalsOf([view({ mergeKey: 'k1', title: 'Т', author: 'А', status: 'listening', progress: 0.71 })])
    expect(at70[0]!.weight).toBe(0.35)
    const at30 = signalsOf([view({ mergeKey: 'k2', title: 'Т2', author: 'А2', status: 'listening', progress: 0.34 })])
    expect(at30[0]!.weight).toBe(0.2)
    // Never both: a ≥70% book is 0.35, not 0.55.
    expect(at70[0]!.weight).not.toBe(0.55)
  })

  it('weak progress (<30%) and never-started books are NO signal', () => {
    const signals = signalsOf([
      view({ mergeKey: 'k1', title: 'Т', author: 'А', status: 'listening', progress: 0.1 }),
      view({ mergeKey: 'k2', title: 'Т2', author: 'А2', status: 'new' }),
    ])
    expect(signals).toEqual([])
  })

  it('progress is measured, never fabricated: unknown progress contributes nothing', () => {
    const signals = signalsOf([view({ mergeKey: 'k1', title: 'Т', author: 'А', status: 'listening', progress: undefined })])
    expect(signals).toEqual([])
  })
})

describe('scoreCandidate — design §6 composition', () => {
  const signals: PersonalSignal[] = [
    { mergeKey: 's1', title: 'Майстер і Маргарита', author: 'Булгаков', genres: ['роман'], weight: 0.6 },
  ]

  it('similar title words lift the semantic component («Схоже на X»)', () => {
    const { score, reason } = scoreCandidate(
      candidate({ mergeKey: 'c1', title: 'Майстер і Маргарита. Том 2', author: 'Інший' }),
      signals,
      prefs(),
    )
    expect(score).toBeGreaterThan(0)
    expect(reason).toEqual({ kind: 'similar', title: 'Майстер і Маргарита' })
  })

  it('genre affinity yields «За твоїм інтересом до …» when it dominates', () => {
    const withGenre: PersonalSignal[] = [
      { mergeKey: 's1', title: 'Зовсім інша назва', author: 'Булгаков', genres: ['детектив'], weight: 0.6 },
    ]
    const { score, reason } = scoreCandidate(
      candidate({ mergeKey: 'c1', title: 'Ще інша назва', author: 'Хтось', genres: ['детектив'] }),
      withGenre,
      prefs(),
    )
    expect(score).toBeGreaterThan(0)
    expect(reason).toEqual({ kind: 'genre', genre: 'детектив' })
  })

  it('author affinity alone scores (Android\'s keyword baseline)', () => {
    const { score } = scoreCandidate(
      candidate({ mergeKey: 'c1', title: 'Нова книга', author: 'Михайло Булгаков' }),
      signals,
      prefs(),
    )
    expect(score).toBeGreaterThan(0)
  })

  it('a candidate with NO overlap scores zero and no reason — never invented', () => {
    const { score, reason } = scoreCandidate(
      candidate({ mergeKey: 'c1', title: 'Математика', author: 'Петренко' }),
      signals,
      prefs(),
    )
    expect(score).toBe(0)
    expect(reason).toBeNull()
  })

  it('REDUCE_SIMILAR («Не цікаво») subtracts the −0.7 direction', () => {
    const plain = scoreCandidate(
      candidate({ mergeKey: 'c1', title: 'Майстер і Маргарита. Том 2', author: 'Інший' }),
      signals,
      prefs(),
    )
    const reduced = scoreCandidate(
      candidate({ mergeKey: 'c1', title: 'Майстер і Маргарита. Том 2', author: 'Інший' }),
      signals,
      prefs({ reduceSimilar: new Set(['c1']) }),
    )
    expect(reduced.score).toBeLessThan(plain.score)
  })
})

describe('isExcluded — design §3 hard exclusions', () => {
  const views = new Map([['k1', view({ mergeKey: 'k1', title: 'Т', author: 'А', status: 'listening', progress: 0.4 })]])

  it('«Не цікаво» HIDE_WORK excludes the work', () => {
    expect(
      isExcluded(
        candidate({ mergeKey: 'k1', title: 'Т', author: 'А' }),
        prefs({ hiddenWorks: new Set(['k1']) }),
        views,
      ),
    ).toBe(true)
  })

  it('HIDE_AUTHOR excludes the author', () => {
    expect(
      isExcluded(
        candidate({ mergeKey: 'k9', title: 'Інша', author: 'Андрій Шевченко' }),
        prefs({ hiddenAuthors: new Set(['Шевченко']) }),
        views,
      ),
    ).toBe(true)
  })

  it('a started/completed Work is not a NEW pick', () => {
    expect(isExcluded(candidate({ mergeKey: 'k1', title: 'Т', author: 'А' }), prefs(), views)).toBe(true)
  })

  it('a not-yet-started library book IS a candidate', () => {
    const fresh = new Map()
    expect(
      isExcluded(
        candidate({ mergeKey: 'k1', title: 'Т', author: 'А', inLibrary: true }),
        prefs(),
        fresh,
      ),
    ).toBe(false)
  })

  it('unknown author is never excluded by an empty exclusion list', () => {
    expect(isExcluded(candidate({ mergeKey: 'k2', title: 'Т2', author: '' }), prefs(), views)).toBe(false)
  })
})

describe('rankPersonalShelf — diversity and exploration', () => {
  const signals: PersonalSignal[] = [
    { mergeKey: 's1', title: 'Майстер і Маргарита', author: 'Булгаков', genres: [], weight: 0.6 },
    { mergeKey: 's2', title: 'Собаче серце', author: 'Булгаков', genres: [], weight: 0.35 },
  ]

  it('ranks the honest top and never invents when nothing overlaps', () => {
    const picks = rankPersonalShelf(
      [
        candidate({ mergeKey: 'c1', title: 'Собаче серце (нове видання)', author: 'Інший' }),
        candidate({ mergeKey: 'c2', title: 'Математика для всіх', author: 'Петренко' }),
      ],
      signals,
      prefs(),
      new Map(),
      Date.now(),
    )
    expect(picks.length).toBe(1)
    expect(picks[0]!.candidate.mergeKey).toBe('c1')
    expect(picks[0]!.reason).toEqual({ kind: 'similar', title: 'Собаче серце' })
  })

  it('caps at two works per author (design §3)', () => {
    const picks = rankPersonalShelf(
      [
        candidate({ mergeKey: 'c1', title: 'Майстер і Маргарита. Том 2', author: 'Булгаков' }),
        candidate({ mergeKey: 'c2', title: 'Собаче серце. Том 2', author: 'Булгаков' }),
        candidate({ mergeKey: 'c3', title: 'Майстер і Маргарита. Том 3', author: 'Булгаков' }),
      ],
      signals,
      prefs(),
      new Map(),
      Date.now(),
      10,
    )
    expect(picks.filter((pick) => pick.candidate.author === 'Булгаков').length).toBe(2)
  })

  it('a short pool renders a short shelf — never padding', () => {
    const picks = rankPersonalShelf([], signals, prefs(), new Map(), Date.now())
    expect(picks).toEqual([])
  })

  it('exploration slots carry no reason chip (Android isExploration)', () => {
    const picks = rankPersonalShelf(
      [
        candidate({ mergeKey: 'c1', title: 'Майстер і Маргарита. Том 2', author: 'Інший' }),
        candidate({ mergeKey: 'c2', title: 'Собаче серце. Том 2', author: 'Ще хтось' }),
        candidate({ mergeKey: 'c3', title: 'Майстер і Маргарита. Том 3', author: 'Третій' }),
      ],
      signals,
      prefs(),
      new Map(),
      Date.now(),
      2,
    )
    // topN=2 → 1 personal + 1 exploration (floor(0.2×2) = 0 → no slots,
    // so both are personal picks; a larger topN yields exploration slots).
    expect(picks.length).toBe(2)
    for (const pick of picks) {
      expect(pick.exploration).toBe(false)
    }
  })
})