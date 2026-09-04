import type { CatalogCard, SourceId, UnifiedEdition, UnifiedSource, UnifiedWork, UnifiedWorkPage } from './types'
import { sourceContentLanguage } from './sourceMetadata'
import { normalizeLanguage } from './language'

export type SourceCards = { sourceId: SourceId; cards: CatalogCard[] }

// Browser-only 4read comes last for automatic source choice. This is a
// capability preference *inside the selected Edition*, never a reason to
// replace that Edition with a different narrator.
const SOURCE_PRIORITY: Record<SourceId, number> = {
  'sound-books': 0,
  sluhayua: 1,
  'audiobook-mp3': 2,
  lihtar: 3,
  sluhay: 4,
  fourread: 5,
  librivox: 6,
}

function isDirect(source: UnifiedSource): boolean {
  return source.sourceId !== 'fourread'
}

/** #458: deterministic Edition choice; another Edition is never a fallback. */
export function rankEditionsForPlayback(editions: UnifiedEdition[]): UnifiedEdition[] {
  return editions
    .map((edition, index) => ({ edition, index }))
    .sort((left, right) => {
      const tuple = (edition: UnifiedEdition): [number, number, number, number, number] => [
        edition.sources.some((source) => isDirect(source) && source.availability === 'available') ? 1 : 0,
        edition.isComplete === true ? 1 : 0,
        edition.narrator?.trim() ? 1 : 0,
        (edition.chapterCount ?? 0) > 0 ? 1 : 0,
        Math.max(edition.verifiedAt ?? 0, ...edition.sources.map((source) => source.verifiedAt ?? 0)),
      ]
      const a = tuple(left.edition)
      const b = tuple(right.edition)
      for (let index = 0; index < a.length; index += 1) {
        if (a[index] !== b[index]) return b[index] - a[index]
      }
      return left.index - right.index || left.edition.id.localeCompare(right.edition.id)
    })
    .map(({ edition }) => edition)
}

/** The same bibliographic identity as Android's MergeKey: title + author only. */
export function mergeKeyOf(card: Pick<CatalogCard, 'title' | 'author'>): string {
  const normalize = (value: string): string => value.toLocaleLowerCase('uk-UA').replace(/\s+/g, ' ').trim()
  return `${normalize(card.title)}|${normalize(card.author)}`
}

function editionKeyOf(card: CatalogCard, language: string): string {
  // Spec-45 (#405) — the language is rendition identity (Android ADR-0010 +
  // #405): an en and a uk narration of one Work are two Editions, so the key
  // mirrors the Android EditionId formula mergeKey|narrator|language.
  const narrator = card.narrator?.toLocaleLowerCase('uk-UA').trim() || 'unknown-narrator'
  return language ? `${mergeKeyOf(card)}|${narrator}|${language}` : `${mergeKeyOf(card)}|${narrator}`
}

/**
 * Pure bounded Work projection. A Source failure is represented by absence
 * from input, never by a fabricated card. Sources of one Edition retain
 * their input/capability order; another narrator becomes a distinct Edition.
 */
export function mergeWorkFeed(inputs: SourceCards[], offset = 0, limit = 30): UnifiedWorkPage {
  const works = new Map<string, UnifiedWork>()
  for (const { sourceId, cards } of inputs) {
    // One source = one content language (a per-card claim may still override).
    const sourceLanguage = sourceContentLanguage(sourceId)
    for (const card of cards) {
      // Spec-45 (#405) R8 (#515): the language is normalized to its canonical
      // BCP-47 tag BEFORE identity construction and before anything is
      // returned downstream — English, en and en-US are one Edition language;
      // uk and en stay distinct Editions of one Work; an unparseable claim
      // falls back to the (already canonical) source language, and unknown
      // stays absent. mergeKeyOf remains purely bibliographic.
      const language = (normalizeLanguage(card.language) ?? sourceLanguage) || ''
      const mergeKey = mergeKeyOf(card)
      const work = works.get(mergeKey) ?? {
        id: mergeKey,
        mergeKey,
        title: card.title,
        author: card.author,
        coverImageUrl: card.coverImageUrl,
        editions: [],
      }
      if (!works.has(mergeKey)) works.set(mergeKey, work)
      const editionKey = editionKeyOf(card, language)
      let edition = work.editions.find((candidate) => candidate.id === editionKey)
      if (!edition) {
        edition = {
          id: editionKey,
          narrator: card.narrator,
          language: language || undefined,
          durationSeconds: card.durationSeconds,
          sources: [],
        }
        work.editions.push(edition)
      }
      if (!edition.sources.some((candidate) => candidate.sourceId === sourceId && candidate.url === card.url)) {
        edition.sources.push({ sourceId, url: card.url })
        edition.sources.sort((left, right) =>
          SOURCE_PRIORITY[left.sourceId] - SOURCE_PRIORITY[right.sourceId] ||
          left.sourceId.localeCompare(right.sourceId) ||
          left.url.localeCompare(right.url),
        )
      }
    }
  }
  const all = [...works.values()]
  const start = Math.max(0, offset)
  const size = Math.max(1, Math.min(limit, 30))
  const page = all.slice(start, start + size)
  return { works: page, nextCursor: start + size < all.length ? String(start + size) : undefined }
}
