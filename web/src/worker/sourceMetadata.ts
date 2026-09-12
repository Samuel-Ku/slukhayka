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
  // Spec-47 T6: ukrainianaudiobooks is a browser-gated web source too.
  'ukrainianaudiobooks',
])

const factsById = new Map(sources.sources.map((source) => [source.id, source]))

function factsOf(webId: SourceId) {
  return factsById.get(WEB_KEY_TO_ID[webId] ?? webId)
}

/**
 * The scam sources (ADR-0038 carrier fact): 4read's clean-client audio is a
 * 52-second artefact, never the book. The worker never serves them — no
 * catalog, search, feed or book fetch — and the HTTP host allowlist drops
 * their hosts too.
 */
const SCAM_IDS: ReadonlySet<string> = new Set(
  sources.sources
    .filter((source) => (source as { scam?: boolean }).scam === true)
    .map((source) => source.id),
)

/** Whether a web key (fourread) or a canonical id (4read) is a scam source. */
export function isScamSourceKey(webKey: string): boolean {
  return SCAM_IDS.has(WEB_KEY_TO_ID[webKey] ?? webKey)
}

/** Every web source the worker knows, in the carrier's `order`. */
export const ALL_WEB_ORDER: readonly SourceId[] = sources.sources
  .filter((source) => WEB_IDS.has(source.id))
  .map((source) => ID_TO_WEB_KEY[source.id] ?? (source.id as SourceId))

/**
 * The registry order (ADR-0038): every SERVED web source in the carrier's
 * `order` — scam sources are excluded.
 */
export const SOURCE_ORDER: readonly SourceId[] = ALL_WEB_ORDER
  .filter((webId) => !isScamSourceKey(webId))

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
  ALL_WEB_ORDER.map((webId) => {
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
