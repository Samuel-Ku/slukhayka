import sources from '../../../sources.json'
import type { SourceId } from './types'

/**
 * ADR-0038 — worker-local key aliases of the canonical persisted ids
 * (`sources.json`). Kept until the consumer migration that changes call
 * sites to the domain ids; the parity test holds the alias map.
 */
export const WEB_KEY_TO_ID: Record<string, string> = {
  fourread: '4read',
  'sound-books': 'soundbooks',
  'audiobook-mp3': 'audiobookmp3',
}

export const ID_TO_WEB_KEY: Record<string, SourceId> = {
  '4read': 'fourread',
  soundbooks: 'sound-books',
  audiobookmp3: 'audiobook-mp3',
}

/** The ids the WEB client actually serves (local/telegram/sluhayknigi are not worker sources). */
const WEB_IDS = new Set([
  '4read',
  'soundbooks',
  'audiobookmp3',
  'sluhayua',
  'sluhay',
  'lihtar',
  'librivox',
  'audiobookcoua',
  'chytaylo',
  'knigionline',
  'chitaka',
])

const factsById = new Map(sources.sources.map((source) => [source.id, source]))

function factsOf(webId: SourceId) {
  return factsById.get(WEB_KEY_TO_ID[webId] ?? webId)
}

/**
 * The registry order (ADR-0038): every web source in the carrier's `order`.
 */
export const SOURCE_ORDER: readonly SourceId[] = sources.sources
  .filter((source) => WEB_IDS.has(source.id))
  .map((source) => ID_TO_WEB_KEY[source.id] ?? (source.id as SourceId))

export const SOURCE_METADATA: Record<SourceId, {
  label: string
  homeUrl: string
  browserSessionRequired: boolean
  /**
   * Spec-45 (#405) — BCP-47 content language of the source's catalogue.
   * One owner per source: a card may override it per book (a future
   * mixed-language source), but the merge defaults to this value, so a
   * source never has to tag every parse site. '' = unknown.
   */
  contentLanguage: string
}> = Object.fromEntries(
  SOURCE_ORDER.map((webId) => {
    const facts = factsOf(webId)!
    return [
      webId,
      {
        label: facts.displayName,
        // ADR-0038: trailing-slash normalization rides the consumer migration.
        homeUrl: (facts.homeUrl ?? '').replace(/\/$/, ''),
        // ADR-0038 §4: session-bound-ness is platform knowledge — sluhay
        // needs the Android session while the worker fetches it server-side.
        browserSessionRequired: webId === 'sluhay' ? false : facts.accessMode === 'BROWSER',
        contentLanguage: facts.contentLanguage ?? '',
      },
    ]
  }),
) as Record<SourceId, {
  label: string
  homeUrl: string
  browserSessionRequired: boolean
  contentLanguage: string
}>

/** The content language a source's cards default to when a card carries none. */
export function sourceContentLanguage(sourceId: SourceId): string {
  return SOURCE_METADATA[sourceId].contentLanguage
}
