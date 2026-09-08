/**
 * W3.1 — the CollectionMatcher/CollectionJson port pinned to Android's own
 * fixture suite (CollectionMatcherTest.kt + CollectionJsonTest.kt): every
 * case that pins the Kotlin matcher pins the web port (the two-runtime rule
 * of ADR-0034 — one decision, tested once on each runtime).
 */
import { describe, expect, it } from 'vitest'
import {
  decodeCollection,
  entryMatches,
  matchAllCollections,
  matchCollection,
  normalizeCollectionAuthor,
  normalizeCollectionTitle,
  type CollectionEntry,
  type CollectionList,
} from './collectionModel'

const book = (title: string, author: string) => ({ title, author })
const entry = (author: string, title?: string): CollectionEntry => (title === undefined ? { author } : { author, title })

function collection(id: string, name: string, entries: CollectionEntry[]): CollectionList {
  return { id, name, sourceNote: '', entries }
}

describe('collection matcher — exact + author+title rule', () => {
  it('matches on exact author and title', () => {
    const catalog = [book('Старий і море', 'Ернест Гемінґвей')]
    const c = collection('nobel', 'Нобелівські лауреати', [entry('Ернест Гемінґвей', 'Старий і море')])

    const matched = matchCollection(c, catalog)

    expect(matched.books.map((b) => b.title)).toEqual(['Старий і море'])
  })

  it('a book never appears under an entry it does not match', () => {
    const catalog = [book('Старий і море', 'Ернест Гемінґвей')]
    const c = collection('nobel', 'Нобелівські лауреати', [entry('Ернест Гемінґвей', 'Інша книга')])

    // Author agrees but the title does not — no match, nothing fabricated.
    expect(matchCollection(c, catalog).books).toHaveLength(0)
  })

  it('a blank author never matches anything', () => {
    const catalog = [book('Старий і море', 'Ернест Гемінґвей')]
    const c = collection('x', 'X', [entry('', 'Старий і море')])

    expect(matchCollection(c, catalog).books).toHaveLength(0)
  })
})

describe('collection matcher — tolerance', () => {
  it('case and punctuation are tolerated', () => {
    const catalog = [book('КОБЗАР!', 'Тарас Шевченко')]
    const c = collection('shev', 'Шевченківська премія', [entry('Тарас Шевченко', 'кобзар')])

    expect(matchCollection(c, catalog).books).toHaveLength(1)
  })

  it('diacritics are tolerated', () => {
    // Latin-script diacritics fold: «García» ≈ «Garcia», «Márquez» ≈ «Marquez».
    const catalog = [book('Cien anos de soledad', 'Gabriel Garcia Marquez')]
    const c = collection('nobel', 'Нобелівські лауреати', [entry('Gabriel García Márquez', 'Cien años de soledad')])

    expect(matchCollection(c, catalog).books).toHaveLength(1)
  })

  it('subtitle is cut like the union merge key', () => {
    // The union merges on the MergeKey rule (subtitle cut) — the matcher
    // must agree with it: «Енеїда: поема» is the same Work as «Енеїда».
    const catalog = [book('Енеїда: поема', 'Іван Котляревський')]
    const c = collection('x', 'X', [entry('Іван Котляревський', 'Енеїда')])

    expect(matchCollection(c, catalog).books).toHaveLength(1)
  })

  it('parenthetical annotation is trimmed', () => {
    const catalog = [book('Кобзар (повне видання)', 'Тарас Шевченко')]
    const c = collection('shev', 'Шевченківська премія', [entry('Тарас Шевченко', 'Кобзар')])

    expect(matchCollection(c, catalog).books).toHaveLength(1)
  })
})

describe('collection matcher — author-only fallback + empty', () => {
  it('title-less entry matches every catalog book of that author', () => {
    const catalog = [
      book('Маруся Чурай', 'Ліна Костенко'),
      book('Берестечко', 'Ліна Костенко'),
      book('Собор', 'Олесь Гончар'),
    ]
    const c = collection('x', 'X', [entry('Ліна Костенко')])

    const matched = matchCollection(c, catalog)

    expect(new Set(matched.books.map((b) => b.title))).toEqual(new Set(['Маруся Чурай', 'Берестечко']))
  })

  it('entry matching nothing contributes nothing', () => {
    const catalog = [book('Собор', 'Олесь Гончар')]
    const c = collection('x', 'X', [entry('Неіснуючий Автор', 'Неіснуюча книга')])

    expect(matchCollection(c, catalog).books).toHaveLength(0)
  })

  it('matchAll drops empty collections and keeps asset order', () => {
    const catalog = [book('Старий і море', 'Ернест Гемінґвей')]
    const nobel = collection('nobel', 'Нобелівські лауреати', [entry('Ернест Гемінґвей', 'Старий і море')])
    const empty = collection('booker', 'Букер', [entry('Янн Мартел', 'Життя Пі')])

    const matched = matchAllCollections([nobel, empty], catalog)

    expect(matched.map((m) => m.id)).toEqual(['nobel'])
    expect(matched[0]!.books).toHaveLength(1)
  })
})

describe('collection matcher — normalization unit pins', () => {
  it('normalize folds case punctuation and diacritics', () => {
    // Cyrillic stays Cyrillic, case-folded, punctuation dropped.
    expect(normalizeCollectionAuthor('Габрієль Гарсія Маркес')).toBe('габрієль гарсія маркес')
    // Latin diacritics fold to the base letters.
    expect(normalizeCollectionAuthor('Gabriel García Márquez')).toBe('gabriel garcia marquez')
    expect(normalizeCollectionTitle('Cien años de soledad!')).toBe('cien anos de soledad')
  })

  it('normalizeTitle cuts the subtitle', () => {
    expect(normalizeCollectionTitle('Енеїда: поема')).toBe('енеїда')
    expect(normalizeCollectionTitle('Кобзар (повне видання)')).toBe('кобзар')
  })

  it('entryMatches agrees with the public rule', () => {
    expect(entryMatches(entry('Тарас Шевченко', 'Кобзар'), book('Кобзар', 'Тарас Шевченко'))).toBe(true)
    expect(entryMatches(entry('Тарас Шевченко'), book('Гайдамаки', 'Тарас Шевченко'))).toBe(true)
    expect(entryMatches(entry('Тарас Шевченко', 'Кобзар'), book('Гайдамаки', 'Тарас Шевченко'))).toBe(false)
    expect(entryMatches(entry('', 'Кобзар'), book('Кобзар', 'Тарас Шевченко'))).toBe(false)
  })
})

describe('collection JSON decode', () => {
  const sample = `
    {
      "id": "nobel",
      "name": "Нобелівські лауреати",
      "sourceNote": "Лауреати Нобелівської премії.",
      "entries": [
        { "author": "Сельма Лагерлеф", "title": "Сага про Єсту Берлінга", "note": "1909" },
        { "author": "Томас Манн", "title": "Будденброки" },
        { "author": "Альбер Камю" }
      ]
    }
  `

  it('decodes the curated asset shape', () => {
    const list = decodeCollection(sample)

    expect(list).not.toBeNull()
    expect(list!.id).toBe('nobel')
    expect(list!.name).toBe('Нобелівські лауреати')
    expect(list!.sourceNote).toBe('Лауреати Нобелівської премії.')
    expect(list!.entries).toHaveLength(3)
    expect(list!.entries[0]).toEqual({ author: 'Сельма Лагерлеф', title: 'Сага про Єсту Берлінга', note: '1909' })
    expect(list!.entries[1]).toEqual({ author: 'Томас Манн', title: 'Будденброки' })
    expect(list!.entries[2]).toEqual({ author: 'Альбер Камю' })
  })

  it('string escapes are honoured', () => {
    const list = decodeCollection('{"id":"x","name":"A \\"книга\\"","sourceNote":"line\\nbreak","entries":[{"author":"Автор \\"Ім\'я\\" \\u041a"}]}')
    expect(list).not.toBeNull()
    expect(list!.name).toBe('A "книга"')
    expect(list!.sourceNote).toBe('line\nbreak')
    expect(list!.entries[0]!.author).toBe('Автор "Ім\'я" К')
  })

  it('empty entries array is valid', () => {
    expect(decodeCollection('{"id":"e","name":"Порожня","entries":[]}')!.entries).toHaveLength(0)
  })

  it('missing entries field is valid and empty', () => {
    expect(decodeCollection('{"id":"e","name":"Без записів"}')!.entries).toHaveLength(0)
  })

  it('malformed or invalid input decodes to null', () => {
    expect(decodeCollection('not json')).toBeNull()
    expect(decodeCollection('{"id": 1, "name": "x"}')).toBeNull() // wrong value type
    expect(decodeCollection('{"id":"x"}')).toBeNull() // missing name
    expect(decodeCollection('{"name":"x"}')).toBeNull() // missing id
    expect(decodeCollection('{"id":"","name":"x"}')).toBeNull() // blank id
    expect(decodeCollection('{"id":"x","name":"y","entries":[{"author":""}]}')).toBeNull() // blank author
    expect(decodeCollection('{"id":"x","name":"y","entries":"no"}')).toBeNull() // entries not an array
    expect(decodeCollection('{"id":"x","name":"y","entries":[{"author":"A"},"bad"]}')).toBeNull()
    expect(decodeCollection('{"id":"x","name":"y"} trailing')).toBeNull() // trailing junk
  })

  it('unicode text round-trips', () => {
    const list = decodeCollection('{"id":"shev","name":"Шевченківська премія","sourceNote":"Національна премія України імені Тараса Шевченка","entries":[{"author":"Ліна Костенко","title":"Маруся Чурай"}]}')
    expect(list).not.toBeNull()
    expect(list!.name).toBe('Шевченківська премія')
    expect(list!.entries[0]!.title).toBe('Маруся Чурай')
  })
})