import type { CatalogCard, SourceId, UnifiedWork, UnifiedWorkPage } from './types'

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
}

/** The same bibliographic identity as Android's MergeKey: title + author only. */
export function mergeKeyOf(card: Pick<CatalogCard, 'title' | 'author'>): string {
  const normalize = (value: string): string => value.toLocaleLowerCase('uk-UA').replace(/\s+/g, ' ').trim()
  return `${normalize(card.title)}|${normalize(card.author)}`
}

function editionKeyOf(card: CatalogCard): string {
  return `${mergeKeyOf(card)}|${card.narrator?.toLocaleLowerCase('uk-UA').trim() || 'unknown-narrator'}`
}

/**
 * Pure bounded Work projection. A Source failure is represented by absence
 * from input, never by a fabricated card. Sources of one Edition retain
 * their input/capability order; another narrator becomes a distinct Edition.
 */
export function mergeWorkFeed(inputs: SourceCards[], offset = 0, limit = 30): UnifiedWorkPage {
  const works = new Map<string, UnifiedWork>()
  for (const { sourceId, cards } of inputs) {
    for (const card of cards) {
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
      const editionKey = editionKeyOf(card)
      let edition = work.editions.find((candidate) => candidate.id === editionKey)
      if (!edition) {
        edition = { id: editionKey, narrator: card.narrator, durationSeconds: card.durationSeconds, sources: [] }
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
