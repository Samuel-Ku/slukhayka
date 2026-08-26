/**
 * spec-43 — Web Transport (ADR-0024): the ONE server-side door between the
 * Web Client and the six sources. Routes:
 *
 *   GET /api/sources                     → [{id, displayName}]
 *   GET /api/catalog?source=&url=        → ParsedCatalog (url optional: source root)
 *   GET /api/book?source=&url=           → BookDetail with enriched chapters
 *
 * Every outbound fetch is host-allowlisted per source; failures degrade to
 * `{ok:false}` with an honest reason — never fabricated data.
 */
import { mayFetch, sourceEntry } from './registry'

interface WorkerEnv {
  // No bindings yet; declared for the Cloudflare module contract.
}

/** The minimal shape Cloudflare's runtime expects; declared locally to stay dep-free. */
interface ExportedHandler<E> {
  fetch(request: Request, env: E, ctx: { waitUntil(promise: Promise<unknown>): void }): Promise<Response> | Response
}

const JSON_HEADERS = { 'content-type': 'application/json; charset=utf-8' }

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS })
}

function ok(data: unknown): Response {
  return json({ ok: true, data })
}

function fail(reason: string, status = 400): Response {
  return json({ ok: false, reason }, status)
}

/** The static browser identity of the transport (mirrors the Kotlin referer rule). */
async function fetchText(url: string, referer: string): Promise<string | null> {
  try {
    const response = await fetch(url, {
      headers: { referer, 'user-agent': DEFAULT_UA },
      redirect: 'follow',
    })
    if (!response.ok) return null
    return await response.text()
  } catch {
    return null
  }
}

const DEFAULT_UA =
  'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36'

export default {
  async fetch(request: Request, _env: WorkerEnv): Promise<Response> {
    const url = new URL(request.url)
    const { pathname, searchParams } = url

    if (pathname === '/api/sources') {
      return ok(
        Object.entries(sourceList()).map(([id, displayName]) => ({ id, displayName })),
      )
    }

    if (pathname === '/api/catalog' || pathname === '/api/book') {
      const sourceId = searchParams.get('source') ?? ''
      const pageUrl = searchParams.get('url') ?? ''
      const entry = sourceEntry(sourceId)
      if (!entry) return fail(`unknown source: ${sourceId}`)
      const target = pageUrl || entry.adapter.baseUrl
      if (!mayFetch(entry, target)) return fail(`url is outside ${sourceId}'s own hosts`)

      const html = await fetchText(target, entry.adapter.baseUrl)
      if (html === null) return fail(`source did not answer for ${target}`, 502)

      try {
        if (pathname === '/api/catalog') {
          const parsed = entry.adapter.parseCatalog(html, target)
          if (!parsed) return fail(`unrecognized catalog markup at ${target}`, 502)
          return ok(parsed)
        }
        const detail = await entry.buildBook(html, target, (inner) =>
          mayFetch(entry, inner) ? fetchText(inner, entry.adapter.baseUrl) : Promise.resolve(null),
        )
        if (!detail) return fail(`unrecognized book markup at ${target}`, 502)
        if (detail.chapters.length === 0) return ok({ ...detail, unavailableReason: 'no-chapters' })
        return ok(detail)
      } catch {
        return fail(`parsing failed for ${target}`, 502)
      }
    }

    return fail('not found', 404)
  },
} satisfies ExportedHandler<WorkerEnv>

function sourceList(): Record<string, string> {
  return {
    fourread: '4read',
    'sound-books': 'sound-books.net',
    'audiobook-mp3': 'audiobook-mp3',
    lihtar: 'lihtar',
    sluhayua: 'sluhay.com.ua',
    sluhay: 'sluhay',
  }
}
