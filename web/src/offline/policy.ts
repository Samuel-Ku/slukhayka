/**
 * W6.2 (#593) — the offline streaming-cache policy, pure and tested (the
 * AC's «тести політики кешування»): WHAT is cached (only full relay
 * responses of audio chapters — never partial Range responses, never
 * other origins), WHERE it lives (one bounded cache), and HOW a cached
 * chapter answers a Range request offline (a synthesized 206, because a
 * resumed <audio> always asks for `bytes=N-`).
 *
 * Honesty rule (ADR-0014): the cache is a FALLBACK for the network, never
 * a claim of freshness. A chapter is cached only after it was actually
 * listened to (the primer fires on the playing event); the UI derives its
 * badges from this same cache, so «у кеші» can never lie.
 *
 * ADR-0024: direct-first audio and the relay fallback stay untouched —
 * the SW intercepts requests network-first and only serves the cache when
 * the network itself failed.
 */

/** One bounded cache for all primed audio chapters. */
export const AUDIO_CACHE_NAME = 'audio-streams-v1'

/** «Нещодавно слухане»: keep this many chapters, evict the oldest. */
export const AUDIO_CACHE_MAX_ENTRIES = 12

/**
 * Resolves a URL the way the Cache API itself does: relative keys (the dev
 * relay `/api/audio?u=…`) resolve against the document origin; in the SW
 * every request is already absolute.
 */
function resolveUrl(url: string | URL): URL | null {
  if (url instanceof URL) return url
  try {
    return new URL(String(url))
  } catch {
    try {
      const base = typeof location !== 'undefined' ? location.href : 'https://slukhayka.invalid/'
      return new URL(String(url), base)
    } catch {
      return null
    }
  }
}

/**
 * The relay route: `relayUrlFor` builds `${relayBase}/audio?u=<stream>`.
 * Pathname ends with `/audio` in both the dev proxy (/api/audio) and the
 * production worker (/audio).
 */
export function isRelayAudioUrl(url: string | URL): boolean {
  const parsed = resolveUrl(url)
  return parsed !== null && parsed.pathname.endsWith('/audio')
}

/** The direct stream URL behind a relay URL (`u` param), or null. */
export function relayTargetOf(url: string | URL): string | null {
  const parsed = resolveUrl(url)
  if (parsed === null) return null
  const target = parsed.searchParams.get('u')
  return target !== null && target !== '' ? target : null
}

/**
 * The one cache-write rule: a FULL (no Range) successful relay response.
 * Range responses are the <audio> element's normal asks — they must never
 * be cached as fragments, or the honest full chapter would be lost.
 */
export function shouldCacheResponse(
  url: string | URL,
  status: number,
  rangeHeader: string | null,
): boolean {
  if (!isRelayAudioUrl(url)) return false
  if (status !== 200) return false
  return rangeHeader === null
}

export interface ByteRange {
  start: number
  end: number
}

/**
 * Parses a `bytes=start-end` header against the known size. Returns null
 * for anything malformed or unsatisfiable — the caller then serves the
 * full body, which every media element accepts.
 */
export function parseRange(rangeHeader: string, size: number): ByteRange | null {
  const match = /^bytes=(\d*)-(\d*)$/.exec(rangeHeader.trim())
  if (match === null) return null
  const startText = match[1]
  const endText = match[2]
  if (startText === '' && endText === '') return null
  if (startText !== '') {
    const start = Number(startText)
    if (!Number.isInteger(start) || start < 0 || start >= size) return null
    const end = endText === '' ? size - 1 : Math.min(Number(endText), size - 1)
    if (end < start) return null
    return { start, end }
  }
  // Suffix range `bytes=-N`: the last N bytes.
  const suffix = Number(endText)
  if (!Number.isInteger(suffix) || suffix <= 0) return null
  const start = Math.max(0, size - suffix)
  return { start, end: size - 1 }
}

/**
 * Builds the synthesized 206 for a cached full body — the honest answer to
 * an offline Range request. jsdom-tested; the SW wires it into respondWith.
 */
export function buildRangeResponse(
  body: ArrayBuffer,
  size: number,
  range: ByteRange,
  contentType: string,
): Response {
  const slice = body.slice(range.start, range.end + 1)
  return new Response(slice, {
    status: 206,
    headers: {
      'Content-Type': contentType,
      'Content-Length': String(slice.byteLength),
      'Content-Range': `bytes ${range.start}-${range.end}/${size}`,
      'Accept-Ranges': 'bytes',
    },
  })
}

/**
 * The bounded-cache eviction rule: Cache keys() come back in insertion
 * order, so keeping the newest is dropping the first keys beyond the cap.
 */
export function evictToMax(keys: readonly string[], max: number): string[] {
  if (keys.length <= max) return []
  return keys.slice(0, keys.length - max)
}