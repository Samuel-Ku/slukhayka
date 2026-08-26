/**
 * spec-43/T3+T4 — the Worker-side registry of every verified Source behind
 * the adapter seam. Two jobs beyond lookup:
 *
 *  1. **Host allowlist** — the transport only ever fetches URLs whose host
 *     belongs to that source's own infrastructure (the SSRF guard; playlist
 *     CDNs are listed explicitly per source). An off-site URL refuses the
 *     fetch instead of going anywhere.
 *  2. **Chapter enrichment** — several sources keep their real stream lists
 *     one fetch away (.m3u playlists, playerjs JSON, lihtar's player page,
 *     sluhayua's /play loop). Each entry declares how its parsed book page
 *     becomes a final Chapter[]; every helper it calls is a pure export of
 *     the adapter module.
 */
import type { BookDetail, Chapter, SourceAdapter, SourceId } from './types'
import { fourread } from './adapters/fourread'
import { soundBooksAdapter, parseM3u, playlistUrlOf as sbPlaylistUrl } from './adapters/soundbooks'
import { sluhayAdapter, parsePlayerjsPlaylist as sluhayPlaylist, playlistUrlOf as sluhayPlaylistUrl } from './adapters/sluhay'
import { audiobookMp3Adapter, parsePlayerjsPlaylist as abMp3Playlist, playlistUrlOf as abMp3PlaylistUrl } from './adapters/audiobookmp3'
import { lihtarAdapter, parsePlayerPage as lihtarPlayer, playerUrlOf as lihtarPlayerUrl } from './adapters/lihtar'
import { sluhayuaAdapter, chapterCountOf, chaptersFromPlayResponses } from './adapters/sluhayua'

export interface SourceEntry {
  readonly adapter: SourceAdapter
  readonly allowedHosts: readonly string[]
  /**
   * Builds the FULL book detail (with enriched chapters) from an already
   * fetched page body. `fetchText` is the guarded transport; it may return
   * null on any failure and callers degrade honestly.
   */
  readonly buildBook: (
    html: string,
    pageUrl: string,
    fetchText: (url: string) => Promise<string | null>,
  ) => Promise<BookDetail | null>
}

function hostAllowed(allowed: readonly string[], url: string): boolean {
  try {
    const host = new URL(url).hostname
    return allowed.some((pattern) => host === pattern || host.endsWith(`.${pattern}`))
  } catch {
    return false
  }
}

async function expandViaPlaylist(
  detail: BookDetail,
  html: string,
  playlistUrlOf: (html: string) => string | null,
  parse: (body: string) => Chapter[],
  fetchText: (url: string) => Promise<string | null>,
): Promise<BookDetail> {
  const url = playlistUrlOf(html)
  if (!url) return detail
  const body = await fetchText(url)
  if (!body) return detail
  const chapters = parse(body)
  return chapters.length ? { ...detail, chapters } : detail
}

export const REGISTRY: Record<SourceId, SourceEntry> = {
  fourread: {
    adapter: fourread,
    allowedHosts: ['4read.org'],
    buildBook: (html, pageUrl, fetchText) => buildFourreadBook(html, pageUrl, fetchText),
  },
  'sound-books': {
    adapter: soundBooksAdapter,
    allowedHosts: ['sound-books.net', 'arch.sound-books.net'],
    buildBook: async (html, pageUrl, fetchText) =>
      expandViaPlaylist(soundBooksAdapter.parseBookPage(html, pageUrl)!, html, sbPlaylistUrl, parseM3u, fetchText),
  },
  sluhay: {
    adapter: sluhayAdapter,
    // redirectto.cc — CDN плейлістів sluhay (позначено в еталоні).
    allowedHosts: ['sluhay.com', 'redirectto.cc'],
    buildBook: async (html, pageUrl, fetchText) =>
      expandViaPlaylist(sluhayAdapter.parseBookPage(html, pageUrl)!, html, sluhayPlaylistUrl, sluhayPlaylist, fetchText),
  },
  'audiobook-mp3': {
    adapter: audiobookMp3Adapter,
    allowedHosts: ['audiobook-mp3.com'],
    buildBook: async (html, pageUrl, fetchText) =>
      expandViaPlaylist(audiobookMp3Adapter.parseBookPage(html, pageUrl)!, html, abMp3PlaylistUrl, abMp3Playlist, fetchText),
  },
  lihtar: {
    adapter: lihtarAdapter,
    allowedHosts: ['lihtar.in.ua'],
    buildBook: async (html, pageUrl, fetchText) => {
      const detail = lihtarAdapter.parseBookPage(html, pageUrl)!
      const playerUrl = lihtarPlayerUrl(html)
      if (!playerUrl) return detail
      const body = await fetchText(playerUrl)
      if (!body) return detail
      const chapters = lihtarPlayer(body, detail.title)
      return chapters.length ? { ...detail, chapters } : detail
    },
  },
  sluhayua: {
    adapter: sluhayuaAdapter,
    allowedHosts: ['sluhay.com.ua'],
    buildBook: async (html, pageUrl, fetchText) => {
      const detail = sluhayuaAdapter.parseBookPage(html, pageUrl)!
      const bookId = pageUrl.split('/').pop()!.split(':')[0] ?? ''
      const chapterCount = chapterCountOf(html)
      if (!/^\d+$/.test(bookId) || chapterCount <= 0) return detail
      const responses: string[] = []
      for (let fileId = 0; fileId < chapterCount; fileId++) {
        const body = await fetchText(`https://sluhay.com.ua/play?bookId=${bookId}&fileId=${fileId}`)
        if (body === null) break
        responses.push(body)
      }
      const chapters = chaptersFromPlayResponses(responses)
      return chapters.length ? { ...detail, chapters } : detail
    },
  },
}

/** Порт `WebViewHtmlParser.parse` для 4read: плейлісти та iframe через resolve. */
async function buildFourreadBook(
  html: string,
  pageUrl: string,
  fetchText: (url: string) => Promise<string | null>,
): Promise<BookDetail | null> {
  const { buildBookDetail } = await import('./adapters/fourread')
  return buildBookDetail(html, pageUrl, fetchText)
}

export function sourceEntry(id: string): SourceEntry | null {
  return (REGISTRY as Record<string, SourceEntry | undefined>)[id] ?? null
}

export function mayFetch(entry: SourceEntry, url: string): boolean {
  return hostAllowed(entry.allowedHosts, url)
}
