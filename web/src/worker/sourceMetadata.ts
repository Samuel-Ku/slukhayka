import type { SourceId } from './types'

export const SOURCE_ORDER: readonly SourceId[] = [
  'fourread',
  'sound-books',
  'sluhayua',
  'sluhay',
  'audiobook-mp3',
  'lihtar',
]

export const SOURCE_METADATA: Record<SourceId, {
  label: string
  homeUrl: string
  browserSessionRequired: boolean
}> = {
  fourread: { label: '4read', homeUrl: 'https://4read.org', browserSessionRequired: true },
  'sound-books': { label: 'Sound-Books', homeUrl: 'https://sound-books.net', browserSessionRequired: false },
  sluhayua: { label: 'Sluhay UA', homeUrl: 'https://sluhay.com.ua', browserSessionRequired: false },
  sluhay: { label: 'Sluhay', homeUrl: 'https://sluhay.com', browserSessionRequired: false },
  'audiobook-mp3': { label: 'Audio-MP3', homeUrl: 'https://audiobook-mp3.com', browserSessionRequired: false },
  lihtar: { label: 'Lihtar', homeUrl: 'https://lihtar.in.ua', browserSessionRequired: false },
}
