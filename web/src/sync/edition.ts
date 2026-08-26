import { sha256 } from 'js-sha256'

/**
 * Port of app/src/main/java/com/slukhayka/audiobooks/data/merge/MergeKey.kt
 * and EditionId.forBook — deterministic so phone and browser compute the
 * SAME document key for the SAME rendition. Pure, no DOM, no async.
 */

function normalize(input: string): string {
  return input
    .trim()
    .toLowerCase()
    .replace(/[^\p{L}\p{N} ]/gu, '')
    .replace(/\s+/g, ' ')
    .trim()
}

function normalizeTitleRaw(title: string): string {
  // Mirror Kotlin MetadataAssertions.normalizeTitle scrub + MergeKey subtitle cut.
  // Strip the curated SEO suffix that Android scrubs before hashing.
  const scrubbed = title.replace(/\s*-\s*аудіокниги українською\s*$/i, '').trim()
  const withoutSubtitle = scrubbed.split(':')[0]!.split('—')[0]!.split('–')[0]!.split(' - ')[0]!
  return normalize(withoutSubtitle)
}

export function normalizeTitle(title: string): string {
  return normalizeTitleRaw(title)
}

export function normalizePerson(name: string): string {
  return normalize(name)
}

export function mergeKeyFor(title: string, author: string): string {
  const base = [normalizeTitle(title), normalizePerson(author)].filter((s) => s !== '')
  if (base.length < 2) return ''
  return base.join('|')
}

function sha256Hex(input: string): string {
  return sha256(input)
}

/**
 * ADR-0007 + ADR-0010 — deterministic Edition id.
 * hash(mergeKey|narrator|language).take(24) or hash(bookId|narrator|language) when mergeKey blank.
 */
export function editionIdFor(
  mergeKey: string,
  bookId: string,
  narrator = '',
  language = '',
): string {
  const base = `${mergeKey || bookId}\u0000${narrator}\u0000${language}`
  // Kotlin uses UTF-8 bytes; js-sha256 hashes the UTF-16 string as UTF-8 by default.
  return sha256Hex(base).slice(0, 24)
}
