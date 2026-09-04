import type { SourceId } from './types'

export const SOURCE_ORDER: readonly SourceId[] = [
  'fourread',
  'sound-books',
  'sluhayua',
  'sluhay',
  'audiobook-mp3',
  'lihtar',
  'librivox',
]

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
}> = {
  fourread: { label: '4read', homeUrl: 'https://4read.org', browserSessionRequired: true, contentLanguage: 'uk' },
  'sound-books': { label: 'Sound-Books', homeUrl: 'https://sound-books.net', browserSessionRequired: false, contentLanguage: 'uk' },
  sluhayua: { label: 'Sluhay UA', homeUrl: 'https://sluhay.com.ua', browserSessionRequired: false, contentLanguage: 'uk' },
  sluhay: { label: 'Sluhay', homeUrl: 'https://sluhay.com', browserSessionRequired: false, contentLanguage: 'uk' },
  'audiobook-mp3': { label: 'Audio-MP3', homeUrl: 'https://audiobook-mp3.com', browserSessionRequired: false, contentLanguage: 'uk' },
  lihtar: { label: 'Lihtar', homeUrl: 'https://lihtar.in.ua', browserSessionRequired: false, contentLanguage: 'uk' },
  librivox: { label: 'LibriVox', homeUrl: 'https://librivox.org', browserSessionRequired: false, contentLanguage: 'en' },
}

/** The content language a source's cards default to when a card carries none. */
export function sourceContentLanguage(sourceId: SourceId): string {
  return SOURCE_METADATA[sourceId].contentLanguage
}
