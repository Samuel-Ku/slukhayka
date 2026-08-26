import { describe, expect, it } from 'vitest'
import { editionIdFor, mergeKeyFor } from '../edition'

describe('mergeKeyFor', () => {
  it('produces title|author lowercased', () => {
    expect(mergeKeyFor('Кобзар', 'Тарас Шевченко')).toBe('кобзар|тарас шевченко')
  })
  it('returns empty when title or author missing', () => {
    expect(mergeKeyFor('', 'Автор')).toBe('')
    expect(mergeKeyFor('Книга', '')).toBe('')
  })
})

describe('editionIdFor', () => {
  it('same inputs give same id', () => {
    const a = editionIdFor('кобзар|тарас шевченко', 'b1', 'Валерій Завалко')
    const b = editionIdFor('кобзар|тарас шевченко', 'b1', 'Валерій Завалко')
    expect(a).toBe(b)
    expect(a).toHaveLength(24)
  })
  it('different narrators give different ids', () => {
    const a = editionIdFor('кобзар|тарас шевченко', 'b1', 'Валерій Завалко')
    const b = editionIdFor('кобзар|тарас шевченко', 'b1', 'Богдан Бенюк')
    expect(a).not.toBe(b)
  })
  it('falls back to bookId when mergeKey blank', () => {
    const a = editionIdFor('', 'local-1', '')
    const b = editionIdFor('', 'local-1', 'Наратор')
    expect(a).not.toBe(b)
  })
})
