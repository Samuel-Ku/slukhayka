import { normalizePerson as webNormalizePerson, normalizeTitle as webNormalizeTitle } from '../sync/edition'

/**
 * W3.1 (#557) — the web port of Android's `CollectionJson` + `CollectionMatcher`
 * (spec-16 T1): the curated collection assets decoded strictly, then matched
 * locally against the catalog union (ADR-0015: transparent, deterministic,
 * offline-capable — a collection is data, not a network service).
 *
 * A match requires at least author agreement:
 *  - entry with a title → BOTH normalized author AND title agree;
 *  - title-less entry (author-only fallback) → every catalog book of that
 *    author belongs to the collection;
 *  - a blank author never matches anything.
 *
 * An entry that matches nothing contributes nothing — the matcher never
 * fabricates a card. `matchAll` drops the empty collections (block hiding),
 * exactly like Android.
 *
 * Normalization reuses the SAME MergeKey rule the web union merges on
 * (`sync/edition.ts` — case-fold, punctuation strip, whitespace collapse,
 * subtitle cut) and adds the two collection-specific folds on top:
 *  - diacritics: NFKD decomposition + combining-mark drop, so «García» ≈
 *    «Garcia» — but ONLY on non-Cyrillic characters (Ukrainian ї/й must
 *    never fold: «Енеїда» stays «Енеїда»);
 *  - trailing parenthetical annotation trim («Кобзар (повне видання)» →
 *    «Кобзар»), repeated for stacked groups.
 */

/** One curated claim: an author (always) plus optional title and note. */
export interface CollectionEntry {
  author: string
  title?: string
  note?: string
}

/** One curated collection asset: stable id, display name, source note, entries. */
export interface CollectionList {
  id: string
  name: string
  sourceNote: string
  entries: CollectionEntry[]
}

/** One collection with only its matched catalog cards. */
export interface MatchedCollection<T extends { title: string; author: string }> {
  id: string
  name: string
  sourceNote: string
  books: T[]
}

/**
 * Strict decoder for the curated asset shape (the web's JSON.parse is the
 * strict equivalent of Android's MiniJson; both reject trailing junk):
 * ```
 * { "id": "nobel", "name": "…", "sourceNote": "…", "entries": [
 *     { "author": "…", "title": "…", "note": "…" }, { "author": "…" } ] }
 * ```
 * Returns `null` on anything malformed — a bad asset contributes no
 * collection, never a crash.
 */
export function decodeCollection(text: string): CollectionList | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return null
  }
  return validateCollection(parsed)
}

/** Validates an already-parsed asset object (the imported JSON modules). */
export function validateCollection(parsed: unknown): CollectionList | null {
  if (typeof parsed !== 'object' || parsed === null) return null
  const obj = parsed as Record<string, unknown>
  const id = obj['id']
  const name = obj['name']
  if (typeof id !== 'string' || id.trim() === '' || typeof name !== 'string' || name.trim() === '') return null
  const sourceNote = typeof obj['sourceNote'] === 'string' ? obj['sourceNote'] : ''
  const rawEntries = obj['entries']
  if (rawEntries === undefined || rawEntries === null) {
    return { id, name, sourceNote, entries: [] }
  }
  if (!Array.isArray(rawEntries)) return null
  const entries: CollectionEntry[] = []
  for (const raw of rawEntries) {
    // Strict: any malformed entry invalidates the whole collection — a
    // curated asset either parses fully or is absent.
    if (typeof raw !== 'object' || raw === null) return null
    const entry = raw as Record<string, unknown>
    const author = entry['author']
    if (typeof author !== 'string' || author.trim() === '') return null
    entries.push({
      author,
      title: typeof entry['title'] === 'string' ? entry['title'] : undefined,
      note: typeof entry['note'] === 'string' ? entry['note'] : undefined,
    })
  }
  return { id, name, sourceNote, entries }
}

/** NFKD decomposition, combining marks dropped — only on NON-Cyrillic chars. */
function dropDiacritics(value: string): string {
  let out = ''
  for (const ch of value) {
    const code = ch.codePointAt(0)!
    if (code >= 0x0400 && code <= 0x052f) {
      out += ch // Cyrillic blocks untouched («Енеїда» stays «Енеїда»)
      continue
    }
    const decomposed = ch.normalize('NFKD')
    for (const d of decomposed) {
      const dc = d.codePointAt(0)!
      if (dc < 0x0300 || dc > 0x036f) out += d // drop combining marks
    }
  }
  return out
}

/** Cuts one trailing parenthetical group, repeatedly («Кобзар (повне видання) (т. 1)» → «Кобзар»). */
function stripTrailingParenthetical(value: string): string {
  let result = value
  for (;;) {
    const trimmed = result.trimEnd()
    const open = trimmed.lastIndexOf('(')
    if (open <= 0 || !trimmed.endsWith(')')) return trimmed
    result = trimmed.slice(0, open).trimEnd()
  }
}

/** Normalizes a title: annotation trim + MergeKey title rule + diacritics. */
export function normalizeCollectionTitle(title: string): string {
  return dropDiacritics(webNormalizeTitle(stripTrailingParenthetical(title)))
}

/** Normalizes an author: annotation trim + MergeKey person rule + diacritics. */
export function normalizeCollectionAuthor(author: string): string {
  return dropDiacritics(webNormalizePerson(stripTrailingParenthetical(author)))
}

/** One entry matched against one catalog card. */
export function entryMatches(entry: CollectionEntry, book: { title: string; author: string }): boolean {
  if (entry.author.trim() === '') return false
  if (normalizeCollectionAuthor(book.author) !== normalizeCollectionAuthor(entry.author)) return false
  const entryTitle = entry.title?.trim()
  if (!entryTitle) return true // author-only fallback
  return normalizeCollectionTitle(book.title) === normalizeCollectionTitle(entryTitle)
}

/** Matches one collection against the catalog union. */
export function matchCollection<T extends { title: string; author: string }>(
  collection: CollectionList,
  catalog: T[],
): MatchedCollection<T> {
  const books = catalog.filter((book) => collection.entries.some((entry) => entryMatches(entry, book)))
  return { id: collection.id, name: collection.name, sourceNote: collection.sourceNote, books }
}

/** Matches every collection and drops the empty ones (block hiding). */
export function matchAllCollections<T extends { title: string; author: string }>(
  collections: CollectionList[],
  catalog: T[],
): MatchedCollection<T>[] {
  return collections.map((collection) => matchCollection(collection, catalog)).filter((matched) => matched.books.length > 0)
}