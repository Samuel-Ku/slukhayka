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
import { buildBookDetail, fourread } from './adapters/fourread'
import { soundBooksAdapter, parseM3u, playlistUrlOf as sbPlaylistUrl } from './adapters/soundbooks'
import { sluhayAdapter, parsePlayerjsPlaylist as sluhayPlaylist, playlistUrlOf as sluhayPlaylistUrl } from './adapters/sluhay'
import { audiobookMp3Adapter, parsePlayerjsPlaylist as abMp3Playlist, playlistUrlOf as abMp3PlaylistUrl } from './adapters/audiobookmp3'
import { lihtarAdapter, parsePlayerPage as lihtarPlayer, playerUrlOf as lihtarPlayerUrl } from './adapters/lihtar'
import { sluhayuaAdapter, chapterCountOf, chaptersFromPlayResponses } from './adapters/sluhayua'
import { archiveSearchUrl, buildBookDetail as buildLibrivoxDetail, catalogUrlOf, identifierOf as librivoxIdentifierOf, librivoxAdapter } from './adapters/librivox'

export interface SourceEntry {
  readonly adapter: SourceAdapter
  readonly allowedHosts: readonly string[]
  /**
   * The catalogue feed's starting URL. Defaults to the adapter's baseUrl;
   * a source whose feed is a JSON API endpoint (librivox) declares it here
   * so the work-feed route never parses its HTML homepage.
   */
  readonly catalogUrl?: string
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
  /** Search URL builder; absent = source has no usable search endpoint. */
  readonly searchUrl?: (query: string) => string
  /** Extra headers the source's search endpoint requires (e.g. XHR). */
  readonly searchHeaders?: Record<string, string>
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
  detail: BookDetail | null,
  html: string,
  playlistUrlOf: (html: string) => string | null,
  parse: (body: string) => Chapter[],
  fetchText: (url: string) => Promise<string | null>,
): Promise<BookDetail | null> {
  if (!detail) return null
  const url = playlistUrlOf(html)
  if (!url) return detail
  const body = await fetchText(url)
  if (!body) return detail
  const chapters = parse(body)
  return chapters.length ? { ...detail, chapters } : detail
}


/** parseBookPage за контрактом може не впізнати сторінку — null чесно повертається нагору. */
function soundBooksDetail(html: string, pageUrl: string): BookDetail | null {
  return soundBooksAdapter.parseBookPage(html, pageUrl)
}

function sluhayDetail(html: string, pageUrl: string): BookDetail | null {
  return sluhayAdapter.parseBookPage(html, pageUrl)
}

function audiobookMp3Detail(html: string, pageUrl: string): BookDetail | null {
  return audiobookMp3Adapter.parseBookPage(html, pageUrl)
}

function lihtarDetail(html: string, pageUrl: string): BookDetail | null {
  return lihtarAdapter.parseBookPage(html, pageUrl)
}

function searchUrlFourread(query: string): string {
  return `https://4read.org/index.php?do=search&subaction=search&story=${encodeURIComponent(query)}`
}

function searchUrlSluhayua(query: string): string {
  return `https://sluhay.com.ua/find/allcards?search=${encodeURIComponent(query)}&page=1`
}

export const REGISTRY: Record<SourceId, SourceEntry> = {
  fourread: {
    adapter: fourread,
    allowedHosts: ['4read.org'],
    buildBook: (html, pageUrl, fetchText) => buildBookDetail(html, pageUrl, fetchText),
    searchUrl: searchUrlFourread,
  },
  'sound-books': {
    adapter: soundBooksAdapter,
    allowedHosts: ['sound-books.net', 'arch.sound-books.net'],
    buildBook: async (html, pageUrl, fetchText) =>
      await expandViaPlaylist(soundBooksDetail(html, pageUrl), html, sbPlaylistUrl, parseM3u, fetchText),
  },
  sluhay: {
    adapter: sluhayAdapter,
    // redirectto.cc — CDN плейлістів sluhay (позначено в еталоні).
    allowedHosts: ['sluhay.com', 'redirectto.cc'],
    buildBook: async (html, pageUrl, fetchText) =>
      await expandViaPlaylist(sluhayDetail(html, pageUrl), html, sluhayPlaylistUrl, sluhayPlaylist, fetchText),
  },
  'audiobook-mp3': {
    adapter: audiobookMp3Adapter,
    allowedHosts: ['audiobook-mp3.com'],
    buildBook: async (html, pageUrl, fetchText) =>
      await expandViaPlaylist(audiobookMp3Detail(html, pageUrl), html, abMp3PlaylistUrl, abMp3Playlist, fetchText),
  },
  lihtar: {
    adapter: lihtarAdapter,
    allowedHosts: ['lihtar.in.ua'],
    buildBook: async (html, pageUrl, fetchText) => {
      const detail = lihtarDetail(html, pageUrl)
      if (!detail) return null
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
    searchUrl: searchUrlSluhayua,
    searchHeaders: { 'x-requested-with': 'XMLHttpRequest' },
    buildBook: async (html, pageUrl, fetchText) => {
      const detail = sluhayuaAdapter.parseBookPage(html, pageUrl)
      if (!detail) return null
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
  librivox: {
    adapter: librivoxAdapter,
    // archive.org is the SAME source's mirror transport (search + metadata
    // + audio), never a second catalogue row.
    allowedHosts: ['librivox.org', 'archive.org'],
    catalogUrl: catalogUrlOf(),
    searchUrl: (query) => archiveSearchUrl(query),
    buildBook: async (_html, pageUrl, fetchText) => {
      const identifier = librivoxIdentifierOf(pageUrl)
      if (identifier === null) return null
      const body = await fetchText(`https://archive.org/metadata/${identifier}`)
      if (body === null) return null
      return buildLibrivoxDetail(body, pageUrl)
    },
  },
}


export function sourceEntry(id: string): SourceEntry | null {
  return (REGISTRY as Record<string, SourceEntry | undefined>)[id] ?? null
}

export function mayFetch(entry: SourceEntry, url: string): boolean {
  return hostAllowed(entry.allowedHosts, url)
}
